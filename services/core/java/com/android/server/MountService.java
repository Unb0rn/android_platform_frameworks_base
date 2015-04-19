/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.res.ObbInfo;
import android.mtp.MtpStorage;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.DiskInfo;
import android.os.storage.IMountService;
import android.os.storage.IMountServiceListener;
import android.os.storage.IMountShutdownObserver;
import android.os.storage.IObbActionListener;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageResultCode;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.DebugUtils;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;
import libcore.util.HexEncoding;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IMediaContainerService;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.NativeDaemonConnector.Command;
import com.android.server.NativeDaemonConnector.SensitiveArg;
import com.android.server.pm.PackageManagerService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Service responsible for various storage media. Connects to {@code vold} to
 * watch for and manage dynamically added storage, such as SD cards and USB mass
 * storage. Also decides how storage should be presented to users on the device.
 */
class MountService extends IMountService.Stub
        implements INativeDaemonConnectorCallbacks, Watchdog.Monitor {

    // TODO: finish enforcing UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA

    // Static direct instance pointer for the tightly-coupled idle service to use
    static MountService sSelf = null;

    public static class Lifecycle extends SystemService {
        private MountService mMountService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mMountService = new MountService(getContext());
            publishBinderService("mount", mMountService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mMountService.systemReady();
            }
        }

        @Override
        public void onStartUser(int userHandle) {
            mMountService.onStartUser(userHandle);
        }

        @Override
        public void onCleanupUser(int userHandle) {
            mMountService.onCleanupUser(userHandle);
        }
    }

    private static final boolean LOCAL_LOGD = false;
    private static final boolean DEBUG_EVENTS = false;
    private static final boolean DEBUG_OBB = false;

    // Disable this since it messes up long-running cryptfs operations.
    private static final boolean WATCHDOG_ENABLE = false;

    private static final String TAG = "MountService";

    private static final String VOLD_TAG = "VoldConnector";

    /** Maximum number of ASEC containers allowed to be mounted. */
    private static final int MAX_CONTAINERS = 250;

    /*
     * Internal vold response code constants
     */
    class VoldResponseCode {
        /*
         * 100 series - Requestion action was initiated; expect another reply
         *              before proceeding with a new command.
         */
        public static final int VolumeListResult               = 110;
        public static final int AsecListResult                 = 111;
        public static final int StorageUsersListResult         = 112;
        public static final int CryptfsGetfieldResult          = 113;

        /*
         * 200 series - Requestion action has been successfully completed.
         */
        public static final int ShareStatusResult              = 210;
        public static final int AsecPathResult                 = 211;
        public static final int ShareEnabledResult             = 212;

        /*
         * 400 series - Command was accepted, but the requested action
         *              did not take place.
         */
        public static final int OpFailedNoMedia                = 401;
        public static final int OpFailedMediaBlank             = 402;
        public static final int OpFailedMediaCorrupt           = 403;
        public static final int OpFailedVolNotMounted          = 404;
        public static final int OpFailedStorageBusy            = 405;
        public static final int OpFailedStorageNotFound        = 406;

        /*
         * 600 series - Unsolicited broadcasts.
         */
        public static final int DISK_CREATED = 640;
        public static final int DISK_SIZE_CHANGED = 641;
        public static final int DISK_LABEL_CHANGED = 642;
        public static final int DISK_UNSUPPORTED = 643;
        public static final int DISK_DESTROYED = 649;

        public static final int VOLUME_CREATED = 650;
        public static final int VOLUME_STATE_CHANGED = 651;
        public static final int VOLUME_FS_TYPE_CHANGED = 652;
        public static final int VOLUME_FS_UUID_CHANGED = 653;
        public static final int VOLUME_FS_LABEL_CHANGED = 654;
        public static final int VOLUME_PATH_CHANGED = 655;
        public static final int VOLUME_DESTROYED = 659;

        /*
         * 700 series - fstrim
         */
        public static final int FstrimCompleted                = 700;
    }

    private static final String TAG_VOLUMES = "volumes";
    private static final String TAG_VOLUME = "volume";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_FS_UUID = "fsUuid";
    private static final String ATTR_NICKNAME = "nickname";
    private static final String ATTR_USER_FLAGS = "userFlags";

    private final AtomicFile mMetadataFile;

    private static class VolumeMetadata {
        public final int type;
        public final String fsUuid;
        public String nickname;
        public int userFlags;

        public VolumeMetadata(int type, String fsUuid) {
            this.type = type;
            this.fsUuid = Preconditions.checkNotNull(fsUuid);
        }

        public static VolumeMetadata read(XmlPullParser in) throws IOException {
            final int type = readIntAttribute(in, ATTR_TYPE);
            final String fsUuid = readStringAttribute(in, ATTR_FS_UUID);
            final VolumeMetadata meta = new VolumeMetadata(type, fsUuid);
            meta.nickname = readStringAttribute(in, ATTR_NICKNAME);
            meta.userFlags = readIntAttribute(in, ATTR_USER_FLAGS);
            return meta;
        }

        public static void write(XmlSerializer out, VolumeMetadata meta) throws IOException {
            out.startTag(null, TAG_VOLUME);
            writeIntAttribute(out, ATTR_TYPE, meta.type);
            writeStringAttribute(out, ATTR_FS_UUID, meta.fsUuid);
            writeStringAttribute(out, ATTR_NICKNAME, meta.nickname);
            writeIntAttribute(out, ATTR_USER_FLAGS, meta.userFlags);
            out.endTag(null, TAG_VOLUME);
        }

        public void dump(IndentingPrintWriter pw) {
            pw.println("VolumeMetadata:");
            pw.increaseIndent();
            pw.printPair("type", DebugUtils.valueToString(VolumeInfo.class, "TYPE_", type));
            pw.printPair("fsUuid", fsUuid);
            pw.printPair("nickname", nickname);
            pw.printPair("userFlags",
                    DebugUtils.flagsToString(VolumeInfo.class, "USER_FLAG_", userFlags));
            pw.decreaseIndent();
            pw.println();
        }
    }

    /**
     * <em>Never</em> hold the lock while performing downcalls into vold, since
     * unsolicited events can suddenly appear to update data structures.
     */
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private int[] mStartedUsers = EmptyArray.INT;

    /** Map from disk ID to disk */
    @GuardedBy("mLock")
    private ArrayMap<String, DiskInfo> mDisks = new ArrayMap<>();
    /** Map from volume ID to disk */
    @GuardedBy("mLock")
    private ArrayMap<String, VolumeInfo> mVolumes = new ArrayMap<>();

    /** Map from UUID to metadata */
    @GuardedBy("mLock")
    private ArrayMap<String, VolumeMetadata> mMetadata = new ArrayMap<>();

    private DiskInfo findDiskById(String id) {
        synchronized (mLock) {
            final DiskInfo disk = mDisks.get(id);
            if (disk != null) {
                return disk;
            }
        }
        throw new IllegalArgumentException("No disk found for ID " + id);
    }

    private VolumeInfo findVolumeById(String id) {
        synchronized (mLock) {
            final VolumeInfo vol = mVolumes.get(id);
            if (vol != null) {
                return vol;
            }
        }
        throw new IllegalArgumentException("No volume found for ID " + id);
    }

    @Deprecated
    private String findVolumeIdForPath(String path) {
        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.path != null && path.startsWith(vol.path)) {
                    return vol.id;
                }
            }
        }
        throw new IllegalArgumentException("No volume found for path " + path);
    }

    private VolumeMetadata findOrCreateMetadataLocked(VolumeInfo vol) {
        VolumeMetadata meta = mMetadata.get(vol.fsUuid);
        if (meta == null) {
            meta = new VolumeMetadata(vol.type, vol.fsUuid);
            mMetadata.put(meta.fsUuid, meta);
        }
        return meta;
    }

    private static int sNextMtpIndex = 1;

    private static int allocateMtpIndex(String volId) {
        if (VolumeInfo.ID_EMULATED_INTERNAL.equals(volId)) {
            return 0;
        } else {
            return sNextMtpIndex++;
        }
    }

    /** List of crypto types.
      * These must match CRYPT_TYPE_XXX in cryptfs.h AND their
      * corresponding commands in CommandListener.cpp */
    public static final String[] CRYPTO_TYPES
        = { "password", "default", "pattern", "pin" };

    private final Context mContext;
    private final NativeDaemonConnector mConnector;

    private volatile boolean mSystemReady = false;
    private volatile boolean mDaemonConnected = false;

    private PackageManagerService mPms;

    private final Callbacks mCallbacks;

    private final CountDownLatch mConnectedSignal = new CountDownLatch(1);
    private final CountDownLatch mAsecsScanned = new CountDownLatch(1);

    private final Object mUnmountLock = new Object();
    @GuardedBy("mUnmountLock")
    private CountDownLatch mUnmountSignal;

    /**
     * Private hash of currently mounted secure containers.
     * Used as a lock in methods to manipulate secure containers.
     */
    final private HashSet<String> mAsecMountSet = new HashSet<String>();

    /**
     * The size of the crypto algorithm key in bits for OBB files. Currently
     * Twofish is used which takes 128-bit keys.
     */
    private static final int CRYPTO_ALGORITHM_KEY_SIZE = 128;

    /**
     * The number of times to run SHA1 in the PBKDF2 function for OBB files.
     * 1024 is reasonably secure and not too slow.
     */
    private static final int PBKDF2_HASH_ROUNDS = 1024;

    /**
     * Mounted OBB tracking information. Used to track the current state of all
     * OBBs.
     */
    final private Map<IBinder, List<ObbState>> mObbMounts = new HashMap<IBinder, List<ObbState>>();

    /** Map from raw paths to {@link ObbState}. */
    final private Map<String, ObbState> mObbPathToStateMap = new HashMap<String, ObbState>();

    class ObbState implements IBinder.DeathRecipient {
        public ObbState(String rawPath, String canonicalPath, int callingUid,
                IObbActionListener token, int nonce) {
            this.rawPath = rawPath;
            this.canonicalPath = canonicalPath.toString();

            final int userId = UserHandle.getUserId(callingUid);
            this.ownerPath = buildObbPath(canonicalPath, userId, false);
            this.voldPath = buildObbPath(canonicalPath, userId, true);

            this.ownerGid = UserHandle.getSharedAppGid(callingUid);
            this.token = token;
            this.nonce = nonce;
        }

        final String rawPath;
        final String canonicalPath;
        final String ownerPath;
        final String voldPath;

        final int ownerGid;

        // Token of remote Binder caller
        final IObbActionListener token;

        // Identifier to pass back to the token
        final int nonce;

        public IBinder getBinder() {
            return token.asBinder();
        }

        @Override
        public void binderDied() {
            ObbAction action = new UnmountObbAction(this, true);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));
        }

        public void link() throws RemoteException {
            getBinder().linkToDeath(this, 0);
        }

        public void unlink() {
            getBinder().unlinkToDeath(this, 0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ObbState{");
            sb.append("rawPath=").append(rawPath);
            sb.append(",canonicalPath=").append(canonicalPath);
            sb.append(",ownerPath=").append(ownerPath);
            sb.append(",voldPath=").append(voldPath);
            sb.append(",ownerGid=").append(ownerGid);
            sb.append(",token=").append(token);
            sb.append(",binder=").append(getBinder());
            sb.append('}');
            return sb.toString();
        }
    }

    // OBB Action Handler
    final private ObbActionHandler mObbActionHandler;

    // OBB action handler messages
    private static final int OBB_RUN_ACTION = 1;
    private static final int OBB_MCS_BOUND = 2;
    private static final int OBB_MCS_UNBIND = 3;
    private static final int OBB_MCS_RECONNECT = 4;
    private static final int OBB_FLUSH_MOUNT_STATE = 5;

    /*
     * Default Container Service information
     */
    static final ComponentName DEFAULT_CONTAINER_COMPONENT = new ComponentName(
            "com.android.defcontainer", "com.android.defcontainer.DefaultContainerService");

    final private DefaultContainerConnection mDefContainerConn = new DefaultContainerConnection();

    class DefaultContainerConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG_OBB)
                Slog.i(TAG, "onServiceConnected");
            IMediaContainerService imcs = IMediaContainerService.Stub.asInterface(service);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_MCS_BOUND, imcs));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_OBB)
                Slog.i(TAG, "onServiceDisconnected");
        }
    };

    // Used in the ObbActionHandler
    private IMediaContainerService mContainerService = null;

    // Last fstrim operation tracking
    private static final String LAST_FSTRIM_FILE = "last-fstrim";
    private final File mLastMaintenanceFile;
    private long mLastMaintenance;

    // Handler messages
    private static final int H_SYSTEM_READY = 1;
    private static final int H_DAEMON_CONNECTED = 2;
    private static final int H_SHUTDOWN = 3;
    private static final int H_FSTRIM = 4;
    private static final int H_VOLUME_MOUNT = 5;
    private static final int H_VOLUME_BROADCAST = 6;

    class MountServiceHandler extends Handler {
        public MountServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case H_SYSTEM_READY: {
                    handleSystemReady();
                    break;
                }
                case H_DAEMON_CONNECTED: {
                    handleDaemonConnected();
                    break;
                }
                case H_FSTRIM: {
                    if (!isReady()) {
                        Slog.i(TAG, "fstrim requested, but no daemon connection yet; trying again");
                        sendMessageDelayed(obtainMessage(H_FSTRIM), DateUtils.SECOND_IN_MILLIS);
                    }

                    Slog.i(TAG, "Running fstrim idle maintenance");

                    // Remember when we kicked it off
                    try {
                        mLastMaintenance = System.currentTimeMillis();
                        mLastMaintenanceFile.setLastModified(mLastMaintenance);
                    } catch (Exception e) {
                        Slog.e(TAG, "Unable to record last fstrim!");
                    }

                    try {
                        // This method must be run on the main (handler) thread,
                        // so it is safe to directly call into vold.
                        mConnector.execute("fstrim", "dotrim");
                        EventLogTags.writeFstrimStart(SystemClock.elapsedRealtime());
                    } catch (NativeDaemonConnectorException ndce) {
                        Slog.e(TAG, "Failed to run fstrim!");
                    }

                    // invoke the completion callback, if any
                    Runnable callback = (Runnable) msg.obj;
                    if (callback != null) {
                        callback.run();
                    }
                    break;
                }
                case H_SHUTDOWN: {
                    final IMountShutdownObserver obs = (IMountShutdownObserver) msg.obj;
                    boolean success = false;
                    try {
                        success = mConnector.execute("volume", "shutdown").isClassOk();
                    } catch (NativeDaemonConnectorException ignored) {
                    }
                    if (obs != null) {
                        try {
                            obs.onShutDownComplete(success ? 0 : -1);
                        } catch (RemoteException ignored) {
                        }
                    }
                    break;
                }
                case H_VOLUME_MOUNT: {
                    final VolumeInfo vol = (VolumeInfo) msg.obj;
                    try {
                        mConnector.execute("volume", "mount", vol.id, vol.mountFlags,
                                vol.mountUserId);
                    } catch (NativeDaemonConnectorException ignored) {
                    }
                    break;
                }
                case H_VOLUME_BROADCAST: {
                    final StorageVolume userVol = (StorageVolume) msg.obj;
                    final String envState = userVol.getState();
                    Slog.d(TAG, "Volume " + userVol.getId() + " broadcasting " + envState + " to "
                            + userVol.getOwner());

                    final String action = VolumeInfo.getBroadcastForEnvironment(envState);
                    if (action != null) {
                        final Intent intent = new Intent(action,
                                Uri.fromFile(userVol.getPathFile()));
                        intent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, userVol);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        mContext.sendBroadcastAsUser(intent, userVol.getOwner());
                    }
                    break;
                }
            }
        }
    }

    private final Handler mHandler;

    @Override
    public void waitForAsecScan() {
        waitForLatch(mAsecsScanned, "mAsecsScanned");
    }

    private void waitForReady() {
        waitForLatch(mConnectedSignal, "mConnectedSignal");
    }

    private void waitForLatch(CountDownLatch latch, String condition) {
        for (;;) {
            try {
                if (latch.await(5000, TimeUnit.MILLISECONDS)) {
                    return;
                } else {
                    Slog.w(TAG, "Thread " + Thread.currentThread().getName()
                            + " still waiting for " + condition + "...");
                }
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupt while waiting for MountService to be ready.");
            }
        }
    }

    private boolean isReady() {
        try {
            return mConnectedSignal.await(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void handleSystemReady() {
        resetIfReadyAndConnected();

        // Start scheduling nominally-daily fstrim operations
        MountServiceIdler.scheduleIdlePass(mContext);
    }

    private void resetIfReadyAndConnected() {
        Slog.d(TAG, "Thinking about reset, mSystemReady=" + mSystemReady
                + ", mDaemonConnected=" + mDaemonConnected);
        if (mSystemReady && mDaemonConnected) {
            mDisks.clear();
            mVolumes.clear();

            // Create a stub volume that represents internal storage
            final VolumeInfo internal = new VolumeInfo(VolumeInfo.ID_PRIVATE_INTERNAL,
                    VolumeInfo.TYPE_PRIVATE, null, 0);
            internal.state = VolumeInfo.STATE_MOUNTED;
            internal.path = Environment.getDataDirectory().getAbsolutePath();
            mVolumes.put(internal.id, internal);

            try {
                mConnector.execute("volume", "reset");
            } catch (NativeDaemonConnectorException e) {
                Slog.w(TAG, "Failed to reset vold", e);
            }
        }
    }

    private void onStartUser(int userId) {
        Slog.d(TAG, "onStartUser " + userId);

        // We purposefully block here to make sure that user-specific
        // staging area is ready so it's ready for zygote-forked apps to
        // bind mount against.
        try {
            mConnector.execute("volume", "start_user", userId);
        } catch (NativeDaemonConnectorException ignored) {
        }

        // Record user as started so newly mounted volumes kick off events
        // correctly, then synthesize events for any already-mounted volumes.
        synchronized (mVolumes) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.isVisibleToUser(userId) && vol.isMountedReadable()) {
                    final StorageVolume userVol = vol.buildStorageVolume(mContext, userId);
                    mHandler.obtainMessage(H_VOLUME_BROADCAST, userVol).sendToTarget();

                    final String envState = VolumeInfo.getEnvironmentForState(vol.getState());
                    mCallbacks.notifyStorageStateChanged(userVol.getPath(), envState, envState);
                }
            }
            mStartedUsers = ArrayUtils.appendInt(mStartedUsers, userId);
        }
    }

    private void onCleanupUser(int userId) {
        Slog.d(TAG, "onCleanupUser " + userId);

        try {
            mConnector.execute("volume", "cleanup_user", userId);
        } catch (NativeDaemonConnectorException ignored) {
        }

        synchronized (mVolumes) {
            mStartedUsers = ArrayUtils.removeInt(mStartedUsers, userId);
        }
    }

    void runIdleMaintenance(Runnable callback) {
        mHandler.sendMessage(mHandler.obtainMessage(H_FSTRIM, callback));
    }

    // Binder entry point for kicking off an immediate fstrim
    @Override
    public void runMaintenance() {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        runIdleMaintenance(null);
    }

    @Override
    public long lastMaintenance() {
        return mLastMaintenance;
    }

    /**
     * Callback from NativeDaemonConnector
     */
    @Override
    public void onDaemonConnected() {
        mDaemonConnected = true;
        mHandler.obtainMessage(H_DAEMON_CONNECTED).sendToTarget();
    }

    private void handleDaemonConnected() {
        resetIfReadyAndConnected();

        /*
         * Now that we've done our initialization, release
         * the hounds!
         */
        mConnectedSignal.countDown();

        // On an encrypted device we can't see system properties yet, so pull
        // the system locale out of the mount service.
        if ("".equals(SystemProperties.get("vold.encrypt_progress"))) {
            copyLocaleFromMountService();
        }

        // Let package manager load internal ASECs.
        mPms.scanAvailableAsecs();

        // Notify people waiting for ASECs to be scanned that it's done.
        mAsecsScanned.countDown();
    }

    private void copyLocaleFromMountService() {
        String systemLocale;
        try {
            systemLocale = getField(StorageManager.SYSTEM_LOCALE_KEY);
        } catch (RemoteException e) {
            return;
        }
        if (TextUtils.isEmpty(systemLocale)) {
            return;
        }

        Slog.d(TAG, "Got locale " + systemLocale + " from mount service");
        Locale locale = Locale.forLanguageTag(systemLocale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        try {
            ActivityManagerNative.getDefault().updateConfiguration(config);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error setting system locale from mount service", e);
        }

        // Temporary workaround for http://b/17945169.
        Slog.d(TAG, "Setting system properties to " + systemLocale + " from mount service");
        SystemProperties.set("persist.sys.locale", locale.toLanguageTag());
    }

    /**
     * Callback from NativeDaemonConnector
     */
    @Override
    public boolean onCheckHoldWakeLock(int code) {
        return false;
    }

    /**
     * Callback from NativeDaemonConnector
     */
    @Override
    public boolean onEvent(int code, String raw, String[] cooked) {
        synchronized (mLock) {
            return onEventLocked(code, raw, cooked);
        }
    }

    private boolean onEventLocked(int code, String raw, String[] cooked) {
        switch (code) {
            case VoldResponseCode.DISK_CREATED: {
                if (cooked.length != 3) break;
                final String id = cooked[1];
                final int flags = Integer.parseInt(cooked[2]);
                mDisks.put(id, new DiskInfo(id, flags));
                break;
            }
            case VoldResponseCode.DISK_SIZE_CHANGED: {
                if (cooked.length != 3) break;
                final DiskInfo disk = mDisks.get(cooked[1]);
                if (disk != null) {
                    disk.size = Long.parseLong(cooked[2]);
                }
                break;
            }
            case VoldResponseCode.DISK_LABEL_CHANGED: {
                final DiskInfo disk = mDisks.get(cooked[1]);
                if (disk != null) {
                    final StringBuilder builder = new StringBuilder();
                    for (int i = 2; i < cooked.length; i++) {
                        builder.append(cooked[i]).append(' ');
                    }
                    disk.label = builder.toString().trim();
                }
                break;
            }
            case VoldResponseCode.DISK_UNSUPPORTED: {
                if (cooked.length != 2) break;
                final DiskInfo disk = mDisks.get(cooked[1]);
                mCallbacks.notifyDiskUnsupported(disk);
                break;
            }
            case VoldResponseCode.DISK_DESTROYED: {
                if (cooked.length != 2) break;
                mDisks.remove(cooked[1]);
                break;
            }

            case VoldResponseCode.VOLUME_CREATED: {
                final String id = cooked[1];
                final int type = Integer.parseInt(cooked[2]);
                final String diskId = (cooked.length == 4) ? cooked[3] : null;
                final DiskInfo disk = mDisks.get(diskId);
                final int mtpIndex = allocateMtpIndex(id);
                final VolumeInfo vol = new VolumeInfo(id, type, disk, mtpIndex);
                mVolumes.put(id, vol);
                onVolumeCreatedLocked(vol);
                break;
            }
            case VoldResponseCode.VOLUME_STATE_CHANGED: {
                if (cooked.length != 3) break;
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    final int oldState = vol.state;
                    final int newState = Integer.parseInt(cooked[2]);
                    vol.state = newState;
                    onVolumeStateChangedLocked(vol.clone(), oldState, newState);
                }
                break;
            }
            case VoldResponseCode.VOLUME_FS_TYPE_CHANGED: {
                if (cooked.length != 3) break;
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    vol.fsType = cooked[2];
                }
                mCallbacks.notifyVolumeMetadataChanged(vol.clone());
                break;
            }
            case VoldResponseCode.VOLUME_FS_UUID_CHANGED: {
                if (cooked.length != 3) break;
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    vol.fsUuid = cooked[2];
                }
                refreshMetadataLocked();
                mCallbacks.notifyVolumeMetadataChanged(vol.clone());
                break;
            }
            case VoldResponseCode.VOLUME_FS_LABEL_CHANGED: {
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    final StringBuilder builder = new StringBuilder();
                    for (int i = 2; i < cooked.length; i++) {
                        builder.append(cooked[i]).append(' ');
                    }
                    vol.fsLabel = builder.toString().trim();
                }
                mCallbacks.notifyVolumeMetadataChanged(vol.clone());
                break;
            }
            case VoldResponseCode.VOLUME_PATH_CHANGED: {
                if (cooked.length != 3) break;
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    vol.path = cooked[2];
                }
                break;
            }
            case VoldResponseCode.VOLUME_DESTROYED: {
                if (cooked.length != 2) break;
                mVolumes.remove(cooked[1]);
                break;
            }

            case VoldResponseCode.FstrimCompleted: {
                EventLogTags.writeFstrimFinish(SystemClock.elapsedRealtime());
                break;
            }
            default: {
                Slog.d(TAG, "Unhandled vold event " + code);
            }
        }

        return true;
    }

    private void onVolumeCreatedLocked(VolumeInfo vol) {
        final boolean primaryPhysical = SystemProperties.getBoolean(
                StorageManager.PROP_PRIMARY_PHYSICAL, false);
        // TODO: enable switching to another emulated primary
        if (VolumeInfo.ID_EMULATED_INTERNAL.equals(vol.id) && !primaryPhysical) {
            vol.mountFlags |= VolumeInfo.MOUNT_FLAG_PRIMARY;
            vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE;
            mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();

        } else if (vol.type == VolumeInfo.TYPE_PUBLIC) {
            if (primaryPhysical) {
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_PRIMARY;
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE;
            }

            // Adoptable public disks are visible to apps, since they meet
            // public API requirement of being in a stable location.
            final DiskInfo disk = mDisks.get(vol.getDiskId());
            if (disk != null && disk.isAdoptable()) {
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE;
            }

            vol.mountUserId = UserHandle.USER_OWNER;
            mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();

        } else if (vol.type == VolumeInfo.TYPE_PRIVATE) {
            mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();

        } else {
            Slog.d(TAG, "Skipping automatic mounting of " + vol);
        }
    }

    private boolean isBroadcastWorthy(VolumeInfo vol) {
        switch (vol.getType()) {
            case VolumeInfo.TYPE_PUBLIC:
            case VolumeInfo.TYPE_EMULATED:
                break;
            default:
                return false;
        }

        switch (vol.getState()) {
            case VolumeInfo.STATE_MOUNTED:
            case VolumeInfo.STATE_MOUNTED_READ_ONLY:
            case VolumeInfo.STATE_EJECTING:
            case VolumeInfo.STATE_UNMOUNTED:
                break;
            default:
                return false;
        }

        return true;
    }

    private void onVolumeStateChangedLocked(VolumeInfo vol, int oldState, int newState) {
        mCallbacks.notifyVolumeStateChanged(vol, oldState, newState);

        if (isBroadcastWorthy(vol)) {
            final Intent intent = new Intent(VolumeInfo.ACTION_VOLUME_STATE_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            // TODO: require receiver to hold permission
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        final String oldStateEnv = VolumeInfo.getEnvironmentForState(oldState);
        final String newStateEnv = VolumeInfo.getEnvironmentForState(newState);

        if (!Objects.equals(oldStateEnv, newStateEnv)) {
            // Kick state changed event towards all started users. Any users
            // started after this point will trigger additional
            // user-specific broadcasts.
            for (int userId : mStartedUsers) {
                if (vol.isVisibleToUser(userId)) {
                    final StorageVolume userVol = vol.buildStorageVolume(mContext, userId);
                    mHandler.obtainMessage(H_VOLUME_BROADCAST, userVol).sendToTarget();

                    mCallbacks.notifyStorageStateChanged(userVol.getPath(), oldStateEnv,
                            newStateEnv);
                }
            }
        }

        if (vol.type == VolumeInfo.TYPE_PUBLIC && vol.state == VolumeInfo.STATE_EJECTING) {
            // TODO: this should eventually be handled by new ObbVolume state changes
            /*
             * Some OBBs might have been unmounted when this volume was
             * unmounted, so send a message to the handler to let it know to
             * remove those from the list of mounted OBBS.
             */
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(
                    OBB_FLUSH_MOUNT_STATE, vol.path));
        }
    }

    /**
     * Refresh latest metadata into any currently active {@link VolumeInfo}.
     */
    private void refreshMetadataLocked() {
        final int size = mVolumes.size();
        for (int i = 0; i < size; i++) {
            final VolumeInfo vol = mVolumes.valueAt(i);
            final VolumeMetadata meta = mMetadata.get(vol.fsUuid);

            if (meta != null) {
                vol.nickname = meta.nickname;
                vol.userFlags = meta.userFlags;
            } else {
                vol.nickname = null;
                vol.userFlags = 0;
            }
        }
    }

    private void enforcePermission(String perm) {
        mContext.enforceCallingOrSelfPermission(perm, perm);
    }

    private void enforceUserRestriction(String restriction) {
        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um.hasUserRestriction(restriction, Binder.getCallingUserHandle())) {
            throw new SecurityException("User has restriction " + restriction);
        }
    }

    /**
     * Constructs a new MountService instance
     *
     * @param context  Binder context for this service
     */
    public MountService(Context context) {
        sSelf = this;

        mContext = context;
        mCallbacks = new Callbacks(FgThread.get().getLooper());

        // XXX: This will go away soon in favor of IMountServiceObserver
        mPms = (PackageManagerService) ServiceManager.getService("package");

        HandlerThread hthread = new HandlerThread(TAG);
        hthread.start();
        mHandler = new MountServiceHandler(hthread.getLooper());

        // Add OBB Action Handler to MountService thread.
        mObbActionHandler = new ObbActionHandler(IoThread.get().getLooper());

        // Initialize the last-fstrim tracking if necessary
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        mLastMaintenanceFile = new File(systemDir, LAST_FSTRIM_FILE);
        if (!mLastMaintenanceFile.exists()) {
            // Not setting mLastMaintenance here means that we will force an
            // fstrim during reboot following the OTA that installs this code.
            try {
                (new FileOutputStream(mLastMaintenanceFile)).close();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to create fstrim record " + mLastMaintenanceFile.getPath());
            }
        } else {
            mLastMaintenance = mLastMaintenanceFile.lastModified();
        }

        mMetadataFile = new AtomicFile(
                new File(Environment.getSystemSecureDirectory(), "storage.xml"));

        synchronized (mLock) {
            readMetadataLocked();
        }

        /*
         * Create the connection to vold with a maximum queue of twice the
         * amount of containers we'd ever expect to have. This keeps an
         * "asec list" from blocking a thread repeatedly.
         */
        mConnector = new NativeDaemonConnector(this, "vold", MAX_CONTAINERS * 2, VOLD_TAG, 25,
                null);
        mConnector.setDebug(true);

        Thread thread = new Thread(mConnector, VOLD_TAG);
        thread.start();

        // Add ourself to the Watchdog monitors if enabled.
        if (WATCHDOG_ENABLE) {
            Watchdog.getInstance().addMonitor(this);
        }
    }

    private void systemReady() {
        mSystemReady = true;
        mHandler.obtainMessage(H_SYSTEM_READY).sendToTarget();
    }

    private void readMetadataLocked() {
        mMetadata.clear();

        FileInputStream fis = null;
        try {
            fis = mMetadataFile.openRead();
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, null);

            int type;
            while ((type = in.next()) != END_DOCUMENT) {
                if (type == START_TAG) {
                    final String tag = in.getName();
                    if (TAG_VOLUME.equals(tag)) {
                        final VolumeMetadata meta = VolumeMetadata.read(in);
                        mMetadata.put(meta.fsUuid, meta);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // Missing metadata is okay, probably first boot
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed reading metadata", e);
        } catch (XmlPullParserException e) {
            Slog.wtf(TAG, "Failed reading metadata", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    private void writeMetadataLocked() {
        FileOutputStream fos = null;
        try {
            fos = mMetadataFile.startWrite();

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.startTag(null, TAG_VOLUMES);
            final int size = mMetadata.size();
            for (int i = 0; i < size; i++) {
                final VolumeMetadata meta = mMetadata.valueAt(i);
                VolumeMetadata.write(out, meta);
            }
            out.endTag(null, TAG_VOLUMES);
            out.endDocument();

            mMetadataFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mMetadataFile.failWrite(fos);
            }
        }
    }

    /**
     * Exposed API calls below here
     */

    @Override
    public void registerListener(IMountServiceListener listener) {
        mCallbacks.register(listener);
    }

    @Override
    public void unregisterListener(IMountServiceListener listener) {
        mCallbacks.unregister(listener);
    }

    @Override
    public void shutdown(final IMountShutdownObserver observer) {
        enforcePermission(android.Manifest.permission.SHUTDOWN);

        Slog.i(TAG, "Shutting down");
        mHandler.obtainMessage(H_SHUTDOWN, observer).sendToTarget();
    }

    @Override
    public boolean isUsbMassStorageConnected() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setUsbMassStorageEnabled(boolean enable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUsbMassStorageEnabled() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getVolumeState(String mountPoint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isExternalStorageEmulated() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int mountVolume(String path) {
        mount(findVolumeIdForPath(path));
        return 0;
    }

    @Override
    public void unmountVolume(String path, boolean force, boolean removeEncryption) {
        unmount(findVolumeIdForPath(path));
    }

    @Override
    public int formatVolume(String path) {
        format(findVolumeIdForPath(path));
        return 0;
    }

    @Override
    public void mount(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        final VolumeInfo vol = findVolumeById(volId);
        if (vol.type == VolumeInfo.TYPE_PUBLIC || vol.type == VolumeInfo.TYPE_PRIVATE) {
            enforceUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
        }
        try {
            mConnector.execute("volume", "mount", vol.id, vol.mountFlags, vol.mountUserId);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void unmount(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        final VolumeInfo vol = findVolumeById(volId);

        // TODO: expand PMS to know about multiple volumes
        if (vol.isPrimary()) {
            synchronized (mUnmountLock) {
                mUnmountSignal = new CountDownLatch(1);
                mPms.updateExternalMediaStatus(false, true);
                waitForLatch(mUnmountSignal, "mUnmountSignal");
                mUnmountSignal = null;
            }
        }

        try {
            mConnector.execute("volume", "unmount", vol.id);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void format(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        final VolumeInfo vol = findVolumeById(volId);
        try {
            mConnector.execute("volume", "format", vol.id);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void partitionPublic(String diskId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        try {
            mConnector.execute("volume", "partition", diskId, "public");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void partitionPrivate(String diskId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        try {
            mConnector.execute("volume", "partition", diskId, "private");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void partitionMixed(String diskId, int ratio) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        try {
            mConnector.execute("volume", "partition", diskId, "mixed", ratio);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void setVolumeNickname(String volId, String nickname) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        synchronized (mLock) {
            final VolumeInfo vol = findVolumeById(volId);
            final VolumeMetadata meta = findOrCreateMetadataLocked(vol);
            meta.nickname = nickname;
            refreshMetadataLocked();
            writeMetadataLocked();
            mCallbacks.notifyVolumeMetadataChanged(vol.clone());
        }
    }

    @Override
    public void setVolumeUserFlags(String volId, int flags, int mask) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        synchronized (mLock) {
            final VolumeInfo vol = findVolumeById(volId);
            final VolumeMetadata meta = findOrCreateMetadataLocked(vol);
            meta.userFlags = (meta.userFlags & ~mask) | (flags & mask);
            refreshMetadataLocked();
            writeMetadataLocked();
            mCallbacks.notifyVolumeMetadataChanged(vol.clone());
        }
    }

    @Override
    public int[] getStorageUsers(String path) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();
        try {
            final String[] r = NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("storage", "users", path),
                    VoldResponseCode.StorageUsersListResult);

            // FMT: <pid> <process name>
            int[] data = new int[r.length];
            for (int i = 0; i < r.length; i++) {
                String[] tok = r[i].split(" ");
                try {
                    data[i] = Integer.parseInt(tok[0]);
                } catch (NumberFormatException nfe) {
                    Slog.e(TAG, String.format("Error parsing pid %s", tok[0]));
                    return new int[0];
                }
            }
            return data;
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to retrieve storage users list", e);
            return new int[0];
        }
    }

    private void warnOnNotMounted() {
        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.isPrimary() && vol.isMountedWritable()) {
                    // Cool beans, we have a mounted primary volume
                    return;
                }
            }
        }

        Slog.w(TAG, "No primary storage mounted!");
    }

    public String[] getSecureContainerList() {
        enforcePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        try {
            return NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("asec", "list"), VoldResponseCode.AsecListResult);
        } catch (NativeDaemonConnectorException e) {
            return new String[0];
        }
    }

    public int createSecureContainer(String id, int sizeMb, String fstype, String key,
            int ownerUid, boolean external) {
        enforcePermission(android.Manifest.permission.ASEC_CREATE);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "create", id, sizeMb, fstype, new SensitiveArg(key),
                    ownerUid, external ? "1" : "0");
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    @Override
    public int resizeSecureContainer(String id, int sizeMb, String key) {
        enforcePermission(android.Manifest.permission.ASEC_CREATE);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "resize", id, sizeMb, new SensitiveArg(key));
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int finalizeSecureContainer(String id) {
        enforcePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "finalize", id);
            /*
             * Finalization does a remount, so no need
             * to update mAsecMountSet
             */
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int fixPermissionsSecureContainer(String id, int gid, String filename) {
        enforcePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "fixperms", id, gid, filename);
            /*
             * Fix permissions does a remount, so no need to update
             * mAsecMountSet
             */
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int destroySecureContainer(String id, boolean force) {
        enforcePermission(android.Manifest.permission.ASEC_DESTROY);
        waitForReady();
        warnOnNotMounted();

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            final Command cmd = new Command("asec", "destroy", id);
            if (force) {
                cmd.appendArg("force");
            }
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                if (mAsecMountSet.contains(id)) {
                    mAsecMountSet.remove(id);
                }
            }
        }

        return rc;
    }

    public int mountSecureContainer(String id, String key, int ownerUid, boolean readOnly) {
        enforcePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            if (mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "mount", id, new SensitiveArg(key), ownerUid,
                    readOnly ? "ro" : "rw");
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code != VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    public int unmountSecureContainer(String id, boolean force) {
        enforcePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            if (!mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageNotMounted;
            }
         }

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            final Command cmd = new Command("asec", "unmount", id);
            if (force) {
                cmd.appendArg("force");
            }
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.remove(id);
            }
        }
        return rc;
    }

    public boolean isSecureContainerMounted(String id) {
        enforcePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            return mAsecMountSet.contains(id);
        }
    }

    public int renameSecureContainer(String oldId, String newId) {
        enforcePermission(android.Manifest.permission.ASEC_RENAME);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            /*
             * Because a mounted container has active internal state which cannot be
             * changed while active, we must ensure both ids are not currently mounted.
             */
            if (mAsecMountSet.contains(oldId) || mAsecMountSet.contains(newId)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "rename", oldId, newId);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        return rc;
    }

    public String getSecureContainerPath(String id) {
        enforcePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("asec", "path", id);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                Slog.i(TAG, String.format("Container '%s' not found", id));
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    public String getSecureContainerFilesystemPath(String id) {
        enforcePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("asec", "fspath", id);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                Slog.i(TAG, String.format("Container '%s' not found", id));
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    @Override
    public void finishMediaUpdate() {
        if (mUnmountSignal != null) {
            mUnmountSignal.countDown();
        } else {
            Slog.w(TAG, "Odd, nobody asked to unmount?");
        }
    }

    private boolean isUidOwnerOfPackageOrSystem(String packageName, int callerUid) {
        if (callerUid == android.os.Process.SYSTEM_UID) {
            return true;
        }

        if (packageName == null) {
            return false;
        }

        final int packageUid = mPms.getPackageUid(packageName, UserHandle.getUserId(callerUid));

        if (DEBUG_OBB) {
            Slog.d(TAG, "packageName = " + packageName + ", packageUid = " +
                    packageUid + ", callerUid = " + callerUid);
        }

        return callerUid == packageUid;
    }

    public String getMountedObbPath(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        waitForReady();
        warnOnNotMounted();

        final ObbState state;
        synchronized (mObbPathToStateMap) {
            state = mObbPathToStateMap.get(rawPath);
        }
        if (state == null) {
            Slog.w(TAG, "Failed to find OBB mounted at " + rawPath);
            return null;
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("obb", "path", state.voldPath);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    @Override
    public boolean isObbMounted(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        synchronized (mObbMounts) {
            return mObbPathToStateMap.containsKey(rawPath);
        }
    }

    @Override
    public void mountObb(
            String rawPath, String canonicalPath, String key, IObbActionListener token, int nonce) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        Preconditions.checkNotNull(canonicalPath, "canonicalPath cannot be null");
        Preconditions.checkNotNull(token, "token cannot be null");

        final int callingUid = Binder.getCallingUid();
        final ObbState obbState = new ObbState(rawPath, canonicalPath, callingUid, token, nonce);
        final ObbAction action = new MountObbAction(obbState, key, callingUid);
        mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));

        if (DEBUG_OBB)
            Slog.i(TAG, "Send to OBB handler: " + action.toString());
    }

    @Override
    public void unmountObb(String rawPath, boolean force, IObbActionListener token, int nonce) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        final ObbState existingState;
        synchronized (mObbPathToStateMap) {
            existingState = mObbPathToStateMap.get(rawPath);
        }

        if (existingState != null) {
            // TODO: separate state object from request data
            final int callingUid = Binder.getCallingUid();
            final ObbState newState = new ObbState(
                    rawPath, existingState.canonicalPath, callingUid, token, nonce);
            final ObbAction action = new UnmountObbAction(newState, force);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));

            if (DEBUG_OBB)
                Slog.i(TAG, "Send to OBB handler: " + action.toString());
        } else {
            Slog.w(TAG, "Unknown OBB mount at " + rawPath);
        }
    }

    @Override
    public int getEncryptionState() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "no permission to access the crypt keeper");

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "cryptocomplete");
            return Integer.parseInt(event.getMessage());
        } catch (NumberFormatException e) {
            // Bad result - unexpected.
            Slog.w(TAG, "Unable to parse result from cryptfs cryptocomplete");
            return ENCRYPTION_STATE_ERROR_UNKNOWN;
        } catch (NativeDaemonConnectorException e) {
            // Something bad happened.
            Slog.w(TAG, "Error in communicating with cryptfs in validating");
            return ENCRYPTION_STATE_ERROR_UNKNOWN;
        }
    }

    private static String toHex(String password) {
        if (password == null) {
            return "";
        }
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        return new String(HexEncoding.encode(bytes));
    }

    private static String fromHex(String hexPassword) throws IllegalArgumentException {
        if (hexPassword == null) {
            return null;
        }

        final byte[] bytes = HexEncoding.decode(hexPassword.toCharArray(), false);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public int decryptStorage(String password) {
        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "decrypting storage...");
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "checkpw", new SensitiveArg(toHex(password)));

            final int code = Integer.parseInt(event.getMessage());
            if (code == 0) {
                // Decrypt was successful. Post a delayed message before restarting in order
                // to let the UI to clear itself
                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            mConnector.execute("cryptfs", "restart");
                        } catch (NativeDaemonConnectorException e) {
                            Slog.e(TAG, "problem executing in background", e);
                        }
                    }
                }, 1000); // 1 second
            }

            return code;
        } catch (NativeDaemonConnectorException e) {
            // Decryption failed
            return e.getCode();
        }
    }

    public int encryptStorage(int type, String password) {
        if (TextUtils.isEmpty(password) && type != StorageManager.CRYPT_TYPE_DEFAULT) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "encrypting storage...");
        }

        try {
            mConnector.execute("cryptfs", "enablecrypto", "inplace", CRYPTO_TYPES[type],
                               new SensitiveArg(toHex(password)));
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }

        return 0;
    }

    /** Set the password for encrypting the master key.
     *  @param type One of the CRYPTO_TYPE_XXX consts defined in StorageManager.
     *  @param password The password to set.
     */
    public int changeEncryptionPassword(int type, String password) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "changing encryption password...");
        }

        try {
            NativeDaemonEvent event = mConnector.execute("cryptfs", "changepw", CRYPTO_TYPES[type],
                        new SensitiveArg(toHex(password)));
            return Integer.parseInt(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }
    }

    /**
     * Validate a user-supplied password string with cryptfs
     */
    @Override
    public int verifyEncryptionPassword(String password) throws RemoteException {
        // Only the system process is permitted to validate passwords
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException("no permission to access the crypt keeper");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "validating encryption password...");
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "verifypw", new SensitiveArg(toHex(password)));
            Slog.i(TAG, "cryptfs verifypw => " + event.getMessage());
            return Integer.parseInt(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }
    }

    /**
     * Get the type of encryption used to encrypt the master key.
     * @return The type, one of the CRYPT_TYPE_XXX consts from StorageManager.
     */
    @Override
    public int getPasswordType() {

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "getpwtype");
            for (int i = 0; i < CRYPTO_TYPES.length; ++i) {
                if (CRYPTO_TYPES[i].equals(event.getMessage()))
                    return i;
            }

            throw new IllegalStateException("unexpected return from cryptfs");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    /**
     * Set a field in the crypto header.
     * @param field field to set
     * @param contents contents to set in field
     */
    @Override
    public void setField(String field, String contents) throws RemoteException {

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "setfield", field, contents);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    /**
     * Gets a field from the crypto header.
     * @param field field to get
     * @return contents of field
     */
    @Override
    public String getField(String field) throws RemoteException {

        waitForReady();

        final NativeDaemonEvent event;
        try {
            final String[] contents = NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("cryptfs", "getfield", field),
                    VoldResponseCode.CryptfsGetfieldResult);
            String result = new String();
            for (String content : contents) {
                result += content;
            }
            return result;
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public String getPassword() throws RemoteException {
        if (!isReady()) {
            return new String();
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "getpw");
            if ("-1".equals(event.getMessage())) {
                // -1 equals no password
                return null;
            }
            return fromHex(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Invalid response to getPassword");
            return null;
        }
    }

    @Override
    public void clearPassword() throws RemoteException {
        if (!isReady()) {
            return;
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "clearpw");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public int mkdirs(String callingPkg, String appPath) {
        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        final UserEnvironment userEnv = new UserEnvironment(userId);

        // Validate that reported package name belongs to caller
        final AppOpsManager appOps = (AppOpsManager) mContext.getSystemService(
                Context.APP_OPS_SERVICE);
        appOps.checkPackage(Binder.getCallingUid(), callingPkg);

        File appFile = null;
        try {
            appFile = new File(appPath).getCanonicalFile();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to resolve " + appPath + ": " + e);
            return -1;
        }

        // Try translating the app path into a vold path, but require that it
        // belong to the calling package.
        if (FileUtils.contains(userEnv.buildExternalStorageAppDataDirs(callingPkg), appFile) ||
                FileUtils.contains(userEnv.buildExternalStorageAppObbDirs(callingPkg), appFile) ||
                FileUtils.contains(userEnv.buildExternalStorageAppMediaDirs(callingPkg), appFile)) {
            appPath = appFile.getAbsolutePath();
            if (!appPath.endsWith("/")) {
                appPath = appPath + "/";
            }

            try {
                mConnector.execute("volume", "mkdirs", appPath);
                return 0;
            } catch (NativeDaemonConnectorException e) {
                return e.getCode();
            }
        }

        throw new SecurityException("Invalid mkdirs path: " + appFile);
    }

    @Override
    public StorageVolume[] getVolumeList(int userId) {
        final ArrayList<StorageVolume> res = new ArrayList<>();
        boolean foundPrimary = false;

        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.isVisibleToUser(userId)) {
                    final StorageVolume userVol = vol.buildStorageVolume(mContext, userId);
                    if (vol.isPrimary()) {
                        res.add(0, userVol);
                        foundPrimary = true;
                    } else {
                        res.add(userVol);
                    }
                }
            }
        }

        if (!foundPrimary) {
            Log.w(TAG, "No primary storage defined yet; hacking together a stub");

            final boolean primaryPhysical = SystemProperties.getBoolean(
                    StorageManager.PROP_PRIMARY_PHYSICAL, false);

            final String id = "stub_primary";
            final File path = Environment.getLegacyExternalStorageDirectory();
            final String description = mContext.getString(android.R.string.unknownName);
            final boolean primary = true;
            final boolean removable = primaryPhysical;
            final boolean emulated = !primaryPhysical;
            final long mtpReserveSize = 0L;
            final boolean allowMassStorage = false;
            final long maxFileSize = 0L;
            final UserHandle owner = new UserHandle(userId);
            final String uuid = null;
            final String state = Environment.MEDIA_REMOVED;

            res.add(0, new StorageVolume(id, MtpStorage.getStorageIdForIndex(0), path,
                    description, primary, removable, emulated, mtpReserveSize,
                    allowMassStorage, maxFileSize, owner, uuid, state));
        }

        return res.toArray(new StorageVolume[res.size()]);
    }

    @Override
    public DiskInfo[] getDisks() {
        synchronized (mLock) {
            final DiskInfo[] res = new DiskInfo[mDisks.size()];
            for (int i = 0; i < mDisks.size(); i++) {
                res[i] = mDisks.valueAt(i);
            }
            return res;
        }
    }

    @Override
    public VolumeInfo[] getVolumes(int flags) {
        if ((flags & StorageManager.FLAG_ALL_METADATA) != 0) {
            // TODO: implement support for returning all metadata
            throw new UnsupportedOperationException();
        }

        synchronized (mLock) {
            final VolumeInfo[] res = new VolumeInfo[mVolumes.size()];
            for (int i = 0; i < mVolumes.size(); i++) {
                res[i] = mVolumes.valueAt(i);
            }
            return res;
        }
    }

    private void addObbStateLocked(ObbState obbState) throws RemoteException {
        final IBinder binder = obbState.getBinder();
        List<ObbState> obbStates = mObbMounts.get(binder);

        if (obbStates == null) {
            obbStates = new ArrayList<ObbState>();
            mObbMounts.put(binder, obbStates);
        } else {
            for (final ObbState o : obbStates) {
                if (o.rawPath.equals(obbState.rawPath)) {
                    throw new IllegalStateException("Attempt to add ObbState twice. "
                            + "This indicates an error in the MountService logic.");
                }
            }
        }

        obbStates.add(obbState);
        try {
            obbState.link();
        } catch (RemoteException e) {
            /*
             * The binder died before we could link it, so clean up our state
             * and return failure.
             */
            obbStates.remove(obbState);
            if (obbStates.isEmpty()) {
                mObbMounts.remove(binder);
            }

            // Rethrow the error so mountObb can get it
            throw e;
        }

        mObbPathToStateMap.put(obbState.rawPath, obbState);
    }

    private void removeObbStateLocked(ObbState obbState) {
        final IBinder binder = obbState.getBinder();
        final List<ObbState> obbStates = mObbMounts.get(binder);
        if (obbStates != null) {
            if (obbStates.remove(obbState)) {
                obbState.unlink();
            }
            if (obbStates.isEmpty()) {
                mObbMounts.remove(binder);
            }
        }

        mObbPathToStateMap.remove(obbState.rawPath);
    }

    private class ObbActionHandler extends Handler {
        private boolean mBound = false;
        private final List<ObbAction> mActions = new LinkedList<ObbAction>();

        ObbActionHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case OBB_RUN_ACTION: {
                    final ObbAction action = (ObbAction) msg.obj;

                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_RUN_ACTION: " + action.toString());

                    // If a bind was already initiated we don't really
                    // need to do anything. The pending install
                    // will be processed later on.
                    if (!mBound) {
                        // If this is the only one pending we might
                        // have to bind to the service again.
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            action.handleError();
                            return;
                        }
                    }

                    mActions.add(action);
                    break;
                }
                case OBB_MCS_BOUND: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_BOUND");
                    if (msg.obj != null) {
                        mContainerService = (IMediaContainerService) msg.obj;
                    }
                    if (mContainerService == null) {
                        // Something seriously wrong. Bail out
                        Slog.e(TAG, "Cannot bind to media container service");
                        for (ObbAction action : mActions) {
                            // Indicate service bind error
                            action.handleError();
                        }
                        mActions.clear();
                    } else if (mActions.size() > 0) {
                        final ObbAction action = mActions.get(0);
                        if (action != null) {
                            action.execute(this);
                        }
                    } else {
                        // Should never happen ideally.
                        Slog.w(TAG, "Empty queue");
                    }
                    break;
                }
                case OBB_MCS_RECONNECT: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_RECONNECT");
                    if (mActions.size() > 0) {
                        if (mBound) {
                            disconnectService();
                        }
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            for (ObbAction action : mActions) {
                                // Indicate service bind error
                                action.handleError();
                            }
                            mActions.clear();
                        }
                    }
                    break;
                }
                case OBB_MCS_UNBIND: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_UNBIND");

                    // Delete pending install
                    if (mActions.size() > 0) {
                        mActions.remove(0);
                    }
                    if (mActions.size() == 0) {
                        if (mBound) {
                            disconnectService();
                        }
                    } else {
                        // There are more pending requests in queue.
                        // Just post MCS_BOUND message to trigger processing
                        // of next pending install.
                        mObbActionHandler.sendEmptyMessage(OBB_MCS_BOUND);
                    }
                    break;
                }
                case OBB_FLUSH_MOUNT_STATE: {
                    final String path = (String) msg.obj;

                    if (DEBUG_OBB)
                        Slog.i(TAG, "Flushing all OBB state for path " + path);

                    synchronized (mObbMounts) {
                        final List<ObbState> obbStatesToRemove = new LinkedList<ObbState>();

                        final Iterator<ObbState> i = mObbPathToStateMap.values().iterator();
                        while (i.hasNext()) {
                            final ObbState state = i.next();

                            /*
                             * If this entry's source file is in the volume path
                             * that got unmounted, remove it because it's no
                             * longer valid.
                             */
                            if (state.canonicalPath.startsWith(path)) {
                                obbStatesToRemove.add(state);
                            }
                        }

                        for (final ObbState obbState : obbStatesToRemove) {
                            if (DEBUG_OBB)
                                Slog.i(TAG, "Removing state for " + obbState.rawPath);

                            removeObbStateLocked(obbState);

                            try {
                                obbState.token.onObbResult(obbState.rawPath, obbState.nonce,
                                        OnObbStateChangeListener.UNMOUNTED);
                            } catch (RemoteException e) {
                                Slog.i(TAG, "Couldn't send unmount notification for  OBB: "
                                        + obbState.rawPath);
                            }
                        }
                    }
                    break;
                }
            }
        }

        private boolean connectToService() {
            if (DEBUG_OBB)
                Slog.i(TAG, "Trying to bind to DefaultContainerService");

            Intent service = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
            if (mContext.bindService(service, mDefContainerConn, Context.BIND_AUTO_CREATE)) {
                mBound = true;
                return true;
            }
            return false;
        }

        private void disconnectService() {
            mContainerService = null;
            mBound = false;
            mContext.unbindService(mDefContainerConn);
        }
    }

    abstract class ObbAction {
        private static final int MAX_RETRIES = 3;
        private int mRetries;

        ObbState mObbState;

        ObbAction(ObbState obbState) {
            mObbState = obbState;
        }

        public void execute(ObbActionHandler handler) {
            try {
                if (DEBUG_OBB)
                    Slog.i(TAG, "Starting to execute action: " + toString());
                mRetries++;
                if (mRetries > MAX_RETRIES) {
                    Slog.w(TAG, "Failed to invoke remote methods on default container service. Giving up");
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                    handleError();
                    return;
                } else {
                    handleExecute();
                    if (DEBUG_OBB)
                        Slog.i(TAG, "Posting install MCS_UNBIND");
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                }
            } catch (RemoteException e) {
                if (DEBUG_OBB)
                    Slog.i(TAG, "Posting install MCS_RECONNECT");
                mObbActionHandler.sendEmptyMessage(OBB_MCS_RECONNECT);
            } catch (Exception e) {
                if (DEBUG_OBB)
                    Slog.d(TAG, "Error handling OBB action", e);
                handleError();
                mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
            }
        }

        abstract void handleExecute() throws RemoteException, IOException;
        abstract void handleError();

        protected ObbInfo getObbInfo() throws IOException {
            ObbInfo obbInfo;
            try {
                obbInfo = mContainerService.getObbInfo(mObbState.ownerPath);
            } catch (RemoteException e) {
                Slog.d(TAG, "Couldn't call DefaultContainerService to fetch OBB info for "
                        + mObbState.ownerPath);
                obbInfo = null;
            }
            if (obbInfo == null) {
                throw new IOException("Couldn't read OBB file: " + mObbState.ownerPath);
            }
            return obbInfo;
        }

        protected void sendNewStatusOrIgnore(int status) {
            if (mObbState == null || mObbState.token == null) {
                return;
            }

            try {
                mObbState.token.onObbResult(mObbState.rawPath, mObbState.nonce, status);
            } catch (RemoteException e) {
                Slog.w(TAG, "MountServiceListener went away while calling onObbStateChanged");
            }
        }
    }

    class MountObbAction extends ObbAction {
        private final String mKey;
        private final int mCallingUid;

        MountObbAction(ObbState obbState, String key, int callingUid) {
            super(obbState);
            mKey = key;
            mCallingUid = callingUid;
        }

        @Override
        public void handleExecute() throws IOException, RemoteException {
            waitForReady();
            warnOnNotMounted();

            final ObbInfo obbInfo = getObbInfo();

            if (!isUidOwnerOfPackageOrSystem(obbInfo.packageName, mCallingUid)) {
                Slog.w(TAG, "Denied attempt to mount OBB " + obbInfo.filename
                        + " which is owned by " + obbInfo.packageName);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
                return;
            }

            final boolean isMounted;
            synchronized (mObbMounts) {
                isMounted = mObbPathToStateMap.containsKey(mObbState.rawPath);
            }
            if (isMounted) {
                Slog.w(TAG, "Attempt to mount OBB which is already mounted: " + obbInfo.filename);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_ALREADY_MOUNTED);
                return;
            }

            final String hashedKey;
            if (mKey == null) {
                hashedKey = "none";
            } else {
                try {
                    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");

                    KeySpec ks = new PBEKeySpec(mKey.toCharArray(), obbInfo.salt,
                            PBKDF2_HASH_ROUNDS, CRYPTO_ALGORITHM_KEY_SIZE);
                    SecretKey key = factory.generateSecret(ks);
                    BigInteger bi = new BigInteger(key.getEncoded());
                    hashedKey = bi.toString(16);
                } catch (NoSuchAlgorithmException e) {
                    Slog.e(TAG, "Could not load PBKDF2 algorithm", e);
                    sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
                    return;
                } catch (InvalidKeySpecException e) {
                    Slog.e(TAG, "Invalid key spec when loading PBKDF2 algorithm", e);
                    sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
                    return;
                }
            }

            int rc = StorageResultCode.OperationSucceeded;
            try {
                mConnector.execute("obb", "mount", mObbState.voldPath, new SensitiveArg(hashedKey),
                        mObbState.ownerGid);
            } catch (NativeDaemonConnectorException e) {
                int code = e.getCode();
                if (code != VoldResponseCode.OpFailedStorageBusy) {
                    rc = StorageResultCode.OperationFailedInternalError;
                }
            }

            if (rc == StorageResultCode.OperationSucceeded) {
                if (DEBUG_OBB)
                    Slog.d(TAG, "Successfully mounted OBB " + mObbState.voldPath);

                synchronized (mObbMounts) {
                    addObbStateLocked(mObbState);
                }

                sendNewStatusOrIgnore(OnObbStateChangeListener.MOUNTED);
            } else {
                Slog.e(TAG, "Couldn't mount OBB file: " + rc);

                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT);
            }
        }

        @Override
        public void handleError() {
            sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("MountObbAction{");
            sb.append(mObbState);
            sb.append('}');
            return sb.toString();
        }
    }

    class UnmountObbAction extends ObbAction {
        private final boolean mForceUnmount;

        UnmountObbAction(ObbState obbState, boolean force) {
            super(obbState);
            mForceUnmount = force;
        }

        @Override
        public void handleExecute() throws IOException {
            waitForReady();
            warnOnNotMounted();

            final ObbInfo obbInfo = getObbInfo();

            final ObbState existingState;
            synchronized (mObbMounts) {
                existingState = mObbPathToStateMap.get(mObbState.rawPath);
            }

            if (existingState == null) {
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_NOT_MOUNTED);
                return;
            }

            if (existingState.ownerGid != mObbState.ownerGid) {
                Slog.w(TAG, "Permission denied attempting to unmount OBB " + existingState.rawPath
                        + " (owned by GID " + existingState.ownerGid + ")");
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
                return;
            }

            int rc = StorageResultCode.OperationSucceeded;
            try {
                final Command cmd = new Command("obb", "unmount", mObbState.voldPath);
                if (mForceUnmount) {
                    cmd.appendArg("force");
                }
                mConnector.execute(cmd);
            } catch (NativeDaemonConnectorException e) {
                int code = e.getCode();
                if (code == VoldResponseCode.OpFailedStorageBusy) {
                    rc = StorageResultCode.OperationFailedStorageBusy;
                } else if (code == VoldResponseCode.OpFailedStorageNotFound) {
                    // If it's not mounted then we've already won.
                    rc = StorageResultCode.OperationSucceeded;
                } else {
                    rc = StorageResultCode.OperationFailedInternalError;
                }
            }

            if (rc == StorageResultCode.OperationSucceeded) {
                synchronized (mObbMounts) {
                    removeObbStateLocked(existingState);
                }

                sendNewStatusOrIgnore(OnObbStateChangeListener.UNMOUNTED);
            } else {
                Slog.w(TAG, "Could not unmount OBB: " + existingState);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_COULD_NOT_UNMOUNT);
            }
        }

        @Override
        public void handleError() {
            sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UnmountObbAction{");
            sb.append(mObbState);
            sb.append(",force=");
            sb.append(mForceUnmount);
            sb.append('}');
            return sb.toString();
        }
    }

    @VisibleForTesting
    public static String buildObbPath(final String canonicalPath, int userId, boolean forVold) {
        // TODO: allow caller to provide Environment for full testing
        // TODO: extend to support OBB mounts on secondary external storage

        // Only adjust paths when storage is emulated
        if (!Environment.isExternalStorageEmulated()) {
            return canonicalPath;
        }

        String path = canonicalPath.toString();

        // First trim off any external storage prefix
        final UserEnvironment userEnv = new UserEnvironment(userId);

        // /storage/emulated/0
        final String externalPath = userEnv.getExternalStorageDirectory().getAbsolutePath();
        // /storage/emulated_legacy
        final String legacyExternalPath = Environment.getLegacyExternalStorageDirectory()
                .getAbsolutePath();

        if (path.startsWith(externalPath)) {
            path = path.substring(externalPath.length() + 1);
        } else if (path.startsWith(legacyExternalPath)) {
            path = path.substring(legacyExternalPath.length() + 1);
        } else {
            return canonicalPath;
        }

        // Handle special OBB paths on emulated storage
        final String obbPath = "Android/obb";
        if (path.startsWith(obbPath)) {
            path = path.substring(obbPath.length() + 1);

            final UserEnvironment ownerEnv = new UserEnvironment(UserHandle.USER_OWNER);
            return new File(ownerEnv.buildExternalStorageAndroidObbDirs()[0], path)
                    .getAbsolutePath();
        }

        // Handle normal external storage paths
        return new File(userEnv.getExternalStorageDirectory(), path).getAbsolutePath();
    }

    private static class Callbacks extends Handler {
        private static final int MSG_STORAGE_STATE_CHANGED = 1;
        private static final int MSG_VOLUME_STATE_CHANGED = 2;
        private static final int MSG_VOLUME_METADATA_CHANGED = 3;
        private static final int MSG_DISK_UNSUPPORTED = 4;

        private final RemoteCallbackList<IMountServiceListener>
                mCallbacks = new RemoteCallbackList<>();

        public Callbacks(Looper looper) {
            super(looper);
        }

        public void register(IMountServiceListener callback) {
            mCallbacks.register(callback);
        }

        public void unregister(IMountServiceListener callback) {
            mCallbacks.unregister(callback);
        }

        @Override
        public void handleMessage(Message msg) {
            final SomeArgs args = (SomeArgs) msg.obj;
            final int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                final IMountServiceListener callback = mCallbacks.getBroadcastItem(i);
                try {
                    invokeCallback(callback, msg.what, args);
                } catch (RemoteException ignored) {
                }
            }
            mCallbacks.finishBroadcast();
            args.recycle();
        }

        private void invokeCallback(IMountServiceListener callback, int what, SomeArgs args)
                throws RemoteException {
            switch (what) {
                case MSG_STORAGE_STATE_CHANGED: {
                    callback.onStorageStateChanged((String) args.arg1, (String) args.arg2,
                            (String) args.arg3);
                    break;
                }
                case MSG_VOLUME_STATE_CHANGED: {
                    callback.onVolumeStateChanged((VolumeInfo) args.arg1, args.argi2, args.argi3);
                    break;
                }
                case MSG_VOLUME_METADATA_CHANGED: {
                    callback.onVolumeMetadataChanged((VolumeInfo) args.arg1);
                    break;
                }
                case MSG_DISK_UNSUPPORTED: {
                    callback.onDiskUnsupported((DiskInfo) args.arg1);
                    break;
                }
            }
        }

        private void notifyStorageStateChanged(String path, String oldState, String newState) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = path;
            args.arg2 = oldState;
            args.arg3 = newState;
            obtainMessage(MSG_STORAGE_STATE_CHANGED, args).sendToTarget();
        }

        private void notifyVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = vol;
            args.argi2 = oldState;
            args.argi3 = newState;
            obtainMessage(MSG_VOLUME_STATE_CHANGED, args).sendToTarget();
        }

        private void notifyVolumeMetadataChanged(VolumeInfo vol) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = vol;
            obtainMessage(MSG_VOLUME_METADATA_CHANGED, args).sendToTarget();
        }

        private void notifyDiskUnsupported(DiskInfo disk) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = disk;
            obtainMessage(MSG_DISK_UNSUPPORTED, args).sendToTarget();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

        for (String arg : args) {
            if ("--clear-metadata".equals(arg)) {
                synchronized (mLock) {
                    mMetadata.clear();
                    writeMetadataLocked();
                }
            }
        }

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ", 160);
        synchronized (mLock) {
            pw.println("Disks:");
            pw.increaseIndent();
            for (int i = 0; i < mDisks.size(); i++) {
                final DiskInfo disk = mDisks.valueAt(i);
                disk.dump(pw);
            }
            pw.decreaseIndent();

            pw.println();
            pw.println("Volumes:");
            pw.increaseIndent();
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (VolumeInfo.ID_PRIVATE_INTERNAL.equals(vol.id)) continue;
                vol.dump(pw);
            }
            pw.decreaseIndent();

            pw.println();
            pw.println("Metadata:");
            pw.increaseIndent();
            for (int i = 0; i < mMetadata.size(); i++) {
                final VolumeMetadata meta = mMetadata.valueAt(i);
                meta.dump(pw);
            }
            pw.decreaseIndent();
        }

        synchronized (mObbMounts) {
            pw.println();
            pw.println("mObbMounts:");
            pw.increaseIndent();
            final Iterator<Entry<IBinder, List<ObbState>>> binders = mObbMounts.entrySet()
                    .iterator();
            while (binders.hasNext()) {
                Entry<IBinder, List<ObbState>> e = binders.next();
                pw.println(e.getKey() + ":");
                pw.increaseIndent();
                final List<ObbState> obbStates = e.getValue();
                for (final ObbState obbState : obbStates) {
                    pw.println(obbState);
                }
                pw.decreaseIndent();
            }
            pw.decreaseIndent();

            pw.println();
            pw.println("mObbPathToStateMap:");
            pw.increaseIndent();
            final Iterator<Entry<String, ObbState>> maps = mObbPathToStateMap.entrySet().iterator();
            while (maps.hasNext()) {
                final Entry<String, ObbState> e = maps.next();
                pw.print(e.getKey());
                pw.print(" -> ");
                pw.println(e.getValue());
            }
            pw.decreaseIndent();
        }

        pw.println();
        pw.println("mConnection:");
        pw.increaseIndent();
        mConnector.dump(fd, pw, args);
        pw.decreaseIndent();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        pw.println();
        pw.print("Last maintenance: ");
        pw.println(sdf.format(new Date(mLastMaintenance)));
    }

    /** {@inheritDoc} */
    @Override
    public void monitor() {
        if (mConnector != null) {
            mConnector.monitor();
        }
    }
}
