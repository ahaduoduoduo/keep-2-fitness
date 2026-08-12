package dev.c1bridge.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BLUETOOTH_PERMISSION_REQUEST = 100;
    private static final int DEVICE_SETUP_REQUEST = 101;

    private CyclingPowerPeripheral peripheral;
    private C1MiniClient bikeClient;
    private BridgeDashboardView dashboard;
    private DashboardPreview.Session previewSession;
    private Integer pendingResistance;
    private Integer pendingTrainingStatus;
    private int maxResistance = 18;
    private int actualResistance = 1;
    private int trainingStatus = C1MiniClient.TRAINING_STATUS_IDLE;
    private boolean bikeConnected;
    private boolean pendingBluetoothStart;
    private boolean bluetoothPermissionRequested;
    private boolean waitingForBluetoothSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BridgeUi.configureWindow(this);
        boolean previewMode = DashboardPreview.prepare(this, getIntent());
        peripheral = new CyclingPowerPeripheral(this, this::showWatchStatus);
        bikeClient = new C1MiniClient(this, createBikeListener());
        dashboard = new BridgeDashboardView(this, createDashboardListener());
        setContentView(dashboard);
        if (previewMode) {
            previewSession = DashboardPreview.apply(dashboard, getIntent());
        } else {
            refreshDashboard();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForBluetoothSettings) {
            waitingForBluetoothSettings = false;
            if (hasBluetoothPermissions()) {
                startBikeClient();
            } else {
                dashboard.setStatus("附近设备权限仍未开放；桥接尚未启动");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (previewSession != null) {
            previewSession.stop();
        }
        bikeClient.stop();
        peripheral.stop();
        super.onDestroy();
    }

    private BridgeDashboardView.Listener createDashboardListener() {
        return new BridgeDashboardView.Listener() {
            @Override
            public void onStartBridge() {
                startBikeClient();
            }

            @Override
            public void onStopBridge() {
                stopBridge();
            }

            @Override
            public void onManageDevice() {
                openDeviceSetup();
            }

            @Override
            public void onResistanceSelected(int resistance) {
                applyResistance(resistance);
            }

            @Override
            public void onTrainingStatusSelected(int status) {
                applyTrainingStatus(status);
            }
        };
    }

    private C1MiniClient.Listener createBikeListener() {
        return new C1MiniClient.Listener() {
            @Override
            public void onBikeStatus(String status) {
                showBikeStatus(status);
            }

            @Override
            public void onMetrics(KirinFrameParser.Metrics metrics) {
                runOnUiThread(() -> {
                    bikeConnected = true;
                    peripheral.setMetrics(metrics.powerWatts, metrics.cadenceRpm);
                    if (metrics.resistance > 0) {
                        actualResistance = metrics.resistance;
                    }
                    if (metrics.status > 0) {
                        trainingStatus = metrics.status;
                    }
                    if (pendingResistance != null
                            && pendingResistance == metrics.resistance) {
                        pendingResistance = null;
                    }
                    if (pendingTrainingStatus != null
                            && pendingTrainingStatus == metrics.status) {
                        pendingTrainingStatus = null;
                    }
                    dashboard.setMetrics(metrics);
                    dashboard.setControlPending(hasPendingControl());
                    refreshDashboard();
                });
            }

            @Override
            public void onBikeConfig(KirinFrameParser.CycleConfig config) {
                runOnUiThread(() -> {
                    if (config.maxResistance > 0) {
                        maxResistance = config.maxResistance;
                        dashboard.setMaxResistance(maxResistance);
                    }
                    dashboard.setStatus(
                            "设备配置已读取 · 阻力范围 1–" + maxResistance
                    );
                });
            }

            @Override
            public void onResistanceChanged(
                    int resistance,
                    boolean changedByDevice,
                    boolean changeFinished
            ) {
                runOnUiThread(() -> {
                    actualResistance = resistance;
                    if (pendingResistance != null && pendingResistance == resistance) {
                        pendingResistance = null;
                    }
                    dashboard.setResistance(resistance);
                    dashboard.setControlPending(hasPendingControl());
                    String source = changedByDevice ? "单车端" : "远程控制";
                    dashboard.setStatus(
                            source + "设置阻力 " + resistance
                                    + (changeFinished ? " · 已完成" : "")
                    );
                });
            }

            @Override
            public void onControlAuthorization(boolean authorized, int coapCode) {
                runOnUiThread(() -> {
                    pendingResistance = null;
                    pendingTrainingStatus = null;
                    dashboard.setStatus(authorized
                            ? "单车业务会话已连接 · 控制可用"
                            : "单车拒绝控制授权 · 保持只读（CoAP "
                                    + formatCoapCode(coapCode) + "）");
                    refreshDashboard();
                });
            }

            @Override
            public void onResistanceCommandResult(boolean accepted, int coapCode) {
                runOnUiThread(() -> {
                    if (accepted && pendingResistance != null) {
                        actualResistance = pendingResistance;
                        dashboard.setResistance(actualResistance);
                    }
                    pendingResistance = null;
                    dashboard.setControlPending(hasPendingControl());
                    dashboard.setStatus(accepted
                            ? "单车已接受阻力请求"
                            : "单车拒绝阻力请求 · CoAP " + formatCoapCode(coapCode));
                });
            }

            @Override
            public void onTrainingCommandResult(int status, boolean accepted, int coapCode) {
                runOnUiThread(() -> {
                    if (accepted) {
                        trainingStatus = status;
                        dashboard.setTrainingStatus(status);
                    }
                    pendingTrainingStatus = null;
                    dashboard.setControlPending(hasPendingControl());
                    dashboard.setStatus(accepted
                            ? trainingResultText(status)
                            : "单车拒绝训练状态请求 · CoAP " + formatCoapCode(coapCode));
                });
            }

            @Override
            public void onBikeDisconnected() {
                runOnUiThread(() -> {
                    bikeConnected = false;
                    pendingResistance = null;
                    pendingTrainingStatus = null;
                    refreshDashboard();
                });
            }
        };
    }

    private void startBikeClient() {
        if (!hasBluetoothPermissions()) {
            pendingBluetoothStart = true;
            requestBluetoothPermissions();
            return;
        }
        pendingBluetoothStart = false;
        pendingResistance = null;
        pendingTrainingStatus = null;
        if (!peripheral.isRunning()) {
            peripheral.setMetrics(0, 0);
            peripheral.start();
        }
        if (!bikeClient.start()) {
            dashboard.setStatus("蓝牙不可用；请开启蓝牙后重试");
        }
        refreshDashboard();
    }

    private void stopBridge() {
        pendingResistance = null;
        pendingTrainingStatus = null;
        bikeConnected = false;
        bikeClient.stop();
        peripheral.stop();
        dashboard.clearMetrics();
        dashboard.setStatus("桥接已停止");
        refreshDashboard();
    }

    private void applyResistance(int resistance) {
        if (resistance < 1 || resistance > maxResistance) {
            dashboard.setResistance(actualResistance);
            dashboard.setStatus("阻力值超出设备范围 1–" + maxResistance);
            return;
        }
        if (!bikeClient.setResistance(resistance)) {
            dashboard.setResistance(actualResistance);
            dashboard.setStatus("单车尚未完成控制授权，阻力请求未发送");
            return;
        }
        pendingResistance = resistance;
        dashboard.setControlPending(true);
        dashboard.setStatus("正在设置阻力 " + resistance + "…");
    }

    private void applyTrainingStatus(int status) {
        if (!bikeClient.setTrainingStatus(status)) {
            dashboard.setStatus("单车尚未完成控制授权，训练状态请求未发送");
            return;
        }
        pendingTrainingStatus = status;
        dashboard.setControlPending(true);
        dashboard.setStatus(trainingPendingText(status));
    }

    private void openDeviceSetup() {
        Intent intent = new Intent(this, DeviceSetupActivity.class);
        intent.putExtra(DeviceSetupActivity.EXTRA_SUGGESTED_SN, bikeClient.getSuggestedDeviceSn());
        startActivityForResult(intent, DEVICE_SETUP_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != DEVICE_SETUP_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }
        if (data.getBooleanExtra(DeviceSetupActivity.EXTRA_USE_READ_ONLY, false)) {
            pendingResistance = null;
            pendingTrainingStatus = null;
            bikeClient.clearDeviceBinding();
            dashboard.setStatus("已切换为第一台设备只读模式");
            refreshDashboard();
            return;
        }
        String serial = data.getStringExtra(DeviceSetupActivity.EXTRA_DEVICE_SN);
        if (!bikeClient.bindDeviceSn(serial)) {
            dashboard.setStatus("设备 SN 无效，绑定未保存");
            return;
        }
        pendingResistance = null;
        pendingTrainingStatus = null;
        dashboard.setStatus("完整 SN 已保存 · 正在切换到绑定设备");
        refreshDashboard();
    }

    private void refreshDashboard() {
        if (dashboard == null || bikeClient == null) {
            return;
        }
        dashboard.setDeviceMode(bikeClient.isDeviceBound(), bikeClient.getBoundDeviceSn());
        dashboard.setMaxResistance(maxResistance);
        dashboard.setResistance(actualResistance);
        dashboard.setTrainingStatus(trainingStatus);
        dashboard.setBridgeState(
                bikeClient.isRunning(),
                bikeConnected,
                bikeClient.isControlAuthorized()
        );
        dashboard.setControlPending(hasPendingControl());
    }

    private boolean hasPendingControl() {
        return pendingResistance != null || pendingTrainingStatus != null;
    }

    private boolean hasBluetoothPermissions() {
        for (String permission : requiredBluetoothPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private List<String> requiredBluetoothPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT <= 31) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        return permissions;
    }

    private void requestBluetoothPermissions() {
        List<String> missing = new ArrayList<>();
        boolean rationaleAvailable = false;
        for (String permission : requiredBluetoothPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
                rationaleAvailable |= shouldShowRequestPermissionRationale(permission);
            }
        }
        if (missing.isEmpty()) {
            startBikeClient();
            return;
        }
        if (bluetoothPermissionRequested && !rationaleAvailable) {
            dashboard.setStatus("附近设备权限已关闭；请在系统设置中授权后返回");
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            waitingForBluetoothSettings = true;
            startActivity(intent);
            return;
        }
        bluetoothPermissionRequested = true;
        requestPermissions(
                missing.toArray(new String[0]),
                BLUETOOTH_PERMISSION_REQUEST
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != BLUETOOTH_PERMISSION_REQUEST) {
            return;
        }
        if (hasBluetoothPermissions() && pendingBluetoothStart) {
            startBikeClient();
        } else {
            pendingBluetoothStart = false;
            dashboard.setStatus(
                    "需要附近设备权限才能扫描单车；Android 12 及以下还需要定位权限"
            );
        }
    }

    private void showWatchStatus(String status) {
        runOnUiThread(() -> dashboard.setWatchStatus(status));
    }

    private void showBikeStatus(String status) {
        runOnUiThread(() -> {
            dashboard.setStatus(status);
            refreshDashboard();
        });
    }

    private static String trainingPendingText(int status) {
        if (status == C1MiniClient.TRAINING_STATUS_TRAINING) {
            return "正在更新为训练状态…";
        }
        if (status == C1MiniClient.TRAINING_STATUS_PAUSED) {
            return "正在暂停单车训练…";
        }
        return "正在停止单车训练…";
    }

    private static String trainingResultText(int status) {
        if (status == C1MiniClient.TRAINING_STATUS_TRAINING) {
            return "单车训练状态 · 进行中";
        }
        if (status == C1MiniClient.TRAINING_STATUS_PAUSED) {
            return "单车训练状态 · 已暂停";
        }
        return "单车训练状态 · 已停止";
    }

    private static String formatCoapCode(int code) {
        return String.format(Locale.US, "%d.%02d", code >>> 5, code & 0x1F);
    }
}
