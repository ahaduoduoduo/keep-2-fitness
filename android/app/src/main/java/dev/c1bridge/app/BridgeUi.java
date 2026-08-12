package dev.c1bridge.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

final class BridgeUi {
    static final int BACKGROUND = Color.rgb(9, 10, 10);
    static final int SURFACE = Color.rgb(17, 19, 18);
    static final int SURFACE_HIGH = Color.rgb(24, 26, 25);
    static final int TEXT = Color.rgb(241, 238, 230);
    static final int MUTED = Color.rgb(140, 145, 141);
    static final int FAINT = Color.rgb(59, 63, 60);
    static final int RULE = Color.rgb(78, 81, 78);
    static final int ACCENT = Color.rgb(231, 120, 70);
    static final int DANGER = Color.rgb(187, 100, 88);

    static Typeface DISPLAY = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    static Typeface HERO = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);

    private BridgeUi() {
    }

    static void configureWindow(Activity activity) {
        DISPLAY = activity.getResources().getFont(R.font.barlow_condensed_regular);
        HERO = activity.getResources().getFont(R.font.bebas_neue_regular);
        Window window = activity.getWindow();
        window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);
        if (Build.VERSION.SDK_INT >= 26) {
            window.getDecorView().setSystemUiVisibility(0);
        }
    }

    static TextView text(Context context, String value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sizeSp);
        view.setFontFeatureSettings("tnum");
        view.setIncludeFontPadding(false);
        return view;
    }

    static TextView label(Context context, String value) {
        TextView view = text(context, value, 11, MUTED);
        view.setTypeface(MEDIUM);
        view.setLetterSpacing(0.12f);
        view.setAllCaps(true);
        return view;
    }

    static TextView button(Context context, String value, boolean primary) {
        TextView view = text(context, value, 15, primary ? BACKGROUND : TEXT);
        view.setTypeface(MEDIUM);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 54));
        view.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12));
        view.setBackground(ripple(
                context,
                primary ? ACCENT : SURFACE,
                primary ? ACCENT : FAINT,
                18
        ));
        addPressMotion(view);
        return view;
    }

    static TextView textButton(Context context, String value) {
        TextView view = text(context, value, 14, MUTED);
        view.setTypeface(MEDIUM);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        addPressMotion(view);
        return view;
    }

    static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(FAINT);
        return view;
    }

    static GradientDrawable shape(int fill, int stroke, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke != Color.TRANSPARENT) {
            drawable.setStroke(dp(context, 1), stroke);
        }
        return drawable;
    }

    static RippleDrawable ripple(
            Context context,
            int fill,
            int stroke,
            float radiusDp
    ) {
        GradientDrawable content = shape(fill, stroke, radiusDp, context);
        GradientDrawable mask = shape(Color.WHITE, Color.TRANSPARENT, radiusDp, context);
        return new RippleDrawable(
                ColorStateList.valueOf(0x24FFFFFF),
                content,
                mask
        );
    }

    static void addPressMotion(View view) {
        view.setOnTouchListener((target, event) -> {
            if (!target.isEnabled() || !animationsEnabled()) {
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                target.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                target.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
            }
            return false;
        });
    }

    static boolean animationsEnabled() {
        return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
