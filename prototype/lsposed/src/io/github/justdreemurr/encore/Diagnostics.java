package io.github.justdreemurr.encore;

import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.justdreemurr.encore.LinkMonitor.Access;
import io.github.justdreemurr.encore.LinkMonitor.RootOutput;
import io.github.justdreemurr.encore.LinkMonitor.Route;
import io.github.justdreemurr.encore.LinkMonitor.Snapshot;

/** Environment checks and log export for the manager's diagnostics page. */
final class Diagnostics {
    enum Level { OK, INFO, WARN, FAIL }

    enum Group { ENVIRONMENT, CONNECTION, CONFLICTS }

    enum Lsposed { NO_SERVICE, NO_SCOPE, NOT_LOADED, UP_TO_DATE, STALE, RELOADING, FAILED, ERROR }

    record Check(Group group, Level level, String title, String detail) {}

    /** State only the app process can see: firmware, LSPosed service and saved settings. */
    record Environment(String appVersion, Lsposed lsposed, int pid, boolean routeEnabled, boolean gameVnd,
            String sceneLock) {}

    record Result(List<Check> checks, Map<String, String> files, String time) {}

    private static final String SECTION = "=== ";
    private static final String FILE_PREFIX = "file:";
    private static final String X4_MODULE = "enco_x4_lc3";
    private static final String FILTER_MODULE = "lea_native_filter";
    private static final Pattern FULL_MAC = Pattern.compile(
            "\\b(?:[0-9A-Fa-f]{2}:){4}([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})\\b");
    private static final Pattern DECISION = Pattern.compile("decision=(.*?), failure=(.*)$");

    static final String CHECK_SCRIPT = "echo '=== props ==='\n"
            + "for p in persist.sys.bt.leacoexist " + RouteSettings.COEXIST_PROPERTY
            + " persist.bluetooth.real.gamemode debug.leafx.force_game; do echo \"$p=$(getprop $p)\"; done\n"
            + "echo '=== settings ==='\n"
            + "for k in bluetooth_on low_power debug_gamemode_value debug_gamemode_savegame"
            + " oplus_games_low_latency_state; do echo \"$k=$(settings get global $k)\"; done\n"
            + "echo '=== modules ==='\n"
            + "for m in /data/adb/modules/*; do [ -f \"$m/module.prop\" ] || continue;"
            + " s=enabled; [ -f \"$m/disable\" ] && s=disabled; [ -f \"$m/remove\" ] && s=remove;"
            + " echo \"$(sed -n 's/^id=//p' \"$m/module.prop\")|$(sed -n 's/^version=//p' \"$m/module.prop\")|$s\";"
            + " done\n"
            + "echo '=== x4lc3 ==='\n"
            + "cat /data/adb/enco_x4_lc3/state 2>/dev/null\n"
            + "echo '=== encore ==='\n"
            + "dumpsys bluetooth_manager | tr -d '\\000' | grep '^  Encore' | grep -v '^  Encore event:'\n"
            + "echo '=== live ==='\n"
            + LinkMonitor.DUMP_SCRIPT + "\n";

    static final String EXPORT_SCRIPT = CHECK_SCRIPT
            + "echo '=== file:properties.txt ==='\n"
            + "getprop | grep -i -E 'blue|bt\\.|leaudio|le_audio|gamemode|leafx|ro\\.build\\.display|ro\\.product\\.model'\n"
            + "echo '=== file:bluetooth_manager.txt ==='\n"
            + "dumpsys bluetooth_manager | tr -d '\\000'\n"
            + "echo '=== file:audio.txt ==='\n"
            + "dumpsys audio | grep -a -E 'AudioPlaybackConfiguration|state:started' | head -n 60\n"
            + "dumpsys media.audio_flinger\n"
            + "echo '=== file:enco_x4_lc3.txt ==='\n"
            + "if [ -f /data/adb/modules/enco_x4_lc3/x4lc3.sh ]; then"
            + " timeout 15 sh /data/adb/modules/enco_x4_lc3/x4lc3.sh status;"
            + " echo; echo '--- x4lc3.log'; tail -n 200 /data/adb/enco_x4_lc3/x4lc3.log; else echo 'not installed'; fi\n"
            + "echo '=== file:lea_native_filter.txt ==='\n"
            + "cat /data/adb/modules/lea_native_filter/module.prop /data/adb/modules/lea_native_filter/state/applied.conf"
            + " 2>/dev/null || echo 'not installed'\n"
            + "echo '=== file:lsposed.log ==='\n"
            + "for f in /data/adb/lspd/log/modules_*.log; do grep -a -E 'Encore|io\\.github\\.justdreemurr' \"$f\""
            + " | tail -n 400; done\n"
            + "echo '=== file:logcat.txt ==='\n"
            + "logcat -d -b main,system,crash -v threadtime | grep -a -E 'Encore|LeAudio|le_audio|BluetoothLowLatency"
            + "|OPLUS_LE_A2DP|ActiveDeviceManager|AndroidRuntime' | tail -n 3000\n";

    private final Context context;

    Diagnostics(Context context) {
        this.context = context;
    }

    Result evaluate(RootOutput output, Environment environment) {
        Map<String, List<String>> sections = sections(output.lines());
        Map<String, String> props = keyValues(sections.get("props"));
        Map<String, String> settings = keyValues(sections.get("settings"));
        Map<String, String[]> modules = modules(sections.get("modules"));
        List<String> encore = sections.getOrDefault("encore", List.of());
        boolean root = output.access() == Access.GRANTED;
        List<Check> checks = new ArrayList<>();

        boolean supported = RouteSettings.SUPPORTED_FIRMWARE.equals(Build.DISPLAY);
        add(checks, Group.ENVIRONMENT, supported ? Level.OK : Level.FAIL, R.string.diag_firmware,
                supported ? Build.DISPLAY : string(R.string.diag_firmware_unsupported, Build.DISPLAY));
        add(checks, Group.ENVIRONMENT, root ? Level.OK : Level.FAIL, R.string.diag_root,
                string(root ? R.string.diag_root_ok : R.string.diag_root_denied));
        checks.add(lsposedCheck(environment));
        if (environment.lsposed() == Lsposed.UP_TO_DATE || environment.lsposed() == Lsposed.STALE) {
            checks.add(moduleCheck(environment, encore, root));
        }
        if (!root) {
            return new Result(checks, Map.of(), now());
        }

        boolean bluetoothOn = "1".equals(settings.get("bluetooth_on"));
        add(checks, Group.CONNECTION, bluetoothOn ? Level.OK : Level.FAIL, R.string.diag_bluetooth,
                string(bluetoothOn ? R.string.diag_on : R.string.diag_bluetooth_off));
        boolean saver = "1".equals(settings.get("low_power"));
        add(checks, Group.CONNECTION, saver ? Level.WARN : Level.OK, R.string.diag_power_saver,
                string(saver ? R.string.diag_power_saver_on : R.string.diag_off));
        boolean coexist = "true".equals(props.get("persist.sys.bt.leacoexist"));
        add(checks, Group.CONNECTION, coexist ? Level.OK : Level.FAIL, R.string.diag_coexist,
                string(coexist ? R.string.diag_on : R.string.diag_coexist_off));
        List<String> liveLines = sections.get("live");
        Snapshot live = liveLines == null ? null : LinkMonitor.parse(liveLines, new HashMap<>());
        boolean x4 = live != null && live.device() != null && live.device().contains("Enco X4");
        checks.add(x4Check(x4 ? live : null));
        checks.add(routeCheck(environment, encore, x4 && live.route() == Route.A2DP));

        boolean systemGame = "1".equals(settings.get("debug_gamemode_value"))
                || "true".equals(props.get("persist.bluetooth.real.gamemode"));
        add(checks, Group.CONFLICTS, systemGame ? Level.WARN : Level.OK, R.string.diag_system_game,
                string(systemGame ? R.string.diag_system_game_on : R.string.diag_off));
        String[] x4Module = modules.get(X4_MODULE);
        if (x4Module != null) {
            List<String> state = sections.getOrDefault("x4lc3", List.of());
            boolean enabled = "enabled".equals(x4Module[1]);
            boolean globalOn = enabled && !state.isEmpty() && "on".equals(state.get(0).trim());
            add(checks, Group.CONFLICTS, globalOn ? Level.WARN : Level.INFO, R.string.diag_x4_module,
                    globalOn ? string(R.string.diag_x4_module_on, x4Module[0])
                            : string(enabled ? R.string.diag_x4_module_off : R.string.diag_module_disabled, x4Module[0]));
        }
        String[] filter = modules.get(FILTER_MODULE);
        if (filter != null) {
            boolean forced = "1".equals(props.get("debug.leafx.force_game"));
            add(checks, Group.CONFLICTS, forced ? Level.WARN : Level.INFO, R.string.diag_filter,
                    forced ? string(R.string.diag_filter_forced, filter[0])
                            : string("enabled".equals(filter[1]) ? R.string.diag_filter_on : R.string.diag_module_disabled,
                                    filter[0]));
        }
        boolean byContent = "false".equals(props.get(RouteSettings.COEXIST_PROPERTY));
        String scene = string(byContent ? R.string.diag_scene_by_content : R.string.diag_scene_default);
        if (RouteSettings.SCENE_GAME.equals(environment.sceneLock())) {
            scene = scene + " · " + string(R.string.scene_lock_game);
        }
        add(checks, Group.CONFLICTS, Level.INFO, R.string.diag_scene, scene);

        Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            if (section.getKey().startsWith(FILE_PREFIX)) {
                files.put(section.getKey().substring(FILE_PREFIX.length()), String.join("\n", section.getValue()));
            }
        }
        return new Result(checks, files, now());
    }

    /** Writes the summary and collected files as a zip, with Bluetooth addresses reduced to their last two bytes. */
    void write(OutputStream stream, Result result, Environment environment, Snapshot live) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(stream)) {
            entry(zip, "summary.txt", summary(result, environment, live));
            for (Map.Entry<String, String> file : result.files().entrySet()) {
                entry(zip, file.getKey(), file.getValue());
            }
        }
    }

    static String fileName() {
        return "encore-diag-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
    }

    private String summary(Result result, Environment environment, Snapshot live) {
        StringBuilder text = new StringBuilder();
        text.append("Encore ").append(environment.appVersion()).append('\n')
                .append("Collected: ").append(result.time()).append('\n')
                .append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append(")\n")
                .append("Firmware: ").append(Build.DISPLAY).append('\n')
                .append("LSPosed: ").append(environment.lsposed()).append(" pid=").append(environment.pid()).append('\n')
                .append("Settings: route=").append(environment.routeEnabled()).append(" gameVnd=")
                .append(environment.gameVnd()).append(" sceneLock=").append(environment.sceneLock()).append('\n')
                .append("Live: ").append(live.summary()).append("\n\n");
        for (Check check : result.checks()) {
            text.append('[').append(check.level()).append("] ").append(check.title()).append(": ")
                    .append(check.detail()).append('\n');
        }
        return text.toString();
    }

    private Check lsposedCheck(Environment environment) {
        return switch (environment.lsposed()) {
            case NO_SERVICE -> check(Group.ENVIRONMENT, Level.FAIL, R.string.diag_lsposed, R.string.module_waiting);
            case NO_SCOPE -> check(Group.ENVIRONMENT, Level.FAIL, R.string.diag_lsposed, R.string.module_scope);
            case ERROR -> check(Group.ENVIRONMENT, Level.FAIL, R.string.diag_lsposed, R.string.module_error);
            case NOT_LOADED -> check(Group.ENVIRONMENT, Level.FAIL, R.string.diag_lsposed, R.string.module_not_loaded);
            case RELOADING -> check(Group.ENVIRONMENT, Level.INFO, R.string.diag_lsposed, R.string.module_reloading);
            case FAILED -> check(Group.ENVIRONMENT, Level.FAIL, R.string.diag_lsposed, R.string.module_failed);
            default -> check(Group.ENVIRONMENT, Level.OK, R.string.diag_lsposed, R.string.diag_lsposed_ok);
        };
    }

    private Check moduleCheck(Environment environment, List<String> encore, boolean root) {
        String running = null;
        for (String line : encore) {
            if (line.startsWith("  Encore version: ")) {
                running = line.substring("  Encore version: ".length()).trim();
            }
        }
        String version = running != null ? running : root && !encore.isEmpty() ? string(R.string.diag_version_old)
                : string(R.string.value_missing);
        if (environment.lsposed() == Lsposed.STALE) {
            String detail = version.equals(environment.appVersion()) ? string(R.string.module_stale)
                    : string(R.string.diag_module_stale, version, environment.appVersion());
            return new Check(Group.ENVIRONMENT, Level.WARN, string(R.string.diag_module),
                    detail.replaceAll("。$", ""));
        }
        return new Check(Group.ENVIRONMENT, Level.OK, string(R.string.diag_module),
                string(R.string.diag_module_running, version, environment.pid()));
    }

    private Check x4Check(Snapshot snapshot) {
        if (snapshot == null) {
            return check(Group.CONNECTION, Level.INFO, R.string.diag_x4, R.string.diag_x4_none);
        }
        String playing = string(snapshot.streaming() ? R.string.hero_playing : R.string.hero_standby);
        if (snapshot.route() == Route.LE_AUDIO) {
            String scenario = snapshot.scenario() != null ? snapshot.scenario() : string(R.string.value_missing);
            return new Check(Group.CONNECTION, Level.OK, string(R.string.diag_x4),
                    string(R.string.diag_x4_le, scenario, playing));
        }
        String codec = snapshot.codec() != null ? snapshot.codec() : string(R.string.value_missing);
        return new Check(Group.CONNECTION, Level.WARN, string(R.string.diag_x4),
                string(R.string.diag_x4_a2dp, codec, playing));
    }

    private Check routeCheck(Environment environment, List<String> encore, boolean x4OnClassic) {
        if (!environment.routeEnabled()) {
            return check(Group.CONNECTION, Level.INFO, R.string.diag_route, R.string.diag_route_off);
        }
        for (String line : encore) {
            if (line.startsWith("  Encore: ")) {
                Matcher match = DECISION.matcher(line);
                if (match.find()) {
                    boolean failed = !"null".equals(match.group(2).trim());
                    if (!failed && x4OnClassic) {
                        return check(Group.CONNECTION, Level.WARN, R.string.diag_route, R.string.diag_route_stale);
                    }
                    return new Check(Group.CONNECTION, failed ? Level.WARN : Level.INFO, string(R.string.diag_route),
                            failed ? match.group(2).trim() : match.group(1).trim());
                }
            }
        }
        return check(Group.CONNECTION, Level.INFO, R.string.diag_route, R.string.diag_route_unknown);
    }

    private static Map<String, List<String>> sections(List<String> lines) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : lines) {
            if (line.startsWith(SECTION) && line.endsWith(" ===")) {
                current = new ArrayList<>();
                sections.put(line.substring(SECTION.length(), line.length() - 4).trim(), current);
            } else if (current != null) {
                current.add(line);
            }
        }
        return sections;
    }

    private static Map<String, String> keyValues(List<String> lines) {
        Map<String, String> values = new HashMap<>();
        if (lines != null) {
            for (String line : lines) {
                int split = line.indexOf('=');
                if (split > 0) {
                    values.put(line.substring(0, split), line.substring(split + 1).trim());
                }
            }
        }
        return values;
    }

    /** Maps module id to {version, state}. */
    private static Map<String, String[]> modules(List<String> lines) {
        Map<String, String[]> modules = new HashMap<>();
        if (lines != null) {
            for (String line : lines) {
                String[] parts = line.split("\\|", -1);
                if (parts.length == 3 && !parts[0].isEmpty()) {
                    modules.put(parts[0], new String[] {parts[1], parts[2]});
                }
            }
        }
        return modules;
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(redact(text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String redact(String text) {
        return FULL_MAC.matcher(text).replaceAll("XX:XX:XX:XX:$1");
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void add(List<Check> checks, Group group, Level level, int title, String detail) {
        checks.add(new Check(group, level, string(title), detail));
    }

    private Check check(Group group, Level level, int title, int detail) {
        return new Check(group, level, string(title), string(detail));
    }

    private String string(int resource, Object... args) {
        return context.getString(resource, args);
    }
}
