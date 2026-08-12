package dev.c1bridge.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@OptIn(markerClass = ExperimentalGetImage.class)
public final class QrScannerActivity extends ComponentActivity {
    private final AtomicBoolean analyzing = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private PreviewView previewView;
    private TextView statusText;
    private BridgeIconView flashButton;
    private ExecutorService cameraExecutor;
    private BarcodeScanner scanner;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private boolean torchEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BridgeUi.configureWindow(this);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }
        cameraExecutor = Executors.newSingleThreadExecutor();
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        scanner = BarcodeScanning.getClient(options);
        setContentView(buildContent());
        startCamera();
    }

    private FrameLayout buildContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BridgeUi.BACKGROUND);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, match());
        root.addView(new ScannerOverlayView(this), match());

        BridgeIconView close = new BridgeIconView(this, BridgeIconView.BACK);
        close.setBackground(BridgeUi.ripple(this, 0x99111312, BridgeUi.FAINT, 20));
        close.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(42),
                dp(42),
                Gravity.START | Gravity.TOP
        );
        closeParams.leftMargin = dp(22);
        closeParams.topMargin = dp(18);
        root.addView(close, closeParams);

        flashButton = new BridgeIconView(this, BridgeIconView.FLASH);
        flashButton.setAlpha(0.35f);
        flashButton.setEnabled(false);
        flashButton.setBackground(BridgeUi.ripple(this, 0x99111312, BridgeUi.FAINT, 20));
        flashButton.setOnClickListener(view -> toggleTorch());
        FrameLayout.LayoutParams flashParams = new FrameLayout.LayoutParams(
                dp(42),
                dp(42),
                Gravity.END | Gravity.TOP
        );
        flashParams.rightMargin = dp(22);
        flashParams.topMargin = dp(18);
        root.addView(flashButton, flashParams);

        TextView instruction = BridgeUi.text(this, "对准单车上的二维码", 16, BridgeUi.TEXT);
        instruction.setTypeface(BridgeUi.MEDIUM);
        instruction.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams instructionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
                Gravity.TOP
        );
        instructionParams.leftMargin = dp(68);
        instructionParams.rightMargin = dp(68);
        instructionParams.topMargin = dp(64);
        root.addView(instruction, instructionParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_HORIZONTAL);
        copy.setPadding(dp(24), 0, dp(24), dp(30));
        FrameLayout.LayoutParams copyParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        root.addView(copy, copyParams);

        statusText = BridgeUi.text(this, "正在自动识别  ·", 13, BridgeUi.TEXT);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        copy.addView(statusText, statusParams);

        TextView manual = BridgeUi.textButton(this, "改用手动输入 SN");
        manual.setTextColor(BridgeUi.TEXT);
        manual.setOnClickListener(view -> {
            setResult(DeviceSetupActivity.RESULT_USE_MANUAL);
            finish();
        });
        LinearLayout.LayoutParams manualParams = matchWrap();
        manualParams.topMargin = dp(22);
        copy.addView(manual, manualParams);
        return root;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception exception) {
                statusText.setText("相机启动失败，请改用手动输入 SN");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
        );
        boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
        flashButton.setEnabled(hasFlash);
        flashButton.setAlpha(hasFlash ? 1f : 0.35f);
    }

    private void analyzeImage(ImageProxy imageProxy) {
        if (completed.get() || !analyzing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            analyzing.set(false);
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.getImageInfo().getRotationDegrees()
        );
        scanner.process(image)
                .addOnSuccessListener(this::handleBarcodes)
                .addOnFailureListener(error -> statusText.post(
                        () -> statusText.setText("正在重新识别…")
                ))
                .addOnCompleteListener(task -> {
                    analyzing.set(false);
                    imageProxy.close();
                });
    }

    private void handleBarcodes(List<Barcode> barcodes) {
        for (Barcode barcode : barcodes) {
            String serial = C1SerialCodec.fromQrPayload(barcode.getRawValue());
            if (serial != null && completed.compareAndSet(false, true)) {
                runOnUiThread(() -> returnSerial(serial));
                return;
            }
        }
    }

    private void returnSerial(String serial) {
        statusText.setText("已识别 C1 Mini");
        Intent result = new Intent();
        result.putExtra(DeviceSetupActivity.EXTRA_DEVICE_SN, serial);
        setResult(RESULT_OK, result);
        finish();
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            return;
        }
        torchEnabled = !torchEnabled;
        camera.getCameraControl().enableTorch(torchEnabled);
        flashButton.setIconColor(torchEnabled ? BridgeUi.ACCENT : BridgeUi.TEXT);
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (scanner != null) {
            scanner.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        super.onDestroy();
    }

    private int dp(float value) {
        return BridgeUi.dp(this, value);
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
}
