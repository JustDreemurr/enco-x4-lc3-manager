package io.github.justdreemurr.encore;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

final class ManagerAppearance {
    private static final String PALETTE = "palette";
    private static final String DISPLAY = "display";
    private static final String REFRESH = "refresh_seconds";
    static final int[] REFRESH_SECONDS = {1, 2, 5, 10};
    private static final int DEFAULT_REFRESH_SECONDS = 2;
    private final SharedPreferences settings;

    ManagerAppearance(Context context) {
        settings = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    }

    enum Display {
        SYSTEM(R.string.display_system), LIGHT(R.string.display_light), DARK(R.string.display_dark);

        final int title;

        Display(int title) {
            this.title = title;
        }
    }

    enum Scheme {
        WALLPAPER(R.string.palette_wallpaper, null, null),
        IRIS(R.string.palette_iris,
                new Colors(0xfffbf8ff, 0xffefedf4, 0xffe7e4eb, 0xff1d1b20, 0xff4a4453, 0xff6750a2, 0xffffffff, 0xffe9ddff, 0xff170d54, 0xffe9ddfc, 0xff1f182e, 0xff8c4a5b, 0xffffd9e0, 0xff3e0219, 0xff7a7485),
                new Colors(0xff151218, 0xff211f24, 0xff2b292f, 0xffe4e1e9, 0xffcac3d6, 0xffd2bbff, 0xff33236d, 0xff4d3987, 0xffe9ddff, 0xff4b435c, 0xffe9ddfc, 0xfffeb2c3, 0xff713244, 0xffffd9e0, 0xff948e9f)),
        OCEAN(R.string.palette_ocean,
                new Colors(0xfff6faff, 0xffe9eef5, 0xffe0e6ed, 0xff181c21, 0xff3b4856, 0xff006497, 0xffffffff, 0xffcee5ff, 0xff001e31, 0xffcee5ff, 0xff031d30, 0xff75527a, 0xfffad7ff, 0xff2c0f33, 0xff6b7888),
                new Colors(0xff0f1419, 0xff1c2025, 0xff262a2f, 0xffdee3ea, 0xffbac8d9, 0xff97cbff, 0xff003351, 0xff004b73, 0xffcee5ff, 0xff33495e, 0xffcee5ff, 0xffe1bae6, 0xff5c3b61, 0xfffad7ff, 0xff8592a2)),
        TIDE(R.string.palette_tide,
                new Colors(0xfff0fcfc, 0xffe5f0f1, 0xffdce7e8, 0xff151d1e, 0xff324b4d, 0xff00696f, 0xffffffff, 0xff71f5ff, 0xff002022, 0xffc2eaed, 0xff002022, 0xff3d6185, 0xffd0e4ff, 0xff001d32, 0xff617c7e),
                new Colors(0xff0a1516, 0xff192122, 0xff232c2c, 0xffd9e5e5, 0xffafccce, 0xff3adae5, 0xff00363a, 0xff004f54, 0xff71f5ff, 0xff284c4f, 0xffc2eaed, 0xffa7caf2, 0xff214a6c, 0xffd0e4ff, 0xff7a9698)),
        FOREST(R.string.palette_forest,
                new Colors(0xfff5fbf4, 0xffe9efe9, 0xffe1e7e0, 0xff181d18, 0xff3e4a3d, 0xff306a34, 0xffffffff, 0xffb4f2b2, 0xff042100, 0xffd4e8d2, 0xff101f0f, 0xff13696c, 0xffa4eff3, 0xff002021, 0xff6d7b6c),
                new Colors(0xff10150f, 0xff1c211c, 0xff272b26, 0xffdee4de, 0xffbccabb, 0xff98d597, 0xff00390d, 0xff15521e, 0xffb4f2b2, 0xff3a4b3a, 0xffd4e8d2, 0xff88d3d6, 0xff004f52, 0xffa4eff3, 0xff879486)),
        AMBER(R.string.palette_amber,
                new Colors(0xfffff8f3, 0xfff4ede7, 0xffebe4de, 0xff1f1b17, 0xff524438, 0xff88510f, 0xffffffff, 0xffffdcbf, 0xff291800, 0xfff9dec6, 0xff291801, 0xff4e6538, 0xffd1ebb6, 0xff141f00, 0xff847467),
                new Colors(0xff18120d, 0xff241f1a, 0xff2e2925, 0xffe9e1db, 0xffd5c3b5, 0xfffeb874, 0xff492900, 0xff6a3c00, 0xffffdcbf, 0xff574330, 0xfff9dec6, 0xffb5cf9b, 0xff364d22, 0xffd1ebb6, 0xff9e8e81)),
        CORAL(R.string.palette_coral,
                new Colors(0xfffff8f6, 0xfff6ece9, 0xffeee3e1, 0xff211a18, 0xff56423e, 0xffa23d32, 0xffffffff, 0xffffdad3, 0xff3a0b00, 0xffffdad3, 0xff2f130e, 0xff695e2c, 0xfff3e2a9, 0xff211b00, 0xff89726e),
                new Colors(0xff1a1110, 0xff251e1c, 0xff302827, 0xffebe0de, 0xffdac1bc, 0xffffb4a6, 0xff680205, 0xff85231c, 0xffffdad3, 0xff5f3e38, 0xffffdad3, 0xffd6c68f, 0xff504716, 0xfff3e2a9, 0xffa38b87)),
        SAKURA(R.string.palette_sakura,
                new Colors(0xfffff7fa, 0xfff5ebef, 0xffede3e6, 0xff211a1c, 0xff554148, 0xff9b3c66, 0xffffffff, 0xffffd8e5, 0xff3e0020, 0xfffed9e5, 0xff2d131e, 0xff80543c, 0xffffdbc9, 0xff2e1500, 0xff877179),
                new Colors(0xff191114, 0xff251e20, 0xff2f282a, 0xffeae0e3, 0xffd9c0c8, 0xffffb0cd, 0xff640037, 0xff80224e, 0xffffd8e5, 0xff5c3e49, 0xfffed9e5, 0xfff0bb9f, 0xff653d26, 0xffffdbc9, 0xffa28b92)),
        GRAPHITE(R.string.palette_graphite,
                new Colors(0xfff5faff, 0xffe9eef5, 0xffe0e6ed, 0xff181c21, 0xff404850, 0xff575f68, 0xffffffff, 0xffdbe3ed, 0xff151c23, 0xffdbe3ed, 0xff151c23, 0xff635c65, 0xffe8e0ea, 0xff1f1a21, 0xff707881),
                new Colors(0xff0f1419, 0xff1c2025, 0xff262a2f, 0xffdde3ea, 0xffbfc7d1, 0xffbfc7d1, 0xff2a3139, 0xff404850, 0xffdbe3ed, 0xff404850, 0xffdbe3ed, 0xffccc4ce, 0xff4b454d, 0xffe8e0ea, 0xff89929b));

        final int title;
        private final Colors light;
        private final Colors dark;

        Scheme(int title, Colors light, Colors dark) {
            this.title = title;
            this.light = light;
            this.dark = dark;
        }

        Colors colors(Context context, boolean night) {
            if (this != WALLPAPER) {
                return night ? dark : light;
            }
            if (Build.VERSION.SDK_INT >= 34) {
                return night ? new Colors(
                        context.getColor(android.R.color.system_surface_dark),
                        context.getColor(android.R.color.system_surface_container_dark),
                        context.getColor(android.R.color.system_surface_container_high_dark),
                        context.getColor(android.R.color.system_on_surface_dark),
                        context.getColor(android.R.color.system_on_surface_variant_dark),
                        context.getColor(android.R.color.system_primary_dark),
                        context.getColor(android.R.color.system_on_primary_dark),
                        context.getColor(android.R.color.system_primary_container_dark),
                        context.getColor(android.R.color.system_on_primary_container_dark),
                        context.getColor(android.R.color.system_secondary_container_dark),
                        context.getColor(android.R.color.system_on_secondary_container_dark),
                        context.getColor(android.R.color.system_tertiary_dark),
                        context.getColor(android.R.color.system_tertiary_container_dark),
                        context.getColor(android.R.color.system_on_tertiary_container_dark),
                        context.getColor(android.R.color.system_outline_dark))
                        : new Colors(
                        context.getColor(android.R.color.system_surface_light),
                        context.getColor(android.R.color.system_surface_container_light),
                        context.getColor(android.R.color.system_surface_container_high_light),
                        context.getColor(android.R.color.system_on_surface_light),
                        context.getColor(android.R.color.system_on_surface_variant_light),
                        context.getColor(android.R.color.system_primary_light),
                        context.getColor(android.R.color.system_on_primary_light),
                        context.getColor(android.R.color.system_primary_container_light),
                        context.getColor(android.R.color.system_on_primary_container_light),
                        context.getColor(android.R.color.system_secondary_container_light),
                        context.getColor(android.R.color.system_on_secondary_container_light),
                        context.getColor(android.R.color.system_tertiary_light),
                        context.getColor(android.R.color.system_tertiary_container_light),
                        context.getColor(android.R.color.system_on_tertiary_container_light),
                        context.getColor(android.R.color.system_outline_light));
            }
            return night ? new Colors(
                    context.getColor(android.R.color.system_neutral1_900),
                    context.getColor(android.R.color.system_neutral1_800),
                    context.getColor(android.R.color.system_neutral1_700),
                    context.getColor(android.R.color.system_neutral1_100),
                    context.getColor(android.R.color.system_neutral2_200),
                    context.getColor(android.R.color.system_accent1_200),
                    context.getColor(android.R.color.system_accent1_800),
                    context.getColor(android.R.color.system_accent1_700),
                    context.getColor(android.R.color.system_accent1_100),
                    context.getColor(android.R.color.system_accent2_700),
                    context.getColor(android.R.color.system_accent2_100),
                    context.getColor(android.R.color.system_accent3_200),
                    context.getColor(android.R.color.system_accent3_700),
                    context.getColor(android.R.color.system_accent3_100),
                    context.getColor(android.R.color.system_neutral2_400))
                    : new Colors(
                    context.getColor(android.R.color.system_neutral1_10),
                    context.getColor(android.R.color.system_neutral1_50),
                    context.getColor(android.R.color.system_neutral1_100),
                    context.getColor(android.R.color.system_neutral1_900),
                    context.getColor(android.R.color.system_neutral2_700),
                    context.getColor(android.R.color.system_accent1_600),
                    context.getColor(android.R.color.system_accent1_0),
                    context.getColor(android.R.color.system_accent1_100),
                    context.getColor(android.R.color.system_accent1_900),
                    context.getColor(android.R.color.system_accent2_100),
                    context.getColor(android.R.color.system_accent2_900),
                    context.getColor(android.R.color.system_accent3_600),
                    context.getColor(android.R.color.system_accent3_100),
                    context.getColor(android.R.color.system_accent3_900),
                    context.getColor(android.R.color.system_neutral2_500));
        }
    }

    record Colors(int surface, int container, int containerHigh, int text, int muted, int primary,
            int onPrimary, int primaryContainer, int onPrimaryContainer, int secondaryContainer,
            int onSecondaryContainer, int tertiary, int tertiaryContainer, int onTertiaryContainer, int outline) {}

    Scheme scheme() {
        return read(PALETTE, Scheme.WALLPAPER);
    }

    void setScheme(Scheme scheme) {
        settings.edit().putString(PALETTE, scheme.name()).apply();
    }

    Display display() {
        return read(DISPLAY, Display.SYSTEM);
    }

    void setDisplay(Display display) {
        settings.edit().putString(DISPLAY, display.name()).apply();
    }

    int refreshSeconds() {
        int stored = settings.getInt(REFRESH, DEFAULT_REFRESH_SECONDS);
        for (int seconds : REFRESH_SECONDS) {
            if (seconds == stored) {
                return stored;
            }
        }
        return DEFAULT_REFRESH_SECONDS;
    }

    void setRefreshSeconds(int seconds) {
        settings.edit().putInt(REFRESH, seconds).apply();
    }

    boolean isDark(Configuration config) {
        return switch (display()) {
            case LIGHT -> false;
            case DARK -> true;
            case SYSTEM -> (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        };
    }

    private <T extends Enum<T>> T read(String key, T fallback) {
        String stored = settings.getString(key, fallback.name());
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), stored);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
