package io.github.justdreemurr.encore;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LinkMonitor {
    private static final long REPORT_MAX_AGE_SECONDS = 20;
    static final String DUMP_SCRIPT = "date '+%m-%d %H:%M:%S'\n"
            + "dumpsys bluetooth_manager | grep -E '"
            + "^  (A2DP|HFP|HA|LE Audio|LE HA):$|^    Active: |Current scenario:|Group \\(gID\\)|== Inactive Groups"
            + "|Current state: |Configuration context type:|Stream config:|Presentation delay for playback"
            + "|Device \\[|Audio Locations:|^[[:space:]]+[0-9]+[[:space:]]+true[[:space:]]+sink"
            + "|Monitoring, Handle: 0x[0-9a-f]+, ISO|SDU Interval:|Current Codec:|^A2DP .* State:$"
            + "|^  Config: Rate=|mIsPlaying:|^  Encore reconnect: |^ IsInCall: .*IsInGame: "
            + "|handle(A2dp|LeAudio)ActiveDeviceChanged: '\n"
            + "echo \"Encore coexist property: [$(getprop " + RouteSettings.COEXIST_PROPERTY + ")]\"";
    private static final String RESTART_SCRIPT = "if [ \"$(settings get global bluetooth_on)\" = 0 ]; then"
            + " echo off; else svc bluetooth disable >/dev/null 2>&1"
            + " && cmd bluetooth_manager wait-for-state:STATE_OFF >/dev/null 2>&1"
            + " && svc bluetooth enable >/dev/null 2>&1"
            + " && cmd bluetooth_manager wait-for-state:STATE_ON >/dev/null 2>&1 && echo restarted; fi";
    private static final Pattern ACTIVE_DEVICE =
            Pattern.compile("^Active: (?:[0-9A-Fa-fX]{2}:){5}[0-9A-Fa-fX]{2}:\\s*(.+)$");
    private static final Pattern ASE_ROW = Pattern.compile(
            "^\\d+\\s+true\\s+sink\\s+\\d+\\s*/\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
    private static final Pattern QUALITY_REPORT = Pattern.compile(
            "(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d) Monitoring, Handle: 0x([0-9a-f]+), ISO,.*?RSSI: (-?\\d+),"
                    + ".*?TxTotal: (\\d+),.*?TxFlushed: (\\d+)");
    private static final Pattern A2DP_SECTION = Pattern.compile("^A2DP (.+) State:$");
    private static final Pattern DEVICE_SECTION = Pattern.compile("^  (A2DP|HFP|HA|LE Audio|LE HA):$");
    private static final Pattern A2DP_CONFIG = Pattern.compile("Rate=(\\d+) Bits=(\\d+)");
    private static final Pattern SAMPLE_RATE = Pattern.compile("_(8|16|24|32|44|48|96)_");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern RECONNECT = Pattern.compile("^Encore reconnect: (\\d+) ([A-Z_]+)$");
    private static final Pattern ACTIVATION = Pattern.compile("handle(A2dp|LeAudio)ActiveDeviceChanged: (\\S+?),");
    private static final Pattern COEXIST_PROPERTY = Pattern.compile("^Encore coexist property: \\[(.*)]$");
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    enum Access { PENDING, GRANTED, DENIED }

    enum Route { NONE, LE_AUDIO, A2DP }

    enum RestartResult { RESTARTED, BLUETOOTH_OFF, DENIED, FAILED }

    enum WriteResult { SAVED, DENIED, FAILED }

    /** Output of a root script; lines is empty unless access is GRANTED. */
    record RootOutput(Access access, List<String> lines) {}

    record QualityReport(LocalDateTime time, int rssi, int sent, int flushed) {}

    record Ear(String side, int rssi, int sent, int flushed) {
        boolean hasReport() {
            return sent > 0;
        }

        float lossPercent() {
            return sent > 0 ? flushed * 100f / sent : 0f;
        }
    }

    record Reconnect(long id, String state) {
        static final Reconnect UNKNOWN = new Reconnect(0, null);
    }

    /** coexistProperty is null when unknown and empty when the property is unset. */
    record Scene(String coexistProperty, boolean inGame) {
        static final Scene UNKNOWN = new Scene(null, false);

        boolean switchesByContent() {
            return "false".equals(coexistProperty);
        }
    }

    record Snapshot(Access access, Route route, String device, boolean streaming, String scenario,
            String codec, int sampleRateHz, int bitsPerSample, int frameUs, int sduOctets,
            int transportLatencyMs, int retransmissions, int presentationDelayUs, List<Ear> ears,
            Reconnect reconnect, Scene scene) {
        static final Snapshot PENDING = idle(Access.PENDING, Reconnect.UNKNOWN, Scene.UNKNOWN);

        static Snapshot idle(Access access, Reconnect reconnect, Scene scene) {
            return new Snapshot(access, Route.NONE, null, false, null, null, 0, 0, 0, 0, 0, -1, 0, List.of(), reconnect,
                    scene);
        }

        boolean gameVnd() {
            return route == Route.LE_AUDIO && "GAME_VND".equals(scenario);
        }

        String summary() {
            StringBuilder ears = new StringBuilder();
            for (Ear ear : this.ears) {
                ears.append(' ').append(ear.side()).append(':').append(ear.rssi()).append("dBm/")
                        .append(String.format(Locale.ROOT, "%.1f%%", ear.lossPercent()));
            }
            return access + "/" + route + "/" + device + " streaming=" + streaming + " scenario=" + scenario
                    + " codec=" + codec + " rate=" + sampleRateHz + " frame=" + frameUs + " sdu=" + sduOctets
                    + " latency=" + transportLatencyMs + " rtn=" + retransmissions
                    + " delay=" + presentationDelayUs + " ears=[" + ears.toString().trim() + "]"
                    + " reconnect=" + reconnect.id() + "/" + reconnect.state()
                    + " coexist=[" + scene.coexistProperty() + "] inGame=" + scene.inGame();
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("encore-link");
    private final Handler worker;
    private final Consumer<Snapshot> listener;
    private final Map<Integer, QualityReport> qualityReports = new HashMap<>();
    private final Runnable pollTask = this::poll;
    private volatile long intervalMs;
    private volatile boolean closed;
    private volatile RootShell shell;
    private boolean denied;

    LinkMonitor(long intervalMs, Consumer<Snapshot> listener) {
        this.intervalMs = intervalMs;
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(pollTask);
    }

    void setInterval(long intervalMs) {
        this.intervalMs = intervalMs;
        worker.post(() -> {
            if (!denied && worker.hasCallbacks(pollTask)) {
                worker.removeCallbacks(pollTask);
                poll();
            }
        });
    }

    void retry() {
        worker.post(() -> {
            denied = false;
            worker.removeCallbacksAndMessages(null);
            poll();
        });
    }

    void restartBluetooth(Consumer<RestartResult> done) {
        worker.post(() -> {
            RestartResult result;
            try {
                List<String> output = openShell().run(RESTART_SCRIPT);
                if (output.contains("restarted")) {
                    result = RestartResult.RESTARTED;
                } else if (output.contains("off")) {
                    result = RestartResult.BLUETOOTH_OFF;
                } else {
                    result = RestartResult.FAILED;
                }
            } catch (RootDeniedException error) {
                result = RestartResult.DENIED;
            } catch (IOException error) {
                closeShell();
                result = RestartResult.FAILED;
            }
            RestartResult finished = result;
            main.post(() -> {
                if (!closed) {
                    done.accept(finished);
                }
            });
        });
    }

    void close() {
        closed = true;
        worker.removeCallbacksAndMessages(null);
        closeShell();
        thread.quitSafely();
    }

    /**
     * Runs a read-only root script on the monitor thread, with stdin detached so commands cannot consume
     * the shell's input. The result is delivered on the main thread.
     */
    void collect(String script, Consumer<RootOutput> done) {
        worker.post(() -> {
            RootOutput result;
            try {
                result = new RootOutput(Access.GRANTED, openShell().run("{\n" + script + "\n} </dev/null 2>&1"));
            } catch (RootDeniedException error) {
                result = new RootOutput(Access.DENIED, List.of());
            } catch (IOException error) {
                closeShell();
                result = new RootOutput(Access.PENDING, List.of());
            }
            RootOutput finished = result;
            main.post(() -> {
                if (!closed) {
                    done.accept(finished);
                }
            });
        });
    }

    /**
     * Writes the ColorOS dual-mode coexistence switch. Clearing it restores the unset default, which the
     * Bluetooth stack reads as true; the Bluetooth stack reads it again when the earbuds reconnect.
     */
    void setSwitchByContent(boolean enabled, Consumer<WriteResult> done) {
        String property = RouteSettings.COEXIST_PROPERTY;
        String write = enabled ? "setprop " + property + " false"
                : "resetprop -p --delete " + property + " >/dev/null 2>&1 || setprop " + property + " true";
        worker.post(() -> {
            WriteResult result;
            try {
                List<String> output = openShell().run(write + "\necho \"[$(getprop " + property + ")]\"");
                boolean switched = output.contains("[false]");
                result = switched == enabled ? WriteResult.SAVED : WriteResult.FAILED;
            } catch (RootDeniedException error) {
                result = WriteResult.DENIED;
            } catch (IOException error) {
                closeShell();
                result = WriteResult.FAILED;
            }
            WriteResult finished = result;
            main.post(() -> {
                if (!closed) {
                    done.accept(finished);
                }
            });
            worker.removeCallbacks(pollTask);
            poll();
        });
    }

    private void poll() {
        if (closed) {
            return;
        }
        Snapshot snapshot;
        try {
            snapshot = parse(openShell().run(DUMP_SCRIPT), qualityReports);
        } catch (RootDeniedException error) {
            snapshot = Snapshot.idle(Access.DENIED, Reconnect.UNKNOWN, Scene.UNKNOWN);
        } catch (IOException error) {
            closeShell();
            snapshot = Snapshot.PENDING;
        }
        Snapshot published = snapshot;
        main.post(() -> {
            if (!closed) {
                listener.accept(published);
            }
        });
        if (!denied) {
            worker.postDelayed(pollTask, intervalMs);
        }
    }

    private RootShell openShell() throws IOException {
        RootShell current = shell;
        if (current != null) {
            return current;
        }
        if (denied) {
            throw new RootDeniedException();
        }
        try {
            current = RootShell.open();
        } catch (IOException error) {
            denied = !closed;
            throw new RootDeniedException();
        }
        shell = current;
        if (closed) {
            closeShell();
            throw new IOException("Link monitor closed");
        }
        return current;
    }

    private void closeShell() {
        RootShell current = shell;
        shell = null;
        if (current != null) {
            current.close();
        }
    }

    static Snapshot parse(List<String> lines, Map<Integer, QualityReport> qualityReports) {
        LocalDateTime now = lines.isEmpty() ? null : reportTime(lines.get(0).trim());
        String section = "";
        String leDevice = null;
        String a2dpDevice = null;
        String scenario = null;
        boolean inActiveGroup = false;
        String groupState = null;
        String streamConfig = null;
        int presentationDelay = 0;
        String side = null;
        List<String> sides = new ArrayList<>();
        List<Integer> handles = new ArrayList<>();
        int sdu = 0;
        int latency = 0;
        int retransmissions = -1;
        int sduInterval = 0;
        String a2dpCodec = null;
        String a2dpSection = null;
        String a2dpConfig = null;
        boolean a2dpPlaying = false;
        Reconnect reconnect = Reconnect.UNKNOWN;
        String coexistProperty = null;
        boolean inGame = false;
        String lastActivated = null;

        for (String raw : lines) {
            String line = raw.trim();
            Matcher report = QUALITY_REPORT.matcher(line);
            if (report.find()) {
                LocalDateTime time = reportTime(report.group(1));
                if (time != null) {
                    qualityReports.put(Integer.parseInt(report.group(2), 16), new QualityReport(time,
                            Integer.parseInt(report.group(3)), Integer.parseInt(report.group(4)),
                            Integer.parseInt(report.group(5))));
                }
                continue;
            }
            Matcher deviceSection = DEVICE_SECTION.matcher(raw);
            if (deviceSection.matches()) {
                section = deviceSection.group(1);
                continue;
            }
            if (raw.startsWith("    Active: ")) {
                Matcher active = ACTIVE_DEVICE.matcher(line);
                String name = active.matches() ? active.group(1).trim() : null;
                if ("LE Audio".equals(section)) {
                    leDevice = name;
                } else if ("A2DP".equals(section)) {
                    a2dpDevice = name;
                }
                continue;
            }
            if (line.startsWith("Current scenario:")) {
                scenario = firstWord(line.substring("Current scenario:".length()));
            } else if (line.contains("Group (gID)")) {
                inActiveGroup = line.contains(", Active");
            } else if (line.startsWith("== Inactive Groups")) {
                inActiveGroup = false;
            } else if (line.startsWith("SDU Interval:")) {
                int value = number(line);
                if (sduInterval == 0 && value > 0) {
                    sduInterval = value;
                }
            } else if (line.startsWith("Current Codec:")) {
                a2dpCodec = line.substring("Current Codec:".length()).trim();
            } else if (line.startsWith("Encore reconnect: ")) {
                Matcher state = RECONNECT.matcher(line);
                if (state.matches()) {
                    reconnect = new Reconnect(Long.parseLong(state.group(1)), state.group(2));
                }
            } else if (line.startsWith("Encore coexist property: ")) {
                Matcher property = COEXIST_PROPERTY.matcher(line);
                coexistProperty = property.matches() ? property.group(1) : null;
            } else if (line.startsWith("IsInCall: ")) {
                inGame = line.endsWith("IsInGame: true");
            } else if (line.contains("ActiveDeviceChanged: ")) {
                Matcher activation = ACTIVATION.matcher(line);
                if (activation.find() && !"null".equals(activation.group(2))) {
                    lastActivated = activation.group(1);
                }
            } else if (line.startsWith("mIsPlaying:")) {
                a2dpPlaying |= line.endsWith("true");
            } else if (line.startsWith("A2DP ") && line.endsWith(" State:")) {
                Matcher header = A2DP_SECTION.matcher(line);
                a2dpSection = header.matches() ? header.group(1) : null;
            } else if (line.startsWith("Config: Rate=") && a2dpSection != null && a2dpSection.equals(a2dpCodec)) {
                a2dpConfig = line;
            } else if (inActiveGroup) {
                if (line.startsWith("Current state:")) {
                    groupState = firstWord(line.substring("Current state:".length()));
                } else if (line.startsWith("Stream config:")) {
                    streamConfig = line.substring("Stream config:".length()).trim().replace("\"", "");
                } else if (line.startsWith("Presentation delay for playback")) {
                    presentationDelay = number(line.substring(line.indexOf(':') + 1));
                } else if (line.startsWith("● Device [") || line.startsWith("Device [")) {
                    side = null;
                } else if (line.startsWith("Audio Locations:")) {
                    side = line.contains("Sink= left") ? "left" : line.contains("Sink= right") ? "right" : null;
                } else {
                    Matcher ase = ASE_ROW.matcher(line);
                    if (ase.find()) {
                        handles.add(Integer.parseInt(ase.group(1)));
                        sides.add(side == null ? "mono" : side);
                        sdu = Math.max(sdu, Integer.parseInt(ase.group(2)));
                        latency = Math.max(latency, Integer.parseInt(ase.group(3)));
                        retransmissions = Math.max(retransmissions, Integer.parseInt(ase.group(4)));
                    }
                }
            }
        }

        qualityReports.values().removeIf(report -> now == null
                || Math.abs(Duration.between(report.time(), now).getSeconds()) > REPORT_MAX_AGE_SECONDS);
        Scene scene = new Scene(coexistProperty, inGame);
        // On ColorOS dual-mode earbuds ActiveDeviceManager can keep the LE Audio record after A2DP takes the
        // output back, so the LE record alone does not prove where audio is going.
        boolean leStreaming = "STREAMING".equals(groupState);
        boolean classicOutput = a2dpDevice != null && !leStreaming && (a2dpPlaying || "A2dp".equals(lastActivated));
        if (leDevice != null && !classicOutput) {
            List<Ear> ears = new ArrayList<>();
            for (int index = 0; index < handles.size(); index++) {
                QualityReport report = qualityReports.get(handles.get(index));
                ears.add(report == null ? new Ear(sides.get(index), 0, 0, 0)
                        : new Ear(sides.get(index), report.rssi(), report.sent(), report.flushed()));
            }
            ears.sort((left, right) -> left.side().compareTo(right.side()));
            boolean streaming = "STREAMING".equals(groupState);
            String codec = streamConfig != null && streamConfig.contains("Lc3") ? "LC3" : null;
            int rate = 0;
            if (streamConfig != null) {
                Matcher match = SAMPLE_RATE.matcher(streamConfig);
                rate = match.find() ? Integer.parseInt(match.group(1)) * 1000 : 0;
            }
            return new Snapshot(Access.GRANTED, Route.LE_AUDIO, leDevice, streaming, scenario, codec, rate, 0,
                    sduInterval, sdu, latency, retransmissions, presentationDelay, ears, reconnect, scene);
        }
        if (a2dpDevice != null) {
            int rate = 0;
            int bits = 0;
            if (a2dpConfig != null) {
                Matcher config = A2DP_CONFIG.matcher(a2dpConfig);
                if (config.find()) {
                    rate = Integer.parseInt(config.group(1));
                    bits = Integer.parseInt(config.group(2));
                }
            }
            return new Snapshot(Access.GRANTED, Route.A2DP, a2dpDevice, a2dpPlaying, scenario, a2dpCodec, rate,
                    bits, 0, 0, 0, -1, 0, List.of(), reconnect, scene);
        }
        return Snapshot.idle(Access.GRANTED, reconnect, scene);
    }

    private static LocalDateTime reportTime(String monthDayTime) {
        try {
            return LocalDateTime.parse("2000-" + monthDayTime, REPORT_TIME);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static String firstWord(String value) {
        String trimmed = value.trim();
        int end = trimmed.indexOf(' ');
        return end < 0 ? trimmed : trimmed.substring(0, end);
    }

    private static int number(String value) {
        Matcher match = NUMBER.matcher(value);
        return match.find() ? Integer.parseInt(match.group(1)) : 0;
    }

    private static final class RootDeniedException extends IOException {}

    private static final class RootShell implements Closeable {
        private final Process process;
        private final BufferedWriter input;
        private final BufferedReader output;
        private int sequence;

        private RootShell(Process process) {
            this.process = process;
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        static RootShell open() throws IOException {
            RootShell shell = new RootShell(new ProcessBuilder("su").redirectErrorStream(true).start());
            try {
                if (!shell.run("id -u").contains("0")) {
                    throw new IOException("Root access was not granted");
                }
            } catch (IOException error) {
                shell.close();
                throw error;
            }
            return shell;
        }

        List<String> run(String script) throws IOException {
            String marker = "__ENCORE_DONE_" + (++sequence) + "__";
            input.write(script);
            input.write("\necho " + marker + "\n");
            input.flush();
            List<String> lines = new ArrayList<>();
            for (String line = output.readLine(); line != null; line = output.readLine()) {
                if (line.equals(marker)) {
                    return lines;
                }
                lines.add(line);
            }
            throw new IOException("Root shell closed");
        }

        @Override
        public void close() {
            process.destroy();
        }
    }
}
