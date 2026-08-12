package dev.c1bridge.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class BridgeIconView extends View {
    static final int MARK = 0;
    static final int QR = 1;
    static final int MANUAL = 2;
    static final int READ_ONLY = 3;
    static final int FLASH = 4;
    static final int CLOSE = 5;
    static final int BACK = 6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int icon;

    BridgeIconView(Context context, int icon) {
        super(context);
        this.icon = icon;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(BridgeUi.dp(context, 1.5f));
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setColor(BridgeUi.TEXT);
    }

    void setIcon(int icon) {
        this.icon = icon;
        invalidate();
    }

    void setIconColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float left = w * 0.22f;
        float top = h * 0.22f;
        float right = w * 0.78f;
        float bottom = h * 0.78f;
        switch (icon) {
            case MARK:
                drawMark(canvas, w, h);
                break;
            case QR:
                drawQr(canvas, left, top, right, bottom);
                break;
            case MANUAL:
                drawManual(canvas, left, top, right, bottom);
                break;
            case READ_ONLY:
                canvas.drawCircle(w / 2f, h / 2f, w * 0.22f, paint);
                canvas.drawLine(w * 0.38f, h * 0.5f, w * 0.47f, h * 0.59f, paint);
                canvas.drawLine(w * 0.47f, h * 0.59f, w * 0.65f, h * 0.39f, paint);
                break;
            case FLASH:
                Path bolt = new Path();
                bolt.moveTo(w * 0.57f, h * 0.16f);
                bolt.lineTo(w * 0.33f, h * 0.53f);
                bolt.lineTo(w * 0.51f, h * 0.53f);
                bolt.lineTo(w * 0.42f, h * 0.84f);
                bolt.lineTo(w * 0.69f, h * 0.43f);
                bolt.lineTo(w * 0.51f, h * 0.43f);
                bolt.close();
                canvas.drawPath(bolt, paint);
                break;
            case CLOSE:
                canvas.drawLine(left, top, right, bottom, paint);
                canvas.drawLine(right, top, left, bottom, paint);
                break;
            case BACK:
                canvas.drawLine(w * 0.68f, h * 0.22f, w * 0.34f, h * 0.5f, paint);
                canvas.drawLine(w * 0.34f, h * 0.5f, w * 0.68f, h * 0.78f, paint);
                canvas.drawLine(w * 0.35f, h * 0.5f, w * 0.82f, h * 0.5f, paint);
                break;
            default:
                break;
        }
    }

    private void drawMark(Canvas canvas, float w, float h) {
        for (int index = 0; index < 3; index++) {
            float offset = index * w * 0.19f;
            canvas.drawLine(w * 0.16f + offset, h * 0.68f, w * 0.38f + offset, h * 0.3f, paint);
        }
    }

    private void drawQr(Canvas canvas, float left, float top, float right, float bottom) {
        float unit = (right - left) / 3f;
        canvas.drawRect(left, top, left + unit, top + unit, paint);
        canvas.drawRect(right - unit, top, right, top + unit, paint);
        canvas.drawRect(left, bottom - unit, left + unit, bottom, paint);
        canvas.drawLine(right - unit, bottom - unit, right, bottom - unit, paint);
        canvas.drawLine(right - unit, bottom - unit, right - unit, bottom, paint);
        canvas.drawLine(right - unit * 0.4f, bottom, right, bottom, paint);
    }

    private void drawManual(Canvas canvas, float left, float top, float right, float bottom) {
        canvas.drawRect(left, top, right, bottom, paint);
        for (int row = 0; row < 3; row++) {
            float y = top + (row + 1) * (bottom - top) / 4f;
            canvas.drawLine(left + (right - left) * 0.2f, y, right - (right - left) * 0.2f, y, paint);
        }
    }
}
