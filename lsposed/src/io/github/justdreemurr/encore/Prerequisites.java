package io.github.justdreemurr.encore;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

import io.github.justdreemurr.encore.Diagnostics.Check;
import io.github.justdreemurr.encore.Diagnostics.Group;
import io.github.justdreemurr.encore.Diagnostics.Level;
import io.github.justdreemurr.encore.LinkMonitor.Requirements;

/** The conditions ColorOS checks before it keeps an LE Audio connection to X4, with a way to resolve each one. */
final class Prerequisites {
    enum Fix { ENABLE_DUAL_MODE, POWER_SAVER, ENABLE_PREFERENCE, INSTALL_APP, BLUETOOTH_SETTINGS, RECONNECT,
        RESTART_SCOPE }

    private static final int POLICY_FORBIDDEN = 0;
    private static final int POLICY_ALLOWED = 100;

    private Prerequisites() {}

    /** Returns an empty list while root output is not available yet. */
    static List<Check> evaluate(Context context, Requirements requirements) {
        List<Check> checks = new ArrayList<>();
        if (requirements.dualModeProperty() == null) {
            return checks;
        }
        boolean dualMode = "true".equals(requirements.dualModeProperty());
        if (!dualMode) {
            checks.add(check(context, Level.FAIL, R.string.req_dual_mode, R.string.req_dual_mode_off,
                    R.string.req_dual_mode_hint, Fix.ENABLE_DUAL_MODE));
        } else if (requirements.reported() && !requirements.flag("feature")) {
            checks.add(check(context, Level.FAIL, R.string.req_dual_mode, R.string.req_dual_mode_pending,
                    R.string.req_restart_hint, Fix.RESTART_SCOPE));
        } else {
            checks.add(check(context, Level.OK, R.string.req_dual_mode, R.string.diag_on, 0, null));
        }
        boolean saver = "1".equals(requirements.powerSaver()) || requirements.flag("powerSave");
        checks.add(saver ? check(context, Level.FAIL, R.string.diag_power_saver, R.string.diag_power_saver_on,
                R.string.req_power_saver_hint, Fix.POWER_SAVER)
                : check(context, Level.OK, R.string.diag_power_saver, R.string.diag_off, 0, null));
        if (!requirements.reported()) {
            checks.add(check(context, Level.INFO, R.string.req_module, R.string.req_module_missing,
                    R.string.req_restart_hint, Fix.RESTART_SCOPE));
            return checks;
        }

        boolean lowLatency = requirements.flag("lowLatency");
        if (lowLatency) {
            checks.add(new Check(Group.PREREQUISITES, Level.OK, context.getString(R.string.req_whitelist),
                    appLabels(context, requirements.apps()), null, null));
        } else if (requirements.flag("whitelistBypass")) {
            checks.add(check(context, Level.OK, R.string.req_whitelist, R.string.req_whitelist_bypassed, 0, null));
        } else {
            checks.add(check(context, Level.FAIL, R.string.req_whitelist, R.string.req_whitelist_none,
                    R.string.req_whitelist_hint, Fix.INSTALL_APP));
        }
        if (!"found".equals(requirements.module().get("device"))) {
            checks.add(check(context, Level.FAIL, R.string.diag_x4, R.string.req_device_none,
                    R.string.req_device_hint, Fix.BLUETOOTH_SETTINGS));
            return checks;
        }
        if (!requirements.flag("dual")) {
            checks.add(check(context, Level.FAIL, R.string.req_dual_support, R.string.req_dual_support_no,
                    R.string.req_reconnect_hint, Fix.RECONNECT));
        }
        boolean preference = requirements.flag("pref");
        String key = requirements.module().get("prefKey");
        checks.add(preference ? check(context, Level.OK, R.string.req_preference, R.string.diag_on, 0, null)
                : check(context, Level.FAIL, R.string.req_preference, R.string.req_preference_off,
                        R.string.req_preference_hint, validPreferenceKey(key)
                                ? Fix.ENABLE_PREFERENCE : Fix.BLUETOOTH_SETTINGS));
        int hfp = requirements.number("hfp");
        int a2dp = requirements.number("a2dp");
        if (hfp == POLICY_FORBIDDEN || a2dp == POLICY_FORBIDDEN) {
            checks.add(check(context, Level.FAIL, R.string.req_profiles, R.string.req_profiles_off,
                    R.string.req_profiles_hint, Fix.BLUETOOTH_SETTINGS));
        } else if (hfp == POLICY_ALLOWED && a2dp == POLICY_ALLOWED) {
            checks.add(check(context, Level.OK, R.string.req_profiles, R.string.req_profiles_on, 0, null));
        } else {
            checks.add(check(context, Level.WARN, R.string.req_profiles, R.string.req_profiles_unknown,
                    R.string.req_profiles_hint, Fix.BLUETOOTH_SETTINGS));
        }
        int le = requirements.number("le");
        if (le > 0) {
            checks.add(new Check(Group.PREREQUISITES, Level.OK, context.getString(R.string.req_le),
                    context.getString(R.string.req_le_connected, le), null, null));
        } else if (le < 0) {
            checks.add(check(context, Level.INFO, R.string.req_le, R.string.req_unavailable, 0, null));
        } else if (requirements.flag("needed")) {
            checks.add(check(context, Level.WARN, R.string.req_le, R.string.req_le_waiting,
                    R.string.req_reconnect_hint, Fix.RECONNECT));
        } else {
            checks.add(check(context, Level.WARN, R.string.req_le, R.string.req_le_not_needed, 0, null));
        }
        return checks;
    }

    static boolean validPreferenceKey(String key) {
        return key != null && key.matches(RouteSettings.PREFERENCE_KEY_PATTERN);
    }

    /** Conditions that currently stop X4 from using LE Audio. */
    static List<Check> unmet(List<Check> checks) {
        List<Check> unmet = new ArrayList<>();
        for (Check check : checks) {
            if (check.level() == Level.FAIL) {
                unmet.add(check);
            }
        }
        return unmet;
    }

    static int fixLabel(Fix fix) {
        return switch (fix) {
            case ENABLE_DUAL_MODE -> R.string.fix_dual_mode;
            case POWER_SAVER -> R.string.fix_power_saver;
            case ENABLE_PREFERENCE -> R.string.fix_preference;
            case INSTALL_APP -> R.string.fix_install;
            case BLUETOOTH_SETTINGS -> R.string.bluetooth_settings;
            case RECONNECT -> R.string.reconnect_x4;
            case RESTART_SCOPE -> R.string.restart_scope;
        };
    }

    private static String appLabels(Context context, List<String> apps) {
        if (apps.isEmpty()) {
            return context.getString(R.string.req_whitelist_present);
        }
        PackageManager packages = context.getPackageManager();
        List<String> labels = new ArrayList<>();
        for (String app : apps) {
            try {
                labels.add(packages.getApplicationLabel(packages.getApplicationInfo(app, 0)).toString());
            } catch (PackageManager.NameNotFoundException error) {
                labels.add(app);
            }
        }
        return String.join("、", labels);
    }

    private static Check check(Context context, Level level, int title, int detail, int hint, Fix fix) {
        return new Check(Group.PREREQUISITES, level, context.getString(title), context.getString(detail),
                hint == 0 ? null : context.getString(hint), fix);
    }
}
