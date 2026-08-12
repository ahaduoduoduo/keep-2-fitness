package dev.c1bridge.app;

import android.content.Context;
import android.content.Intent;

/** Injects deterministic, human-plausible dashboard data in debug builds. */
final class DashboardPreview {
    static final String EXTRA_ENABLED = "dev.c1bridge.preview";
    static final String EXTRA_FOCUS = "dev.c1bridge.preview.focus";
    static final String EXTRA_POWER = "dev.c1bridge.preview.power";
    static final String EXTRA_CADENCE = "dev.c1bridge.preview.cadence";
    static final String EXTRA_DURATION = "dev.c1bridge.preview.duration";
    static final String EXTRA_DISTANCE = "dev.c1bridge.preview.distance";
    static final String EXTRA_CALORIES = "dev.c1bridge.preview.calories";
    static final String EXTRA_RESISTANCE = "dev.c1bridge.preview.resistance";
    static final String EXTRA_STATUS = "dev.c1bridge.preview.status";
    static final String EXTRA_ANIMATE = "dev.c1bridge.preview.animate";

    private static final int SAMPLE_COUNT = 72;
    private static final long LIVE_SAMPLE_INTERVAL_MS = 1_000L;

    private DashboardPreview() {
    }

    static boolean prepare(Context context, Intent intent) {
        if (!isEnabled(intent)) {
            return false;
        }
        context.getSharedPreferences("c1_mini", Context.MODE_PRIVATE)
                .edit()
                .putInt("primary_metric", focus(intent.getStringExtra(EXTRA_FOCUS)))
                .commit();
        return true;
    }

    static Session apply(BridgeDashboardView dashboard, Intent intent) {
        int power = integer(intent, EXTRA_POWER, 196, 0, 999);
        int cadence = integer(intent, EXTRA_CADENCE, 100, 0, 220);
        int duration = integer(intent, EXTRA_DURATION, 1458, 0, 21_599);
        int distance = integer(intent, EXTRA_DISTANCE, 21_200, 0, 99_999);
        int calories = integer(intent, EXTRA_CALORIES, 312, 0, 4_999);
        int resistance = integer(intent, EXTRA_RESISTANCE, 7, 1, 18);
        int status = integer(
                intent,
                EXTRA_STATUS,
                C1MiniClient.TRAINING_STATUS_TRAINING,
                C1MiniClient.TRAINING_STATUS_IDLE,
                C1MiniClient.TRAINING_STATUS_PAUSED
        );

        dashboard.setDeviceMode(true, "");
        dashboard.setMaxResistance(18);
        dashboard.setBridgeState(true, true, true);
        dashboard.setTrainingStatus(status);

        if (intent.getBooleanExtra(EXTRA_ANIMATE, false)) {
            injectLayoutTestHistory(
                    dashboard,
                    power,
                    cadence,
                    duration,
                    distance,
                    calories,
                    resistance,
                    status
            );
            Session layoutTest = Session.layoutTest(
                    dashboard,
                    power,
                    cadence,
                    duration,
                    distance,
                    calories,
                    resistance,
                    status
            );
            layoutTest.start();
            return layoutTest;
        }

        HumanRideSimulator simulator = new HumanRideSimulator(power, cadence, previewSeed());
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            int remaining = SAMPLE_COUNT - 1 - index;
            HumanRideSimulator.Signal signal = simulator.next();
            dashboard.setMetrics(KirinFrameParser.Metrics.preview(
                    Math.max(0, distance - remaining * 6),
                    Math.max(0, duration - remaining),
                    Math.max(0, calories - remaining / 8),
                    resistance,
                    signal.cadenceRpm,
                    signal.powerWatts,
                    status
            ));
        }
        Session liveSession = Session.live(
                dashboard,
                simulator,
                duration,
                distance,
                calories,
                resistance,
                status
        );
        liveSession.start();
        return liveSession;
    }

    private static void injectLayoutTestHistory(
            BridgeDashboardView dashboard,
            int power,
            int cadence,
            int duration,
            int distance,
            int calories,
            int resistance,
            int status
    ) {
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            int remaining = SAMPLE_COUNT - 1 - index;
            double wave = Math.sin(index * 0.58d) * 0.07d
                    + Math.sin(index * 0.17d) * 0.04d;
            int samplePower = bounded((int) Math.round(power * (1d + wave)), 0, 999);
            int sampleCadence = bounded((int) Math.round(cadence * (1d + wave * 0.42d)), 0, 220);
            if (remaining == 0) {
                samplePower = power;
                sampleCadence = cadence;
            }
            dashboard.setMetrics(KirinFrameParser.Metrics.preview(
                    Math.max(0, distance - remaining * 6),
                    Math.max(0, duration - remaining * 2),
                    Math.max(0, calories - remaining / 8),
                    resistance,
                    sampleCadence,
                    samplePower,
                    status
            ));
        }
    }

    private static long previewSeed() {
        return 0xC1B1D6EL;
    }

    private static boolean isEnabled(Intent intent) {
        return BuildConfig.DEBUG
                && intent != null
                && intent.getBooleanExtra(EXTRA_ENABLED, false);
    }

    private static int focus(String name) {
        if ("cadence".equals(name)) {
            return PowerTraceView.CADENCE;
        }
        if ("duration".equals(name)) {
            return PowerTraceView.DURATION;
        }
        if ("distance".equals(name)) {
            return PowerTraceView.DISTANCE;
        }
        if ("calories".equals(name)) {
            return PowerTraceView.CALORIES;
        }
        return PowerTraceView.POWER;
    }

    private static int integer(Intent intent, String key, int fallback, int minimum, int maximum) {
        return bounded(intent.getIntExtra(key, fallback), minimum, maximum);
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Session {
        private final BridgeDashboardView dashboard;
        private final HumanRideSimulator simulator;
        private final int resistance;
        private final int status;
        private final boolean layoutTest;
        private final int layoutPower;
        private final int layoutCadence;
        private final Runnable update;

        private int duration;
        private int distance;
        private double calories;
        private boolean running;

        private Session(
                BridgeDashboardView dashboard,
                HumanRideSimulator simulator,
                int duration,
                int distance,
                int calories,
                int resistance,
                int status,
                boolean layoutTest,
                int layoutPower,
                int layoutCadence
        ) {
            this.dashboard = dashboard;
            this.simulator = simulator;
            this.duration = duration;
            this.distance = distance;
            this.calories = calories;
            this.resistance = resistance;
            this.status = status;
            this.layoutTest = layoutTest;
            this.layoutPower = layoutPower;
            this.layoutCadence = layoutCadence;
            update = this::update;
        }

        static Session live(
                BridgeDashboardView dashboard,
                HumanRideSimulator simulator,
                int duration,
                int distance,
                int calories,
                int resistance,
                int status
        ) {
            return new Session(
                    dashboard,
                    simulator,
                    duration,
                    distance,
                    calories,
                    resistance,
                    status,
                    false,
                    0,
                    0
            );
        }

        static Session layoutTest(
                BridgeDashboardView dashboard,
                int power,
                int cadence,
                int duration,
                int distance,
                int calories,
                int resistance,
                int status
        ) {
            return new Session(
                    dashboard,
                    null,
                    duration,
                    distance,
                    calories,
                    resistance,
                    status,
                    true,
                    power,
                    cadence
            );
        }

        void start() {
            if (running) {
                return;
            }
            running = true;
            dashboard.postDelayed(update, layoutTest ? 900L : LIVE_SAMPLE_INTERVAL_MS);
        }

        void stop() {
            running = false;
            dashboard.removeCallbacks(update);
        }

        private void update() {
            if (!running) {
                return;
            }
            duration = bounded(duration + 1, 0, 21_599);
            if (layoutTest) {
                distance = bounded(distance + 10, 0, 99_999);
                calories = bounded((int) Math.round(calories) + 1, 0, 4_999);
                dashboard.setMetrics(KirinFrameParser.Metrics.preview(
                        distance,
                        duration,
                        (int) Math.round(calories),
                        resistance,
                        bounded(layoutCadence + 1, 0, 220),
                        bounded(layoutPower + 1, 0, 999),
                        status
                ));
                stop();
                return;
            }

            HumanRideSimulator.Signal signal = simulator.next();
            double metersPerSecond = 1.5d
                    + signal.cadenceRpm * 0.05d
                    + signal.powerWatts * 0.01d;
            distance = bounded(
                    distance + Math.max(0, (int) Math.round(metersPerSecond)),
                    0,
                    99_999
            );
            calories = Math.min(4_999d, calories + signal.powerWatts * 0.00095d);
            dashboard.setMetrics(KirinFrameParser.Metrics.preview(
                    distance,
                    duration,
                    (int) Math.floor(calories),
                    resistance,
                    signal.cadenceRpm,
                    signal.powerWatts,
                    status
            ));
            dashboard.postDelayed(update, LIVE_SAMPLE_INTERVAL_MS);
        }
    }
}
