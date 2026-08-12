package dev.c1bridge.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Locale;

final class PowerTraceView extends View {
    static final int POWER = 0;
    static final int CADENCE = 1;
    static final int DURATION = 2;
    static final int DISTANCE = 3;
    static final int CALORIES = 4;
    private static final int CAPACITY = 72;

    private final float[][] samples = new float[5][CAPACITY];
    private final Paint tracePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int count;
    private int mode = POWER;

    PowerTraceView(Context context) {
        super(context);
        tracePaint.setColor(0xFFD8D5CE);
        tracePaint.setStyle(Paint.Style.STROKE);
        tracePaint.setStrokeWidth(dp(1.15f));
        tracePaint.setStrokeJoin(Paint.Join.ROUND);
        tracePaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setColor(BridgeUi.ACCENT);
        dotPaint.setStyle(Paint.Style.STROKE);
        dotPaint.setStrokeWidth(dp(1.8f));
        labelPaint.setColor(BridgeUi.MUTED);
        labelPaint.setTextSize(dp(9));
        labelPaint.setTypeface(BridgeUi.MONO);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void addSample(
            int watts,
            int cadence,
            int durationSeconds,
            int distanceMeters,
            int calories
    ) {
        if (count < CAPACITY) {
            samples[POWER][count] = watts;
            samples[CADENCE][count] = cadence;
            samples[DURATION][count] = durationSeconds;
            samples[DISTANCE][count] = distanceMeters;
            samples[CALORIES][count] = calories;
            count++;
        } else {
            for (float[] channel : samples) {
                System.arraycopy(channel, 1, channel, 0, CAPACITY - 1);
            }
            samples[POWER][CAPACITY - 1] = watts;
            samples[CADENCE][CAPACITY - 1] = cadence;
            samples[DURATION][CAPACITY - 1] = durationSeconds;
            samples[DISTANCE][CAPACITY - 1] = distanceMeters;
            samples[CALORIES][CAPACITY - 1] = calories;
        }
        invalidate();
    }

    void setMode(int mode) {
        this.mode = Math.max(POWER, Math.min(CALORIES, mode));
        invalidate();
    }

    void clear() {
        count = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float drawWidth = Math.max(0, width - dp(5));
        float top = dp(8);
        float bottom = getHeight() - dp(62);
        if (count == 0) {
            return;
        }

        float maximum = minimumScale();
        for (int index = 0; index < count; index++) {
            maximum = Math.max(maximum, samples[mode][index] * 1.16f);
        }
        float step = count <= 1 ? drawWidth : drawWidth / (CAPACITY - 1f);
        float startX = count <= 1 ? drawWidth : drawWidth - step * (count - 1);
        Path path = new Path();
        float lastX = startX;
        float lastY = bottom;
        for (int index = 0; index < count; index++) {
            float x = startX + step * index;
            float y = bottom - (bottom - top) * samples[mode][index] / maximum;
            if (index == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        canvas.drawPath(path, tracePaint);
        canvas.drawCircle(lastX, lastY, dp(3.7f), dotPaint);
        drawTimeLabels(canvas, drawWidth, Math.round(samples[DURATION][count - 1]));
    }

    private float minimumScale() {
        switch (mode) {
            case CADENCE:
                return 30f;
            case DURATION:
            case DISTANCE:
                return 1f;
            case CALORIES:
                return 5f;
            case POWER:
            default:
                return 60f;
        }
    }

    private void drawTimeLabels(Canvas canvas, float width, int elapsedSeconds) {
        int interval = elapsedSeconds >= 180 ? 60 : elapsedSeconds >= 60 ? 30 : 15;
        float y = getHeight() - dp(8);
        for (int index = 0; index < 5; index++) {
            int value = Math.max(0, elapsedSeconds - interval * (4 - index));
            String text = String.format(Locale.US, "%02d:%02d", value / 60, value % 60);
            float x = width * index / 4f;
            labelPaint.setTextAlign(index == 0
                    ? Paint.Align.LEFT
                    : index == 4 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(text, x, y, labelPaint);
        }
    }

    private float dp(float value) {
        return BridgeUi.dp(getContext(), value);
    }
}
