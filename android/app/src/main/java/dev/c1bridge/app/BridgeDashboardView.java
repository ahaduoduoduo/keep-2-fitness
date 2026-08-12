package dev.c1bridge.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

final class BridgeDashboardView extends FrameLayout {
    interface Listener {
        void onStartBridge();

        void onStopBridge();

        void onManageDevice();

        void onResistanceSelected(int resistance);

        void onTrainingStatusSelected(int status);
    }

    private static final int METRIC_COUNT = 5;

    private final Listener listener;
    private final View connectionDot;
    private final View modeDivider;
    private final TextView headerTitle;
    private final TextView connectionText;
    private final TextView modeText;
    private final PrimaryMetricView primaryMetric;
    private final TextView cadenceText;
    private final TextView middleMetricLabel;
    private final TextView middleMetricText;
    private final TextView middleMetricUnit;
    private final TextView finalMetricLabel;
    private final TextView finalMetricText;
    private final TextView finalMetricUnit;
    private final TextView statusText;
    private final Runnable hideStatus;
    private final PowerTraceView powerTrace;
    private final ResistanceScaleView resistanceScale;
    private final PrimaryMetricView heroNumberRow;
    private final LinearLayout actionArea;

    private boolean bridgeRunning;
    private boolean bikeConnected;
    private boolean controlAuthorized;
    private boolean deviceBound;
    private boolean controlPending;
    private int trainingStatus = C1MiniClient.TRAINING_STATUS_IDLE;
    private int power;
    private int cadence;
    private int durationSeconds;
    private int distanceMeters;
    private int calories;
    private int resistance = 1;
    private int focus;
    private float gestureDownX;
    private float gestureDownY;
    private String primaryDisplay = "";

    BridgeDashboardView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(BridgeUi.BACKGROUND);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(21), dp(16), 0, dp(40));
        addView(content, match());

        FrameLayout topBar = new FrameLayout(context);
        topBar.setPadding(0, 0, dp(21), 0);
        content.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
        ));

        headerTitle = BridgeUi.text(context, "C1 BRIDGE", 12, BridgeUi.TEXT);
        headerTitle.setTypeface(BridgeUi.MONO);
        headerTitle.setLetterSpacing(0.08f);
        topBar.addView(headerTitle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL
        ));

        LinearLayout connectionGroup = new LinearLayout(context);
        connectionGroup.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(connectionGroup, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));

        connectionDot = new View(context);
        GradientDrawable dotBackground = new GradientDrawable();
        dotBackground.setShape(GradientDrawable.OVAL);
        dotBackground.setColor(BridgeUi.FAINT);
        connectionDot.setBackground(dotBackground);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotParams.rightMargin = dp(7);
        connectionGroup.addView(connectionDot, dotParams);

        connectionText = BridgeUi.text(context, "未连接", 11, BridgeUi.MUTED);
        connectionText.setTypeface(BridgeUi.MEDIUM);
        connectionGroup.addView(connectionText);

        LinearLayout modeGroup = new LinearLayout(context);
        modeGroup.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(modeGroup, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL
        ));

        modeDivider = new View(context);
        modeDivider.setBackgroundColor(BridgeUi.FAINT);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(dp(1), dp(13));
        separatorParams.leftMargin = dp(14);
        separatorParams.rightMargin = dp(14);
        modeGroup.addView(modeDivider, separatorParams);

        modeText = BridgeUi.text(context, "已绑定设备", 11, BridgeUi.TEXT);
        modeText.setTypeface(BridgeUi.MEDIUM);
        modeText.setPadding(dp(3), dp(8), 0, dp(8));
        modeText.setOnClickListener(view -> listener.onManageDevice());
        BridgeUi.addPressMotion(modeText);
        modeGroup.addView(modeText);

        FrameLayout hero = new FrameLayout(context);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        heroParams.topMargin = dp(13);
        content.addView(hero, heroParams);

        heroNumberRow = new PrimaryMetricView(context);
        heroNumberRow.setContentDescription("主指标；点按或左右滑动切换");
        heroNumberRow.setClickable(true);
        heroNumberRow.setOnClickListener(view -> selectFocus(focus + 1, true));
        heroNumberRow.setOnTouchListener(this::handleMetricGesture);
        FrameLayout.LayoutParams numberRowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(300),
                Gravity.TOP
        );
        numberRowParams.leftMargin = 0;
        numberRowParams.rightMargin = dp(44);
        numberRowParams.topMargin = dp(-10);
        hero.addView(heroNumberRow, numberRowParams);
        primaryMetric = heroNumberRow;

        powerTrace = new PowerTraceView(context);
        FrameLayout.LayoutParams traceParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(120),
                Gravity.BOTTOM
        );
        traceParams.rightMargin = dp(75);
        traceParams.bottomMargin = dp(55);
        hero.addView(powerTrace, traceParams);

        resistanceScale = new ResistanceScaleView(context);
        resistanceScale.setListener(new ResistanceScaleView.Listener() {
            @Override
            public void onResistancePreview(int target) {
                middleMetricText.setText(String.valueOf(target));
            }

            @Override
            public void onResistanceSelected(int target) {
                BridgeDashboardView.this.listener.onResistanceSelected(target);
            }
        });
        FrameLayout.LayoutParams scaleParams = new FrameLayout.LayoutParams(
                dp(54),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
        );
        scaleParams.topMargin = 0;
        scaleParams.bottomMargin = 0;
        hero.addView(resistanceScale, scaleParams);

        LinearLayout metrics = new LinearLayout(context);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(metrics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(82)
        ));
        MetricViews cadence = addMetricGroup(
                metrics,
                "",
                "0",
                "RPM",
                PowerTraceView.CADENCE,
                BridgeUi.TEXT,
                58,
                0.92f,
                Gravity.START | Gravity.BOTTOM
        );
        cadenceText = cadence.value;
        cadenceText.setTextScaleX(0.72f);
        addMetricDivider(metrics);
        MetricViews middle = addMetricGroup(
                metrics,
                "阻力",
                "1",
                "",
                -1,
                BridgeUi.ACCENT,
                58,
                1f,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM
        );
        middleMetricLabel = middle.label;
        middleMetricText = middle.value;
        middleMetricText.setTextScaleX(0.65f);
        middleMetricUnit = middle.unit;
        addMetricDivider(metrics);
        MetricViews last = addMetricGroup(
                metrics,
                "",
                "00:00",
                "",
                PowerTraceView.DURATION,
                BridgeUi.TEXT,
                58,
                1.08f,
                Gravity.END | Gravity.BOTTOM
        );
        finalMetricLabel = last.label;
        finalMetricText = last.value;
        finalMetricText.setTextScaleX(0.65f);
        finalMetricUnit = last.unit;
        metrics.setPadding(0, 0, dp(21), 0);

        statusText = BridgeUi.text(context, "", 10, BridgeUi.MUTED);
        hideStatus = () -> statusText.setVisibility(GONE);
        statusText.setSingleLine(true);
        statusText.setVisibility(GONE);
        content.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(18)
        ));

        actionArea = new LinearLayout(context);
        actionArea.setOrientation(LinearLayout.VERTICAL);
        actionArea.setGravity(Gravity.BOTTOM);
        actionArea.setPadding(0, 0, dp(21), 0);
        content.addView(actionArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(84)
        ));

        focus = context.getSharedPreferences("c1_mini", Context.MODE_PRIVATE)
                .getInt("primary_metric", PowerTraceView.POWER);
        selectFocus(focus, false);
        renderState();
    }

    void setDeviceMode(boolean bound, String serial) {
        deviceBound = bound;
        headerTitle.setText(bound ? "C1 BRIDGE" : "第一台设备 · 只读");
        modeDivider.setVisibility(bound ? VISIBLE : GONE);
        modeText.setVisibility(bound ? VISIBLE : GONE);
        resistanceScale.setVisibility(bound ? VISIBLE : GONE);
        FrameLayout.LayoutParams numberParams = (FrameLayout.LayoutParams)
                heroNumberRow.getLayoutParams();
        numberParams.rightMargin = dp(bound ? 44 : 0);
        heroNumberRow.setLayoutParams(numberParams);
        FrameLayout.LayoutParams traceParams = (FrameLayout.LayoutParams)
                powerTrace.getLayoutParams();
        traceParams.rightMargin = dp(bound ? 75 : 0);
        powerTrace.setLayoutParams(traceParams);
        renderState();
    }

    void setBridgeState(boolean running, boolean connected, boolean authorized) {
        bridgeRunning = running;
        bikeConnected = connected;
        controlAuthorized = authorized;
        renderState();
    }

    void setMetrics(KirinFrameParser.Metrics metrics) {
        power = metrics.powerWatts;
        cadence = metrics.cadenceRpm;
        durationSeconds = metrics.durationSeconds;
        distanceMeters = metrics.distanceMeters;
        calories = metrics.calories;
        powerTrace.addSample(power, cadence, durationSeconds, distanceMeters, calories);
        cadenceText.setText(String.valueOf(cadence));
        if (metrics.resistance > 0) {
            resistance = metrics.resistance;
            resistanceScale.setValue(resistance);
            middleMetricText.setText(String.valueOf(resistance));
        }
        if (metrics.status > 0) {
            trainingStatus = metrics.status;
        }
        updateSecondaryMetrics();
        updatePrimary();
        renderActions();
    }

    void setMaxResistance(int maximum) {
        resistanceScale.setMaximum(maximum);
    }

    void setResistance(int resistance) {
        this.resistance = resistance;
        middleMetricText.setText(String.valueOf(resistance));
        resistanceScale.setValue(resistance);
    }

    void setTrainingStatus(int status) {
        trainingStatus = status;
        renderActions();
    }

    void setControlPending(boolean pending) {
        controlPending = pending;
        renderState();
    }

    void setStatus(String status) {
        statusText.removeCallbacks(hideStatus);
        statusText.setText(status);
        statusText.setVisibility(status == null || status.isEmpty() ? GONE : VISIBLE);
        statusText.announceForAccessibility(status);
        if (status != null && !status.isEmpty()) {
            statusText.postDelayed(hideStatus, 4200);
        }
    }

    void setWatchStatus(String status) {
        setContentDescription("Apple 功率计输出：" + status);
    }

    void clearMetrics() {
        power = 0;
        cadence = 0;
        durationSeconds = 0;
        distanceMeters = 0;
        calories = 0;
        cadenceText.setText("0");
        middleMetricText.setText(String.valueOf(resistance));
        updateSecondaryMetrics();
        updatePrimary();
        powerTrace.clear();
    }

    private boolean handleMetricGesture(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            gestureDownX = event.getX();
            gestureDownY = event.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float deltaX = event.getX() - gestureDownX;
            float deltaY = event.getY() - gestureDownY;
            if (Math.abs(deltaX) > dp(34) && Math.abs(deltaX) > Math.abs(deltaY)) {
                selectFocus(focus + (deltaX < 0 ? 1 : -1), true);
            } else if (Math.abs(deltaX) < dp(12) && Math.abs(deltaY) < dp(12)) {
                view.performClick();
            }
            return true;
        }
        return event.getActionMasked() == MotionEvent.ACTION_MOVE;
    }

    private void selectFocus(int requestedFocus, boolean animate) {
        focus = (requestedFocus % METRIC_COUNT + METRIC_COUNT) % METRIC_COUNT;
        powerTrace.setMode(focus);
        getContext().getSharedPreferences("c1_mini", Context.MODE_PRIVATE)
                .edit()
                .putInt("primary_metric", focus)
                .apply();
        updatePrimary();
        if (animate && BridgeUi.animationsEnabled()) {
            primaryMetric.animate().cancel();
            primaryMetric.setAlpha(0.52f);
            primaryMetric.setTranslationX(dp(requestedFocus > focus ? 8 : -8));
            primaryMetric.animate().alpha(1f).translationX(0).setDuration(170).start();
        }
    }

    private void updatePrimary() {
        switch (focus) {
            case PowerTraceView.CADENCE:
                setPrimaryText(String.valueOf(cadence), "RPM", 370);
                break;
            case PowerTraceView.DURATION:
                setPrimaryText(formatDuration(durationSeconds), "", 300);
                break;
            case PowerTraceView.DISTANCE:
                setPrimaryText(String.format(Locale.US, "%.2f", distanceMeters / 1_000f), "KM", 270);
                break;
            case PowerTraceView.CALORIES:
                setPrimaryText(String.valueOf(calories), "KCAL", 345);
                break;
            case PowerTraceView.POWER:
            default:
                setPrimaryText(String.valueOf(power), "W", 380);
                break;
        }
    }

    private void setPrimaryText(String value, String unit, float sizeSp) {
        primaryDisplay = value;
        float scaleX = value.length() >= 7
                ? 0.48f
                : value.length() >= 5
                ? 0.55f
                : value.length() == 4
                ? 0.57f
                : value.length() == 3
                ? 0.62f
                : value.length() == 2 ? 0.68f : 0.77f;
        float unitSizeSp = unit.length() == 1 ? 68 : unit.length() <= 3 ? 42 : 32;
        primaryMetric.setMetric(value, unit, sizeSp, scaleX, unitSizeSp);
    }

    private void updateSecondaryMetrics() {
        if (deviceBound) {
            middleMetricLabel.setText("阻力");
            middleMetricText.setText(String.valueOf(resistance));
            middleMetricText.setTextColor(BridgeUi.ACCENT);
            middleMetricUnit.setText("");
            middleMetricUnit.setVisibility(GONE);
            finalMetricLabel.setText("");
            finalMetricLabel.setVisibility(GONE);
            setFinalMetricValue(formatDuration(durationSeconds));
            finalMetricUnit.setText("");
            finalMetricUnit.setVisibility(GONE);
            return;
        }
        middleMetricLabel.setText("阻力");
        middleMetricText.setText(String.valueOf(resistance));
        middleMetricText.setTextColor(BridgeUi.ACCENT);
        middleMetricUnit.setVisibility(GONE);
        finalMetricLabel.setText("");
        finalMetricLabel.setVisibility(GONE);
        setFinalMetricValue(String.format(Locale.US, "%.2f", distanceMeters / 1_000f));
        finalMetricUnit.setText("KM");
        finalMetricUnit.setVisibility(VISIBLE);
    }

    private void renderState() {
        int dotColor = bikeConnected
                ? BridgeUi.ACCENT
                : bridgeRunning ? BridgeUi.MUTED : BridgeUi.FAINT;
        ((GradientDrawable) connectionDot.getBackground()).setColor(dotColor);
        connectionText.setText(bikeConnected ? "已连接" : bridgeRunning ? "连接中" : "未连接");
        connectionText.setTextColor(bikeConnected ? BridgeUi.TEXT : BridgeUi.MUTED);
        resistanceScale.setEnabled(
                deviceBound && bridgeRunning && controlAuthorized && !controlPending
        );
        updateSecondaryMetrics();
        renderActions();
    }

    private void renderActions() {
        actionArea.removeAllViews();
        if (!bridgeRunning) {
            actionArea.addView(lineAction("开始桥接", "→", listener::onStartBridge), matchWrap());
            if (!deviceBound) {
                TextView manage = BridgeUi.textButton(getContext(), "管理设备");
                manage.setTextColor(BridgeUi.MUTED);
                manage.setOnClickListener(view -> listener.onManageDevice());
                actionArea.addView(manage, matchWrap());
            }
            return;
        }
        if (!deviceBound || !controlAuthorized) {
            actionArea.addView(lineAction("停止桥接", "□", listener::onStopBridge), matchWrap());
            if (!deviceBound) {
                TextView manage = BridgeUi.textButton(getContext(), "管理设备");
                manage.setOnClickListener(view -> listener.onManageDevice());
                actionArea.addView(manage, matchWrap());
            }
            return;
        }
        if (trainingStatus == C1MiniClient.TRAINING_STATUS_TRAINING) {
            addTrainingPair(
                    "Ⅱ   暂停",
                    () -> listener.onTrainingStatusSelected(C1MiniClient.TRAINING_STATUS_PAUSED),
                    "□   停止",
                    () -> listener.onTrainingStatusSelected(C1MiniClient.TRAINING_STATUS_IDLE)
            );
        } else if (trainingStatus == C1MiniClient.TRAINING_STATUS_PAUSED) {
            addTrainingPair(
                    "▷   继续",
                    () -> listener.onTrainingStatusSelected(C1MiniClient.TRAINING_STATUS_TRAINING),
                    "□   停止",
                    () -> listener.onTrainingStatusSelected(C1MiniClient.TRAINING_STATUS_IDLE)
            );
        } else {
            addTrainingPair(
                    "▷   开始训练",
                    () -> listener.onTrainingStatusSelected(C1MiniClient.TRAINING_STATUS_TRAINING),
                    "停止桥接",
                    listener::onStopBridge
            );
        }
    }

    private void addTrainingPair(
            String firstLabel,
            Runnable firstAction,
            String secondLabel,
            Runnable secondAction
    ) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView first = outlineAction(firstLabel, firstAction);
        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        firstParams.rightMargin = dp(12);
        row.addView(first, firstParams);
        TextView second = plainAction(secondLabel, secondAction);
        row.addView(second, new LinearLayout.LayoutParams(0, dp(54), 0.9f));
        actionArea.addView(row, matchWrap());
    }

    private TextView outlineAction(String label, Runnable action) {
        TextView view = BridgeUi.text(getContext(), label, 18, BridgeUi.ACCENT);
        view.setTypeface(BridgeUi.DISPLAY);
        view.setGravity(Gravity.CENTER);
        view.setEnabled(!controlPending);
        view.setAlpha(controlPending ? 0.45f : 1f);
        view.setBackground(BridgeUi.ripple(
                getContext(),
                Color.TRANSPARENT,
                BridgeUi.ACCENT,
                4
        ));
        view.setOnClickListener(target -> action.run());
        BridgeUi.addPressMotion(view);
        return view;
    }

    private TextView plainAction(String label, Runnable action) {
        TextView view = BridgeUi.text(getContext(), label, 18, BridgeUi.MUTED);
        view.setTypeface(BridgeUi.DISPLAY);
        view.setGravity(Gravity.CENTER);
        view.setEnabled(!controlPending);
        view.setAlpha(controlPending ? 0.45f : 1f);
        view.setOnClickListener(target -> action.run());
        BridgeUi.addPressMotion(view);
        return view;
    }

    private View lineAction(String label, String icon, Runnable action) {
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(7), 0, dp(5), 0);
        row.setBackground(BridgeUi.ripple(
                getContext(),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                0
        ));
        TextView copy = BridgeUi.text(getContext(), label, 18, BridgeUi.TEXT);
        copy.setTypeface(BridgeUi.DISPLAY);
        row.addView(copy, new LinearLayout.LayoutParams(0, dp(53), 1f));
        TextView arrow = BridgeUi.text(getContext(), icon, 23, BridgeUi.TEXT);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(42), dp(53)));
        container.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(53)
        ));
        View divider = BridgeUi.divider(getContext());
        container.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        ));
        container.setEnabled(!controlPending);
        container.setAlpha(controlPending ? 0.45f : 1f);
        row.setOnClickListener(view -> action.run());
        BridgeUi.addPressMotion(row);
        return container;
    }

    private MetricViews addMetricGroup(
            LinearLayout row,
            String label,
            String value,
            String unit,
            int targetFocus,
            int valueColor,
            float valueSizeSp,
            float weight,
            int contentGravity
    ) {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(contentGravity);
        int horizontalGravity = Gravity.getAbsoluteGravity(
                contentGravity,
                getLayoutDirection()
        ) & Gravity.HORIZONTAL_GRAVITY_MASK;
        int rightPadding = horizontalGravity == Gravity.RIGHT ? 18 : 0;
        group.setPadding(0, 0, dp(rightPadding), dp(8));
        row.addView(group, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                weight
        ));
        TextView caption = BridgeUi.text(getContext(), label, 14, BridgeUi.MUTED);
        caption.setTypeface(BridgeUi.MEDIUM);
        caption.setVisibility(label.isEmpty() ? GONE : VISIBLE);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        captionParams.gravity = Gravity.BOTTOM;
        captionParams.rightMargin = dp(label.isEmpty() ? 0 : 7);
        group.addView(caption, captionParams);
        TextView number = BridgeUi.text(getContext(), value, valueSizeSp, valueColor);
        number.setTypeface(BridgeUi.DISPLAY);
        number.setSingleLine(true);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        numberParams.gravity = Gravity.BOTTOM;
        group.addView(number, numberParams);
        TextView suffix = BridgeUi.text(getContext(), unit, 14, BridgeUi.MUTED);
        suffix.setTypeface(BridgeUi.DISPLAY);
        LinearLayout.LayoutParams suffixParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        suffixParams.gravity = Gravity.BOTTOM;
        suffixParams.leftMargin = dp(5);
        suffix.setVisibility(unit.isEmpty() ? GONE : VISIBLE);
        group.addView(suffix, suffixParams);
        if (targetFocus >= 0) {
            group.setClickable(true);
            group.setOnClickListener(view -> selectFocus(targetFocus, true));
            BridgeUi.addPressMotion(group);
        }
        return new MetricViews(caption, number, suffix);
    }

    private void setFinalMetricValue(String value) {
        finalMetricText.setTextSize(value.length() > 5 ? 42 : 58);
        finalMetricText.setText(value);
    }

    private void addMetricDivider(LinearLayout row) {
        View divider = new View(getContext());
        divider.setBackgroundColor(BridgeUi.RULE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(44));
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        row.addView(divider, params);
    }

    private int dp(float value) {
        return BridgeUi.dp(getContext(), value);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static String formatDuration(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static final class MetricViews {
        final TextView label;
        final TextView value;
        final TextView unit;

        MetricViews(TextView label, TextView value, TextView unit) {
            this.label = label;
            this.value = value;
            this.unit = unit;
        }
    }
}
