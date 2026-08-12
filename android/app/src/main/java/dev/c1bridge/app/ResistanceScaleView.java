package dev.c1bridge.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

final class ResistanceScaleView extends View {
    interface Listener {
        void onResistancePreview(int resistance);

        void onResistanceSelected(int resistance);
    }

    private static final long HOLD_DELAY_MS = 250L;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scaleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int touchSlop;
    private final Runnable beginAdjustment = this::beginAdjustment;
    private Listener listener;
    private int maximum = 18;
    private int value = 1;
    private int originalValue = 1;
    private float downX;
    private float downY;
    private boolean waitingForHold;
    private boolean adjusting;

    ResistanceScaleView(Context context) {
        super(context);
        trackPaint.setColor(BridgeUi.FAINT);
        trackPaint.setStrokeWidth(dp(0.8f));
        activePaint.setColor(BridgeUi.ACCENT);
        activePaint.setStrokeWidth(dp(1.5f));
        activePaint.setStyle(Paint.Style.STROKE);
        bubblePaint.setColor(BridgeUi.SURFACE_HIGH);
        bubblePaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(BridgeUi.TEXT);
        textPaint.setTextSize(dp(11));
        textPaint.setTypeface(BridgeUi.DISPLAY);
        textPaint.setTextAlign(Paint.Align.CENTER);
        scaleTextPaint.setColor(BridgeUi.MUTED);
        scaleTextPaint.setTextSize(dp(10f));
        scaleTextPaint.setTypeface(BridgeUi.DISPLAY);
        scaleTextPaint.setTextAlign(Paint.Align.RIGHT);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setContentDescription("阻力刻度；按住后上下拖动调节");
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setMaximum(int maximum) {
        this.maximum = Math.max(2, maximum);
        setValue(value);
    }

    void setValue(int value) {
        if (adjusting) {
            return;
        }
        this.value = Math.max(1, Math.min(maximum, value));
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (!enabled) {
            cancelAdjustment(true);
        }
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.34f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = dp(adjusting ? 16 : 10);
        float bottom = getHeight() - dp(adjusting ? 18 : 10);
        float trackX = adjusting ? getWidth() - dp(11) : getWidth();

        float handleY = yForValue(value, top, bottom);
        if (adjusting) {
            activePaint.setStyle(Paint.Style.STROKE);
            activePaint.setStrokeWidth(dp(2.2f));
            canvas.drawLine(trackX, handleY, trackX, bottom, activePaint);
        }
        for (int current = 1; current <= maximum; current++) {
            float y = yForValue(current, top, bottom);
            float tick = current == value
                    ? adjusting ? 18 : 12
                    : current % 3 == 0 ? 7 : 4;
            canvas.drawLine(
                    trackX - dp(tick),
                    y,
                    trackX,
                    y,
                    current == value ? activePaint : trackPaint
            );
            if (!adjusting) {
                scaleTextPaint.setColor(current == value ? BridgeUi.ACCENT : BridgeUi.MUTED);
                canvas.drawText(
                        String.valueOf(current),
                        trackX - dp(16),
                        y - (scaleTextPaint.ascent() + scaleTextPaint.descent()) / 2,
                        scaleTextPaint
                );
                if (current == value) {
                    canvas.drawLine(
                            trackX - dp(36),
                            y,
                            trackX - dp(29),
                            y,
                            activePaint
                    );
                }
            }
        }

        if (adjusting) {
            RectF bubble = new RectF(dp(0), handleY - dp(15), dp(31), handleY + dp(15));
            canvas.drawRoundRect(bubble, dp(4), dp(4), bubblePaint);
            activePaint.setStyle(Paint.Style.STROKE);
            activePaint.setStrokeWidth(dp(1));
            canvas.drawRoundRect(bubble, dp(4), dp(4), activePaint);
            canvas.drawText(
                    String.valueOf(value),
                    bubble.centerX(),
                    bubble.centerY() - (textPaint.ascent() + textPaint.descent()) / 2,
                    textPaint
            );
            activePaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(trackX, handleY, dp(6.5f), activePaint);
        } else {
            activePaint.setStyle(Paint.Style.STROKE);
        }
        activePaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                originalValue = value;
                waitingForHold = true;
                postDelayed(beginAdjustment, HOLD_DELAY_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (waitingForHold
                        && (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop)) {
                    waitingForHold = false;
                    removeCallbacks(beginAdjustment);
                }
                if (adjusting) {
                    updateFromY(event.getY());
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(beginAdjustment);
                waitingForHold = false;
                if (adjusting) {
                    updateFromY(event.getY());
                    adjusting = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (listener != null) {
                        listener.onResistanceSelected(value);
                    }
                    performClick();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelAdjustment(true);
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void beginAdjustment() {
        if (!waitingForHold || !isEnabled()) {
            return;
        }
        waitingForHold = false;
        adjusting = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        updateFromY(downY);
        getParent().requestDisallowInterceptTouchEvent(true);
        invalidate();
    }

    private void cancelAdjustment(boolean restoreValue) {
        removeCallbacks(beginAdjustment);
        waitingForHold = false;
        if (restoreValue && adjusting) {
            value = originalValue;
            if (listener != null) {
                listener.onResistancePreview(value);
            }
        }
        adjusting = false;
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        invalidate();
    }

    private void updateFromY(float y) {
        float top = dp(16);
        float bottom = getHeight() - dp(18);
        float fraction = 1f - Math.max(0f, Math.min(1f, (y - top) / (bottom - top)));
        int nextValue = 1 + Math.round(fraction * (maximum - 1));
        if (nextValue == value) {
            return;
        }
        value = nextValue;
        if (listener != null) {
            listener.onResistancePreview(value);
        }
        invalidate();
    }

    private float yForValue(int current, float top, float bottom) {
        float fraction = (current - 1f) / (maximum - 1f);
        return bottom - (bottom - top) * fraction;
    }

    private float dp(float value) {
        return BridgeUi.dp(getContext(), value);
    }
}
