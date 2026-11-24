package io.github.justdreemurr.encore;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import io.github.justdreemurr.encore.LinkMonitor.Access;
import io.github.justdreemurr.encore.LinkMonitor.Ear;
import io.github.justdreemurr.encore.LinkMonitor.Route;
import io.github.justdreemurr.encore.LinkMonitor.Scene;
import io.github.justdreemurr.encore.LinkMonitor.Snapshot;
import io.github.justdreemurr.encore.LinkMonitor.WriteResult;
import io.github.justdreemurr.encore.ManagerAppearance.Colors;
import io.github.justdreemurr.encore.ManagerAppearance.Display;
import io.github.justdreemurr.encore.ManagerAppearance.Scheme;
import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

public final class ManagerActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String TAG = "Encore";
    private static final long SNACKBAR_MS = 2800;
    private static final long RECONNECT_POLL_MS = 1000;
    private static final int GROUP_GAP = 2;
    private static final int GROUP_OUTER_RADIUS = 24;
    private static final int GROUP_INNER_RADIUS = 6;
    private static final int BUTTON_GROUP_HEIGHT = 44;
    private static final int BUTTON_GROUP_INNER_RADIUS = 8;
    private static final long RECONNECT_TIMEOUT_MS = 30000;

    private static final int REQUEST_EXPORT = 1;

    private enum Page { MAIN, SETTINGS, ENHANCE, DIAGNOSTICS }

    private record Metric(int label, String value) {}

    private record EarRow(TextView detail, View bar) {}

    private record ActionButton(LinearLayout root, ImageView icon, TextView label) {}

    private static final class AudioViews {
        GradientDrawable heroShape;
        GradientDrawable medallionShape;
        GradientDrawable dotShape;
        View liveDot;
        TextView liveLabel;
        TextView device;
        TextView title;
        TextView detail;
        ImageView glyph;
        View medallion;
        int heroBackground;
        int heroForeground;
        String heroKey;
        ValueAnimator heroAnimator;
        ObjectAnimator pulse;
        LinearLayout grid;
        LinearLayout ears;
        TextView note;
        TextView retry;
        List<Integer> metricKeys = new ArrayList<>();
        final List<TextView> metricValues = new ArrayList<>();
        List<String> earKeys = new ArrayList<>();
        final List<EarRow> earRows = new ArrayList<>();
        Switch route;
        Switch game;
        TextView gameDescription;
        TextView moduleStatus;
        ActionButton restart;
        ObjectAnimator spin;
        ActionButton reconnect;
        ObjectAnimator reconnectSpin;
        LinearLayout banner;
        TextView bannerDetail;
        String bannerKey;
    }

    private static final class EnhanceViews {
        Switch switchByContent;
        TextView status;
        Switch lockGame;
        TextView lockGameDescription;
    }

    private static final class DiagnosticViews {
        TextView summary;
        LinearLayout results;
        ActionButton rerun;
        ActionButton export;
    }

    private record LsposedStatus(Diagnostics.Lsposed state, int pid) {}

    private ManagerAppearance appearance;
    private Colors colors;
    private Page page = Page.MAIN;
    private final int[] scrollPositions = new int[Page.values().length];
    private FrameLayout host;
    private LinearLayout shell;
    private LinearLayout topBar;
    private FrameLayout pageFrame;
    private ScrollView pageView;
    private TextView topTitle;
    private TextView topSubtitle;
    private ImageView backButton;
    private ImageView settingsButton;
    private int bottomInset;
    private boolean backRegistered;
    private final OnBackInvokedCallback backCallback = this::navigateBack;
    private TextView snackbar;
    private final Runnable hideSnackbar = this::hideSnackbar;
    private AudioViews audio;
    private EnhanceViews enhance;
    private DiagnosticViews diagnosticViews;
    private Diagnostics diagnostics;
    private Diagnostics.Result diagnosticResult;
    private boolean diagnosing;
    private boolean exporting;
    private boolean applyingFix;
    private boolean topBarRaised;
    private int topBarColor;
    private Scheme poppedScheme;

    private SharedPreferences routePreferences;
    private boolean routeEnabled = RouteSettings.DEFAULT_ROUTE_ENABLED;
    private boolean gameEnabled;
    private String sceneLock = RouteSettings.SCENE_FOLLOW;
    private boolean writingSwitchByContent;
    private Boolean pendingSwitchByContent;
    private boolean renderingSwitches;
    private LinkMonitor monitor;
    private Snapshot snapshot = Snapshot.PENDING;
    private boolean restarting;
    private long reconnectRequest;
    private String reconnectPhase;
    private final Runnable reconnectTimeout = () -> finishReconnect(R.string.reconnect_no_response);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appearance = new ManagerAppearance(this);
        diagnostics = new Diagnostics(this);
        if (savedInstanceState != null) {
            page = Page.values()[savedInstanceState.getInt("page")];
            int[] stored = savedInstanceState.getIntArray("scroll");
            if (stored != null && stored.length == scrollPositions.length) {
                System.arraycopy(stored, 0, scrollPositions, 0, stored.length);
            }
        }
        getWindow().setDecorFitsSystemWindows(false);
        host = new FrameLayout(this);
        setContentView(host);
        buildShell();
        if (savedInstanceState == null) {
            staggerIn(contentOf(pageView));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        scrollPositions[page.ordinal()] = pageView.getScrollY();
        state.putInt("page", page.ordinal());
        state.putIntArray("scroll", scrollPositions);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStart() {
        super.onStart();
        restarting = false;
        app().observe(this::refreshRoute);
        refreshRoute();
        monitor = new LinkMonitor(pollInterval(), this::onSnapshot);
        renderRestart();
        renderReconnect();
        diagnosing = false;
        exporting = false;
        applyingFix = false;
        if (page == Page.DIAGNOSTICS) {
            runDiagnostics();
        }
    }

    @Override
    protected void onStop() {
        app().observe(null);
        monitor.close();
        monitor = null;
        stopAudioAnimations();
        super.onStop();
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        if (appearance == null) {
            return;
        }
        writer.println(prefix + "Manager: page=" + page + " display=" + appearance.display()
                + " dark=" + isDark() + " palette=" + appearance.scheme()
                + " surface=" + Integer.toHexString(colors.surface()) + " motion=" + Motion.enabled()
                + " refresh=" + appearance.refreshSeconds() + "s"
                + " routeWritable=" + (routePreferences != null) + " route=" + routeEnabled
                + " gameSaved=" + gameEnabled + " gameEditable=" + (audio != null && audio.game.isEnabled())
                + " restarting=" + restarting + " reconnect=" + reconnectRequest + "/" + reconnectPhase);
        writer.println(prefix + "Live: " + snapshot.summary());
        writer.println(prefix + "Banner: " + (audio == null ? null
                : (audio.banner.getVisibility() == View.VISIBLE ? "shown " : "hidden ") + audio.bannerKey));
        writer.println(prefix + "Enhance: sceneLock=" + sceneLock + " writing=" + writingSwitchByContent
                + " pending=" + pendingSwitchByContent + (enhance == null ? "" : " shown=["
                + enhance.switchByContent.isChecked() + "/" + enhance.lockGame.isChecked() + "] status="
                + enhance.status.getText().toString().replace('\n', '|')));
        StringBuilder checks = new StringBuilder();
        if (diagnosticResult != null) {
            for (Diagnostics.Check check : diagnosticResult.checks()) {
                checks.append(" [").append(check.level()).append(' ').append(check.title()).append(": ")
                        .append(check.detail()).append(']');
            }
        }
        writer.println(prefix + "Diagnostics: running=" + diagnosing + " exporting=" + exporting
                + " time=" + (diagnosticResult == null ? null : diagnosticResult.time()) + checks);
        if (audio != null) {
            StringBuilder metrics = new StringBuilder();
            for (int index = 0; index < audio.metricKeys.size(); index++) {
                metrics.append(' ').append(getString(audio.metricKeys.get(index))).append('=')
                        .append(audio.metricValues.get(index).getText());
            }
            writer.println(prefix + "Shown: " + audio.title.getText() + " | " + audio.device.getText() + " | "
                    + audio.detail.getText() + " | metrics:" + metrics + " | module=" + audio.moduleStatus.getText());
        }
    }

    private RouteApplication app() {
        return (RouteApplication) getApplication();
    }

    private boolean isDark() {
        return appearance.isDark(getResources().getConfiguration());
    }

    private void buildShell() {
        boolean dark = isDark();
        colors = appearance.scheme().colors(this, dark);
        host.setBackgroundColor(colors.surface());
        topBarRaised = false;

        LinearLayout nextShell = column();
        nextShell.setBackgroundColor(colors.surface());
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        topBarColor = colors.surface();
        bar.setBackgroundColor(topBarColor);
        backButton = iconButton(R.id.navigate_back, R.drawable.ic_back, R.string.navigate_back, this::navigateBack);
        bar.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = column();
        topTitle = label(0, 24, colors.text(), true);
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        topTitle.setAccessibilityHeading(true);
        titles.addView(topTitle);
        topSubtitle = label(0, 13, colors.muted(), false);
        topSubtitle.setSingleLine(true);
        topSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(topSubtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1));
        settingsButton = iconButton(R.id.open_settings, R.drawable.ic_settings, R.string.settings_title,
                () -> switchPage(Page.SETTINGS));
        bar.addView(settingsButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        FrameLayout frame = new FrameLayout(this);
        nextShell.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        nextShell.addView(frame, new LinearLayout.LayoutParams(MATCH, 0, 1));
        nextShell.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, 0, bars.right, 0);
            bar.setPadding(dp(8), bars.top + dp(10), dp(8), dp(10));
            bottomInset = bars.bottom;
            pageView.setPadding(0, 0, 0, bottomInset);
            return insets;
        });
        shell = nextShell;
        topBar = bar;
        pageFrame = frame;
        host.addView(shell, 0, new FrameLayout.LayoutParams(MATCH, MATCH));

        audio = null;
        enhance = null;
        diagnosticViews = null;
        pageView = buildPage(page);
        pageFrame.addView(pageView);
        renderTopBar(false);
        raiseTopBar(scrollPositions[page.ordinal()] > 0);
        snackbar = buildSnackbar();
        host.addView(snackbar);
        updateSystemBars(dark);
        shell.requestApplyInsets();
    }

    private void rebuildShell() {
        stopAudioAnimations();
        snackbar.removeCallbacks(hideSnackbar);
        host.removeView(shell);
        host.removeView(snackbar);
        buildShell();
    }

    private void updateSystemBars(boolean dark) {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark ? 0 : bars, bars);
        }
    }

    private void raiseTopBar(boolean raised) {
        if (raised == topBarRaised) {
            return;
        }
        topBarRaised = raised;
        int target = raised ? colors.container() : colors.surface();
        animateArgb(topBarColor, target, Motion.MEDIUM, color -> {
            topBarColor = color;
            topBar.setBackgroundColor(color);
        });
    }

    private ImageView iconButton(int id, int iconResource, int description, Runnable action) {
        ImageView button = icon(iconResource, colors.muted());
        button.setId(id);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(getString(description));
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        makeClickable(button, null, 24, action);
        return button;
    }

    private void renderTopBar(boolean animate) {
        boolean nested = page != Page.MAIN;
        int title = switch (page) {
            case MAIN -> R.string.app_name;
            case SETTINGS -> R.string.settings_title;
            case ENHANCE -> R.string.enhance_title;
            case DIAGNOSTICS -> R.string.diag_title;
        };
        int subtitle = switch (page) {
            case MAIN -> R.string.app_subtitle;
            case SETTINGS -> R.string.settings_subtitle;
            case ENHANCE -> R.string.enhance_subtitle;
            case DIAGNOSTICS -> R.string.diag_subtitle;
        };
        swapText(topTitle, getString(title), animate);
        swapText(topSubtitle, getString(subtitle), animate);
        topTitle.setPaddingRelative(nested ? dp(4) : dp(16), 0, 0, 0);
        topSubtitle.setPaddingRelative(nested ? dp(4) : dp(16), 0, 0, 0);
        toggleIconButton(backButton, nested, animate, -90f);
        toggleIconButton(settingsButton, !nested, animate, 90f);
        setBackCallback(nested);
    }

    private void toggleIconButton(View button, boolean visible, boolean animate, float rotation) {
        button.animate().cancel();
        if (!animate || !Motion.enabled()) {
            button.setVisibility(visible ? View.VISIBLE : View.GONE);
            button.setAlpha(1f);
            button.setScaleX(1f);
            button.setScaleY(1f);
            button.setRotation(0f);
            return;
        }
        if (visible) {
            button.setVisibility(View.VISIBLE);
            button.setAlpha(0f);
            button.setScaleX(0.6f);
            button.setScaleY(0.6f);
            button.setRotation(rotation);
            button.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        } else {
            button.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).rotation(rotation).setDuration(Motion.SHORT)
                    .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                    .withEndAction(() -> button.setVisibility(View.GONE)).start();
        }
    }

    private void setBackCallback(boolean enabled) {
        if (Build.VERSION.SDK_INT < 33 || enabled == backRegistered) {
            return;
        }
        if (enabled) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        backRegistered = enabled;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (page != Page.MAIN) {
            navigateBack();
        } else {
            super.onBackPressed();
        }
    }

    private void navigateBack() {
        switchPage(Page.MAIN);
    }

    private ScrollView buildPage(Page target) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, 0, 0, bottomInset);
        FrameLayout centered = new FrameLayout(this);
        LinearLayout content = column();
        content.setPadding(dp(16), dp(4), dp(16), dp(28));
        int width = getResources().getConfiguration().screenWidthDp > 640 ? dp(600) : MATCH;
        centered.addView(content, new FrameLayout.LayoutParams(width, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(centered);
        if (target == Page.MAIN) {
            buildAudio(content);
            renderRoute(false);
            renderSnapshot(false);
            renderModule();
            renderRestart();
        } else if (target == Page.SETTINGS) {
            buildSettings(content);
        } else if (target == Page.ENHANCE) {
            buildEnhance(content);
        } else {
            buildDiagnostics(content);
        }
        animateLayout(content);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (view == pageView) {
                raiseTopBar(y > 0);
            }
        });
        int position = scrollPositions[target.ordinal()];
        if (position > 0) {
            scroll.post(() -> scroll.scrollTo(0, position));
        }
        return scroll;
    }

    private static ViewGroup contentOf(ScrollView scroll) {
        return (ViewGroup) ((ViewGroup) scroll.getChildAt(0)).getChildAt(0);
    }

    private void switchPage(Page target) {
        if (target == page) {
            return;
        }
        scrollPositions[page.ordinal()] = pageView.getScrollY();
        scrollPositions[target.ordinal()] = 0;
        stopAudioAnimations();
        audio = null;
        enhance = null;
        diagnosticViews = null;
        int direction = target.ordinal() > page.ordinal() ? 1 : -1;
        if (getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            direction = -direction;
        }
        page = target;
        renderTopBar(true);
        ScrollView previous = pageView;
        pageView = buildPage(target);
        pageFrame.addView(pageView);
        raiseTopBar(false);
        if (!Motion.enabled()) {
            pageFrame.removeView(previous);
            return;
        }
        float shift = dp(30) * direction;
        previous.setOnScrollChangeListener(null);
        previous.animate().alpha(0f).translationX(-shift).setDuration(Motion.SHORT)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> pageFrame.removeView(previous)).start();
        ScrollView next = pageView;
        next.setAlpha(0f);
        next.setTranslationX(shift);
        next.animate().alpha(1f).translationX(0f).setStartDelay(60).setDuration(Motion.MEDIUM)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        staggerIn(contentOf(next));
    }

    private void appearanceChanged(View origin) {
        scrollPositions[page.ordinal()] = pageView.getScrollY();
        if (!Motion.enabled() || host.getWidth() == 0 || host.getHeight() == 0) {
            rebuildShell();
            poppedScheme = null;
            return;
        }
        int[] hostLocation = new int[2];
        int[] originLocation = new int[2];
        host.getLocationInWindow(hostLocation);
        origin.getLocationInWindow(originLocation);
        int centerX = originLocation[0] - hostLocation[0] + origin.getWidth() / 2;
        int centerY = originLocation[1] - hostLocation[1] + origin.getHeight() / 2;
        Bitmap shot = Bitmap.createBitmap(host.getWidth(), host.getHeight(), Bitmap.Config.ARGB_8888);
        host.draw(new Canvas(shot));
        ImageView cover = new ImageView(this);
        cover.setImageBitmap(shot);
        rebuildShell();
        poppedScheme = null;
        host.addView(cover, 0, new FrameLayout.LayoutParams(MATCH, MATCH));
        View revealed = shell;
        float radius = (float) Math.hypot(Math.max(centerX, host.getWidth() - centerX),
                Math.max(centerY, host.getHeight() - centerY));
        revealed.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                revealed.getViewTreeObserver().removeOnPreDrawListener(this);
                Animator reveal = ViewAnimationUtils.createCircularReveal(revealed, centerX, centerY, 0f, radius);
                reveal.setDuration(Motion.LONG);
                reveal.setInterpolator(Motion.EMPHASIZED);
                reveal.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        host.removeView(cover);
                        cover.setImageDrawable(null);
                        shot.recycle();
                    }
                });
                reveal.start();
                return true;
            }
        });
    }

    private void staggerIn(ViewGroup content) {
        if (!Motion.enabled()) {
            return;
        }
        for (int index = 0; index < content.getChildCount(); index++) {
            View child = content.getChildAt(index);
            child.setAlpha(0f);
            child.setTranslationY(dp(28));
            child.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(index, 8) * 45L)
                    .setDuration(Motion.LONG).setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        }
    }

    private void buildAudio(LinearLayout content) {
        AudioViews views = new AudioViews();
        audio = views;

        views.banner = column();
        views.banner.setId(R.id.prerequisite_banner);
        views.banner.setPadding(dp(20), dp(16), dp(20), dp(14));
        views.banner.setBackground(shape(colors.tertiaryContainer(), 24));
        TextView bannerTitle = label(R.string.banner_title, 16, colors.onTertiaryContainer(), true);
        bannerTitle.setAccessibilityHeading(true);
        views.banner.addView(bannerTitle);
        views.bannerDetail = label(0, 14, colors.onTertiaryContainer(), false);
        views.bannerDetail.setPadding(0, dp(4), 0, dp(10));
        views.bannerDetail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        views.banner.addView(views.bannerDetail);
        ActionButton bannerAction = button(View.NO_ID, R.string.banner_action, R.drawable.ic_chevron_end,
                shape(colors.onTertiaryContainer(), 20), colors.tertiaryContainer(),
                () -> switchPage(Page.DIAGNOSTICS));
        views.banner.addView(bannerAction.root(), new LinearLayout.LayoutParams(WRAP, dp(40)));
        views.banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        bannerParams.bottomMargin = dp(16);
        content.addView(views.banner, bannerParams);

        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(22), dp(20), dp(18), dp(22));
        views.heroShape = shape(colors.containerHigh(), 28);
        hero.setBackground(views.heroShape);
        LinearLayout heroText = column();
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        views.liveDot = new View(this);
        views.dotShape = shape(colors.text(), 4);
        views.liveDot.setBackground(views.dotShape);
        chip.addView(views.liveDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        views.liveLabel = label(0, 12, colors.text(), true);
        views.liveLabel.setPaddingRelative(dp(6), 0, 0, 0);
        chip.addView(views.liveLabel);
        heroText.addView(chip);
        views.device = label(0, 14, colors.text(), false);
        views.device.setPadding(0, dp(10), 0, 0);
        views.device.setSingleLine(true);
        views.device.setEllipsize(TextUtils.TruncateAt.END);
        heroText.addView(views.device);
        views.title = label(0, 32, colors.text(), true);
        views.title.setAccessibilityHeading(true);
        views.title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        heroText.addView(views.title);
        views.detail = label(0, 14, colors.text(), false);
        views.detail.setPadding(0, dp(2), 0, 0);
        heroText.addView(views.detail);
        hero.addView(heroText, new LinearLayout.LayoutParams(0, WRAP, 1));
        FrameLayout medallion = new FrameLayout(this);
        views.medallionShape = shape(colors.text(), 36);
        medallion.setBackground(views.medallionShape);
        views.glyph = icon(R.drawable.ic_encore_glyph, colors.containerHigh());
        medallion.addView(views.glyph, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        views.medallion = medallion;
        hero.addView(medallion, new LinearLayout.LayoutParams(dp(72), dp(72)));
        views.heroBackground = colors.containerHigh();
        views.heroForeground = colors.text();
        animateLayout(hero);
        content.addView(hero, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout live = card(content, 16);
        LinearLayout liveHeader = new LinearLayout(this);
        liveHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView liveTitle = label(R.string.live_heading, 16, colors.text(), true);
        liveTitle.setAccessibilityHeading(true);
        liveHeader.addView(liveTitle, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView refresh = label(0, 12, colors.muted(), false);
        refresh.setText(getString(R.string.live_refresh, appearance.refreshSeconds()));
        liveHeader.addView(refresh);
        live.addView(liveHeader, new LinearLayout.LayoutParams(MATCH, WRAP));
        views.note = label(0, 14, colors.muted(), false);
        views.note.setPadding(0, dp(12), 0, dp(4));
        live.addView(views.note);
        views.retry = label(R.string.retry, 14, colors.primary(), true);
        views.retry.setGravity(Gravity.CENTER);
        views.retry.setMinHeight(dp(40));
        views.retry.setPadding(dp(16), 0, dp(16), 0);
        makeClickable(views.retry, null, 20, this::retryLive);
        live.addView(views.retry, new LinearLayout.LayoutParams(WRAP, WRAP));
        views.grid = column();
        views.grid.setPadding(0, dp(14), 0, dp(4));
        live.addView(views.grid, new LinearLayout.LayoutParams(MATCH, WRAP));
        views.ears = column();
        live.addView(views.ears, new LinearLayout.LayoutParams(MATCH, WRAP));
        animateLayout(live);

        addHeading(content, R.string.audio_controls);
        LinearLayout settings = listGroup(content);
        LinearLayout routeItem = listItem(settings);
        views.route = addSwitch(routeItem, R.id.route_switch, R.string.enable_route);
        addDescription(routeItem, R.string.summary_route);
        LinearLayout gameItem = listItem(settings);
        views.game = addSwitch(gameItem, R.id.game_switch, R.string.enable_game);
        views.gameDescription = addDescription(gameItem, R.string.summary_game);
        views.route.setOnCheckedChangeListener((button, checked) -> saveRoute(RouteSettings.ROUTE_ENABLED, checked));
        views.game.setOnCheckedChangeListener((button, checked) -> saveRoute(RouteSettings.GAME_VND, checked));
        LinearLayout reconnectItem = listItem(settings);
        views.reconnect = button(R.id.reconnect_x4, R.string.reconnect_x4, R.drawable.ic_encore_glyph,
                shape(colors.primary(), 20), colors.onPrimary(), this::reconnectX4);
        LinearLayout.LayoutParams reconnectParams = new LinearLayout.LayoutParams(WRAP, dp(40));
        reconnectParams.setMargins(0, dp(4), 0, dp(10));
        reconnectItem.addView(views.reconnect.root(), reconnectParams);
        addDescription(reconnectItem, R.string.summary_reconnect);
        addNavigationEntry(settings, R.id.open_enhance, R.string.enhance_entry_title, R.string.enhance_entry_summary,
                Page.ENHANCE);
        addNavigationEntry(settings, R.id.open_diagnostics, R.string.diag_entry_title, R.string.diag_entry_summary,
                Page.DIAGNOSTICS);
        animateLayout(gameItem);

        addHeading(content, R.string.module_heading);
        LinearLayout module = listGroup(content);
        LinearLayout statusItem = listItem(module);
        views.moduleStatus = label(0, 14, colors.text(), false);
        views.moduleStatus.setPadding(0, dp(4), 0, dp(4));
        views.moduleStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusItem.addView(views.moduleStatus);
        LinearLayout actions = new LinearLayout(this);
        views.restart = button(R.id.restart_scope, R.string.restart_scope, R.drawable.ic_restart,
                shape(colors.secondaryContainer(), 20), colors.onSecondaryContainer(), this::restartScope);
        actions.addView(views.restart.root(), new LinearLayout.LayoutParams(WRAP, dp(40)));
        GradientDrawable outline = shape(0x00000000, 20);
        outline.setStroke(dp(1), colors.outline());
        ActionButton settingsButton = button(R.id.bluetooth_settings, R.string.bluetooth_settings,
                R.drawable.ic_bluetooth, outline, colors.primary(), this::openBluetoothSettings);
        LinearLayout.LayoutParams secondary = new LinearLayout.LayoutParams(WRAP, dp(40));
        secondary.setMarginStart(dp(8));
        actions.addView(settingsButton.root(), secondary);
        listItem(module).addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        animateLayout(statusItem);
    }

    private void buildSettings(LinearLayout content) {
        buildSettingsPage(content);
    }

    private void addNavigationEntry(LinearLayout group, int id, int title, int summaryText, Page target) {
        LinearLayout item = listItem(group);
        item.setId(id);
        item.setPadding(dp(20), dp(14), dp(12), dp(14));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = column();
        titles.addView(label(title, 16, colors.text(), true));
        TextView summary = label(summaryText, 13, colors.muted(), false);
        summary.setPadding(0, dp(2), 0, 0);
        titles.addView(summary);
        row.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1));
        row.addView(icon(R.drawable.ic_chevron_end, colors.muted()), new LinearLayout.LayoutParams(dp(24), dp(24)));
        item.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        item.setContentDescription(getString(title));
        makeClickable(item, (GradientDrawable) item.getBackground(), () -> switchPage(target));
    }

    private void buildDiagnostics(LinearLayout content) {
        DiagnosticViews views = new DiagnosticViews();
        views.summary = label(0, 13, colors.muted(), false);
        views.summary.setPadding(dp(8), dp(8), dp(8), 0);
        views.summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(views.summary);
        views.results = column();
        content.addView(views.results, new LinearLayout.LayoutParams(MATCH, WRAP));

        addHeading(content, R.string.diag_logs_heading);
        LinearLayout logs = listItem(listGroup(content));
        LinearLayout actions = new LinearLayout(this);
        views.export = button(R.id.diag_export, R.string.diag_export, R.drawable.ic_download,
                shape(colors.primary(), 20), colors.onPrimary(), this::exportDiagnostics);
        actions.addView(views.export.root(), new LinearLayout.LayoutParams(WRAP, dp(40)));
        views.rerun = button(R.id.diag_rerun, R.string.diag_rerun, R.drawable.ic_restart,
                shape(colors.secondaryContainer(), 20), colors.onSecondaryContainer(), this::runDiagnostics);
        LinearLayout.LayoutParams secondary = new LinearLayout.LayoutParams(WRAP, dp(40));
        secondary.setMarginStart(dp(8));
        actions.addView(views.rerun.root(), secondary);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        actionParams.setMargins(0, dp(4), 0, dp(10));
        logs.addView(actions, actionParams);
        addDescription(logs, R.string.diag_export_summary);
        diagnosticViews = views;
        renderDiagnostics(false);
        runDiagnostics();
    }

    private void runDiagnostics() {
        if (monitor == null || diagnosing || exporting || applyingFix || restarting) {
            return;
        }
        diagnosing = true;
        renderDiagnostics(true);
        Diagnostics.Environment environment = diagnosticEnvironment();
        monitor.collect(Diagnostics.CHECK_SCRIPT, output -> {
            diagnosing = false;
            diagnosticResult = diagnostics.evaluate(output, environment);
            renderDiagnostics(true);
        });
    }

    private void exportDiagnostics() {
        if (diagnosing || exporting || applyingFix || restarting) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, Diagnostics.fileName());
        try {
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (ActivityNotFoundException error) {
            showSnackbar(R.string.diag_export_failed);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            writeDiagnostics(data.getData());
        }
    }

    private void writeDiagnostics(Uri target) {
        if (monitor == null || exporting) {
            showSnackbar(R.string.diag_export_failed);
            return;
        }
        exporting = true;
        renderDiagnostics(true);
        Diagnostics.Environment environment = diagnosticEnvironment();
        Snapshot live = snapshot;
        monitor.collect(Diagnostics.EXPORT_SCRIPT, output -> {
            Diagnostics.Result result = diagnostics.evaluate(output, environment);
            diagnosticResult = result;
            renderDiagnostics(true);
            new Thread(() -> {
                boolean written;
                try (OutputStream stream = getContentResolver().openOutputStream(target, "w")) {
                    if (stream == null) {
                        throw new IOException("No output stream for " + target);
                    }
                    diagnostics.write(stream, result, environment, live);
                    written = true;
                } catch (IOException | RuntimeException error) {
                    Log.w(TAG, "Could not export diagnostics", error);
                    written = false;
                }
                int message = !written ? R.string.diag_export_failed
                        : output.access() == Access.GRANTED ? R.string.diag_exported : R.string.diag_export_root;
                runOnUiThread(() -> {
                    exporting = false;
                    showSnackbar(message);
                    renderDiagnostics(true);
                });
            }, "encore-export").start();
        });
    }

    private void renderDiagnostics(boolean animate) {
        DiagnosticViews views = diagnosticViews;
        if (views == null) {
            return;
        }
        String summary;
        if (diagnosing || diagnosticResult == null) {
            summary = getString(R.string.diag_checking);
        } else {
            int ok = 0;
            int attention = 0;
            for (Diagnostics.Check check : diagnosticResult.checks()) {
                if (check.level() == Diagnostics.Level.OK) {
                    ok++;
                } else if (check.level() != Diagnostics.Level.INFO) {
                    attention++;
                }
            }
            summary = getString(R.string.diag_checked, diagnosticResult.time(), ok, attention);
        }
        swapText(views.summary, summary, animate);
        boolean busy = diagnosing || exporting || applyingFix || restarting;
        for (ActionButton button : List.of(views.rerun, views.export)) {
            button.root().setEnabled(!busy);
            button.root().animate().alpha(busy ? 0.72f : 1f).setDuration(Motion.SHORT).start();
        }
        swapText(views.export.label(), getString(exporting ? R.string.diag_exporting : R.string.diag_export), animate);

        views.results.removeAllViews();
        if (diagnosticResult == null) {
            return;
        }
        Diagnostics.Group shown = null;
        LinearLayout group = null;
        for (Diagnostics.Check check : diagnosticResult.checks()) {
            if (check.group() != shown) {
                shown = check.group();
                addHeading(views.results, switch (shown) {
                    case ENVIRONMENT -> R.string.diag_group_environment;
                    case PREREQUISITES -> R.string.diag_group_prerequisites;
                    case CONNECTION -> R.string.diag_group_connection;
                    case CONFLICTS -> R.string.diag_group_conflicts;
                });
                group = listGroup(views.results);
            }
            addCheckItem(group, check);
        }
    }

    private void addCheckItem(LinearLayout group, Diagnostics.Check check) {
        LinearLayout item = listItem(group);
        item.setPadding(dp(20), dp(14), dp(20), dp(14));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(levelColor(check.level()));
        dot.setBackground(dotShape);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.setMarginEnd(dp(16));
        row.addView(dot, dotParams);
        LinearLayout texts = column();
        texts.addView(label(0, 15, colors.text(), true));
        ((TextView) texts.getChildAt(0)).setText(check.title());
        TextView detail = label(0, 13, colors.muted(), false);
        detail.setText(check.detail());
        detail.setPadding(0, dp(2), 0, 0);
        texts.addView(detail);
        if (check.hint() != null && check.level() != Diagnostics.Level.OK) {
            TextView hint = label(0, 13, colors.text(), false);
            hint.setText(check.hint());
            hint.setPadding(0, dp(6), 0, 0);
            texts.addView(hint);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1));
        item.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        if (check.fix() != null && check.level() != Diagnostics.Level.OK) {
            Prerequisites.Fix fix = check.fix();
            ActionButton action = button(R.id.fix_action, Prerequisites.fixLabel(fix), R.drawable.ic_check,
                    shape(colors.secondaryContainer(), 20), colors.onSecondaryContainer(), () -> applyFix(fix));
            action.root().setEnabled(!diagnosing && !exporting && !applyingFix && !restarting);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(WRAP, dp(40));
            actionParams.setMarginStart(dp(26));
            actionParams.topMargin = dp(10);
            item.addView(action.root(), actionParams);
        }
        item.setContentDescription(getString(switch (check.level()) {
            case OK -> R.string.diag_level_ok;
            case INFO -> R.string.diag_level_info;
            case WARN -> R.string.diag_level_warn;
            case FAIL -> R.string.diag_level_fail;
        }) + "，" + check.title() + "，" + check.detail() + (check.hint() == null ? "" : "，" + check.hint()));
        item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (int index = 0; index < row.getChildCount(); index++) {
            row.getChildAt(index).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    /** Shows the unmet LE Audio conditions at the top of the main page; hidden when all are met or unknown. */
    private void renderBanner() {
        List<String> titles = new ArrayList<>();
        for (Diagnostics.Check check : Prerequisites.unmet(Prerequisites.evaluate(this, snapshot.requirements()))) {
            titles.add(check.title());
        }
        String key = String.join("、", titles);
        if (key.equals(audio.bannerKey)) {
            return;
        }
        audio.bannerKey = key;
        if (titles.isEmpty()) {
            audio.banner.setVisibility(View.GONE);
            return;
        }
        audio.bannerDetail.setText(getString(R.string.banner_detail, titles.size(), key));
        audio.banner.setVisibility(View.VISIBLE);
    }

    private void applyFix(Prerequisites.Fix fix) {
        if (diagnosing || exporting || applyingFix || restarting) {
            return;
        }
        switch (fix) {
            case ENABLE_DUAL_MODE -> runRootFix("setprop " + RouteSettings.DUAL_MODE_PROPERTY + " true && "
                    + "getprop " + RouteSettings.DUAL_MODE_PROPERTY, "true", R.string.fix_dual_mode_done, true);
            case ENABLE_PREFERENCE -> {
                String key = diagnosticResult.requirements().module().get("prefKey");
                if (!Prerequisites.validPreferenceKey(key)) {
                    showSnackbar(R.string.fix_failed);
                    return;
                }
                runRootFix("if dumpsys bluetooth_manager | grep '^  Encore prerequisites: state=ok .* prefKey="
                        + key + " ' >/dev/null; then\n"
                        + "content call --uri " + RouteSettings.PREFERENCE_URI + " --method PUT_global --arg " + key
                        + " --extra value:s:1 >/dev/null &&\n"
                        + "content call --uri " + RouteSettings.PREFERENCE_URI + " --method GET_global --arg " + key
                        + "\nfi",
                        "Result: Bundle[{value=1}]", R.string.fix_preference_done, false);
            }
            case POWER_SAVER -> openSettings(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
            case INSTALL_APP -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=" + RouteSettings.WHITELIST_APP)));
                } catch (ActivityNotFoundException error) {
                    openSettings(new Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://play.google.com/store/apps/details?id=" + RouteSettings.WHITELIST_APP)));
                }
            }
            case BLUETOOTH_SETTINGS -> openBluetoothSettings();
            case RECONNECT -> reconnectX4();
            case RESTART_SCOPE -> restartScope();
        }
    }

    /** Runs a root write, checks its exact readback, then refreshes the diagnosis. */
    private void runRootFix(String script, String expected, int done, boolean restartAfter) {
        if (monitor == null) {
            showSnackbar(R.string.fix_failed);
            return;
        }
        applyingFix = true;
        renderDiagnostics(true);
        monitor.collect(script, output -> {
            applyingFix = false;
            if (output.access() == Access.DENIED) {
                showSnackbar(R.string.fix_root);
                renderDiagnostics(true);
                return;
            }
            if (output.access() != Access.GRANTED || !output.lines().contains(expected)) {
                showSnackbar(R.string.fix_failed);
                runDiagnostics();
                return;
            }
            if (restartAfter) {
                restartScope();
                renderDiagnostics(true);
            } else {
                showSnackbar(done);
                applyingFix = true;
                LinkMonitor currentMonitor = monitor;
                host.postDelayed(() -> {
                    if (monitor == currentMonitor) {
                        applyingFix = false;
                        runDiagnostics();
                    }
                }, 1500);
            }
        });
    }

    private boolean openSettings(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException error) {
            showSnackbar(R.string.fix_unavailable);
            return false;
        }
    }

    private int levelColor(Diagnostics.Level level) {
        return switch (level) {
            case OK -> colors.primary();
            case INFO -> colors.outline();
            case WARN -> colors.tertiary();
            case FAIL -> isDark() ? 0xFFFFB4AB : 0xFFBA1A1A;
        };
    }

    private Diagnostics.Environment diagnosticEnvironment() {
        LsposedStatus lsposed = lsposedStatus();
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            version = getString(R.string.value_missing);
        }
        return new Diagnostics.Environment(version, lsposed.state(), lsposed.pid(), routeEnabled, gameEnabled,
                sceneLock);
    }

    private LsposedStatus lsposedStatus() {
        XposedService service = app().service();
        if (service == null) {
            return new LsposedStatus(Diagnostics.Lsposed.NO_SERVICE, 0);
        }
        try {
            if (!service.getScope().contains(RouteSettings.BLUETOOTH_PACKAGE)) {
                return new LsposedStatus(Diagnostics.Lsposed.NO_SCOPE, 0);
            }
            for (HookedTarget target : service.getRunningTargets()) {
                if (RouteSettings.BLUETOOTH_PACKAGE.equals(target.getProcessName())) {
                    Diagnostics.Lsposed state = switch (target.getState()) {
                        case UP_TO_DATE -> Diagnostics.Lsposed.UP_TO_DATE;
                        case STALE -> Diagnostics.Lsposed.STALE;
                        case RELOADING -> Diagnostics.Lsposed.RELOADING;
                        case FAILED -> Diagnostics.Lsposed.FAILED;
                    };
                    return new LsposedStatus(state, target.getPid());
                }
            }
            return new LsposedStatus(Diagnostics.Lsposed.NOT_LOADED, 0);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not read LSPosed state", error);
            return new LsposedStatus(Diagnostics.Lsposed.ERROR, 0);
        }
    }

    private void buildEnhance(LinearLayout content) {
        EnhanceViews views = new EnhanceViews();
        addHeading(content, R.string.scene_switch_heading);
        LinearLayout switching = listGroup(content);
        LinearLayout switchItem = listItem(switching);
        views.switchByContent = addSwitch(switchItem, R.id.scene_switch_by_content, R.string.scene_switch_by_content);
        addDescription(switchItem, R.string.summary_scene_switch_by_content);
        LinearLayout statusItem = listItem(switching);
        views.status = label(0, 14, colors.text(), false);
        views.status.setPadding(0, dp(4), 0, dp(4));
        views.status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusItem.addView(views.status);
        views.switchByContent.setOnCheckedChangeListener((button, checked) -> saveSwitchByContent(checked));

        addHeading(content, R.string.scene_lock_heading);
        LinearLayout locks = listGroup(content);
        LinearLayout gameItem = listItem(locks);
        views.lockGame = addSwitch(gameItem, R.id.scene_lock_game, R.string.scene_lock_game);
        views.lockGameDescription = addDescription(gameItem, R.string.summary_scene_lock_game);
        views.lockGame.setOnCheckedChangeListener((button, checked) -> saveSceneLock(checked));
        enhance = views;
        renderEnhance(false);
    }

    private void renderEnhance(boolean animate) {
        if (enhance == null) {
            return;
        }
        Scene scene = snapshot.scene();
        boolean known = scene.coexistProperty() != null;
        renderingSwitches = true;
        enhance.switchByContent.setEnabled(known && !writingSwitchByContent);
        enhance.switchByContent.setChecked(pendingSwitchByContent != null
                ? pendingSwitchByContent : scene.switchesByContent());
        enhance.lockGame.setEnabled(routePreferences != null);
        enhance.lockGame.setChecked(RouteSettings.SCENE_GAME.equals(sceneLock));
        if (!animate || !Motion.enabled()) {
            enhance.switchByContent.jumpDrawablesToCurrentState();
            enhance.lockGame.jumpDrawablesToCurrentState();
        }
        renderingSwitches = false;
        swapText(enhance.status, sceneStatus(scene), animate);
        int lockDescription = known && !scene.switchesByContent()
                ? R.string.summary_scene_lock_game_default : R.string.summary_scene_lock_game;
        swapText(enhance.lockGameDescription, getString(lockDescription), animate);
    }

    private String sceneStatus(Scene scene) {
        if (snapshot.access() == Access.DENIED) {
            return getString(R.string.scene_status_denied);
        }
        if (scene.coexistProperty() == null) {
            return getString(R.string.scene_status_waiting);
        }
        String property = scene.coexistProperty().isEmpty()
                ? getString(R.string.scene_property_unset) : scene.coexistProperty();
        String scenario = snapshot.route() == Route.LE_AUDIO && snapshot.scenario() != null
                ? snapshot.scenario() : getString(R.string.value_missing);
        return getString(R.string.scene_status, property, scenario,
                getString(scene.inGame() ? R.string.scene_in_game_on : R.string.scene_in_game_off));
    }

    private void saveSwitchByContent(boolean enabled) {
        if (renderingSwitches || monitor == null || writingSwitchByContent) {
            return;
        }
        writingSwitchByContent = true;
        pendingSwitchByContent = enabled;
        renderEnhance(true);
        monitor.setSwitchByContent(enabled, result -> {
            writingSwitchByContent = false;
            if (result != WriteResult.SAVED) {
                pendingSwitchByContent = null;
            }
            showSnackbar(switch (result) {
                case SAVED -> R.string.saved_reconnect;
                case DENIED -> R.string.scene_write_root;
                case FAILED -> R.string.save_error;
            });
            renderEnhance(true);
        });
    }

    private void saveSceneLock(boolean game) {
        if (renderingSwitches || routePreferences == null) {
            return;
        }
        String value = game ? RouteSettings.SCENE_GAME : RouteSettings.SCENE_FOLLOW;
        try {
            if (routePreferences.edit().putString(RouteSettings.SCENE_LOCK, value).commit()) {
                sceneLock = value;
                showSnackbar(R.string.saved_scene_lock);
                renderEnhance(true);
                return;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not save scene lock", error);
        }
        renderEnhance(false);
        showSnackbar(R.string.save_error);
    }

    private void buildSettingsPage(LinearLayout content) {
        boolean dark = isDark();
        addHeading(content, R.string.refresh_heading);
        List<String> intervals = new ArrayList<>();
        int selectedInterval = 0;
        for (int index = 0; index < ManagerAppearance.REFRESH_SECONDS.length; index++) {
            int seconds = ManagerAppearance.REFRESH_SECONDS[index];
            intervals.add(getString(R.string.refresh_option, seconds));
            if (seconds == appearance.refreshSeconds()) {
                selectedInterval = index;
            }
        }
        List<Segment> refresh = segmented(content, R.id.refresh_interval, intervals, selectedInterval);
        for (int index = 0; index < refresh.size(); index++) {
            int choice = index;
            refresh.get(index).view().setOnClickListener(ignored -> {
                int seconds = ManagerAppearance.REFRESH_SECONDS[choice];
                if (seconds != appearance.refreshSeconds()) {
                    appearance.setRefreshSeconds(seconds);
                    renderSegments(refresh, choice, true);
                    if (monitor != null) {
                        monitor.setInterval(pollInterval());
                    }
                }
            });
        }
        TextView refreshSummary = label(R.string.refresh_summary, 13, colors.muted(), false);
        refreshSummary.setPadding(dp(8), dp(8), dp(8), 0);
        content.addView(refreshSummary);

        addHeading(content, R.string.appearance_display);
        List<String> displays = new ArrayList<>();
        for (Display display : Display.values()) {
            displays.add(getString(display.title));
        }
        List<Segment> modes = segmented(content, R.id.display_mode, displays, appearance.display().ordinal());
        for (Display display : Display.values()) {
            View segment = modes.get(display.ordinal()).view();
            segment.setOnClickListener(ignored -> {
                if (appearance.display() != display) {
                    appearance.setDisplay(display);
                    appearanceChanged(segment);
                }
            });
        }

        addHeading(content, R.string.appearance_palette);
        LinearLayout grid = column();
        grid.setId(R.id.palette_grid);
        Scheme[] schemes = Scheme.values();
        int rows = (schemes.length + 2) / 3;
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            for (int column = 0; column < 3; column++) {
                int index = rowIndex * 3 + column;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, WRAP, 1);
                params.setMarginStart(column > 0 ? dp(GROUP_GAP) : 0);
                row.addView(index < schemes.length
                        ? paletteTile(schemes[index], dark, rowIndex, column, rows) : new View(this), params);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(MATCH, WRAP);
            rowParams.topMargin = rowIndex > 0 ? dp(GROUP_GAP) : 0;
            grid.addView(row, rowParams);
        }
        content.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));

        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView footer = label(0, 12, colors.muted(), false);
            footer.setText(getString(R.string.version_label, version));
            footer.setGravity(Gravity.CENTER);
            footer.setPadding(0, dp(24), 0, 0);
            content.addView(footer, new LinearLayout.LayoutParams(MATCH, WRAP));
        } catch (PackageManager.NameNotFoundException error) {
            Log.w(TAG, "Could not load app version", error);
        }
    }

    private record Segment(TextView view, GradientDrawable fill, float[] corners) {}

    private List<Segment> segmented(LinearLayout content, int id, List<String> labels, int selected) {
        LinearLayout row = new LinearLayout(this);
        row.setId(id);
        List<Segment> segments = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            TextView segment = label(0, 14, colors.text(), true);
            segment.setText(labels.get(index));
            segment.setGravity(Gravity.CENTER);
            segment.setCompoundDrawablePadding(dp(6));
            float[] corners = segmentCorners(index, labels.size(), index == selected);
            GradientDrawable fill = cornerShape(colors.containerHigh(), corners);
            makeClickable(segment, fill, null);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(BUTTON_GROUP_HEIGHT), 1);
            params.setMarginStart(index > 0 ? dp(GROUP_GAP) : 0);
            row.addView(segment, params);
            segments.add(new Segment(segment, fill, corners));
        }
        content.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        renderSegments(segments, selected, false);
        return segments;
    }

    private float[] segmentCorners(int index, int count, boolean selected) {
        float full = BUTTON_GROUP_HEIGHT / 2f;
        if (selected) {
            return new float[] {full, full, full, full};
        }
        boolean first = index == 0;
        boolean last = index == count - 1;
        return startEndCorners(first, last, last, first, full, BUTTON_GROUP_INNER_RADIUS);
    }

    private void renderSegments(List<Segment> segments, int selected, boolean animate) {
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            boolean active = index == selected;
            TextView view = segment.view();
            view.setSelected(active);
            int fill = active ? colors.primary() : colors.containerHigh();
            int text = active ? colors.onPrimary() : colors.text();
            float[] target = segmentCorners(index, segments.size(), active);
            Drawable check = null;
            if (active) {
                check = getDrawable(R.drawable.ic_check).mutate();
                check.setTint(text);
                check.setBounds(0, 0, dp(18), dp(18));
            }
            view.setCompoundDrawablesRelative(check, null, null, null);
            if (animate && Motion.enabled()) {
                int fromFill = segment.fill().getColor() != null ? segment.fill().getColor().getDefaultColor() : fill;
                float[] from = segment.corners().clone();
                ArgbEvaluator evaluator = new ArgbEvaluator();
                ValueAnimator morph = ValueAnimator.ofFloat(0f, 1f);
                morph.setDuration(Motion.MEDIUM);
                morph.setInterpolator(Motion.EMPHASIZED);
                morph.addUpdateListener(update -> {
                    float fraction = update.getAnimatedFraction();
                    for (int corner = 0; corner < 4; corner++) {
                        segment.corners()[corner] = from[corner] + (target[corner] - from[corner]) * fraction;
                    }
                    applyCorners(segment.fill(), segment.corners());
                    segment.fill().setColor((int) evaluator.evaluate(fraction, fromFill, fill));
                });
                morph.start();
                animateArgb(view.getCurrentTextColor(), text, Motion.MEDIUM, view::setTextColor);
                if (active) {
                    view.setScaleX(0.94f);
                    view.setScaleY(0.94f);
                    view.animate().scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM)
                            .setInterpolator(new OvershootInterpolator(2f)).start();
                }
            } else {
                System.arraycopy(target, 0, segment.corners(), 0, 4);
                applyCorners(segment.fill(), segment.corners());
                segment.fill().setColor(fill);
                view.setTextColor(text);
            }
        }
    }

    private View paletteTile(Scheme scheme, boolean dark, int row, int column, int rows) {
        boolean selected = appearance.scheme() == scheme;
        Colors sample = scheme.colors(this, dark);
        LinearLayout tile = column();
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(6), dp(16), dp(6), dp(12));
        tile.setSelected(selected);
        tile.setContentDescription(getString(scheme.title));
        float[] corners;
        if (selected) {
            corners = new float[] {GROUP_OUTER_RADIUS, GROUP_OUTER_RADIUS, GROUP_OUTER_RADIUS, GROUP_OUTER_RADIUS};
        } else {
            boolean top = row == 0;
            boolean bottom = row == rows - 1;
            boolean start = column == 0;
            boolean end = column == 2;
            corners = startEndCorners(top && start, top && end, bottom && end, bottom && start,
                    GROUP_OUTER_RADIUS, GROUP_INNER_RADIUS);
        }
        GradientDrawable background = cornerShape(selected ? colors.secondaryContainer() : colors.container(), corners);
        makeClickable(tile, background, () -> {
            if (appearance.scheme() != scheme) {
                appearance.setScheme(scheme);
                poppedScheme = scheme;
                appearanceChanged(tile);
            }
        });
        FrameLayout swatch = new FrameLayout(this);
        View disc = new View(this);
        disc.setBackground(new SwatchDrawable(sample, colors.outline(), dp(1)));
        swatch.addView(disc, new FrameLayout.LayoutParams(MATCH, MATCH));
        if (selected) {
            ImageView badge = icon(R.drawable.ic_check, colors.onPrimary());
            badge.setBackground(shape(colors.primary(), 14));
            badge.setPadding(dp(4), dp(4), dp(4), dp(4));
            swatch.addView(badge, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
            if (scheme == poppedScheme && Motion.enabled()) {
                badge.setScaleX(0f);
                badge.setScaleY(0f);
                badge.animate().scaleX(1f).scaleY(1f).setStartDelay(Motion.MEDIUM).setDuration(Motion.MEDIUM)
                        .setInterpolator(new OvershootInterpolator(2.4f)).start();
            }
        }
        tile.addView(swatch, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView name = label(scheme.title, 13, selected ? colors.onSecondaryContainer() : colors.text(), selected);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(10), 0, 0);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(name, new LinearLayout.LayoutParams(MATCH, WRAP));
        pressScale(tile);
        return tile;
    }

    private void refreshRoute() {
        routePreferences = null;
        XposedService service = app().service();
        if (service != null) {
            try {
                if (service.getScope().contains(RouteSettings.BLUETOOTH_PACKAGE)) {
                    SharedPreferences remote = service.getRemotePreferences(RouteSettings.FILE);
                    routeEnabled = remote.getBoolean(RouteSettings.ROUTE_ENABLED, RouteSettings.DEFAULT_ROUTE_ENABLED);
                    gameEnabled = remote.getBoolean(RouteSettings.GAME_VND, false);
                    sceneLock = remote.getString(RouteSettings.SCENE_LOCK, RouteSettings.SCENE_FOLLOW);
                    routePreferences = remote;
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not read routing settings", error);
                showSnackbar(R.string.read_error);
            }
        }
        renderRoute(false);
        renderEnhance(false);
        renderModule();
    }

    private void saveRoute(String key, boolean checked) {
        if (renderingSwitches || routePreferences == null) {
            return;
        }
        try {
            if (routePreferences.edit().putBoolean(key, checked).commit()) {
                if (RouteSettings.ROUTE_ENABLED.equals(key)) {
                    routeEnabled = checked;
                } else {
                    gameEnabled = checked;
                }
                Log.i(TAG, "Saved " + key + "=" + checked);
                renderRoute(true);
                showSnackbar(R.string.saved_reconnect);
                return;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not save routing setting", error);
        }
        renderRoute(false);
        showSnackbar(R.string.save_error);
    }

    private void renderRoute(boolean animate) {
        if (audio == null) {
            return;
        }
        boolean ready = routePreferences != null;
        renderingSwitches = true;
        audio.route.setEnabled(ready);
        audio.route.setChecked(ready && routeEnabled);
        audio.game.setEnabled(ready && routeEnabled);
        audio.game.setChecked(ready && routeEnabled && gameEnabled);
        if (!animate || !Motion.enabled()) {
            audio.route.jumpDrawablesToCurrentState();
            audio.game.jumpDrawablesToCurrentState();
        }
        renderingSwitches = false;
        int description = R.string.summary_game;
        if (ready && !routeEnabled) {
            description = gameEnabled ? R.string.summary_game_remembered : R.string.summary_game_blocked;
        }
        swapText(audio.gameDescription, getString(description), animate);
        renderReconnect();
    }

    private void onSnapshot(Snapshot next) {
        snapshot = next;
        if (reconnectRequest != 0 && next.reconnect().id() == reconnectRequest
                && !next.reconnect().state().equals(reconnectPhase)) {
            reconnectPhase = next.reconnect().state();
            switch (reconnectPhase) {
                case "DONE" -> finishReconnect(R.string.reconnect_done);
                case "NO_DEVICE" -> finishReconnect(R.string.reconnect_no_device);
                case "TIMEOUT" -> finishReconnect(R.string.reconnect_timeout);
                case "FAILED" -> finishReconnect(R.string.reconnect_failed);
                default -> renderReconnect();
            }
        }
        if (routePreferences == null && app().service() != null) {
            refreshRoute();
        }
        if (!writingSwitchByContent) {
            pendingSwitchByContent = null;
        }
        renderSnapshot(true);
        renderEnhance(true);
        renderModule();
    }

    private void retryLive() {
        if (monitor != null) {
            snapshot = Snapshot.PENDING;
            renderSnapshot(true);
            monitor.retry();
        }
    }

    private void renderSnapshot(boolean animate) {
        if (audio == null) {
            return;
        }
        renderBanner();
        Snapshot current = snapshot;
        boolean granted = current.access() == Access.GRANTED;
        int title;
        int background = colors.containerHigh();
        int foreground = colors.text();
        String device = current.device() != null ? current.device() : getString(R.string.hero_no_device);
        List<String> details = new ArrayList<>();
        if (current.access() == Access.PENDING) {
            title = R.string.hero_title_waiting;
            device = getString(R.string.app_subtitle);
        } else if (current.access() == Access.DENIED) {
            title = R.string.hero_title_denied;
            device = getString(R.string.app_subtitle);
        } else if (current.route() == Route.LE_AUDIO) {
            boolean game = current.gameVnd();
            title = game ? R.string.hero_title_game : R.string.hero_title_le;
            background = game ? colors.tertiaryContainer() : colors.primaryContainer();
            foreground = game ? colors.onTertiaryContainer() : colors.onPrimaryContainer();
        } else if (current.route() == Route.A2DP) {
            title = R.string.hero_title_a2dp;
            background = colors.secondaryContainer();
            foreground = colors.onSecondaryContainer();
        } else {
            title = R.string.hero_title_none;
        }
        if (granted && current.route() != Route.NONE) {
            if (current.codec() != null) {
                details.add(current.codec());
            }
            if (current.sampleRateHz() > 0) {
                details.add(kilohertz(current.sampleRateHz()));
            }
            details.add(getString(current.streaming() ? R.string.hero_playing : R.string.hero_standby));
        }
        swapText(audio.title, getString(title), animate);
        swapText(audio.device, device, animate);
        swapText(audio.detail, TextUtils.join(" · ", details), animate);
        audio.detail.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        audio.liveLabel.setText(!granted ? R.string.live_offline
                : current.streaming() ? R.string.live_on : R.string.live_idle);
        String heroKey = current.access() + "/" + current.route() + "/" + current.gameVnd();
        boolean heroChanged = audio.heroKey != null && !audio.heroKey.equals(heroKey);
        audio.heroKey = heroKey;
        paintHero(background, foreground, animate);
        if (heroChanged && animate && Motion.enabled()) {
            View medallion = audio.medallion;
            medallion.setScaleX(0.7f);
            medallion.setScaleY(0.7f);
            medallion.setRotation(-90f);
            medallion.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(Motion.LONG)
                    .setInterpolator(new OvershootInterpolator(1.6f)).start();
        }
        setPulse(granted && current.streaming());

        int note = 0;
        if (current.access() == Access.PENDING) {
            note = R.string.live_waiting;
        } else if (current.access() == Access.DENIED) {
            note = R.string.live_denied;
        } else if (current.route() == Route.NONE) {
            note = R.string.live_none;
        }
        if (note != 0) {
            audio.note.setText(note);
        }
        audio.note.setVisibility(note != 0 ? View.VISIBLE : View.GONE);
        audio.retry.setVisibility(current.access() == Access.DENIED ? View.VISIBLE : View.GONE);
        renderMetrics(metricsFor(current), animate);
        renderEars(granted && current.route() == Route.LE_AUDIO ? current.ears() : List.of(), animate);
    }

    private List<Metric> metricsFor(Snapshot current) {
        List<Metric> metrics = new ArrayList<>();
        String missing = getString(R.string.value_missing);
        if (current.access() != Access.GRANTED) {
            return metrics;
        }
        if (current.route() == Route.LE_AUDIO) {
            metrics.add(new Metric(R.string.metric_codec, current.codec() != null ? current.codec() : missing));
            metrics.add(new Metric(R.string.metric_frame,
                    current.frameUs() > 0 ? milliseconds(current.frameUs()) : missing));
            metrics.add(new Metric(R.string.metric_bitrate, current.sduOctets() > 0 && current.frameUs() > 0
                    ? current.sduOctets() * 8000 / current.frameUs() + " kbps" : missing));
            metrics.add(new Metric(R.string.metric_transport,
                    current.transportLatencyMs() > 0 ? current.transportLatencyMs() + " ms" : missing));
            metrics.add(new Metric(R.string.metric_retransmissions,
                    current.retransmissions() >= 0 ? String.valueOf(current.retransmissions()) : missing));
            metrics.add(new Metric(R.string.metric_presentation,
                    current.presentationDelayUs() > 0 ? milliseconds(current.presentationDelayUs()) : missing));
        } else if (current.route() == Route.A2DP) {
            metrics.add(new Metric(R.string.metric_codec, current.codec() != null ? current.codec() : missing));
            metrics.add(new Metric(R.string.metric_sample_rate,
                    current.sampleRateHz() > 0 ? kilohertz(current.sampleRateHz()) : missing));
            metrics.add(new Metric(R.string.metric_bits,
                    current.bitsPerSample() > 0 ? current.bitsPerSample() + " bit" : missing));
        }
        return metrics;
    }

    private void renderMetrics(List<Metric> metrics, boolean animate) {
        List<Integer> keys = new ArrayList<>();
        for (Metric metric : metrics) {
            keys.add(metric.label());
        }
        if (!keys.equals(audio.metricKeys)) {
            audio.metricKeys = keys;
            audio.metricValues.clear();
            audio.grid.removeAllViews();
            LinearLayout row = null;
            for (int index = 0; index < metrics.size(); index++) {
                if (index % 3 == 0) {
                    row = new LinearLayout(this);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
                    params.topMargin = index > 0 ? dp(14) : 0;
                    audio.grid.addView(row, params);
                }
                TextView value = addMetric(row, metrics.get(index).label());
                value.setText(metrics.get(index).value());
                audio.metricValues.add(value);
            }
            while (row != null && row.getChildCount() < 3) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
            }
            if (animate && Motion.enabled() && !metrics.isEmpty()) {
                audio.grid.setAlpha(0f);
                audio.grid.setTranslationY(dp(12));
                audio.grid.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM)
                        .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
            }
        } else {
            for (int index = 0; index < metrics.size(); index++) {
                swapText(audio.metricValues.get(index), metrics.get(index).value(), animate);
            }
        }
        audio.grid.setVisibility(metrics.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void renderEars(List<Ear> ears, boolean animate) {
        List<String> keys = new ArrayList<>();
        for (Ear ear : ears) {
            keys.add(ear.side());
        }
        if (!keys.equals(audio.earKeys)) {
            audio.earKeys = keys;
            audio.earRows.clear();
            audio.ears.removeAllViews();
            if (!ears.isEmpty()) {
                addDivider(audio.ears);
            }
            for (Ear ear : ears) {
                audio.earRows.add(addEarRow(ear.side()));
            }
        }
        for (int index = 0; index < ears.size(); index++) {
            Ear ear = ears.get(index);
            EarRow row = audio.earRows.get(index);
            String text = ear.hasReport()
                    ? getString(R.string.ear_detail, ear.rssi(), String.format(Locale.ROOT, "%.1f%%", ear.lossPercent()))
                    : getString(R.string.ear_waiting);
            swapText(row.detail(), text, animate);
            float level = ear.hasReport() ? Math.max(0.04f, Math.min(1f, (ear.rssi() + 90) / 60f)) : 0f;
            row.bar().animate().cancel();
            if (animate && Motion.enabled()) {
                row.bar().animate().scaleX(level).setDuration(Motion.LONG).setInterpolator(Motion.EMPHASIZED).start();
            } else {
                row.bar().setScaleX(level);
            }
        }
        audio.ears.setVisibility(ears.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private EarRow addEarRow(String side) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        int name = switch (side) {
            case "left" -> R.string.ear_left;
            case "right" -> R.string.ear_right;
            default -> R.string.ear_mono;
        };
        row.addView(label(name, 14, colors.text(), true), new LinearLayout.LayoutParams(dp(60), WRAP));
        LinearLayout column = column();
        TextView detail = label(0, 13, colors.muted(), false);
        column.addView(detail);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(shape(colors.secondaryContainer(), 2));
        View bar = new View(this);
        bar.setBackground(shape(colors.primary(), 2));
        bar.setPivotX(0f);
        bar.setScaleX(0f);
        track.addView(bar, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(MATCH, dp(4));
        trackParams.topMargin = dp(6);
        column.addView(track, trackParams);
        row.addView(column, new LinearLayout.LayoutParams(0, WRAP, 1));
        audio.ears.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return new EarRow(detail, bar);
    }

    private void paintHero(int background, int foreground, boolean animate) {
        AudioViews views = audio;
        if (views.heroAnimator != null) {
            views.heroAnimator.cancel();
            views.heroAnimator = null;
        }
        int fromBackground = views.heroBackground;
        int fromForeground = views.heroForeground;
        if (!animate || !Motion.enabled() || (fromBackground == background && fromForeground == foreground)) {
            applyHero(views, background, foreground);
            return;
        }
        ArgbEvaluator evaluator = new ArgbEvaluator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(Motion.LONG);
        animator.setInterpolator(Motion.EMPHASIZED);
        animator.addUpdateListener(update -> {
            float fraction = update.getAnimatedFraction();
            applyHero(views, (int) evaluator.evaluate(fraction, fromBackground, background),
                    (int) evaluator.evaluate(fraction, fromForeground, foreground));
        });
        views.heroAnimator = animator;
        animator.start();
    }

    private static void applyHero(AudioViews views, int background, int foreground) {
        views.heroBackground = background;
        views.heroForeground = foreground;
        views.heroShape.setColor(background);
        views.medallionShape.setColor(foreground);
        views.dotShape.setColor(foreground);
        views.glyph.setImageTintList(ColorStateList.valueOf(background));
        views.title.setTextColor(foreground);
        views.liveLabel.setTextColor(foreground);
        views.device.setTextColor(withAlpha(foreground, 0xd0));
        views.detail.setTextColor(withAlpha(foreground, 0xd0));
    }

    private void setPulse(boolean active) {
        if (active && Motion.enabled()) {
            if (audio.pulse == null) {
                ObjectAnimator pulse = ObjectAnimator.ofFloat(audio.liveDot, View.ALPHA, 1f, 0.2f);
                pulse.setDuration(900);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.setInterpolator(Motion.STANDARD);
                audio.pulse = pulse;
                pulse.start();
            }
            return;
        }
        if (audio.pulse != null) {
            audio.pulse.cancel();
            audio.pulse = null;
        }
        audio.liveDot.setAlpha(active ? 1f : 0.45f);
    }

    private void renderModule() {
        if (audio == null) {
            return;
        }
        LsposedStatus lsposed = lsposedStatus();
        boolean unsupported = "unsupported".equals(snapshot.requirements().module().get("state"));
        String status = switch (lsposed.state()) {
            case NO_SERVICE -> getString(R.string.module_waiting);
            case NO_SCOPE -> getString(R.string.module_scope);
            case NOT_LOADED -> getString(R.string.module_not_loaded);
            case UP_TO_DATE -> unsupported ? getString(R.string.module_unsupported)
                    : getString(R.string.module_running, lsposed.pid());
            case STALE -> getString(R.string.module_stale);
            case RELOADING -> getString(R.string.module_reloading);
            case FAILED -> getString(R.string.module_failed);
            case ERROR -> getString(R.string.module_error);
        };
        swapText(audio.moduleStatus, status, true);
    }

    private void restartScope() {
        if (restarting || monitor == null) {
            return;
        }
        restarting = true;
        renderRestart();
        monitor.restartBluetooth(result -> {
            restarting = false;
            renderRestart();
            showSnackbar(switch (result) {
                case RESTARTED -> R.string.restart_done;
                case BLUETOOTH_OFF -> R.string.restart_off;
                case DENIED -> R.string.restart_root;
                case FAILED -> R.string.restart_failed;
            });
            renderModule();
            if (page == Page.DIAGNOSTICS) {
                runDiagnostics();
            }
        });
    }

    private void renderRestart() {
        if (audio == null) {
            return;
        }
        ActionButton restart = audio.restart;
        restart.root().setEnabled(!restarting);
        restart.root().animate().alpha(restarting ? 0.72f : 1f).setDuration(Motion.SHORT).start();
        swapText(restart.label(), getString(restarting ? R.string.restart_running : R.string.restart_scope), true);
        audio.spin = toggleSpin(audio.spin, restart.icon(), restarting);
    }

    private long pollInterval() {
        return reconnectRequest != 0 ? RECONNECT_POLL_MS : appearance.refreshSeconds() * 1000L;
    }

    private void reconnectX4() {
        if (reconnectRequest != 0) {
            return;
        }
        if (routePreferences == null) {
            showSnackbar(R.string.reconnect_unavailable);
            return;
        }
        long id = System.currentTimeMillis();
        try {
            if (!routePreferences.edit().putLong(RouteSettings.RECONNECT_REQUEST, id).commit()) {
                showSnackbar(R.string.save_error);
                return;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not request X4 reconnection", error);
            showSnackbar(R.string.save_error);
            return;
        }
        reconnectRequest = id;
        reconnectPhase = null;
        host.postDelayed(reconnectTimeout, RECONNECT_TIMEOUT_MS);
        if (monitor != null) {
            monitor.setInterval(pollInterval());
        }
        renderReconnect();
    }

    private void finishReconnect(int message) {
        reconnectRequest = 0;
        host.removeCallbacks(reconnectTimeout);
        if (monitor != null) {
            monitor.setInterval(pollInterval());
        }
        renderReconnect();
        showSnackbar(message);
        if (page == Page.DIAGNOSTICS) {
            runDiagnostics();
        }
    }

    private void renderReconnect() {
        if (audio == null) {
            return;
        }
        ActionButton reconnect = audio.reconnect;
        boolean running = reconnectRequest != 0;
        reconnect.root().setEnabled(!running && routePreferences != null);
        reconnect.root().animate().alpha(running || routePreferences == null ? 0.72f : 1f)
                .setDuration(Motion.SHORT).start();
        int label = R.string.reconnect_x4;
        if (running) {
            label = reconnectPhase == null ? R.string.reconnect_requesting
                    : reconnectPhase.equals("CONNECTING") ? R.string.reconnect_connecting
                    : R.string.reconnect_disconnecting;
        }
        swapText(reconnect.label(), getString(label), true);
        audio.reconnectSpin = toggleSpin(audio.reconnectSpin, reconnect.icon(), running);
    }

    private ObjectAnimator toggleSpin(ObjectAnimator current, ImageView icon, boolean spinning) {
        if (spinning && Motion.enabled()) {
            if (current != null) {
                return current;
            }
            ObjectAnimator spin = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f);
            spin.setDuration(900);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            spin.start();
            return spin;
        }
        if (current != null) {
            current.cancel();
            icon.animate().rotation(0f).setDuration(Motion.SHORT).start();
        }
        return null;
    }

    private void stopAudioAnimations() {
        if (audio == null) {
            return;
        }
        if (audio.pulse != null) {
            audio.pulse.cancel();
            audio.pulse = null;
        }
        if (audio.spin != null) {
            audio.spin.cancel();
            audio.spin = null;
        }
        if (audio.reconnectSpin != null) {
            audio.reconnectSpin.cancel();
            audio.reconnectSpin = null;
        }
        if (audio.heroAnimator != null) {
            audio.heroAnimator.cancel();
            audio.heroAnimator = null;
        }
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.bluetooth_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private TextView buildSnackbar() {
        TextView bar = label(0, 14, colors.surface(), false);
        bar.setBackground(shape(colors.text(), 4));
        bar.setPadding(dp(16), dp(14), dp(16), dp(14));
        bar.setElevation(dp(6));
        bar.setVisibility(View.GONE);
        bar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(MATCH, WRAP,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.setMargins(dp(16), 0, dp(16), dp(16));
        bar.setLayoutParams(params);
        return bar;
    }

    private void showSnackbar(int message) {
        snackbar.removeCallbacks(hideSnackbar);
        snackbar.setText(message);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) snackbar.getLayoutParams();
        params.bottomMargin = bottomInset + dp(16);
        snackbar.setLayoutParams(params);
        snackbar.animate().cancel();
        if (snackbar.getVisibility() != View.VISIBLE && Motion.enabled()) {
            snackbar.setAlpha(0f);
            snackbar.setTranslationY(dp(24));
            snackbar.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        } else {
            snackbar.setAlpha(1f);
            snackbar.setTranslationY(0f);
        }
        snackbar.setVisibility(View.VISIBLE);
        snackbar.postDelayed(hideSnackbar, SNACKBAR_MS);
    }

    private void hideSnackbar() {
        if (!Motion.enabled()) {
            snackbar.setVisibility(View.GONE);
            return;
        }
        snackbar.animate().alpha(0f).translationY(dp(12)).setDuration(Motion.SHORT)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> snackbar.setVisibility(View.GONE)).start();
    }

    private void swapText(TextView view, CharSequence text, boolean animate) {
        if (TextUtils.equals(view.getText(), text)) {
            return;
        }
        if (!animate || !Motion.enabled() || !view.isAttachedToWindow() || view.getText().length() == 0) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setTranslationY(0f);
            view.setText(text);
            return;
        }
        float shift = dp(8);
        view.animate().cancel();
        view.animate().alpha(0f).translationY(-shift).setDuration(90).setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> {
                    view.setText(text);
                    view.setTranslationY(shift);
                    view.animate().alpha(1f).translationY(0f).setDuration(210)
                            .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
                }).start();
    }

    private static void animateArgb(int from, int to, long duration, IntConsumer apply) {
        if (from == to || !Motion.enabled()) {
            apply.accept(to);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofArgb(from, to);
        animator.setDuration(duration);
        animator.setInterpolator(Motion.STANDARD);
        animator.addUpdateListener(update -> apply.accept((int) update.getAnimatedValue()));
        animator.start();
    }

    private void animateLayout(ViewGroup group) {
        if (!Motion.enabled()) {
            return;
        }
        LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.setDuration(Motion.MEDIUM);
        transition.setInterpolator(LayoutTransition.CHANGING, Motion.EMPHASIZED);
        transition.setInterpolator(LayoutTransition.CHANGE_APPEARING, Motion.EMPHASIZED);
        transition.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, Motion.EMPHASIZED);
        group.setLayoutTransition(transition);
    }

    private void pressScale(View view) {
        if (!Motion.enabled()) {
            return;
        }
        StateListAnimator animator = new StateListAnimator();
        animator.addState(new int[] {android.R.attr.state_pressed}, scaleTo(0.94f));
        animator.addState(new int[0], scaleTo(1f));
        view.setStateListAnimator(animator);
    }

    private static Animator scaleTo(float scale) {
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.SCALE_X, scale), PropertyValuesHolder.ofFloat(View.SCALE_Y, scale));
        animator.setDuration(Motion.SHORT);
        animator.setInterpolator(Motion.STANDARD);
        return animator;
    }

    private void addHeading(LinearLayout parent, int name) {
        TextView heading = label(name, 15, colors.primary(), true);
        heading.setAccessibilityHeading(true);
        heading.setPadding(dp(8), dp(22), dp(8), dp(10));
        parent.addView(heading);
    }

    private TextView addDescription(LinearLayout parent, int name) {
        TextView description = label(name, 13, colors.muted(), false);
        description.setPadding(0, 0, 0, dp(10));
        parent.addView(description);
        return description;
    }

    private Switch addSwitch(LinearLayout parent, int id, int name) {
        Switch toggle = new Switch(this);
        toggle.setId(id);
        toggle.setText(name);
        toggle.setTypeface(typeface(true));
        toggle.setTextSize(16);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setMinimumHeight(dp(52));
        toggle.setSwitchMinWidth(dp(52));
        toggle.setSwitchPadding(dp(12));
        int[][] states = {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};
        toggle.setTextColor(new ColorStateList(states, new int[] {
                withAlpha(colors.text(), 0x61), colors.text(), colors.text()
        }));
        toggle.setTrackTintList(new ColorStateList(states, new int[] {
                withAlpha(colors.outline(), 0x40), colors.primary(), colors.containerHigh()
        }));
        toggle.setThumbTintList(new ColorStateList(states, new int[] {
                withAlpha(colors.text(), 0x61), colors.onPrimary(), colors.outline()
        }));
        parent.addView(toggle, new LinearLayout.LayoutParams(MATCH, WRAP));
        return toggle;
    }

    private ActionButton button(int id, int text, int iconResource, Drawable background, int foreground, Runnable action) {
        LinearLayout root = new LinearLayout(this);
        root.setId(id);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(16), 0, dp(24), 0);
        root.setContentDescription(getString(text));
        makeClickable(root, background, 20, action);
        ImageView icon = icon(iconResource, foreground);
        root.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView label = label(text, 14, foreground, true);
        label.setPaddingRelative(dp(8), 0, 0, 0);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(label);
        pressScale(root);
        return new ActionButton(root, icon, label);
    }

    private TextView addMetric(LinearLayout row, int title) {
        LinearLayout column = column();
        TextView value = label(0, 20, colors.text(), true);
        value.setSingleLine(true);
        column.addView(value);
        TextView caption = label(title, 12, colors.muted(), false);
        caption.setPadding(0, dp(2), 0, 0);
        column.addView(caption);
        row.addView(column, new LinearLayout.LayoutParams(0, WRAP, 1));
        return value;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(withAlpha(colors.outline(), 0x40));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, dp(1));
        params.setMargins(0, dp(8), 0, dp(8));
        parent.addView(divider, params);
    }

    private LinearLayout listGroup(LinearLayout parent) {
        LinearLayout group = column();
        parent.addView(group, new LinearLayout.LayoutParams(MATCH, WRAP));
        return group;
    }

    private LinearLayout listItem(LinearLayout group) {
        LinearLayout item = column();
        item.setPadding(dp(20), dp(10), dp(20), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = group.getChildCount() > 0 ? dp(GROUP_GAP) : 0;
        group.addView(item, params);
        int count = group.getChildCount();
        for (int index = 0; index < count; index++) {
            float top = index == 0 ? GROUP_OUTER_RADIUS : GROUP_INNER_RADIUS;
            float bottom = index == count - 1 ? GROUP_OUTER_RADIUS : GROUP_INNER_RADIUS;
            group.getChildAt(index).setBackground(cornerShape(colors.container(), new float[] {top, top, bottom, bottom}));
        }
        return item;
    }

    private float[] startEndCorners(boolean topStart, boolean topEnd, boolean bottomEnd, boolean bottomStart,
            float outer, float inner) {
        float[] logical = {topStart ? outer : inner, topEnd ? outer : inner, bottomEnd ? outer : inner,
                bottomStart ? outer : inner};
        if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            return new float[] {logical[1], logical[0], logical[3], logical[2]};
        }
        return logical;
    }

    private GradientDrawable cornerShape(int color, float[] corners) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        applyCorners(drawable, corners);
        return drawable;
    }

    private void applyCorners(GradientDrawable drawable, float[] corners) {
        float topLeft = dp(1) * corners[0];
        float topRight = dp(1) * corners[1];
        float bottomRight = dp(1) * corners[2];
        float bottomLeft = dp(1) * corners[3];
        drawable.setCornerRadii(new float[] {topLeft, topLeft, topRight, topRight, bottomRight, bottomRight,
                bottomLeft, bottomLeft});
    }

    private void makeClickable(View view, GradientDrawable background, Runnable action) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(colors.text(), 0x1f)),
                background, new ShapeMask(background)));
        view.setClickable(true);
        view.setFocusable(true);
        if (action != null) {
            view.setOnClickListener(ignored -> action.run());
        }
    }

    private static final class ShapeMask extends Drawable {
        private final GradientDrawable shape;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final RectF bounds = new RectF();

        ShapeMask(GradientDrawable shape) {
            this.shape = shape;
            paint.setColor(0xffffffff);
        }

        @Override
        public void draw(Canvas canvas) {
            bounds.set(getBounds());
            path.reset();
            float[] radii = shape.getCornerRadii();
            path.addRoundRect(bounds, radii != null ? radii : new float[8], android.graphics.Path.Direction.CW);
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private LinearLayout card(LinearLayout parent, int topMargin) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(14), dp(20), dp(14));
        card.setBackground(shape(colors.container(), 24));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(topMargin);
        parent.addView(card, params);
        return card;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(int resource, float size, int color, boolean medium) {
        TextView text = new TextView(this);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(typeface(medium));
        if (resource != 0) {
            text.setText(resource);
        }
        return text;
    }

    private static Typeface typeface(boolean medium) {
        return Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
    }

    private ImageView icon(int resource, int color) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(color));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private void makeClickable(View view, Drawable background, int radius, Runnable action) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(colors.text(), 0x1f)),
                background, shape(0xffffffff, radius)));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(ignored -> action.run());
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    private static String milliseconds(int microseconds) {
        return microseconds % 1000 == 0 ? microseconds / 1000 + " ms"
                : String.format(Locale.ROOT, "%.1f ms", microseconds / 1000f);
    }

    private static String kilohertz(int hertz) {
        return hertz % 1000 == 0 ? hertz / 1000 + " kHz" : String.format(Locale.ROOT, "%.1f kHz", hertz / 1000f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SwatchDrawable extends Drawable {
        private final Colors sample;
        private final int ring;
        private final float ringWidth;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        SwatchDrawable(Colors sample, int ring, float ringWidth) {
            this.sample = sample;
            this.ring = ring;
            this.ringWidth = ringWidth;
        }

        @Override
        public void draw(Canvas canvas) {
            oval.set(getBounds());
            oval.inset(ringWidth / 2f, ringWidth / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(sample.primary());
            canvas.drawArc(oval, 180f, 180f, true, paint);
            paint.setColor(sample.secondaryContainer());
            canvas.drawArc(oval, 90f, 90f, true, paint);
            paint.setColor(sample.tertiary());
            canvas.drawArc(oval, 0f, 90f, true, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ringWidth);
            paint.setColor(withAlpha(ring, 0x66));
            canvas.drawOval(oval, paint);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
