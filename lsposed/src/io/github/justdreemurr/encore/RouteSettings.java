package io.github.justdreemurr.encore;

final class RouteSettings {
    static final String FILE = "route";
    static final String ROUTE_ENABLED = "route_enabled";
    static final boolean DEFAULT_ROUTE_ENABLED = true;
    static final String GAME_VND = "game_vnd";
    static final String RECONNECT_REQUEST = "reconnect_request";
    static final String SCENE_LOCK = "scene_lock";
    static final String SCENE_FOLLOW = "follow";
    static final String SCENE_GAME = "game";
    static final String COEXIST_PROPERTY = "persist.sys.bt.coexist_optimization";
    static final String DUAL_MODE_PROPERTY = "persist.sys.bt.leacoexist";
    static final String PREFERENCE_URI = "content://com.oplus.appplatform.settings/global";
    static final String PREFERENCE_KEY_PATTERN = "oplus_lea_dual_connection_preference_[0-9a-f]{64}";
    static final String WHITELIST_APP = "com.superpowered.superpoweredlatency";
    static final String BLUETOOTH_PACKAGE = "com.android.bluetooth";
    static final String SUPPORTED_FIRMWARE = "PJZ110_17.0.0.103(CN01)";

    private RouteSettings() {}
}
