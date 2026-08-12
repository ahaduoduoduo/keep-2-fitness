package dev.c1bridge.app;

import android.bluetooth.le.ScanRecord;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelUuid;
import android.util.SparseArray;

import java.util.UUID;

/** Owns local device selection and persistent bridge identifiers. */
final class C1DeviceSelector {
    private static final String PREFERENCES = "c1_mini";
    private static final String BOUND_DEVICE_SN = "bound_device_sn";
    private static final String BRIDGE_USER_ID = "bridge_user_id";
    private static final String BRIDGE_DEVICE_ID = "bridge_device_id";
    private static final ParcelUuid KIRIN_BEACON = new ParcelUuid(
            UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
    );

    private final SharedPreferences preferences;

    C1DeviceSelector(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    String getSuggestedSn() {
        String bound = getBoundSn();
        return bound == null ? BuildConfig.C1_DEFAULT_SN : bound;
    }

    String getBoundSn() {
        String value = C1SerialCodec.normalize(preferences.getString(BOUND_DEVICE_SN, ""));
        return C1SerialCodec.isValid(value) ? value : null;
    }

    boolean bind(String serial) {
        String normalized = C1SerialCodec.normalize(serial);
        if (!C1SerialCodec.isValid(normalized)) {
            return false;
        }
        preferences.edit().putString(BOUND_DEVICE_SN, normalized).apply();
        return true;
    }

    void clearBinding() {
        preferences.edit().remove(BOUND_DEVICE_SN).apply();
    }

    boolean isKirinBeacon(ScanRecord record, String deviceName) {
        return record != null && (record.getServiceData(KIRIN_BEACON) != null
                || C1SerialCodec.isC1MiniName(record.getDeviceName())
                || C1SerialCodec.isC1MiniName(deviceName));
    }

    String readSerial(ScanRecord record) {
        if (record == null) {
            return null;
        }
        SparseArray<byte[]> entries = record.getManufacturerSpecificData();
        for (int index = 0; index < entries.size(); index++) {
            String serial = C1SerialCodec.fromManufacturerData(entries.valueAt(index));
            if (serial != null) {
                return serial;
            }
        }
        return null;
    }

    boolean accepts(ScanRecord record, String deviceName) {
        if (!isKirinBeacon(record, deviceName)) {
            return false;
        }
        String bound = getBoundSn();
        return bound == null
                || bound.equals(readSerial(record))
                || C1SerialCodec.matchesAdvertisedName(bound, deviceName);
    }

    String getOrCreateIdentifier(String key, int length) {
        String existing = preferences.getString(key, null);
        if (existing != null && existing.length() == length) {
            return existing;
        }
        String generated = UUID.randomUUID().toString().replace("-", "").substring(0, length);
        preferences.edit().putString(key, generated).apply();
        return generated;
    }

    String getOrCreateBridgeUserId() {
        return getOrCreateIdentifier(BRIDGE_USER_ID, 24);
    }

    String getOrCreateBridgeDeviceId() {
        return getOrCreateIdentifier(BRIDGE_DEVICE_ID, 16);
    }
}
