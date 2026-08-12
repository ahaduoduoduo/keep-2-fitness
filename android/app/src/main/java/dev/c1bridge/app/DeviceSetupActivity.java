package dev.c1bridge.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class DeviceSetupActivity extends Activity {
    static final String EXTRA_DEVICE_SN = "device_sn";
    static final String EXTRA_USE_READ_ONLY = "use_read_only";
    static final String EXTRA_SUGGESTED_SN = "suggested_sn";
    static final int RESULT_USE_MANUAL = RESULT_FIRST_USER;

    private static final int CAMERA_PERMISSION_REQUEST = 201;
    private static final int QR_SCANNER_REQUEST = 202;

    private String suggestedSerial;
    private TextView permissionMessage;
    private TextView permissionAction;
    private boolean waitingForSettings;
    private boolean methodSelectionVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BridgeUi.configureWindow(this);
        suggestedSerial = C1SerialCodec.normalize(
                getIntent().getStringExtra(EXTRA_SUGGESTED_SN)
        );
        showMethodSelection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForSettings) {
            waitingForSettings = false;
            if (hasCameraPermission()) {
                launchScanner();
            } else {
                showPermissionError(true);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (methodSelectionVisible) {
            finish();
        } else {
            showMethodSelection();
        }
    }

    private void showMethodSelection() {
        methodSelectionVisible = true;
        LinearLayout content = page("添加设备", null);

        View topDivider = BridgeUi.divider(this);
        LinearLayout.LayoutParams topDividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        topDividerParams.topMargin = dp(31);
        content.addView(topDivider, topDividerParams);

        View scanRow = methodRow(
                BridgeIconView.QR,
                "扫描二维码",
                null
        );
        scanRow.setOnClickListener(view -> requestCameraAccess());
        content.addView(scanRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72)
        ));
        content.addView(BridgeUi.divider(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        ));

        View manualRow = methodRow(
                -1,
                "手动输入 SN",
                null
        );
        manualRow.setOnClickListener(view -> showManualEntry());
        content.addView(manualRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72)
        ));
        content.addView(BridgeUi.divider(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        ));

        permissionMessage = BridgeUi.text(this, "", 12, BridgeUi.DANGER);
        permissionMessage.setVisibility(View.GONE);
        LinearLayout.LayoutParams permissionParams = matchWrap();
        permissionParams.topMargin = dp(16);
        content.addView(permissionMessage, permissionParams);

        permissionAction = BridgeUi.textButton(this, "重新授权相机  →");
        permissionAction.setTextColor(BridgeUi.TEXT);
        permissionAction.setVisibility(View.GONE);
        permissionAction.setOnClickListener(view -> handlePermissionAction());
        LinearLayout.LayoutParams permissionActionParams = matchWrap();
        permissionActionParams.topMargin = dp(10);
        content.addView(permissionAction, permissionActionParams);

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView readOnly = BridgeUi.textButton(this, "使用第一台发现的设备（只读）");
        readOnly.setTextColor(BridgeUi.MUTED);
        readOnly.setOnClickListener(view -> returnReadOnly());
        content.addView(readOnly, matchWrap());

        TextView privacy = BridgeUi.text(
                this,
                "扫描单车二维码以绑定控制功能\n二维码只解析 sn 参数，不读取 Keep 账号，也不上传设备信息。",
                11,
                BridgeUi.MUTED
        );
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = matchWrap();
        privacyParams.topMargin = dp(8);
        content.addView(privacy, privacyParams);

        setContentView(content);
    }

    private void showManualEntry() {
        methodSelectionVisible = false;
        LinearLayout content = page("手动输入", "输入二维码链接中 sn= 后面的 16 位完整序列号。");

        TextView inputLabel = BridgeUi.label(this, "设备 SN");
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.topMargin = dp(42);
        content.addView(inputLabel, labelParams);

        EditText serialInput = new EditText(this);
        serialInput.setSingleLine(true);
        serialInput.setTextColor(BridgeUi.TEXT);
        serialInput.setHintTextColor(BridgeUi.MUTED);
        serialInput.setTextSize(22);
        serialInput.setTypeface(BridgeUi.MONO);
        serialInput.setHint("CC··············");
        serialInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        );
        serialInput.setFilters(new InputFilter[]{
                new InputFilter.AllCaps(),
                new InputFilter.LengthFilter(C1SerialCodec.SERIAL_LENGTH)
        });
        serialInput.setPadding(dp(18), dp(4), dp(18), 0);
        serialInput.setBackground(BridgeUi.shape(
                BridgeUi.SURFACE,
                BridgeUi.FAINT,
                17,
                this
        ));
        if (C1SerialCodec.isValid(suggestedSerial)) {
            serialInput.setText(suggestedSerial);
            serialInput.setSelection(serialInput.length());
        }
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62)
        );
        inputParams.topMargin = dp(12);
        content.addView(serialInput, inputParams);

        TextView error = BridgeUi.text(this, "", 12, BridgeUi.DANGER);
        error.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams errorParams = matchWrap();
        errorParams.topMargin = dp(9);
        content.addView(error, errorParams);

        TextView continueButton = BridgeUi.button(this, "继续", true);
        continueButton.setOnClickListener(view -> {
            String serial = C1SerialCodec.normalize(serialInput.getText().toString());
            if (!C1SerialCodec.isValid(serial)) {
                error.setText("需要二维码中的 16 位完整 CC 序列号");
                error.setVisibility(View.VISIBLE);
                return;
            }
            hideKeyboard(serialInput);
            showConfirmation(serial);
        });
        LinearLayout.LayoutParams continueParams = matchWrap();
        continueParams.topMargin = dp(18);
        content.addView(continueButton, continueParams);

        TextView scanInstead = BridgeUi.textButton(this, "改用二维码扫描");
        scanInstead.setOnClickListener(view -> requestCameraAccess());
        LinearLayout.LayoutParams scanInsteadParams = matchWrap();
        scanInsteadParams.topMargin = dp(8);
        content.addView(scanInstead, scanInsteadParams);

        setContentView(content);
        serialInput.requestFocus();
    }

    private void showConfirmation(String serial) {
        methodSelectionVisible = false;
        suggestedSerial = serial;
        LinearLayout content = page("确认设备", "确认后将精确连接这台 C1 Mini，并启用训练与阻力控制。");

        BridgeIconView icon = new BridgeIconView(this, BridgeIconView.READ_ONLY);
        icon.setIconColor(BridgeUi.ACCENT);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        iconParams.topMargin = dp(62);
        content.addView(icon, iconParams);

        TextView model = BridgeUi.label(this, "KEEP C1 MINI");
        model.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams modelParams = matchWrap();
        modelParams.topMargin = dp(22);
        content.addView(model, modelParams);

        TextView serialText = BridgeUi.text(this, maskedSerial(serial), 24, BridgeUi.TEXT);
        serialText.setTypeface(BridgeUi.MONO);
        serialText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams serialParams = matchWrap();
        serialParams.topMargin = dp(10);
        content.addView(serialText, serialParams);

        TextView localOnly = BridgeUi.text(this, "本机保存 · 随时可切回首台只读模式", 12, BridgeUi.MUTED);
        localOnly.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams localParams = matchWrap();
        localParams.topMargin = dp(12);
        content.addView(localOnly, localParams);

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView confirm = BridgeUi.button(this, "绑定这台设备", true);
        confirm.setOnClickListener(view -> returnSerial(serial));
        content.addView(confirm, matchWrap());

        TextView change = BridgeUi.textButton(this, "更换添加方式");
        change.setOnClickListener(view -> showMethodSelection());
        LinearLayout.LayoutParams changeParams = matchWrap();
        changeParams.topMargin = dp(7);
        content.addView(change, changeParams);

        setContentView(content);
    }

    private LinearLayout page(String title, String subtitle) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(18), dp(24), dp(28));
        content.setBackgroundColor(BridgeUi.BACKGROUND);

        FrameLayout top = new FrameLayout(this);
        content.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        ));
        BridgeIconView back = new BridgeIconView(this, BridgeIconView.BACK);
        back.setBackground(BridgeUi.ripple(this, Color.TRANSPARENT, Color.TRANSPARENT, 18));
        back.setOnClickListener(view -> onBackPressed());
        top.addView(back, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START));

        TextView titleView = BridgeUi.text(this, title, 36, BridgeUi.TEXT);
        titleView.setTypeface(BridgeUi.DISPLAY);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(24);
        content.addView(titleView, titleParams);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = BridgeUi.text(this, subtitle, 14, BridgeUi.MUTED);
            subtitleView.setLineSpacing(0, 1.22f);
            LinearLayout.LayoutParams subtitleParams = matchWrap();
            subtitleParams.topMargin = dp(12);
            content.addView(subtitleView, subtitleParams);
        }
        return content;
    }

    private View methodRow(int iconType, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        row.setBackground(BridgeUi.ripple(this, Color.TRANSPARENT, Color.TRANSPARENT, 0));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        row.addView(copy, copyParams);
        TextView titleView = BridgeUi.text(this, title, 19, BridgeUi.TEXT);
        titleView.setTypeface(BridgeUi.MEDIUM);
        copy.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = BridgeUi.text(this, subtitle, 11, BridgeUi.MUTED);
            LinearLayout.LayoutParams subtitleParams = matchWrap();
            subtitleParams.topMargin = dp(5);
            copy.addView(subtitleView, subtitleParams);
        }
        if (iconType >= 0) {
            BridgeIconView icon = new BridgeIconView(this, iconType);
            icon.setIconColor(BridgeUi.TEXT);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            iconParams.rightMargin = dp(17);
            row.addView(icon, iconParams);
        }
        TextView arrow = BridgeUi.text(this, "→", 22, BridgeUi.MUTED);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));
        BridgeUi.addPressMotion(row);
        return row;
    }

    private void requestCameraAccess() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            if (!methodSelectionVisible) {
                showMethodSelection();
            }
            permissionMessage.setText("此设备没有可用相机，请使用手动输入 SN。");
            permissionMessage.setVisibility(View.VISIBLE);
            permissionAction.setVisibility(View.GONE);
            return;
        }
        if (hasCameraPermission()) {
            launchScanner();
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST
        );
    }

    private void handlePermissionAction() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            requestCameraAccess();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        waitingForSettings = true;
        startActivity(intent);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void launchScanner() {
        startActivityForResult(new Intent(this, QrScannerActivity.class), QR_SCANNER_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }
        if (hasCameraPermission()) {
            launchScanner();
        } else {
            showPermissionError(!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA));
        }
    }

    private void showPermissionError(boolean settingsRequired) {
        if (!methodSelectionVisible || permissionMessage == null || permissionAction == null) {
            showMethodSelection();
        }
        permissionMessage.setText(settingsRequired
                ? "相机权限已关闭。二维码扫描不可用，手动输入仍可使用。"
                : "需要相机权限才能识别二维码；权限仅在扫码页使用。");
        permissionAction.setText(settingsRequired ? "打开系统设置" : "重新授权相机");
        permissionMessage.setVisibility(View.VISIBLE);
        permissionAction.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != QR_SCANNER_REQUEST) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            String serial = data.getStringExtra(EXTRA_DEVICE_SN);
            if (C1SerialCodec.isValid(serial)) {
                showConfirmation(C1SerialCodec.normalize(serial));
            }
        } else if (resultCode == RESULT_USE_MANUAL) {
            showManualEntry();
        }
    }

    private void returnSerial(String serial) {
        Intent result = new Intent();
        result.putExtra(EXTRA_DEVICE_SN, serial);
        setResult(RESULT_OK, result);
        finish();
    }

    private void returnReadOnly() {
        Intent result = new Intent();
        result.putExtra(EXTRA_USE_READ_ONLY, true);
        setResult(RESULT_OK, result);
        finish();
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private int dp(float value) {
        return BridgeUi.dp(this, value);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static String maskedSerial(String serial) {
        return serial.substring(0, 2)
                + "  ••••  ••••  "
                + serial.substring(serial.length() - 4);
    }
}
