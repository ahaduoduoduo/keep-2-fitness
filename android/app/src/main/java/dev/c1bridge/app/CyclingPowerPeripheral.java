package dev.c1bridge.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal Bluetooth Cycling Power Service peripheral.
 *
 * It exposes instantaneous power plus crank revolution timing. Cycling
 * computers derive cadence from the revolution counter and event timestamps.
 */
public final class CyclingPowerPeripheral {
    public interface Listener {
        void onStatus(String status);
    }

    private static final UUID CYCLING_POWER_SERVICE = uuid16(0x1818);
    private static final UUID CYCLING_POWER_MEASUREMENT = uuid16(0x2A63);
    private static final UUID CYCLING_POWER_FEATURE = uuid16(0x2A65);
    private static final UUID SENSOR_LOCATION = uuid16(0x2A5D);
    private static final UUID CCCD = uuid16(0x2902);

    private static final int MEASUREMENT_CRANK_REVOLUTION_PRESENT = 1 << 5;
    private static final int FEATURE_CRANK_REVOLUTION_SUPPORTED = 1 << 3;
    private static final long NOTIFY_INTERVAL_MS = 250L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> subscribedDevices = new HashMap<>();

    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic measurementCharacteristic;
    private boolean running;
    private int powerWatts = 150;
    private int cadenceRpm = 90;
    private int cumulativeCrankRevolutions;
    private int lastCrankEventTime;
    private double eventClockTicks;
    private double crankFraction;
    private long lastTickNanos;

    private final Runnable notifyRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            updateCrankState();
            notifyMeasurement();
            mainHandler.postDelayed(this, NOTIFY_INTERVAL_MS);
        }
    };

    public CyclingPowerPeripheral(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void setMetrics(int powerWatts, int cadenceRpm) {
        this.powerWatts = clamp(powerWatts, -32768, 32767);
        this.cadenceRpm = clamp(cadenceRpm, 0, 300);
    }

    @SuppressLint("MissingPermission")
    public boolean start() {
        if (running) {
            return true;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null) {
            report("失败：系统没有 BluetoothManager");
            return false;
        }
        adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            report("失败：蓝牙未开启");
            return false;
        }
        if (!adapter.isMultipleAdvertisementSupported()) {
            report("失败：手机不支持 BLE 外设广播");
            return false;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            report("失败：BluetoothLeAdvertiser 不可用");
            return false;
        }

        gattServer = manager.openGattServer(context, gattCallback);
        if (gattServer == null) {
            report("失败：无法建立 GATT Server");
            return false;
        }

        BluetoothGattService service = buildCyclingPowerService();
        running = true;
        lastTickNanos = System.nanoTime();
        if (!gattServer.addService(service)) {
            report("失败：无法注册 Cycling Power Service");
            stop();
            return false;
        }
        report("正在注册标准功率计服务…");
        return true;
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        running = false;
        mainHandler.removeCallbacks(notifyRunnable);
        subscribedDevices.clear();
        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (RuntimeException ignored) {
                // Bluetooth may already be shutting down.
            }
        }
        advertiser = null;
        if (gattServer != null) {
            try {
                gattServer.clearServices();
                gattServer.close();
            } catch (RuntimeException ignored) {
                // Bluetooth may already be shutting down.
            }
        }
        gattServer = null;
        measurementCharacteristic = null;
        report("标准功率计广播已停止");
    }

    public boolean isRunning() {
        return running;
    }

    private BluetoothGattService buildCyclingPowerService() {
        BluetoothGattService service = new BluetoothGattService(
                CYCLING_POWER_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        measurementCharacteristic = new BluetoothGattCharacteristic(
                CYCLING_POWER_MEASUREMENT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0
        );
        measurementCharacteristic.addDescriptor(new BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        ));

        BluetoothGattCharacteristic feature = new BluetoothGattCharacteristic(
                CYCLING_POWER_FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        feature.setValue(ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(FEATURE_CRANK_REVOLUTION_SUPPORTED)
                .array());

        BluetoothGattCharacteristic location = new BluetoothGattCharacteristic(
                SENSOR_LOCATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        location.setValue(new byte[]{5}); // Left crank.

        service.addCharacteristic(measurementCharacteristic);
        service.addCharacteristic(feature);
        service.addCharacteristic(location);
        return service;
    }

    @SuppressLint("MissingPermission")
    private void startAdvertising() {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(CYCLING_POWER_SERVICE))
                .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
    }

    private void updateCrankState() {
        long now = System.nanoTime();
        double elapsedSeconds = Math.max(0.0, (now - lastTickNanos) / 1_000_000_000.0);
        lastTickNanos = now;
        eventClockTicks = modulo(eventClockTicks + elapsedSeconds * 1024.0, 65536.0);
        if (cadenceRpm <= 0) {
            return;
        }
        double totalRevolutions = crankFraction + elapsedSeconds * cadenceRpm / 60.0;
        int completedRevolutions = (int) Math.floor(totalRevolutions);
        crankFraction = totalRevolutions - completedRevolutions;
        if (completedRevolutions > 0) {
            cumulativeCrankRevolutions =
                    (cumulativeCrankRevolutions + completedRevolutions) & 0xFFFF;
            double revolutionPeriodTicks = 60.0 * 1024.0 / cadenceRpm;
            double lastEvent = eventClockTicks - crankFraction * revolutionPeriodTicks;
            lastCrankEventTime = ((int) Math.round(modulo(lastEvent, 65536.0))) & 0xFFFF;
        }
    }

    @SuppressLint("MissingPermission")
    private void notifyMeasurement() {
        if (gattServer == null || measurementCharacteristic == null || subscribedDevices.isEmpty()) {
            return;
        }
        byte[] value = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) MEASUREMENT_CRANK_REVOLUTION_PRESENT)
                .putShort((short) powerWatts)
                .putShort((short) cumulativeCrankRevolutions)
                .putShort((short) lastCrankEventTime)
                .array();
        measurementCharacteristic.setValue(value);
        for (BluetoothDevice device : subscribedDevices.values()) {
            if (Build.VERSION.SDK_INT >= 33) {
                gattServer.notifyCharacteristicChanged(device, measurementCharacteristic, false, value);
            } else {
                gattServer.notifyCharacteristicChanged(device, measurementCharacteristic, false);
            }
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            report("正在广播：请在 Apple Watch 蓝牙设置中查找本手机名称");
            mainHandler.removeCallbacks(notifyRunnable);
            lastTickNanos = System.nanoTime();
            mainHandler.post(notifyRunnable);
        }

        @Override
        public void onStartFailure(int errorCode) {
            report("BLE 广播失败，错误码 " + errorCode);
            stop();
        }
    };

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (!running) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report("Cycling Power Service 注册失败，状态 " + status);
                stop();
                return;
            }
            startAdvertising();
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED
                    && subscribedDevices.remove(device.getAddress()) != null) {
                report("功率计客户端已断开，继续等待连接");
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onCharacteristicReadRequest(
                BluetoothDevice device,
                int requestId,
                int offset,
                BluetoothGattCharacteristic characteristic
        ) {
            byte[] value = characteristic.getValue();
            if (value == null || offset > value.length) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                return;
            }
            byte[] response = new byte[value.length - offset];
            System.arraycopy(value, offset, response, 0, response.length);
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response);
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onDescriptorReadRequest(
                BluetoothDevice device,
                int requestId,
                int offset,
                BluetoothGattDescriptor descriptor
        ) {
            boolean enabled = subscribedDevices.containsKey(device.getAddress());
            byte[] value = enabled
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            if (offset > value.length) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                return;
            }
            byte[] response = new byte[value.length - offset];
            System.arraycopy(value, offset, response, 0, response.length);
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response);
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onDescriptorWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattDescriptor descriptor,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value
        ) {
            int responseStatus = BluetoothGatt.GATT_SUCCESS;
            if (!CCCD.equals(descriptor.getUuid()) || offset != 0 || preparedWrite) {
                responseStatus = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            } else if (matches(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                subscribedDevices.put(device.getAddress(), device);
                report("已启用功率/踏频通知：" + safeDeviceName(device));
            } else if (matches(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                subscribedDevices.remove(device.getAddress());
            } else {
                responseStatus = BluetoothGatt.GATT_FAILURE;
            }
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, responseStatus, offset, value);
            }
        }
    };

    @SuppressLint("MissingPermission")
    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? device.getAddress() : name;
        } catch (SecurityException error) {
            return device.getAddress();
        }
    }

    private void report(String status) {
        mainHandler.post(() -> listener.onStatus(status));
    }

    private static boolean matches(byte[] left, byte[] right) {
        if (left == null || left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return false;
            }
        }
        return true;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double modulo(double value, double modulus) {
        double result = value % modulus;
        return result < 0 ? result + modulus : result;
    }

    private static UUID uuid16(int shortUuid) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortUuid));
    }
}
