package dev.c1bridge.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Connects to the C1 Mini and issues the verified Kirin requests directly. */
public final class C1MiniClient {
    public interface Listener {
        void onBikeStatus(String status);

        void onMetrics(KirinFrameParser.Metrics metrics);

        void onBikeConfig(KirinFrameParser.CycleConfig config);

        void onResistanceChanged(
                int resistance,
                boolean changedByDevice,
                boolean changeFinished
        );

        void onControlAuthorization(boolean authorized, int coapCode);

        void onResistanceCommandResult(boolean accepted, int coapCode);

        void onTrainingCommandResult(int status, boolean accepted, int coapCode);

        void onBikeDisconnected();
    }

    private static final UUID KIRIN_SERVICE = uuid16(0x00FF);
    private static final UUID KIRIN_WRITE = uuid16(0xFF01);
    private static final UUID KIRIN_NOTIFY = uuid16(0xFF02);
    private static final UUID CCCD = uuid16(0x2902);
    private static final long SCAN_TIMEOUT_MS = 20_000L;
    private static final long CONNECT_TIMEOUT_MS = 20_000L;
    private static final long HANDSHAKE_TIMEOUT_MS = 5_000L;
    private static final long HANDSHAKE_RETRY_DELAY_MS = 5_000L;
    private static final long AUTHORIZATION_TIMEOUT_MS = 3_000L;
    private static final long REQUEST_TIMEOUT_MS = 4_000L;
    private static final long POLL_INTERVAL_MS = 1_000L;
    private static final float DEFAULT_USER_WEIGHT_KG = 60.0f;
    public static final int TRAINING_STATUS_IDLE = 1;
    public static final int TRAINING_STATUS_TRAINING = 3;
    public static final int TRAINING_STATUS_PAUSED = 4;
    private static final int CONTROL_NONE = 0;
    private static final int CONTROL_RESISTANCE = 1;
    private static final int CONTROL_TRAINING = 2;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final KirinFrameParser parser = new KirinFrameParser();
    private final C1DeviceSelector deviceSelector;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private boolean scanning;
    private boolean running;
    private boolean subscribed;
    private boolean sessionReady;
    private boolean controlAuthorized;
    private boolean dataReadsStarted;
    private boolean configurationPending;
    private boolean pollPending;
    private boolean controlPending;
    private Integer queuedResistance;
    private Integer queuedTrainingStatus;
    private int pendingControlType;
    private int pendingControlValue;
    private String pendingRequestName;
    private int bcpSequence;
    private int messageId;
    private int requestId;
    private int consecutiveWriteFailures;
    private int maxResistance = 18;
    private final String bridgeUserId;
    private final String bridgeDeviceId;

    private final Runnable scanTimeout = () -> {
        if (!scanning) {
            return;
        }
        stopScan();
        running = false;
        report("未发现 C1 Mini；踩动踏板唤醒单车后重试");
    };

    private final Runnable connectTimeout = () -> {
        if (!running || gatt == null || subscribed) {
            return;
        }
        closeGatt();
        report("C1 Mini 连接超时，重新扫描广播…");
        startScan();
    };

    private final Runnable handshakeTimeout = () -> {
        if (running && subscribed && !sessionReady) {
            recoverUnresponsiveSession("Kirin 1/1 握手没有响应");
        }
    };

    private final Runnable authorizationTimeout = () -> {
        if (running && sessionReady && !dataReadsStarted) {
            recoverUnresponsiveSession("Kirin 控制授权没有响应");
        }
    };

    private final Runnable requestTimeout = () -> {
        if (running && (configurationPending || pollPending || controlPending)) {
            recoverUnresponsiveSession(
                    (pendingRequestName == null ? "Kirin 请求" : pendingRequestName) + "没有响应"
            );
        }
    };

    private final Runnable pollTask = this::dispatchNextRequest;

    public C1MiniClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        deviceSelector = new C1DeviceSelector(this.context);
        bridgeUserId = deviceSelector.getOrCreateBridgeUserId();
        bridgeDeviceId = deviceSelector.getOrCreateBridgeDeviceId();
        long seed = SystemClock.elapsedRealtime();
        messageId = (int) seed & 0xFFFF;
        requestId = (int) seed;
    }

    @SuppressLint("MissingPermission")
    public boolean start() {
        if (running) {
            return true;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            report("蓝牙未开启");
            return false;
        }
        running = true;
        startScan();
        return true;
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        boolean wasRunning = running;
        running = false;
        stopScan();
        closeGatt();
        if (wasRunning) {
            report("C1 Mini 读取已停止");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isControlAuthorized() {
        return controlAuthorized;
    }

    public String getSuggestedDeviceSn() {
        return deviceSelector.getSuggestedSn();
    }

    public String getBoundDeviceSn() {
        return deviceSelector.getBoundSn();
    }

    public boolean isDeviceBound() {
        return deviceSelector.getBoundSn() != null;
    }

    public boolean bindDeviceSn(String serial) {
        if (!deviceSelector.bind(serial)) {
            return false;
        }
        restartAfterSelectionChange();
        return true;
    }

    public void clearDeviceBinding() {
        deviceSelector.clearBinding();
        restartAfterSelectionChange();
    }

    public boolean setResistance(int resistance) {
        if (resistance < 1 || resistance > maxResistance
                || !isDeviceBound() || !controlAuthorized) {
            return false;
        }
        queuedResistance = resistance;
        dispatchNextRequest();
        return true;
    }

    public boolean setTrainingStatus(int status) {
        if (!isTrainingStatusSupported(status)
                || !isDeviceBound() || !controlAuthorized) {
            return false;
        }
        queuedTrainingStatus = status;
        dispatchNextRequest();
        return true;
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!running || scanning || adapter == null) {
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            running = false;
            report("BLE 扫描器不可用");
            return;
        }
        scanning = true;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(null, settings, scanCallback);
        mainHandler.removeCallbacks(scanTimeout);
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        report(isDeviceBound()
                ? "正在查找已绑定 SN 的 C1 Mini…"
                : "正在查找第一台 C1 Mini（只读模式）…");
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        mainHandler.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) {
            scanner.stopScan(scanCallback);
        }
        scanning = false;
    }

    @SuppressLint("MissingPermission")
    private boolean connectAddress(String address, String status) {
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            stopScan();
            closeGatt();
            report(status);
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            mainHandler.removeCallbacks(connectTimeout);
            mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void resetSessionState() {
        subscribed = false;
        sessionReady = false;
        controlAuthorized = false;
        dataReadsStarted = false;
        configurationPending = false;
        pollPending = false;
        controlPending = false;
        queuedResistance = null;
        queuedTrainingStatus = null;
        pendingControlType = CONTROL_NONE;
        pendingControlValue = 0;
        pendingRequestName = null;
        writeCharacteristic = null;
        notifyCharacteristic = null;
        consecutiveWriteFailures = 0;
        bcpSequence = 0;
        requestId = 0;
        mainHandler.removeCallbacks(handshakeTimeout);
        mainHandler.removeCallbacks(authorizationTimeout);
        mainHandler.removeCallbacks(requestTimeout);
        mainHandler.removeCallbacks(pollTask);
        mainHandler.post(listener::onBikeDisconnected);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        mainHandler.removeCallbacks(connectTimeout);
        mainHandler.removeCallbacks(pollTask);
        BluetoothGatt current = gatt;
        gatt = null;
        if (current != null) {
            current.disconnect();
            current.close();
        }
        resetSessionState();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            String deviceName = result.getDevice().getName();
            if (!running || !deviceSelector.accepts(result.getScanRecord(), deviceName)) {
                return;
            }
            String serial = deviceSelector.readSerial(result.getScanRecord());
            String address = result.getDevice().getAddress();
            String status;
            if (isDeviceBound()) {
                status = "完整 SN 匹配，正在连接已绑定 C1 Mini…";
            } else {
                status = "发现第一台 C1 Mini"
                        + (serial == null ? "" : "（SN " + serial + "）")
                        + "，正在建立只读连接…";
            }
            connectAddress(address, status);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            running = false;
            report("BLE 扫描失败，错误码 " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
            if (bluetoothGatt != gatt) {
                bluetoothGatt.close();
                return;
            }
            if (status == BluetoothGatt.GATT_SUCCESS
                    && newState == BluetoothProfile.STATE_CONNECTED) {
                mainHandler.removeCallbacks(connectTimeout);
                report("C1 Mini GATT 已连接，正在发现服务…");
                bluetoothGatt.discoverServices();
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED && running) {
                closeGatt();
                report("C1 Mini GATT 已断开，重新扫描…");
                mainHandler.postDelayed(C1MiniClient.this::startScan, 1_000L);
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report("C1 Mini 服务发现失败，状态 " + status);
                return;
            }
            BluetoothGattService service = bluetoothGatt.getService(KIRIN_SERVICE);
            if (service == null) {
                report("C1 Mini 未提供 Kirin 0x00FF 服务");
                return;
            }
            writeCharacteristic = service.getCharacteristic(KIRIN_WRITE);
            notifyCharacteristic = service.getCharacteristic(KIRIN_NOTIFY);
            if (notifyCharacteristic == null) {
                notifyCharacteristic = writeCharacteristic;
            }
            if (writeCharacteristic == null || notifyCharacteristic == null) {
                report("C1 Mini 未提供 Kirin 写入/通知特征");
                return;
            }
            if (!bluetoothGatt.requestMtu(185)) {
                enableNotifications(bluetoothGatt);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt bluetoothGatt, int mtu, int status) {
            enableNotifications(bluetoothGatt);
        }

        @Override
        public void onDescriptorWrite(
                BluetoothGatt bluetoothGatt,
                BluetoothGattDescriptor descriptor,
                int status
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report("Kirin 通知订阅失败，状态 " + status);
                return;
            }
            subscribed = true;
            report("Kirin 通知已订阅，正在执行 1/1 握手…");
            sendFrame(KirinFrameCodec.buildGetWithPayload(
                    nextBcpSequence(),
                    nextMessageId(),
                    nextRequestId(),
                    "1/1",
                    createHandshakePayload(bridgeDeviceId)
            ));
            mainHandler.removeCallbacks(handshakeTimeout);
            mainHandler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS);
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic
        ) {
            handleNotification(characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value
        ) {
            handleNotification(value);
        }
    };

    @SuppressLint("MissingPermission")
    private void enableNotifications(BluetoothGatt bluetoothGatt) {
        if (bluetoothGatt != gatt || notifyCharacteristic == null) {
            return;
        }
        if (!bluetoothGatt.setCharacteristicNotification(notifyCharacteristic, true)) {
            report("Kirin 本地通知注册失败");
            return;
        }
        BluetoothGattDescriptor cccd = notifyCharacteristic.getDescriptor(CCCD);
        if (cccd == null) {
            report("Kirin 特征没有 CCCD，无法订阅");
            return;
        }
        boolean accepted;
        if (Build.VERSION.SDK_INT >= 33) {
            accepted = bluetoothGatt.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS;
        } else {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            accepted = bluetoothGatt.writeDescriptor(cccd);
        }
        if (!accepted) {
            report("Kirin CCCD 写入未被 Android 接受");
        }
    }

    @SuppressLint("MissingPermission")
    private boolean sendFrame(byte[] frame) {
        BluetoothGatt currentGatt = gatt;
        BluetoothGattCharacteristic currentWrite = writeCharacteristic;
        if (!running || !subscribed || currentGatt == null || currentWrite == null) {
            return false;
        }
        boolean accepted;
        if (Build.VERSION.SDK_INT >= 33) {
            accepted = currentGatt.writeCharacteristic(
                    currentWrite,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothStatusCodes.SUCCESS;
        } else {
            currentWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            currentWrite.setValue(frame);
            accepted = currentGatt.writeCharacteristic(currentWrite);
        }
        if (accepted) {
            consecutiveWriteFailures = 0;
        } else if (++consecutiveWriteFailures == 3) {
            report("Kirin 请求连续写入失败");
        }
        return accepted;
    }

    private void handleNotification(byte[] value) {
        KirinFrameParser.Result result = parser.parse(value);
        if (result == null) {
            return;
        }
        mainHandler.post(() -> handleResult(result));
    }

    private void handleResult(KirinFrameParser.Result result) {
        if (result.handshakeComplete && !sessionReady) {
            sessionReady = true;
            mainHandler.removeCallbacks(handshakeTimeout);
            if (isDeviceBound()) {
                report("Kirin 握手完成；正在初始化本地控制授权…");
                sendControlAuthorization();
                mainHandler.removeCallbacks(authorizationTimeout);
                mainHandler.postDelayed(authorizationTimeout, AUTHORIZATION_TIMEOUT_MS);
            } else {
                controlAuthorized = false;
                report("Kirin 握手完成；未绑定 SN，继续只读实时数据");
                startDataReads();
            }
        }
        if (result.authorizationCoapCode != null) {
            mainHandler.removeCallbacks(authorizationTimeout);
            controlAuthorized = result.authorizationCoapCode == 0x45;
            listener.onControlAuthorization(
                    controlAuthorized,
                    result.authorizationCoapCode
            );
            report(controlAuthorized
                    ? "Kirin 握手和控制授权完成；正在读取 106/7 实时数据"
                    : "控制授权被设备拒绝；继续以只读模式读取实时数据");
            startDataReads();
        }
        if (result.metrics != null) {
            if (pollPending) {
                pollPending = false;
                finishActiveRequest();
            }
            listener.onMetrics(result.metrics);
            scheduleNextRequest();
        }
        if (result.cycleConfig != null) {
            if (configurationPending) {
                configurationPending = false;
                finishActiveRequest();
            }
            if (result.cycleConfig.maxResistance > 0) {
                maxResistance = result.cycleConfig.maxResistance;
            }
            listener.onBikeConfig(result.cycleConfig);
            dispatchNextRequest();
        }
        if (result.resistance != null) {
            listener.onResistanceChanged(
                    result.resistance,
                    result.resistanceChangedByDevice,
                    result.resistanceChangeFinished
            );
        }
        if (result.controlCoapCode != null
                && controlPending
                && pendingControlType == CONTROL_RESISTANCE) {
            controlPending = false;
            pendingControlType = CONTROL_NONE;
            pendingControlValue = 0;
            finishActiveRequest();
            listener.onResistanceCommandResult(
                    result.controlCoapCode == 0x45,
                    result.controlCoapCode
            );
            mainHandler.postDelayed(this::dispatchNextRequest, 250L);
        }
        if (result.trainingCoapCode != null
                && controlPending
                && pendingControlType == CONTROL_TRAINING) {
            int requestedStatus = pendingControlValue;
            controlPending = false;
            pendingControlType = CONTROL_NONE;
            pendingControlValue = 0;
            finishActiveRequest();
            listener.onTrainingCommandResult(
                    requestedStatus,
                    result.trainingCoapCode == 0x45,
                    result.trainingCoapCode
            );
            mainHandler.postDelayed(this::dispatchNextRequest, 250L);
        }
    }

    private void sendControlAuthorization() {
        byte[] payload = KirinPayloadCodec.buildUserInfo(
                bridgeUserId,
                bridgeDeviceId,
                DEFAULT_USER_WEIGHT_KG,
                System.currentTimeMillis() / 1_000L
        );
        sendFrame(KirinFrameCodec.buildPut(
                nextBcpSequence(),
                nextMessageId(),
                nextRequestId(),
                "106/3",
                payload
        ));
    }

    private void startDataReads() {
        if (dataReadsStarted) {
            return;
        }
        dataReadsStarted = true;
        configurationPending = sendFrame(KirinFrameCodec.buildGet(
                nextBcpSequence(),
                nextMessageId(),
                nextRequestId(),
                "106/5"
        ));
        if (configurationPending) {
            startRequestTimeout("Kirin 106/5 配置请求");
        } else {
            recoverUnresponsiveSession("Kirin 106/5 配置请求写入失败");
        }
    }

    private void dispatchNextRequest() {
        if (!running || !sessionReady || !dataReadsStarted
                || configurationPending || pollPending || controlPending) {
            return;
        }
        if (queuedTrainingStatus != null && controlAuthorized) {
            int target = queuedTrainingStatus;
            queuedTrainingStatus = null;
            controlPending = sendFrame(KirinFrameCodec.buildPut(
                    nextBcpSequence(),
                    nextMessageId(),
                    nextRequestId(),
                    "106/4",
                    KirinPayloadCodec.buildTrainingStatus(target)
            ));
            if (controlPending) {
                pendingControlType = CONTROL_TRAINING;
                pendingControlValue = target;
                startRequestTimeout("Kirin 106/4 训练状态请求");
            } else {
                queuedTrainingStatus = target;
                mainHandler.postDelayed(this::dispatchNextRequest, 250L);
            }
            return;
        }
        if (queuedResistance != null && controlAuthorized) {
            int target = queuedResistance;
            queuedResistance = null;
            controlPending = sendFrame(KirinFrameCodec.buildPut(
                    nextBcpSequence(),
                    nextMessageId(),
                    nextRequestId(),
                    "106/6",
                    KirinPayloadCodec.buildResistance(target)
            ));
            if (controlPending) {
                pendingControlType = CONTROL_RESISTANCE;
                pendingControlValue = target;
                startRequestTimeout("Kirin 106/6 阻力请求");
            } else {
                queuedResistance = target;
                mainHandler.postDelayed(this::dispatchNextRequest, 250L);
            }
            return;
        }
        pollPending = sendFrame(KirinFrameCodec.buildGet(
                nextBcpSequence(),
                nextMessageId(),
                nextRequestId(),
                "106/7"
        ));
        if (pollPending) {
            startRequestTimeout("Kirin 106/7 实时数据请求");
        } else {
            mainHandler.postDelayed(pollTask, 250L);
        }
    }

    private void scheduleNextRequest() {
        mainHandler.removeCallbacks(pollTask);
        mainHandler.postDelayed(
                pollTask,
                queuedResistance == null && queuedTrainingStatus == null
                        ? POLL_INTERVAL_MS
                        : 0L
        );
    }

    private void startRequestTimeout(String requestName) {
        pendingRequestName = requestName;
        mainHandler.removeCallbacks(requestTimeout);
        mainHandler.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS);
    }

    private void finishActiveRequest() {
        pendingRequestName = null;
        mainHandler.removeCallbacks(requestTimeout);
    }

    private void recoverUnresponsiveSession(String reason) {
        if (!running) {
            return;
        }
        report(reason + "；等待单车释放旧会话后自动重连…");
        closeGatt();
        mainHandler.postDelayed(this::startScan, HANDSHAKE_RETRY_DELAY_MS);
    }

    private int nextBcpSequence() {
        int value = bcpSequence;
        bcpSequence = (bcpSequence + 1) & 0x1FFF;
        return value;
    }

    private int nextMessageId() {
        int value = messageId;
        messageId = (messageId + 1) & 0xFFFF;
        return value;
    }

    private int nextRequestId() {
        int value = requestId;
        requestId++;
        return value;
    }

    private static byte[] createHandshakePayload(String clientId) {
        byte[] text = clientId.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[text.length + 1];
        System.arraycopy(text, 0, payload, 0, text.length);
        return payload;
    }

    private static boolean isTrainingStatusSupported(int status) {
        return status == TRAINING_STATUS_IDLE
                || status == TRAINING_STATUS_TRAINING
                || status == TRAINING_STATUS_PAUSED;
    }

    private void restartAfterSelectionChange() {
        if (!running) {
            return;
        }
        stop();
        start();
    }

    private void report(String status) {
        mainHandler.post(() -> listener.onBikeStatus(status));
    }

    private static UUID uuid16(int shortUuid) {
        return UUID.fromString(String.format(
                "0000%04x-0000-1000-8000-00805f9b34fb",
                shortUuid
        ));
    }
}
