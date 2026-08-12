package dev.c1bridge.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Draws the hero metric from visible glyph bounds instead of font line metrics. */
final class PrimaryMetricView extends View {
    private static final float TRACKING_EM = 0.006f;
    private static final long ROLL_DURATION_MS = 160L;
    private static final long MIN_ROLL_INTERVAL_MS = 650L;

    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect glyphBounds = new Rect();
    private String value = "0";
    private String outgoingValue;
    private String unit = "W";
    private float numberSizeSp = 380f;
    private float numberScaleX = 0.77f;
    private float outgoingNumberScaleX = 0.77f;
    private float unitSizeSp = 68f;
    private float rollProgress = 1f;
    private long lastValueChangeAt;
    private boolean hasValue;
    private ValueAnimator rollAnimator;

    PrimaryMetricView(Context context) {
        super(context);
        numberPaint.setColor(BridgeUi.TEXT);
        numberPaint.setTypeface(BridgeUi.HERO);
        numberPaint.setFontFeatureSettings("pnum,kern");
        unitPaint.setColor(BridgeUi.TEXT);
        unitPaint.setTypeface(BridgeUi.DISPLAY);
    }

    void setMetric(
            String value,
            String unit,
            float numberSizeSp,
            float numberScaleX,
            float unitSizeSp
    ) {
        String previousValue = this.value;
        String previousUnit = this.unit;
        float previousNumberScaleX = this.numberScaleX;
        boolean valueChanged = !value.equals(previousValue);
        boolean digitCountChanged = value.length() != previousValue.length();
        long now = SystemClock.uptimeMillis();
        boolean animateDigits = valueChanged
                && hasValue
                && unit.equals(previousUnit)
                && (digitCountChanged || now - lastValueChangeAt >= MIN_ROLL_INTERVAL_MS)
                && BridgeUi.animationsEnabled();

        this.value = value;
        this.unit = unit;
        this.numberSizeSp = numberSizeSp;
        this.numberScaleX = numberScaleX;
        this.unitSizeSp = unitSizeSp;
        if (valueChanged) {
            lastValueChangeAt = now;
        }
        if (!unit.equals(previousUnit)) {
            cancelDigitRoll();
        } else if (valueChanged) {
            if (animateDigits) {
                startDigitRoll(previousValue, previousNumberScaleX);
            } else {
                cancelDigitRoll();
            }
        }
        hasValue = true;
        setContentDescription("主指标 " + value + (unit.isEmpty() ? "" : " " + unit)
                + "；点按或左右滑动切换");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().scaledDensity;
        numberPaint.setTextSize(numberSizeSp * density);
        numberPaint.setTextScaleX(numberScaleX);
        unitPaint.setTextSize(unitSizeSp * density);
        unitPaint.setTextScaleX(1f);

        float left = dp(4);
        float gap = unit.isEmpty() ? 0f : dp(6);
        float numberWidth = measuredNumberWidth();
        float unitWidth = unit.isEmpty() ? 0f : unitPaint.measureText(unit);
        float available = getWidth() - left - dp(4);
        float total = numberWidth + gap + unitWidth;
        if (total > available && numberWidth > 0f) {
            float fittedNumberWidth = Math.max(dp(80), available - gap - unitWidth);
            float fittedScale = numberPaint.getTextScaleX() * fittedNumberWidth / numberWidth;
            numberPaint.setTextScaleX(Math.max(0.40f, fittedScale));
            numberWidth = measuredNumberWidth();
        }

        float visibleBottom = getHeight() - dp(1);
        numberPaint.getTextBounds(value, 0, value.length(), glyphBounds);
        float numberBaseline = visibleBottom - glyphBounds.bottom;
        drawNumber(canvas, left, numberBaseline);

        if (!unit.isEmpty()) {
            float unitAnchorWidth = numberWidth;
            if (outgoingValue != null && rollProgress < 1f) {
                float currentScaleX = numberPaint.getTextScaleX();
                numberPaint.setTextScaleX(outgoingNumberScaleX);
                float outgoingWidth = measuredNumberWidth(outgoingValue);
                numberPaint.setTextScaleX(currentScaleX);
                unitAnchorWidth = outgoingWidth
                        + (numberWidth - outgoingWidth) * rollProgress;
            }
            visibleBottom = getHeight() - dp(4);
            unitPaint.getTextBounds(unit, 0, unit.length(), glyphBounds);
            float unitBaseline = visibleBottom - glyphBounds.bottom;
            canvas.drawText(unit, left + unitAnchorWidth + gap, unitBaseline, unitPaint);
        }
    }

    private float measuredNumberWidth() {
        return measuredNumberWidth(value);
    }

    private float measuredNumberWidth(String text) {
        float width = 0f;
        float tracking = numberPaint.getTextSize() * TRACKING_EM;
        for (int index = 0; index < text.length(); index++) {
            width += numberPaint.measureText(text, index, index + 1);
            if (index + 1 < text.length()) {
                width += tracking;
            }
        }
        return Math.max(0f, width);
    }

    private void drawNumber(Canvas canvas, float x, float baseline) {
        if (outgoingValue == null || rollProgress >= 1f) {
            drawTrackedText(canvas, value, x, baseline);
            return;
        }

        int checkpoint = canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        float distance = Math.min(dp(34), numberPaint.getTextSize() * 0.12f);
        if (outgoingValue.length() != value.length()) {
            float currentScaleX = numberPaint.getTextScaleX();
            numberPaint.setAlpha(Math.round(255f * (1f - rollProgress)));
            numberPaint.setTextScaleX(outgoingNumberScaleX);
            drawTrackedText(canvas, outgoingValue, x, baseline - distance * rollProgress);
            numberPaint.setAlpha(Math.round(255f * rollProgress));
            numberPaint.setTextScaleX(currentScaleX);
            drawTrackedText(canvas, value, x, baseline + distance * (1f - rollProgress));
        } else {
            float tracking = numberPaint.getTextSize() * TRACKING_EM;
            for (int index = 0; index < value.length(); index++) {
                if (outgoingValue.charAt(index) == value.charAt(index)) {
                    numberPaint.setAlpha(255);
                    canvas.drawText(value, index, index + 1, x, baseline, numberPaint);
                } else {
                    numberPaint.setAlpha(Math.round(255f * (1f - rollProgress)));
                    canvas.drawText(
                            outgoingValue,
                            index,
                            index + 1,
                            x,
                            baseline - distance * rollProgress,
                            numberPaint
                    );
                    numberPaint.setAlpha(Math.round(255f * rollProgress));
                    canvas.drawText(
                            value,
                            index,
                            index + 1,
                            x,
                            baseline + distance * (1f - rollProgress),
                            numberPaint
                    );
                }
                x += numberPaint.measureText(value, index, index + 1) + tracking;
            }
        }
        numberPaint.setAlpha(255);
        canvas.restoreToCount(checkpoint);
    }

    private void drawTrackedText(Canvas canvas, String text, float x, float baseline) {
        float tracking = numberPaint.getTextSize() * TRACKING_EM;
        for (int index = 0; index < text.length(); index++) {
            canvas.drawText(text, index, index + 1, x, baseline, numberPaint);
            x += numberPaint.measureText(text, index, index + 1) + tracking;
        }
    }

    private void startDigitRoll(String previousValue, float previousNumberScaleX) {
        cancelDigitRoll();
        outgoingValue = previousValue;
        outgoingNumberScaleX = previousNumberScaleX;
        rollProgress = 0f;
        rollAnimator = ValueAnimator.ofFloat(0f, 1f);
        rollAnimator.setDuration(ROLL_DURATION_MS);
        rollAnimator.setInterpolator(new PathInterpolator(0.23f, 1f, 0.32f, 1f));
        rollAnimator.addUpdateListener(animation -> {
            rollProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        rollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation == rollAnimator) {
                    outgoingValue = null;
                    rollProgress = 1f;
                    rollAnimator = null;
                    invalidate();
                }
            }
        });
        rollAnimator.start();
    }

    private void cancelDigitRoll() {
        if (rollAnimator != null) {
            rollAnimator.removeAllUpdateListeners();
            rollAnimator.removeAllListeners();
            rollAnimator.cancel();
            rollAnimator = null;
        }
        outgoingValue = null;
        rollProgress = 1f;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelDigitRoll();
        super.onDetachedFromWindow();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
