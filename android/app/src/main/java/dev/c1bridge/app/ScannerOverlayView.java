package dev.c1bridge.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class ScannerOverlayView extends View {
    private final Paint shadePaint = new Paint();
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guide = new RectF();
    private ValueAnimator animator;
    private float scanProgress;

    ScannerOverlayView(Context context) {
        super(context);
        shadePaint.setColor(0x88000000);
        cornerPaint.setColor(BridgeUi.TEXT);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(BridgeUi.dp(context, 2));
        cornerPaint.setStrokeCap(Paint.Cap.SQUARE);
        scanPaint.setColor(0x99C6814D);
        scanPaint.setStrokeWidth(BridgeUi.dp(context, 1));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!BridgeUi.animationsEnabled()) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2_200L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            scanProgress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth() * 0.76f, BridgeUi.dp(getContext(), 330));
        float left = (getWidth() - size) / 2f;
        float top = getHeight() * 0.30f;
        guide.set(left, top, left + size, top + size);

        canvas.drawRect(0, 0, getWidth(), guide.top, shadePaint);
        canvas.drawRect(0, guide.bottom, getWidth(), getHeight(), shadePaint);
        canvas.drawRect(0, guide.top, guide.left, guide.bottom, shadePaint);
        canvas.drawRect(guide.right, guide.top, getWidth(), guide.bottom, shadePaint);

        float length = BridgeUi.dp(getContext(), 29);
        drawCorner(canvas, guide.left, guide.top, length, 1, 1);
        drawCorner(canvas, guide.right, guide.top, length, -1, 1);
        drawCorner(canvas, guide.left, guide.bottom, length, 1, -1);
        drawCorner(canvas, guide.right, guide.bottom, length, -1, -1);

        if (animator != null) {
            float y = guide.top + BridgeUi.dp(getContext(), 12)
                    + (guide.height() - BridgeUi.dp(getContext(), 24)) * scanProgress;
            canvas.drawLine(
                    guide.left + length,
                    y,
                    guide.right - length,
                    y,
                    scanPaint
            );
        }
    }

    private void drawCorner(
            Canvas canvas,
            float x,
            float y,
            float length,
            int horizontal,
            int vertical
    ) {
        canvas.drawLine(x, y, x + length * horizontal, y, cornerPaint);
        canvas.drawLine(x, y, x, y + length * vertical, cornerPaint);
    }
}
