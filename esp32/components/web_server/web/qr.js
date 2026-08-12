let cameraStream;
let cameraFrame;

function serialFromQr(rawValue) {
  try {
    const serial = new URL(rawValue).searchParams.get("sn");
    return /^CC[A-Z0-9]{14}$/.test(serial || "") ? serial : null;
  } catch (_) {
    return null;
  }
}

async function detectQrSerial(image) {
  if ("BarcodeDetector" in window) {
    try {
      const codes = await new BarcodeDetector({ formats: ["qr_code"] }).detect(
        image,
      );
      const serial = serialFromQr(codes[0]?.rawValue || "");
      if (serial) return serial;
    } catch (_) {
      // Safari may expose BarcodeDetector without accepting the selected file.
    }
  }

  if (typeof jsQR !== "function") return null;
  const sourceWidth = image.naturalWidth || image.videoWidth || image.width;
  const sourceHeight = image.naturalHeight || image.videoHeight || image.height;
  if (!sourceWidth || !sourceHeight) return null;

  const fullFrame = {
    x: 0,
    y: 0,
    width: sourceWidth,
    height: sourceHeight,
  };
  const squareSize = Math.min(sourceWidth, sourceHeight);
  const centerSquare = {
    x: (sourceWidth - squareSize) / 2,
    y: (sourceHeight - squareSize) / 2,
    width: squareSize,
    height: squareSize,
  };
  const closeSize = squareSize * 0.64;
  const closeCenter = {
    x: (sourceWidth - closeSize) / 2,
    y: (sourceHeight - closeSize) / 2,
    width: closeSize,
    height: closeSize,
  };
  const attempts = [
    [fullFrame, 1600],
    [fullFrame, 1100],
    [centerSquare, 1400],
    [centerSquare, 1000],
    [centerSquare, 800],
    [closeCenter, 1000],
    [closeCenter, 760],
  ];

  for (const [crop, maximum] of attempts) {
    const serial = decodeQrCrop(image, crop, maximum);
    if (serial) return serial;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  return null;
}

function decodeQrCrop(image, crop, maximum) {
  const scale = Math.min(1, maximum / Math.max(crop.width, crop.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(crop.width * scale));
  canvas.height = Math.max(1, Math.round(crop.height * scale));
  const context = canvas.getContext("2d", { willReadFrequently: true });
  context.drawImage(
    image,
    crop.x,
    crop.y,
    crop.width,
    crop.height,
    0,
    0,
    canvas.width,
    canvas.height,
  );
  const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
  const code = jsQR(pixels.data, pixels.width, pixels.height, {
    inversionAttempts: "attemptBoth",
  });
  return serialFromQr(code?.data || "");
}

async function imageFromFile(file) {
  const url = URL.createObjectURL(file);
  const image = new Image();
  image.decoding = "async";
  image.src = url;
  try {
    await image.decode();
    return image;
  } finally {
    URL.revokeObjectURL(url);
  }
}

$("qrPhoto").onchange = async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  $("qrHint").textContent = "正在本机识别二维码…";
  try {
    const image = await imageFromFile(file);
    const serial = await detectQrSerial(image);
    if (!serial) throw new Error("照片中没有有效 C1 Mini 二维码");
    $("serial").value = serial;
    $("manualSnPanel").hidden = false;
    $("qrHint").textContent = `已读取完整 SN：${serial}`;
  } catch (error) {
    $("manualSnPanel").hidden = false;
    $("qrHint").textContent =
      `${error.message}。可让二维码位于照片中部，或手动输入 SN。`;
  } finally {
    event.target.value = "";
  }
};

function stopCamera() {
  cancelAnimationFrame(cameraFrame);
  cameraStream?.getTracks().forEach((track) => track.stop());
  cameraStream = undefined;
  $("camera").srcObject = null;
  $("cameraPanel").hidden = true;
}

async function scanCameraFrame() {
  if (!cameraStream) return;
  try {
    const serial = await detectQrSerial($("camera"));
    if (serial) {
      $("serial").value = serial;
      $("manualSnPanel").hidden = false;
      $("qrHint").textContent = `已读取完整 SN：${serial}`;
      stopCamera();
      return;
    }
  } catch (_) {}
  cameraFrame = requestAnimationFrame(scanCameraFrame);
}

const liveScanAvailable =
  window.isSecureContext &&
  !!navigator.mediaDevices?.getUserMedia &&
  "BarcodeDetector" in window;
$("openCamera").hidden = !liveScanAvailable;
$("openCamera").onclick = async () => {
  try {
    cameraStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" } },
      audio: false,
    });
    $("cameraPanel").hidden = false;
    $("camera").srcObject = cameraStream;
    await $("camera").play();
    scanCameraFrame();
  } catch (error) {
    $("qrHint").textContent = `相机不可用：${error.message}`;
    stopCamera();
  }
};
$("closeCamera").onclick = stopCamera;
$("settings").addEventListener("close", stopCamera);
