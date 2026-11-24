package io.github.justdreemurr.encore;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class EncoreModule extends XposedModule {
    private static final String TAG = "Encore";
    private static final String VERSION = "0.7.2";
    private static final int EVENT_LIMIT = 80;
    private static final String BLUETOOTH_PACKAGE = RouteSettings.BLUETOOTH_PACKAGE;
    private static final String TARGET_BUILD = RouteSettings.SUPPORTED_FIRMWARE;
    private static final String TARGET_NAME = "OPPO Enco X4";
    private static final String LOW_LATENCY_STATE_MACHINE =
            "com.oplus.bluetooth.feature.lowlatency.BluetoothLowLatencyStateMachine";
    private static final String WEAR_CHECK = "com.oplus.bluetooth.feature.wearcheck.OplusBluetoothWearCheck";
    private static final String LOW_LATENCY_SERVICE = "com.oplus.bluetooth.feature.lowlatency.BluetoothLowLatencyService";
    private static final String DUAL_MODE_MANAGER = "com.oplus.bluetooth.btservice.OplusDualModeManagerImpl";
    private static final long ROUTE_SETTLE_MS = 250;
    private static final long VERIFY_DELAY_MS = 1500;
    private static final long STARTUP_FALLBACK_WINDOW_MS = 3000;
    private static final long HFP_RETRY_WINDOW_MS = 8000;
    private static final long DIRECT_ROUTE_WAIT_MS = 5000;
    private static final long LOW_LATENCY_EXIT_SETTLE_MS = 2000;
    private static final long GAME_EXIT_KEEP_LE_MS = 3000;
    private static final long WALT_PREPARE_TIMEOUT_MS = 12000;
    private static final int USER_DISCONNECT_REASON = 1111;
    private static final long RECONNECT_POLL_MS = 400;
    private static final long RECONNECT_DISCONNECT_TIMEOUT_MS = 6000;
    private static final long RECONNECT_CONNECT_TIMEOUT_MS = 15000;
    private static final int INVALID_GROUP = -1;
    private boolean bluetoothProcess;
    private boolean installed;
    private final ArrayDeque<String> events = new ArrayDeque<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        bluetoothProcess = BLUETOOTH_PACKAGE.equals(param.getProcessName());
        report(Log.INFO, "v" + VERSION + " loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (BLUETOOTH_PACKAGE.equals(param.getPackageName())) {
            report(Log.INFO, "Bluetooth package loaded (first=" + param.isFirstPackage() + ")");
        }
    }

    @Override
    public synchronized void onPackageReady(PackageReadyParam param) {
        if (!bluetoothProcess || !BLUETOOTH_PACKAGE.equals(param.getPackageName()) || installed) {
            return;
        }
        report(Log.INFO, "Bluetooth package ready (first=" + param.isFirstPackage() + ")");
        if (!TARGET_BUILD.equals(Build.DISPLAY)) {
            report(Log.WARN, "Unsupported firmware; routing disabled: " + Build.DISPLAY);
            return;
        }
        try {
            RouteHooks route = new RouteHooks(param.getClassLoader());
            List<HookHandle> handles = new ArrayList<>();
            try {
                handles.add(hook(route.admStart).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object adm = chain.getThisObject();
                            Object result = chain.proceed();
                            route.installWearCheckHooks(adm);
                            route.installWhitelistHooks(adm);
                            route.installGameExitHook(adm);
                            route.schedule(adm);
                            return result;
                        }));
                handles.add(hook(route.admDump).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            PrintWriter writer = (PrintWriter) chain.getArg(0);
                            writer.println("  Encore version: " + VERSION);
                            writer.println("  Encore: " + route.diagnostics());
                            writer.println("  Encore prerequisites: " + route.prerequisites(chain.getThisObject()));
                            dumpEvents(writer);
                            return chain.proceed();
                        }));
                handles.add(hook(route.profileActiveChanged)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        int profile = (int) chain.getArg(0);
                        if (profile == BluetoothProfile.LE_AUDIO) {
                            route.onLeActiveChanged((BluetoothDevice) chain.getArg(1));
                        }
                        if (profile == BluetoothProfile.A2DP || profile == BluetoothProfile.LE_AUDIO) {
                            route.schedule(chain.getThisObject());
                        }
                        return result;
                    }));
                handles.add(hook(route.leSetInGame).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> chain.proceed(new Object[] {route.inGameFor((boolean) chain.getArg(0))})));
                handles.add(hook(route.profileConnectionChanged).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            int profile = (int) chain.getArg(0);
                            int state = (int) chain.getArg(3);
                            BluetoothDevice device = (BluetoothDevice) chain.getArg(1);
                            if (profile == BluetoothProfile.A2DP && state == BluetoothProfile.STATE_DISCONNECTED) {
                                route.clearHfpRetry(device);
                                route.onA2dpDisconnected(device);
                            }
                            if ((profile == BluetoothProfile.LE_AUDIO && state == BluetoothProfile.STATE_CONNECTED)
                                    || (profile == BluetoothProfile.HEADSET
                                            && state == BluetoothProfile.STATE_DISCONNECTED
                                            && device != null && TARGET_NAME.equals(device.getName()))) {
                                route.schedule(chain.getThisObject());
                            }
                            return result;
                        }));
                handles.add(hook(route.hfpConnected).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                            if (device != null && TARGET_NAME.equals(device.getName())) {
                                route.schedule(chain.getThisObject());
                            }
                            return result;
                        }));
                handles.add(hook(route.hfpSetActive).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            route.noteHfpActivation((BluetoothDevice) chain.getArg(0));
                            return chain.proceed();
                        }));
                handles.add(hook(route.connectLeNative).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            route.prepareGameVnd(chain.getThisObject(), (BluetoothDevice) chain.getArg(0));
                            return chain.proceed();
                        }));
                handles.add(hook(route.setClassicActive).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object a2dp = chain.getThisObject();
                            Object adm = route.a2dpAdm.get(a2dp);
                            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                            route.clearGameVndForOtherDevice(device);
                            if (route.keepX4OnLe(device)) {
                                return false;
                            }
                            if (route.deferA2dp(adm, a2dp, device)) {
                                return false;
                            }
                            Object result = chain.proceed();
                            if (Boolean.TRUE.equals(result)) {
                                route.schedule(adm);
                            }
                            return result;
                        }));
                handles.add(hook(route.setLeServiceActive).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                            if (device == null) {
                                if (route.handleLeRemoval(chain.getThisObject(), true)) {
                                    return true;
                                }
                            } else {
                                route.clearGameVndForOtherDevice(device);
                            }
                            return chain.proceed();
                        }));
                handles.add(hook(route.removeLeServiceActive).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            route.handleLeRemoval(chain.getThisObject(), false);
                            return chain.proceed();
                        }));
            } catch (RuntimeException | LinkageError error) {
                for (HookHandle handle : handles) {
                    handle.unhook();
                }
                throw error;
            }
            installed = true;
            route.watchRouteSettings();
            report(Log.INFO, "Ready: Enco X4 LE Audio routing only");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report(Log.WARN, "Firmware bindings unavailable; routing disabled", error);
        }
    }

    private void report(int level, String message) {
        Log.println(level, TAG, message);
        log(level, TAG, message);
        remember(level, message, null);
    }

    private void report(int level, String message, Throwable error) {
        Log.w(TAG, message, error);
        log(level, TAG, message, error);
        remember(level, message, error);
    }

    /** Keeps recent events in memory so diagnostics survive ColorOS log rate limiting. */
    private void remember(int level, String message, Throwable error) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        String levelName = level >= Log.ERROR ? "E" : level == Log.WARN ? "W" : "I";
        String detail = error == null ? "" : " (" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")";
        synchronized (events) {
            if (events.size() == EVENT_LIMIT) {
                events.removeFirst();
            }
            events.addLast(time + " " + levelName + " " + message + detail);
        }
    }

    private void dumpEvents(PrintWriter writer) {
        synchronized (events) {
            for (String event : events) {
                writer.println("  Encore event: " + event);
            }
        }
    }

    private record PendingRoute(Object le, Object a2dp, BluetoothDevice classic, BluetoothDevice lead,
            AudioManager audio, Context context, Object extension, Field wiredOutput, int group,
            long startedAt, boolean retry, AtomicBoolean startupFallbackSuppressed) {}

    private record DeferredA2dp(Object adm, Handler handler, Object a2dp, BluetoothDevice device) {}

    private final class RouteHooks {
        final Method profileActiveChanged;
        final Method profileConnectionChanged;
        final Method hfpConnected;
        final Method hfpSetActive;
        final Method connectLeNative;
        final Method admStart;
        final Method admDump;
        final Method setClassicActive;
        final Method setLeServiceActive;
        final Method removeLeServiceActive;
        final Method leSetInGame;
        final Field a2dpAdm;
        private final Field handler;
        private final Field adapter;
        private final Field adapterAdm;
        private final Field leNativeAdapter;
        private final Field audioManager;
        private final Field admExtension;
        private final Field admLock;
        private final Field hfpConnectedDevices;
        private final Method broadcasting;
        private final Method hearingAids;
        private final Method leActive;
        private final Method setLeActive;
        private final Method a2dpService;
        private final Method headsetService;
        private final Method headsetConnectionState;
        private final Method leService;
        private final Method a2dpActive;
        private final Method a2dpConnectionState;
        private final Method restoreA2dp;
        private final Method findBrDevice;
        private final Method leConnected;
        private final Method leActiveGroup;
        private final Method leConnectionState;
        private final Method leGroup;
        private final Method leLead;
        private final Method leStreamAvailable;
        private final Method propertyBoolean;
        private final Method leSetInGameNative;
        private final Field leNativeInterface;
        private boolean systemInGame;
        private boolean appliedInGame;
        private volatile boolean x4LeActive;
        private volatile String sceneDecision = "Follow system";
        private final ThreadLocal<Boolean> wearKeepsLe = new ThreadLocal<>();
        private volatile boolean wearHooksInstalled;
        private volatile String wearDecision = "Waiting for wear check";
        private final ThreadLocal<Boolean> checkingX4DualConnection = new ThreadLocal<>();
        private final ThreadLocal<Boolean> whitelistAppsChanged = new ThreadLocal<>();
        private volatile boolean whitelistHooksInstalled;
        private volatile String whitelistDecision = "Waiting for dual-mode check";
        private final Object queueLock = new Object();
        private Handler queuedHandler;
        private Handler routeHandler;
        private final AtomicInteger scheduled = new AtomicInteger();
        private final AtomicInteger executed = new AtomicInteger();
        private final AtomicReference<PendingRoute> pendingRoute = new AtomicReference<>();
        private final AtomicReference<PendingRoute> hfpRetry = new AtomicReference<>();
        private final AtomicReference<DeferredA2dp> deferredA2dp = new AtomicReference<>();
        private final SharedPreferences routeSettings;
        private final SharedPreferences.OnSharedPreferenceChangeListener routeSettingChanged =
                this::onRouteSettingChanged;
        private GameVndControl gameVnd;
        private BluetoothDevice gameVndClassic;
        private BluetoothDevice gameVndLead;
        private BluetoothDevice gameVndRemote;
        private long gameVndRequestId;
        private volatile String gameVndDecision = "Waiting for X4";
        private volatile String gameVndRemoteDecision = "No earbud WALT notification";
        private BluetoothDevice directTried;
        private volatile String directResult;
        private volatile String lastFailure;
        private volatile int lastRequestedGroup = INVALID_GROUP;
        private volatile long lowLatencyExitAt;
        private volatile long gameExitKeepLeUntil;
        private volatile boolean gameExitHookInstalled;
        private volatile String lastDecision;
        private volatile String lastRequest;
        private volatile String lastRemoval;
        private volatile String lastVerification;
        private final Field adapterInstance;
        private final Method bondedDevices;
        private final Method deviceConnected;
        private final Method disconnectProfiles;
        private final Method connectProfiles;
        private final Handler reconnectHandler = Handler.createAsync(Looper.getMainLooper());
        private long reconnectId;
        private volatile String reconnectStatus = "0 IDLE";
        private VendorBindings vendor;
        private PrerequisiteBindings prerequisiteBindings;

        RouteHooks(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> adm = load(loader, "com.android.bluetooth.btservice.ActiveDeviceManager");
            Class<?> adapterType = load(loader, "com.android.bluetooth.btservice.AdapterService");
            Class<?> a2dp = load(loader, "com.android.bluetooth.a2dp.A2dpService");
            Class<?> headset = load(loader, "com.android.bluetooth.hfp.HeadsetService");
            Class<?> le = load(loader, "com.android.bluetooth.le_audio.LeAudioService");
            Class<?> leNative = load(loader, "com.android.bluetooth.le_audio.LeAudioNativeInterface");
            Class<?> properties = load(loader, "android.os.SystemProperties");

            profileActiveChanged = method(adm, "profileActiveDeviceChanged", int.class, BluetoothDevice.class);
            profileConnectionChanged = method(adm, "profileConnectionStateChanged", int.class, BluetoothDevice.class,
                    int.class, int.class);
            hfpConnected = method(adm, "handleHfpConnected", BluetoothDevice.class);
            hfpSetActive = method(headset, "setActiveDevice", BluetoothDevice.class);
            connectLeNative = method(leNative, "connectLeAudio", BluetoothDevice.class);
            admStart = method(adm, "start");
            admDump = method(adm, "dump", PrintWriter.class);
            setClassicActive = method(a2dp, "setActiveDevice", BluetoothDevice.class);
            setLeServiceActive = method(le, "setActiveDevice", BluetoothDevice.class);
            removeLeServiceActive = method(le, "removeActiveDevice", boolean.class);
            leSetInGame = method(leNative, "setInGame", boolean.class);
            leSetInGameNative = method(leNative, "setInGameNative", boolean.class);
            leNativeInterface = field(le, "mNativeInterface");
            a2dpAdm = field(a2dp, "mActiveDeviceManager");
            handler = field(adm, "mHandler");
            adapter = field(adm, "mAdapterService");
            adapterAdm = field(adapterType, "mActiveDeviceManager");
            leNativeAdapter = field(leNative, "mAdapterService");
            audioManager = field(adm, "mAudioManager");
            admExtension = field(adm, "mOplusAdmExt");
            admLock = field(adm, "mLock");
            hfpConnectedDevices = field(adm, "mHfpConnectedDevices");
            broadcasting = method(adm, "isBroadcastingAudio");
            hearingAids = method(adm, "getHearingAidActiveDevices");
            leActive = method(adm, "getLeAudioActiveDevice");
            setLeActive = method(adm, "setLeAudioActiveDevice", BluetoothDevice.class, boolean.class);
            a2dpService = method(adapterType, "getA2dpService");
            headsetService = method(adapterType, "getHeadsetService");
            headsetConnectionState = method(headset, "getConnectionState", BluetoothDevice.class);
            leService = method(adapterType, "getLeAudioService");
            a2dpActive = method(a2dp, "getActiveDevice");
            a2dpConnectionState = method(a2dp, "getConnectionState", BluetoothDevice.class);
            restoreA2dp = method(adm, "setA2dpActiveDevice", BluetoothDevice.class, boolean.class);
            findBrDevice = method(BluetoothDevice.class, "findBrDevice");
            leConnected = method(le, "getConnectedDevices");
            leActiveGroup = method(le, "getActiveGroupId");
            leConnectionState = method(le, "getConnectionState", BluetoothDevice.class);
            leGroup = method(le, "getGroupId", BluetoothDevice.class);
            leLead = method(le, "getLeadDevice", BluetoothDevice.class);
            leStreamAvailable = method(le, "isGroupAvailableForStream", int.class);
            propertyBoolean = method(properties, "getBoolean", String.class, boolean.class);
            adapterInstance = field(adapterType, "sAdapterService");
            bondedDevices = method(adapterType, "getBondedDevices");
            deviceConnected = method(adapterType, "isConnected", BluetoothDevice.class);
            disconnectProfiles = method(adapterType, "disconnectAllEnabledProfiles", BluetoothDevice.class, int.class);
            connectProfiles = method(adapterType, "connectAllEnabledProfiles", BluetoothDevice.class);
            SharedPreferences remote = null;
            try {
                remote = getRemotePreferences(RouteSettings.FILE);
            } catch (RuntimeException error) {
                report(Log.WARN, "Could not read Game_Vnd setting", error);
            }
            routeSettings = remote;
        }

        String diagnostics() {
            return "enabled=" + routingEnabled() + ", scheduled=" + scheduled.get() + ", executed=" + executed.get()
                    + ", decision=" + lastDecision + ", failure=" + lastFailure
                    + "\n  Encore request: " + lastRequest
                    + "\n  Encore removal: " + lastRemoval
                    + "\n  Encore direct: " + directResult
                    + "\n  Encore Game_Vnd: " + gameVndDecision
                    + "\n  Encore earbud WALT: " + gameVndRemoteDecision
                    + "\n  Encore verification: " + lastVerification
                    + "\n  Encore scene: " + sceneDecision
                    + "\n  Encore wear: " + wearDecision
                    + "\n  Encore whitelist: " + whitelistDecision
                    + "\n  Encore reconnect: " + reconnectStatus;
        }

        /**
         * Reports the firmware's own dual-connection conditions for X4, as OplusDualModeManagerImpl evaluates them,
         * followed by a second line listing the installed dual-mode whitelist apps.
         */
        String prerequisites(Object adm) {
            try {
                Object extension = admExtension.get(adm);
                Object adapterService = adapter.get(adm);
                if (extension == null || adapterService == null) {
                    return "state=unavailable\n  Encore low latency apps: ";
                }
                VendorBindings vendor = vendorBindings(extension);
                Object dual = vendor.dualInstance.get(null);
                if (dual == null) {
                    return "state=unavailable\n  Encore low latency apps: ";
                }
                PrerequisiteBindings bindings = prerequisiteBindings(dual.getClass());
                boolean lowLatency = flag(bindings.hasLowLatency, dual);
                StringBuilder out = new StringBuilder("state=ok")
                        .append(" feature=").append(bindings.featureEnabled.getBoolean(dual))
                        .append(" powerSave=").append(bindings.powerSave.getBoolean(dual))
                        .append(" lowLatency=").append(lowLatency)
                        .append(" whitelistBypass=").append(whitelistHooksInstalled && routingEnabled());
                BluetoothDevice x4 = null;
                for (Object item : (Set<?>) bondedDevices.invoke(adapterService)) {
                    if (item instanceof BluetoothDevice device && TARGET_NAME.equals(device.getName())
                            && (x4 == null || flag(bindings.supportDual, dual, device))) {
                        x4 = device;
                    }
                }
                if (x4 == null) {
                    out.append(" device=none");
                } else {
                    out.append(" device=found")
                            .append(" dual=").append(flag(bindings.supportDual, dual, x4))
                            .append(" pref=").append(flag(bindings.preference, dual, x4))
                            .append(" needed=").append(flag(vendor.dualNeeded, dual, x4));
                    Object preferences = bindings.preferenceManager.get(dual);
                    Object keyDevice = bindings.keyDevice.invoke(dual, x4);
                    Object key = preferences == null || keyDevice == null ? null
                            : bindings.generateKey.invoke(preferences, keyDevice);
                    BluetoothDevice master = keyDevice instanceof BluetoothDevice device ? device : x4;
                    out.append(" prefKey=").append(key)
                            .append(" hfp=").append(policy(service(headsetService, adapterService), master))
                            .append(" a2dp=").append(policy(service(a2dpService, adapterService), master));
                    Object le = service(leService, adapterService);
                    if (le != null) {
                        int leDevices = 0;
                        for (Object item : (List<?>) leConnected.invoke(le)) {
                            if (item instanceof BluetoothDevice device && TARGET_NAME.equals(device.getName())) {
                                leDevices++;
                            }
                        }
                        out.append(" le=").append(leDevices);
                    }
                }
                Object helper = bindings.helper.get(dual);
                Object apps = helper == null ? null : bindings.installedApps.get(helper);
                out.append("\n  Encore low latency apps: ");
                if (apps instanceof Set<?> set) {
                    synchronized (set) {
                        out.append(String.join(",", set.stream().map(String::valueOf).sorted().toList()));
                    }
                }
                return out.toString();
            } catch (ReflectiveOperationException | RuntimeException error) {
                return "state=error " + error.getClass().getSimpleName() + "\n  Encore low latency apps: ";
            }
        }

        /** The profile's connection policy for the device, or the failure as text. */
        private String policy(Object profile, BluetoothDevice device) {
            if (profile == null) {
                return "none";
            }
            try {
                Method method = profile.getClass().getMethod("getConnectionPolicy", BluetoothDevice.class);
                method.setAccessible(true);
                return String.valueOf(method.invoke(profile, device));
            } catch (java.lang.reflect.InvocationTargetException error) {
                return "error:" + error.getCause().getClass().getSimpleName();
            } catch (ReflectiveOperationException | RuntimeException error) {
                return "error:" + error.getClass().getSimpleName();
            }
        }

        void watchRouteSettings() {
            if (routeSettings != null) {
                try {
                    routeSettings.registerOnSharedPreferenceChangeListener(routeSettingChanged);
                } catch (RuntimeException error) {
                    report(Log.WARN, "Could not observe X4 routing settings", error);
                }
            }
        }

        private boolean routingEnabled() {
            return routeSettings == null
                    || routeSettings.getBoolean(RouteSettings.ROUTE_ENABLED, RouteSettings.DEFAULT_ROUTE_ENABLED);
        }

        private boolean gameVndEnabled() {
            return routingEnabled() && routeSettings != null && routeSettings.getBoolean(RouteSettings.GAME_VND, false);
        }

        private boolean sceneLockedToGame() {
            return routeSettings != null && RouteSettings.SCENE_GAME.equals(
                    routeSettings.getString(RouteSettings.SCENE_LOCK, RouteSettings.SCENE_FOLLOW));
        }

        /** Records the system's own game state and returns the value the LE Audio stack should receive. */
        synchronized boolean inGameFor(boolean requested) {
            systemInGame = requested;
            appliedInGame = requested || (sceneLockedToGame() && x4LeActive);
            describeScene();
            return appliedInGame;
        }

        void onLeActiveChanged(BluetoothDevice device) {
            x4LeActive = device != null && TARGET_NAME.equals(device.getName());
            applySceneLock();
        }

        /**
         * ColorOS wear detection re-selects earbuds when they are put on. For dual-mode earbuds it chooses LE Audio
         * only while the system is in ultra game mode and otherwise makes them the HFP and A2DP device, which drops
         * X4 back to LHDC. While X4 routing is enabled, answer that one check as if game mode were on so the
         * firmware takes its own LE Audio branch; it still skips LE Audio when the earbuds' SCO or CIS is occupied.
         */
        synchronized void installWearCheckHooks(Object adm) {
            if (wearHooksInstalled) {
                return;
            }
            try {
                Object extension = admExtension.get(adm);
                if (extension == null) {
                    return;
                }
                ClassLoader loader = extension.getClass().getClassLoader();
                Method process = method(load(loader, WEAR_CHECK), "processHeadsetStatusChanged",
                        String.class, boolean.class, boolean.class, boolean.class);
                Method ultraGameMode = method(load(loader, LOW_LATENCY_SERVICE), "isUltraGameMode");
                hook(process).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    noteWearStatus((String) chain.getArg(0), (boolean) chain.getArg(1), (boolean) chain.getArg(2),
                            (boolean) chain.getArg(3));
                    if (!wearSelectsX4((String) chain.getArg(0), (boolean) chain.getArg(1))) {
                        return chain.proceed();
                    }
                    wearKeepsLe.set(true);
                    try {
                        return chain.proceed();
                    } finally {
                        wearKeepsLe.remove();
                    }
                });
                hook(ultraGameMode).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    if (Boolean.TRUE.equals(wearKeepsLe.get()) && !Boolean.TRUE.equals(result)
                            && calledByWearStatusChange()) {
                        wearDecision = "Kept X4 on LE Audio after it was put on";
                        report(Log.INFO, wearDecision);
                        return true;
                    }
                    return result;
                });
                deoptimize(process);
                wearHooksInstalled = true;
                wearDecision = "Watching wear check";
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                wearDecision = "Wear check bindings unavailable";
                report(Log.WARN, wearDecision, error);
            }
        }

        /**
         * ColorOS keeps the LE Audio connection of dual-mode earbuds only while an app from its dual-mode whitelist
         * is installed (OplusDualModeManagerImpl.isDualConnectionNeeded → hasLowLatencyPackage). While X4 routing is
         * enabled, answer that one condition as met when the firmware evaluates X4, so no whitelist app is needed.
         * The other conditions, and every other headset, keep the firmware's own answer.
         * When the last whitelist app is removed, onLowLatencyPackageChanged disconnects LE Audio from every
         * dual-mode headset without asking isDualConnectionNeeded; that disconnect is skipped for X4 only.
         */
        synchronized void installWhitelistHooks(Object adm) {
            if (whitelistHooksInstalled) {
                return;
            }
            try {
                Object extension = admExtension.get(adm);
                if (extension == null) {
                    return;
                }
                ClassLoader loader = extension.getClass().getClassLoader();
                Class<?> dualMode = load(loader, DUAL_MODE_MANAGER);
                Method needed = method(dualMode, "isDualConnectionNeeded", BluetoothDevice.class);
                Method installedApp = method(dualMode, "hasLowLatencyPackage");
                Method appsChanged = method(dualMode, "onLowLatencyPackageChanged", boolean.class, boolean.class);
                Method leDisconnect = method(load(loader, "com.android.bluetooth.le_audio.LeAudioService"),
                        "disconnect", BluetoothDevice.class);
                hook(needed).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                    if (device == null || !routingEnabled() || !TARGET_NAME.equals(device.getName())) {
                        return chain.proceed();
                    }
                    checkingX4DualConnection.set(true);
                    try {
                        return chain.proceed();
                    } finally {
                        checkingX4DualConnection.remove();
                    }
                });
                hook(installedApp).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    if (Boolean.TRUE.equals(checkingX4DualConnection.get()) && !Boolean.TRUE.equals(result)) {
                        noteWhitelistDecision("Passed the dual-mode whitelist check for X4");
                        return true;
                    }
                    return result;
                });
                hook(appsChanged).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (!routingEnabled()) {
                        return chain.proceed();
                    }
                    whitelistAppsChanged.set(true);
                    try {
                        return chain.proceed();
                    } finally {
                        whitelistAppsChanged.remove();
                    }
                });
                hook(leDisconnect).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                    if (Boolean.TRUE.equals(whitelistAppsChanged.get()) && device != null
                            && TARGET_NAME.equals(device.getName())) {
                        noteWhitelistDecision("Kept X4 LE Audio after the last whitelist app was removed");
                        return false;
                    }
                    return chain.proceed();
                });
                deoptimize(needed);
                deoptimize(appsChanged);
                whitelistHooksInstalled = true;
                whitelistDecision = "Watching dual-mode whitelist check";
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                whitelistDecision = "Dual-mode whitelist bindings unavailable";
                report(Log.WARN, whitelistDecision, error);
            }
        }

        /**
         * Leaving a whitelist game, ColorOS's LowLatencyState.processExitLowLatency restores the codec settings and,
         * while LE Audio is active, removes the LE Audio device and waits in ExitingLowLatencyState to restore classic
         * audio, which drops X4 to LHDC. For X4 with routing enabled, run it as if LE Audio were inactive: the codec
         * settings are still restored and the machine goes straight back to NormalState, whose own attempt to drop
         * LE Audio shortly after is refused by keepLeThroughGameExit.
         */
        synchronized void installGameExitHook(Object adm) {
            if (gameExitHookInstalled) {
                return;
            }
            try {
                Object extension = admExtension.get(adm);
                if (extension == null) {
                    return;
                }
                ClassLoader loader = extension.getClass().getClassLoader();
                Class<?> machine = load(loader, LOW_LATENCY_STATE_MACHINE);
                Class<?> lowLatencyState = load(loader, LOW_LATENCY_STATE_MACHINE + "$LowLatencyState");
                Method exit = method(lowLatencyState, "processExitLowLatency");
                Method processMessage = method(lowLatencyState, "processMessage", Message.class);
                Field outer = field(lowLatencyState, "this$0");
                Field leAudioActive = field(machine, "mIsLEAudioActive");
                Field lowLatencyDevice = field(machine, "mDevice");
                hook(exit).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object stateMachine = outer.get(chain.getThisObject());
                    BluetoothDevice device = (BluetoothDevice) lowLatencyDevice.get(stateMachine);
                    if (!routingEnabled() || !x4LeActive || device == null || !TARGET_NAME.equals(device.getName())
                            || !leAudioActive.getBoolean(stateMachine)) {
                        return chain.proceed();
                    }
                    gameExitKeepLeUntil = SystemClock.elapsedRealtime() + GAME_EXIT_KEEP_LE_MS;
                    leAudioActive.setBoolean(stateMachine, false);
                    try {
                        return chain.proceed();
                    } finally {
                        leAudioActive.setBoolean(stateMachine, true);
                        report(Log.INFO, "Kept X4 on LE Audio while leaving game low latency");
                    }
                });
                deoptimize(processMessage);
                gameExitHookInstalled = true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                report(Log.WARN, "Game low latency exit bindings unavailable", error);
            }
        }

        /** Refuses the low latency state machine's LE Audio removal for X4 right after leaving game low latency. */
        private boolean keepLeThroughGameExit() {
            if (!x4LeActive || SystemClock.elapsedRealtime() > gameExitKeepLeUntil) {
                return false;
            }
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().startsWith(LOW_LATENCY_STATE_MACHINE)) {
                    lastRemoval = "Kept X4 on LE Audio after game low latency exit <- " + frame.getClassName() + "."
                            + frame.getMethodName();
                    report(Log.INFO, lastRemoval);
                    return true;
                }
            }
            return false;
        }

        /** The firmware checks dual connection often; report only when the decision changes. */
        private void noteWhitelistDecision(String decision) {
            if (!decision.equals(whitelistDecision)) {
                whitelistDecision = decision;
                report(Log.INFO, decision);
            }
        }

        private boolean wearSelectsX4(String address, boolean wearing) {
            if (!wearing || address == null || !routingEnabled()) {
                return false;
            }
            try {
                BluetoothDevice device = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
                return TARGET_NAME.equals(device.getName());
            } catch (RuntimeException error) {
                return false;
            }
        }

        /** Diagnostic: records every wear status change the firmware processes. */
        private void noteWearStatus(String address, boolean wearing, boolean switchOn, boolean realBroadcast) {
            String name = null;
            try {
                name = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address).getName();
            } catch (RuntimeException ignored) {
                // Keep the event even if the name cannot be read.
            }
            String tail = address == null || address.length() < 5 ? String.valueOf(address)
                    : address.substring(address.length() - 5);
            report(Log.INFO, "Wear status " + tail + " (" + name + ") wearing=" + wearing + " switch=" + switchOn
                    + " real=" + realBroadcast + " x4Le=" + x4LeActive);
        }

        /**
         * While X4 is the active LE Audio device, refuses to make it the A2DP device. ColorOS does the same in
         * OplusA2dpServiceExtImpl.oplusSetActiveBefore, but only in ultra game mode; without it, wear check's AVRCP
         * handler (earbuds put back on and resuming playback) activates A2DP and the dual-mode logic drops LE Audio.
         */
        boolean keepX4OnLe(BluetoothDevice device) {
            if (device == null || !x4LeActive || !routingEnabled() || !TARGET_NAME.equals(device.getName())) {
                return false;
            }
            StringBuilder caller = new StringBuilder("Kept X4 on LE Audio, refused A2DP activation");
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String owner = frame.getClassName();
                if ((owner.startsWith("com.oplus.") || owner.startsWith("com.android.bluetooth."))
                        && !owner.startsWith("com.android.bluetooth.a2dp.")) {
                    caller.append(" <- ").append(owner).append('.').append(frame.getMethodName());
                    break;
                }
            }
            wearDecision = caller.toString();
            report(Log.INFO, wearDecision);
            return true;
        }

        /** True only when the wear-status handler itself asks, not its helper checks. */
        private boolean calledByWearStatusChange() {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().equals(WEAR_CHECK)) {
                    return frame.getMethodName().equals("processHeadsetStatusChanged");
                }
            }
            return false;
        }

        /** Records who selects X4 for calls while its LE Audio route is active; ColorOS then drops LE Audio. */
        void noteHfpActivation(BluetoothDevice device) {
            if (device == null || !x4LeActive || !TARGET_NAME.equals(device.getName())) {
                return;
            }
            StringBuilder caller = new StringBuilder("HFP activation while X4 LE Audio active");
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String owner = frame.getClassName();
                if (owner.startsWith("com.oplus.") || owner.startsWith("com.android.bluetooth.")
                        || owner.startsWith("android.bluetooth.")) {
                    caller.append(" <- ").append(owner).append('.').append(frame.getMethodName());
                }
            }
            report(Log.INFO, caller.toString());
        }

        /** Keeps the LE Audio stack in game state while X4 is the active LE Audio device and the lock is on. */
        private synchronized void applySceneLock() {
            boolean desired = systemInGame || (sceneLockedToGame() && x4LeActive);
            if (desired != appliedInGame) {
                try {
                    Object le = service(leService, adapterInstance.get(null));
                    Object nativeInterface = le == null ? null : leNativeInterface.get(le);
                    if (nativeInterface == null) {
                        sceneDecision = "LE Audio service unavailable";
                        return;
                    }
                    leSetInGameNative.invoke(nativeInterface, desired);
                    appliedInGame = desired;
                    report(Log.INFO, "LE Audio in-game state set to " + desired);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    sceneDecision = "Could not apply scene lock";
                    report(Log.WARN, sceneDecision, error);
                    return;
                }
            }
            describeScene();
        }

        private void describeScene() {
            sceneDecision = "lock=" + (sceneLockedToGame() ? RouteSettings.SCENE_GAME : RouteSettings.SCENE_FOLLOW)
                    + " x4Active=" + x4LeActive + " system=" + systemInGame + " applied=" + appliedInGame;
        }

        private void onRouteSettingChanged(SharedPreferences settings, String key) {
            if (RouteSettings.RECONNECT_REQUEST.equals(key)) {
                long id = settings.getLong(key, 0);
                reconnectHandler.post(() -> startReconnect(id));
                return;
            }
            if (RouteSettings.SCENE_LOCK.equals(key)) {
                Handler.createAsync(Looper.getMainLooper()).post(this::applySceneLock);
                return;
            }
            if (RouteSettings.ROUTE_ENABLED.equals(key) || RouteSettings.GAME_VND.equals(key)) {
                Handler.createAsync(Looper.getMainLooper()).post(() -> {
                    if (!gameVndEnabled()) {
                        clearGameVnd();
                    }
                    if (!routingEnabled()) {
                        pendingRoute.set(null);
                        hfpRetry.set(null);
                        lastRequestedGroup = INVALID_GROUP;
                        DeferredA2dp deferred = deferredA2dp.get();
                        if (deferred != null) {
                            Handler.createAsync(deferred.handler().getLooper()).post(() -> finishDirectRoute(deferred));
                        }
                    }
                });
            }
        }

        private void startReconnect(long id) {
            if (id <= reconnectId) {
                return;
            }
            reconnectId = id;
            reconnectHandler.removeCallbacksAndMessages(null);
            try {
                Object adapterService = adapterInstance.get(null);
                List<BluetoothDevice> members = new ArrayList<>();
                if (adapterService != null) {
                    for (Object item : (Set<?>) bondedDevices.invoke(adapterService)) {
                        if (item instanceof BluetoothDevice device && TARGET_NAME.equals(device.getName())) {
                            members.add(device);
                        }
                    }
                }
                if (members.isEmpty()) {
                    reconnectState(id, adapterService == null ? "FAILED" : "NO_DEVICE");
                    return;
                }
                reconnectState(id, "DISCONNECTING");
                for (BluetoothDevice member : members) {
                    disconnectProfiles.invoke(adapterService, member, USER_DISCONNECT_REASON);
                }
                long deadline = SystemClock.uptimeMillis() + RECONNECT_DISCONNECT_TIMEOUT_MS;
                reconnectHandler.postDelayed(() -> awaitDisconnect(id, adapterService, members, deadline),
                        RECONNECT_POLL_MS);
            } catch (ReflectiveOperationException | RuntimeException error) {
                reconnectState(id, "FAILED");
                report(Log.WARN, "Could not disconnect X4", error);
            }
        }

        private void awaitDisconnect(long id, Object adapterService, List<BluetoothDevice> members, long deadline) {
            if (id != reconnectId) {
                return;
            }
            try {
                if (anyConnected(adapterService, members) && SystemClock.uptimeMillis() < deadline) {
                    reconnectHandler.postDelayed(() -> awaitDisconnect(id, adapterService, members, deadline),
                            RECONNECT_POLL_MS);
                    return;
                }
                reconnectState(id, "CONNECTING");
                for (BluetoothDevice member : members) {
                    connectProfiles.invoke(adapterService, member);
                }
                long connectDeadline = SystemClock.uptimeMillis() + RECONNECT_CONNECT_TIMEOUT_MS;
                reconnectHandler.postDelayed(() -> awaitConnect(id, adapterService, members, connectDeadline),
                        RECONNECT_POLL_MS);
            } catch (ReflectiveOperationException | RuntimeException error) {
                reconnectState(id, "FAILED");
                report(Log.WARN, "Could not reconnect X4", error);
            }
        }

        private void awaitConnect(long id, Object adapterService, List<BluetoothDevice> members, long deadline) {
            if (id != reconnectId) {
                return;
            }
            try {
                if (anyConnected(adapterService, members)) {
                    reconnectState(id, "DONE");
                } else if (SystemClock.uptimeMillis() < deadline) {
                    reconnectHandler.postDelayed(() -> awaitConnect(id, adapterService, members, deadline),
                            RECONNECT_POLL_MS);
                } else {
                    reconnectState(id, "TIMEOUT");
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                reconnectState(id, "FAILED");
                report(Log.WARN, "Could not check X4 connection", error);
            }
        }

        private boolean anyConnected(Object adapterService, List<BluetoothDevice> members)
                throws ReflectiveOperationException {
            for (BluetoothDevice member : members) {
                if (flag(deviceConnected, adapterService, member)) {
                    return true;
                }
            }
            return false;
        }

        private void reconnectState(long id, String state) {
            reconnectStatus = id + " " + state;
            report(Log.INFO, "X4 reconnect " + reconnectStatus);
        }

        void prepareGameVnd(Object nativeInterface, BluetoothDevice device) {
            if (device == null || !gameVndEnabled()) {
                return;
            }
            try {
                BluetoothDevice classic = (BluetoothDevice) findBrDevice.invoke(device);
                if (classic == null) {
                    classic = device;
                }
                if (!TARGET_NAME.equals(classic.getName())) {
                    return;
                }
                Object adapterService = leNativeAdapter.get(nativeInterface);
                Object adm = adapterAdm.get(adapterService);
                Handler current = adm == null ? null : (Handler) handler.get(adm);
                if (current == null) {
                    return;
                }
                Object a2dp = service(a2dpService, adapterService);
                Object extension = admExtension.get(adm);
                if (a2dp == null || extension == null) {
                    return;
                }
                Object active = a2dpActive.invoke(a2dp);
                if ((active != null && !classic.equals(active))
                        || routeBlocker(adm, (Context) adapterService, extension) != null) {
                    return;
                }
                VendorBindings bindings = vendorBindings(extension);
                Object dual = bindings.dualInstance.get(null);
                if (dual != null && flag(bindings.dualNeeded, dual, classic)
                        && !flag(bindings.occupied, dual, classic)) {
                    requestGameVnd(extension, a2dp, classic, null, current);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                report(Log.WARN, "Could not prepare WALT before X4 LE connection", error);
            }
        }

        private synchronized void requestGameVnd(Object extension, Object a2dp, BluetoothDevice classic,
                BluetoothDevice lead, Handler prepareHandler) {
            if (!gameVndEnabled()) {
                clearGameVnd();
                gameVndDecision = "Off";
                return;
            }
            if (classic.equals(gameVndClassic)) {
                if (lead != null) {
                    gameVndLead = lead;
                }
                notifyEarbudWalt(a2dp, classic);
                return;
            }
            if (extension == null) {
                return;
            }
            try {
                if (gameVnd == null) {
                    gameVnd = new GameVndControl(extension.getClass().getClassLoader());
                }
                if (prepareHandler != null) {
                    long requestId = ++gameVndRequestId;
                    Handler timeout = Handler.createAsync(prepareHandler.getLooper());
                    if (!timeout.postDelayed(() -> expireUnclaimedGameVnd(requestId), WALT_PREPARE_TIMEOUT_MS)) {
                        return;
                    }
                }
                gameVnd.setMode(false, true);
                gameVndClassic = classic;
                gameVndLead = lead;
                gameVndDecision = prepareHandler == null ? "Requested WALT for active X4 route"
                        : "Requested WALT before X4 LE routing";
                report(Log.INFO, gameVndDecision);
                notifyEarbudWalt(a2dp, classic);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                gameVndClassic = null;
                gameVndLead = null;
                gameVndDecision = "Could not request WALT";
                report(Log.WARN, gameVndDecision, error);
            }
        }

        private void notifyEarbudWalt(Object a2dp, BluetoothDevice classic) {
            if (classic.equals(gameVndRemote)) {
                return;
            }
            try {
                if ((int) a2dpConnectionState.invoke(a2dp, classic) == BluetoothProfile.STATE_CONNECTED) {
                    gameVnd.sendEarbudMode(classic, true);
                    gameVndRemote = classic;
                    gameVndRemoteDecision = "Requested earbud WALT for X4";
                    report(Log.INFO, gameVndRemoteDecision);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                gameVndRemoteDecision = "Could not request earbud WALT";
                report(Log.WARN, gameVndRemoteDecision, error);
            }
        }

        private synchronized void expireUnclaimedGameVnd(long requestId) {
            if (requestId == gameVndRequestId && gameVndLead == null) {
                clearGameVnd();
            }
        }

        synchronized void clearGameVndForOtherDevice(BluetoothDevice device) {
            if (device != null && gameVndClassic != null
                    && !gameVndClassic.equals(device) && !device.equals(gameVndLead)) {
                clearGameVnd();
            }
        }

        private synchronized void clearGameVnd() {
            if (gameVndClassic == null) {
                return;
            }
            try {
                boolean systemGame = flag(propertyBoolean, null, "persist.bluetooth.real.gamemode", false);
                boolean systemWalt = gameVnd.isSystemWalt();
                gameVnd.setMode(systemGame, systemWalt);
                gameVndRequestId++;
                gameVndClassic = null;
                gameVndLead = null;
                gameVndDecision = "Released X4 WALT request";
                if (gameVndRemote != null) {
                    try {
                        gameVnd.sendEarbudMode(gameVndRemote, systemWalt);
                        gameVndRemoteDecision = "Requested restore of X4 earbud WALT";
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                        gameVndRemoteDecision = "Could not request restore of X4 earbud WALT";
                        report(Log.WARN, gameVndRemoteDecision, error);
                    } finally {
                        gameVndRemote = null;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                gameVndDecision = "Could not release WALT";
                report(Log.WARN, gameVndDecision, error);
            }
        }

        boolean deferA2dp(Object adm, Object a2dp, BluetoothDevice device) throws ReflectiveOperationException {
            if (adm == null || device == null || !routingEnabled()) {
                return false;
            }
            DeferredA2dp existing = deferredA2dp.get();
            if (existing != null && !existing.device().equals(device)) {
                deferredA2dp.compareAndSet(existing, null);
            }
            if (!TARGET_NAME.equals(device.getName()) || a2dpActive.invoke(a2dp) != null) {
                return false;
            }
            Object adapterService = adapter.get(adm);
            AudioManager audio = (AudioManager) audioManager.get(adm);
            Object le = service(leService, adapterService);
            if (audio == null || audio.getMode() != AudioManager.MODE_NORMAL || le == null
                    || gameModeActive((Context) adapterService)) {
                return false;
            }
            synchronized (queueLock) {
                if (!routingEnabled()) {
                    return false;
                }
                existing = deferredA2dp.get();
                if (existing != null) {
                    return existing.adm() == adm && existing.device().equals(device);
                }
                if (device.equals(directTried) || (int) leActiveGroup.invoke(le) != INVALID_GROUP) {
                    return false;
                }
                Handler current = (Handler) handler.get(adm);
                if (current == null) {
                    return false;
                }
                DeferredA2dp request = new DeferredA2dp(adm, current, a2dp, device);
                deferredA2dp.set(request);
                Handler timeout = Handler.createAsync(current.getLooper());
                if (!timeout.postDelayed(() -> finishDirectRoute(request), DIRECT_ROUTE_WAIT_MS)) {
                    deferredA2dp.compareAndSet(request, null);
                    return false;
                }
                directTried = device;
                directResult = "Waiting for X4 LE Audio before A2DP";
            }
            Object extension = admExtension.get(adm);
            requestGameVnd(extension, a2dp, device, null, (Handler) handler.get(adm));
            schedule(adm);
            return true;
        }

        void onA2dpDisconnected(BluetoothDevice device) {
            synchronized (queueLock) {
                if (device != null && device.equals(directTried)) {
                    directTried = null;
                    deferredA2dp.set(null);
                }
            }
            synchronized (this) {
                if (device != null && device.equals(gameVndClassic)) {
                    clearGameVnd();
                }
            }
        }

        private void finishDirectRoute(DeferredA2dp request) {
            if (!deferredA2dp.compareAndSet(request, null)) {
                return;
            }
            try {
                Object adm = request.adm();
                if (handler.get(adm) != request.handler() || a2dpActive.invoke(request.a2dp()) != null
                        || (int) a2dpConnectionState.invoke(request.a2dp(), request.device())
                                != BluetoothProfile.STATE_CONNECTED) {
                    directResult = "A2DP replay no longer applicable";
                    return;
                }
                Object le = service(leService, adapter.get(adm));
                if (le != null && (int) leActiveGroup.invoke(le) != INVALID_GROUP) {
                    directResult = "LE Audio active at the end of the A2DP wait";
                    return;
                }
                clearGameVnd();
                directResult = "A2DP fallback accepted=" + flag(restoreA2dp, adm, request.device(), false);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                lastFailure = error + " cause=" + error.getCause();
                report(Log.WARN, "Could not finish direct X4 route selection", error);
            }
        }

        void schedule(Object adm) {
            if (adm == null || !routingEnabled()) {
                return;
            }
            try {
                Handler currentHandler = (Handler) handler.get(adm);
                if (currentHandler == null) {
                    report(Log.INFO, "Route check skipped: active device handler not ready");
                    return;
                }
                synchronized (queueLock) {
                    if (queuedHandler == currentHandler) {
                        return;
                    }
                    Looper looper = currentHandler.getLooper();
                    if (routeHandler == null || routeHandler.getLooper() != looper) {
                        routeHandler = Handler.createAsync(looper);
                    }
                    boolean queued = routeHandler.postDelayed(() -> {
                        synchronized (queueLock) {
                            if (queuedHandler != currentHandler) {
                                return;
                            }
                            queuedHandler = null;
                        }
                        reevaluate(adm, currentHandler);
                    }, ROUTE_SETTLE_MS);
                    if (queued) {
                        queuedHandler = currentHandler;
                        scheduled.incrementAndGet();
                    } else {
                        lastFailure = "Route handler rejected task";
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                lastFailure = error.toString();
                report(Log.WARN, "Could not queue route check", error);
            }
        }

        boolean handleLeRemoval(Object le, boolean allowStartupProtection) {
            if (!routingEnabled()) {
                return false;
            }
            if (allowStartupProtection && keepLeThroughGameExit()) {
                return true;
            }
            try {
                int group = lastRequestedGroup;
                if (group == INVALID_GROUP || (int) leActiveGroup.invoke(le) != group) {
                    return false;
                }
                PendingRoute request = pendingRoute.get();
                boolean fromNormalState = false;
                boolean fromModeChange = false;
                boolean fromVendorLeSelection = false;
                boolean fromHfpSetup = false;
                boolean fromLowLatencyExit = false;
                StringBuilder caller = new StringBuilder("Group " + group + " deactivation");
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    String owner = frame.getClassName();
                    String name = frame.getMethodName();
                    if (owner.startsWith("com.oplus.bluetooth.") || owner.startsWith("com.android.bluetooth.")) {
                        caller.append(" <- ").append(owner).append('.').append(name);
                    }
                    fromNormalState |= owner.equals(LOW_LATENCY_STATE_MACHINE + "$NormalState")
                            && name.equals("enter");
                    fromModeChange |= owner.equals(LOW_LATENCY_STATE_MACHINE + "$BaseState")
                            && name.equals("processLowLatencyModeChanged");
                    fromVendorLeSelection |= owner.equals(LOW_LATENCY_STATE_MACHINE)
                            && name.equals("setActiveDeviceForLea");
                    fromHfpSetup |= owner.equals("com.android.bluetooth.btservice.ActiveDeviceManager")
                            && (name.equals("handleHfpConnected") || name.equals("handleHfpActiveDeviceChanged"));
                    fromLowLatencyExit |= owner.equals(LOW_LATENCY_STATE_MACHINE + "$LowLatencyState")
                            && name.equals("processExitLowLatency");
                }
                boolean eligible = request != null && request.le() == le && request.group() == group
                        && pendingRoute.get() == request && canKeepRoute(request);
                boolean suppressed = eligible && routingEnabled() && allowStartupProtection && fromNormalState && fromModeChange
                        && fromVendorLeSelection && request.startupFallbackSuppressed().compareAndSet(false, true);
                if (!suppressed && request != null && request.le() == le && request.group() == group
                        && pendingRoute.compareAndSet(request, null)
                        && eligible && fromHfpSetup && !request.retry()) {
                    hfpRetry.set(request);
                }
                lastRemoval = (suppressed ? "Suppressed startup fallback: " : "Allowed: ") + caller;
                if (!suppressed) {
                    clearGameVnd();
                }
                if (!suppressed && fromLowLatencyExit) {
                    lowLatencyExitAt = SystemClock.elapsedRealtime();
                    lastRequestedGroup = INVALID_GROUP;
                    lastRemoval = "Will route X4 back to LE Audio after game low latency exit. " + lastRemoval;
                }
                report(Log.INFO, lastRemoval);
                return suppressed;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                lastFailure = error + " cause=" + error.getCause();
                report(Log.WARN, "Could not check LE deactivation", error);
                return false;
            }
        }

        private boolean canKeepRoute(PendingRoute request) throws ReflectiveOperationException {
            if (SystemClock.elapsedRealtime() - request.startedAt() > STARTUP_FALLBACK_WINDOW_MS
                    || request.audio().getMode() != AudioManager.MODE_NORMAL
                    || request.wiredOutput().getBoolean(request.extension())
                    || gameModeActive(request.context())) {
                return false;
            }
            BluetoothDevice current = (BluetoothDevice) a2dpActive.invoke(request.a2dp());
            return (current == null || current.equals(request.classic()))
                    && (int) leConnectionState.invoke(request.le(), request.lead()) == BluetoothProfile.STATE_CONNECTED
                    && (int) leGroup.invoke(request.le(), request.lead()) == request.group();
        }

        void clearHfpRetry(BluetoothDevice device) {
            PendingRoute retry = hfpRetry.get();
            if (retry != null && retry.classic().equals(device)) {
                hfpRetry.compareAndSet(retry, null);
            }
        }

        private void reevaluate(Object adm, Handler currentHandler) {
            executed.incrementAndGet();
            try {
                if (!routingEnabled()) {
                    return;
                }
                if (handler.get(adm) != currentHandler) {
                    decision("Active device handler changed");
                    return;
                }
                Object adapterService = adapter.get(adm);
                Object a2dp = service(a2dpService, adapterService);
                Object le = service(leService, adapterService);
                if (a2dp == null || le == null) {
                    decision("Waiting for A2DP and LE Audio services");
                    return;
                }
                BluetoothDevice classic = (BluetoothDevice) a2dpActive.invoke(a2dp);
                DeferredA2dp direct = deferredA2dp.get();
                boolean selectingDirect = classic == null && direct != null
                        && direct.adm() == adm && direct.a2dp() == a2dp;
                if (selectingDirect) {
                    classic = direct.device();
                    if ((int) a2dpConnectionState.invoke(a2dp, classic) != BluetoothProfile.STATE_CONNECTED) {
                        return;
                    }
                }
                PendingRoute retry = hfpRetry.get();
                if (retry != null && (SystemClock.elapsedRealtime() - retry.startedAt() > HFP_RETRY_WINDOW_MS
                        || (classic != null && !classic.equals(retry.classic())))) {
                    hfpRetry.compareAndSet(retry, null);
                    retry = null;
                }
                if (classic == null || !TARGET_NAME.equals(classic.getName())) {
                    if (classic != null || leActive.invoke(adm) == null) {
                        lastRequestedGroup = INVALID_GROUP;
                    }
                    decision("Classic media route is not X4");
                    return;
                }
                Object extension = admExtension.get(adm);
                if (extension == null) {
                    decision("Waiting for OPLUS route extension");
                    return;
                }
                String blocker = routeBlocker(adm, (Context) adapterService, extension);
                if (blocker != null) {
                    decision(blocker);
                    return;
                }
                if ((int) leActiveGroup.invoke(le) != INVALID_GROUP) {
                    decision("An LE Audio group is already active");
                    return;
                }
                Object dual = vendor.dualInstance.get(null);
                if (dual == null || !flag(vendor.dualNeeded, dual, classic)) {
                    decision("Waiting for X4 dual connection eligibility");
                    return;
                }
                if (flag(vendor.occupied, dual, classic)) {
                    decision("X4 SCO/CIS is occupied");
                    return;
                }
                BluetoothDevice lead = findConnectedLead(le, classic);
                if (lead == null) {
                    decision("Waiting for a stream-ready X4 LE Audio group");
                    return;
                }
                int groupId = (int) leGroup.invoke(le, lead);
                long exitSettling = lowLatencyExitAt + LOW_LATENCY_EXIT_SETTLE_MS - SystemClock.elapsedRealtime();
                if (lowLatencyExitAt != 0 && exitSettling > 0) {
                    decision("Waiting for game low latency exit to settle");
                    Handler.createAsync(currentHandler.getLooper()).postDelayed(() -> schedule(adm), exitSettling);
                    return;
                }
                boolean recoveringHfpFallback = retry != null && retry.le() == le
                        && retry.group() == groupId && retry.classic().equals(classic);
                if (groupId == lastRequestedGroup && !recoveringHfpFallback) {
                    decision("Already requested X4 group " + groupId + " for this route");
                    return;
                }
                if (hfpSetupPending(adm, adapterService, classic)) {
                    decision("Waiting for X4 HFP connection handling");
                    return;
                }
                Object activeClassic = a2dpActive.invoke(a2dp);
                if (selectingDirect ? activeClassic != null || deferredA2dp.get() != direct
                        : !classic.equals(activeClassic)) {
                    decision("Classic media route changed before activation");
                    return;
                }
                if (recoveringHfpFallback && !hfpRetry.compareAndSet(retry, null)) {
                    return;
                }
                lastRequestedGroup = groupId;
                PendingRoute request = new PendingRoute(le, a2dp, classic, lead,
                        (AudioManager) audioManager.get(adm), (Context) adapterService,
                        extension, vendor.wiredOutput, groupId,
                        SystemClock.elapsedRealtime(), recoveringHfpFallback, new AtomicBoolean());
                pendingRoute.set(request);
                requestGameVnd(extension, a2dp, classic, lead, null);
                boolean accepted = false;
                try {
                    accepted = routingEnabled() && flag(setLeActive, adm, lead, false);
                } finally {
                    if (!accepted) {
                        pendingRoute.compareAndSet(request, null);
                        clearGameVnd();
                    }
                }
                lastRequest = "X4 group " + groupId + " LE route request accepted=" + accepted
                        + ", direct=" + selectingDirect + ", HFP fallback retry=" + recoveringHfpFallback;
                decision(lastRequest);
                if (accepted) {
                    Handler verificationHandler = Handler.createAsync(currentHandler.getLooper());
                    verificationHandler.postDelayed(() -> verifyActiveGroup(adm, currentHandler, le, groupId),
                            VERIFY_DELAY_MS);
                    verificationHandler.postDelayed(() -> pendingRoute.compareAndSet(request, null),
                            STARTUP_FALLBACK_WINDOW_MS);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                lastFailure = error + " cause=" + error.getCause();
                report(Log.WARN, "X4 route check failed", error);
            }
        }

        private void decision(String message) {
            if (!message.equals(lastDecision)) {
                lastDecision = message;
                report(Log.INFO, message);
            }
        }

        private boolean hfpSetupPending(Object adm, Object adapterService, BluetoothDevice device)
                throws ReflectiveOperationException {
            Object headset = service(headsetService, adapterService);
            if (headset == null) {
                return false;
            }
            int state = (int) headsetConnectionState.invoke(headset, device);
            if (state == BluetoothProfile.STATE_CONNECTING) {
                return true;
            }
            synchronized (admLock.get(adm)) {
                return state == BluetoothProfile.STATE_CONNECTED
                        && !((List<?>) hfpConnectedDevices.get(adm)).contains(device);
            }
        }

        private synchronized VendorBindings vendorBindings(Object extension) throws ReflectiveOperationException {
            if (vendor == null) {
                vendor = new VendorBindings(extension.getClass().getClassLoader());
            }
            return vendor;
        }

        private synchronized PrerequisiteBindings prerequisiteBindings(Class<?> dual) throws ReflectiveOperationException {
            if (prerequisiteBindings == null) {
                prerequisiteBindings = new PrerequisiteBindings(dual);
            }
            return prerequisiteBindings;
        }

        private String routeBlocker(Object adm, Context context, Object extension)
                throws ReflectiveOperationException {
            AudioManager audio = (AudioManager) audioManager.get(adm);
            if (audio == null || audio.getMode() != AudioManager.MODE_NORMAL) {
                return "Audio mode is not normal";
            }
            if (vendorBindings(extension).wiredOutput.getBoolean(extension)) {
                return "Wired output is active or cannot be checked";
            }
            if (leActive.invoke(adm) != null || !((Set<?>) hearingAids.invoke(adm)).isEmpty()
                    || flag(broadcasting, adm)) {
                return "An LE Audio, hearing aid or broadcast route is active";
            }
            if (gameModeActive(context)) {
                return "System Bluetooth game mode has not exited";
            }
            return null;
        }

        private boolean gameModeActive(Context context) throws ReflectiveOperationException {
            return Settings.Global.getInt(context.getContentResolver(), "debug_gamemode_value", 0) != 0
                    || flag(propertyBoolean, null, "persist.bluetooth.real.gamemode", false);
        }

        private BluetoothDevice findConnectedLead(Object le, BluetoothDevice classic)
                throws ReflectiveOperationException {
            int classicGroup = (int) leGroup.invoke(le, classic);
            BluetoothDevice result = null;
            for (Object item : (List<?>) leConnected.invoke(le)) {
                if (!(item instanceof BluetoothDevice candidate)) {
                    continue;
                }
                int group = (int) leGroup.invoke(le, candidate);
                if (group == INVALID_GROUP || (group != classicGroup && !classic.equals(candidate)
                        && !classic.equals(findBrDevice.invoke(candidate)))) {
                    continue;
                }
                if (!flag(leStreamAvailable, le, group)) {
                    continue;
                }
                BluetoothDevice lead = (BluetoothDevice) leLead.invoke(le, candidate);
                if (lead == null || (int) leGroup.invoke(le, lead) != group
                        || (int) leConnectionState.invoke(le, lead) != BluetoothProfile.STATE_CONNECTED) {
                    continue;
                }
                if (result != null && !result.equals(lead)) {
                    report(Log.WARN, "Ambiguous X4 group; left route unchanged");
                    return null;
                }
                result = lead;
            }
            return result;
        }

        private void verifyActiveGroup(Object adm, Handler currentHandler, Object le, int groupId) {
            try {
                if (handler.get(adm) != currentHandler) {
                    return;
                }
                boolean active = (int) leActiveGroup.invoke(le) == groupId;
                if (!active) {
                    clearGameVnd();
                }
                lastVerification = "X4 group " + groupId + " active in LE Audio service=" + active;
                report(Log.INFO, lastVerification);
            } catch (ReflectiveOperationException | RuntimeException error) {
                report(Log.WARN, "Could not verify LE route", error);
            }
        }

        private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
            return Class.forName(name, false, loader);
        }

        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
            Field result = owner.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        }

        private static Method method(Class<?> owner, String name, Class<?>... types) throws NoSuchMethodException {
            Method result = owner.getDeclaredMethod(name, types);
            result.setAccessible(true);
            return result;
        }

        private static boolean flag(Method method, Object receiver, Object... args)
                throws ReflectiveOperationException {
            return Boolean.TRUE.equals(method.invoke(receiver, args));
        }

        private static Object service(Method method, Object receiver) throws ReflectiveOperationException {
            return ((Optional<?>) method.invoke(receiver)).orElse(null);
        }
    }

    private static final class GameVndControl {
        private final Object channel;
        private final Object latency;
        private final Object smartAudio;
        private final Method setMode;
        private final Method getWalt;
        private final Method sendWalt;

        GameVndControl(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> cache = RouteHooks.load(loader, "com.oplus.bluetooth.common.OplusFeatureCache");
            Class<?> common = RouteHooks.load(loader, "com.oplus.bluetooth.common.IOplusCommonFeature");
            Class<?> channelType = RouteHooks.load(loader,
                    "com.oplus.bluetooth.common.interfaces.IOplusBluetoothNativeChannel");
            Class<?> channelImpl = RouteHooks.load(loader,
                    "com.oplus.bluetooth.feature.nativechannel.OplusBtNativeChannel");
            Class<?> latencyType = RouteHooks.load(loader,
                    "com.oplus.bluetooth.common.interfaces.IBluetoothA2dpLowLatencyMode");
            Class<?> smartAudioType = RouteHooks.load(loader,
                    "com.oplus.bluetooth.common.interfaces.IOplusBluetoothSmartAudio");
            Method get = RouteHooks.method(cache, "get", common);
            channel = channelImpl.cast(get.invoke(null, RouteHooks.field(channelType, "DEFAULT").get(null)));
            latency = get.invoke(null, RouteHooks.field(latencyType, "DEFAULT").get(null));
            smartAudio = get.invoke(null, RouteHooks.field(smartAudioType, "DEFAULT").get(null));
            setMode = RouteHooks.method(channelImpl, "setGameMode", boolean.class, boolean.class);
            getWalt = latency.getClass().getMethod("getWaltMode");
            sendWalt = RouteHooks.method(smartAudioType, "sendWaltMode", BluetoothDevice.class, boolean.class);
        }

        void setMode(boolean game, boolean walt) throws ReflectiveOperationException {
            setMode.invoke(channel, game, walt);
        }

        boolean isSystemWalt() throws ReflectiveOperationException {
            return RouteHooks.flag(getWalt, latency);
        }

        void sendEarbudMode(BluetoothDevice device, boolean enabled) throws ReflectiveOperationException {
            sendWalt.invoke(smartAudio, device, enabled);
        }
    }

    private static final class VendorBindings {
        final Field wiredOutput;
        final Field dualInstance;
        final Method dualNeeded;
        final Method occupied;

        VendorBindings(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> dual = RouteHooks.load(loader, DUAL_MODE_MANAGER);
            Class<?> adm = RouteHooks.load(loader, "com.oplus.bluetooth.btservice.OplusActiveDeviceManagerExtImpl");
            wiredOutput = RouteHooks.field(adm, "mOplusHasAddedWiredDevice");
            dualInstance = RouteHooks.field(dual, "sInstance");
            dualNeeded = RouteHooks.method(dual, "isDualConnectionNeeded", BluetoothDevice.class);
            occupied = RouteHooks.method(dual, "isEarbudsMultiConnOccupied", BluetoothDevice.class);
        }
    }

    private static final class PrerequisiteBindings {
        final Field featureEnabled;
        final Field powerSave;
        final Field helper;
        final Field preferenceManager;
        final Field installedApps;
        final Method supportDual;
        final Method preference;
        final Method hasLowLatency;
        final Method keyDevice;
        final Method generateKey;

        PrerequisiteBindings(Class<?> dual) throws ReflectiveOperationException {
            featureEnabled = RouteHooks.field(dual, "mIsDualModeFeatureEnabled");
            powerSave = RouteHooks.field(dual, "mIsPowerSaveMode");
            helper = RouteHooks.field(dual, "mDualModeHelper");
            preferenceManager = RouteHooks.field(dual, "mPreferenceManager");
            installedApps = RouteHooks.field(helper.getType(), "mInstalledLowLatencyPackages");
            supportDual = RouteHooks.method(dual, "isSupportDualConnection", BluetoothDevice.class);
            preference = RouteHooks.method(dual, "getDualConnectionPreference", BluetoothDevice.class);
            hasLowLatency = RouteHooks.method(dual, "hasLowLatencyPackage");
            keyDevice = RouteHooks.method(dual, "getPreferenceKeyDevice", BluetoothDevice.class);
            generateKey = RouteHooks.method(preferenceManager.getType(), "generatePreferenceKey", BluetoothDevice.class);
        }
    }
}
