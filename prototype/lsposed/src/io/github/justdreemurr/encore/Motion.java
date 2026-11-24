package io.github.justdreemurr.encore;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.Path;
import android.view.animation.PathInterpolator;

final class Motion {
    static final TimeInterpolator EMPHASIZED;
    static final TimeInterpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final TimeInterpolator EMPHASIZED_ACCELERATE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    static final TimeInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final long SHORT = 150;
    static final long MEDIUM = 300;
    static final long LONG = 500;

    static {
        Path path = new Path();
        path.cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f);
        path.cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f);
        EMPHASIZED = new PathInterpolator(path);
    }

    private Motion() {}

    static boolean enabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }
}
