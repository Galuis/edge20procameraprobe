package com.edge20pro.camerakeyprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V8 runtime probe for direct Camera2 CaptureRequest.Builder.set() of Motorola/Qualcomm
 * vendor request keys. This intentionally does NOT depend on AVAILABLE_REQUEST_KEYS.
 *
 * Use this versioned Activity class directly, or rename the class/file to MainActivity and update the manifest accordingly.
 */
public class MainActivity_v8_HiddenRequestRuntimeProbe extends Activity {
    private static final int REQUEST_CAMERA = 2008;
    private static final long OPEN_TIMEOUT_MS = 4000L;
    private static final long SESSION_TIMEOUT_MS = 4000L;
    private static final long REQUEST_SETTLE_MS = 250L;

    private final List<Candidate> candidates = createCandidates();
    private final List<String> cameraIds = Arrays.asList("0", "1", "2", "3");

    private TextView reportView;
    private TextureView textureView;
    private Button runButton;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String lastReport = "";

    private static final class Candidate {
        final String name;
        final Class<?> type;
        final Object value;
        final String expectedTag;

        Candidate(String name, Class<?> type, Object value, String expectedTag) {
            this.name = name;
            this.type = type;
            this.value = value;
            this.expectedTag = expectedTag;
        }
    }

    private static final class CameraRun {
        CameraDevice device;
        CameraCaptureSession session;
    }

    private static final class ResultStats {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        runButton = new Button(this);
        runButton.setText("Run Hidden Request Probe");
        runButton.setOnClickListener(v -> startProbe());
        controls.addView(runButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button shareButton = new Button(this);
        shareButton.setText("Share Report");
        shareButton.setOnClickListener(v -> shareReport());
        controls.addView(shareButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(controls);

        textureView = new TextureView(this);
        root.addView(textureView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                520));

        reportView = new TextView(this);
        reportView.setTextSize(11f);
        reportView.setText("V8 runtime probe ready.\nGrant CAMERA permission, then run the probe.\n");
        reportView.setTextIsSelectable(true);
        reportView.setPadding(12, 12, 12, 12);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(reportView);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        startCameraThread();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onDestroy() {
        stopCameraThread();
        super.onDestroy();
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("Edge20ProV8Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void startProbe() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            appendReport("\nCAMERA permission not granted.\n");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        if (!textureView.isAvailable()) {
            appendReport("\nTextureView is not ready yet. Wait a moment and run again.\n");
            return;
        }

        runButton.setEnabled(false);
        appendReport("\nStarting V8 runtime hidden-request probe...\n");

        new Thread(() -> {
            String report = buildProbeReport();
            lastReport = report;
            saveReport(report);
            runOnUiThread(() -> {
                reportView.setText(report);
                runButton.setEnabled(true);
            });
        }, "Edge20ProV8Probe").start();
    }

    private String buildProbeReport() {
        StringBuilder out = new StringBuilder(30000);
        out.append("Edge20Pro Camera2 Hidden Request Runtime Probe V8\n");
        out.append("SDK: ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        out.append("Model: ").append(android.os.Build.MODEL).append('\n');
        out.append("Manufacturer: ").append(android.os.Build.MANUFACTURER).append('\n');
        out.append("Package: ").append(getPackageName()).append('\n');
        out.append("\nPurpose: directly test CaptureRequest.Builder.set() + setRepeatingRequest()\n");
        out.append("without relying on AVAILABLE_REQUEST_KEYS.\n");
        out.append("\nCANDIDATES\n");
        for (Candidate c : candidates) {
            out.append("  ").append(c.name)
                    .append(" type=").append(c.type.getName())
                    .append(" value=").append(valueToString(c.value))
                    .append(" expectedTag=").append(c.expectedTag)
                    .append('\n');
        }

        if (!textureView.isAvailable()) {
            out.append("\nERROR: TextureView unavailable.\n");
            return out.toString();
        }

        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (texture == null) {
            out.append("\nERROR: SurfaceTexture unavailable.\n");
            return out.toString();
        }
        texture.setDefaultBufferSize(640, 480);
        Surface surface = new Surface(texture);

        android.hardware.camera2.CameraManager manager =
                (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);

        for (String cameraId : cameraIds) {
            out.append("\n============================================================\n");
            out.append("CAMERA ").append(cameraId).append('\n');
            out.append("============================================================\n");

            try {
                CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                Integer hw = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                out.append("facing=").append(facingToString(facing))
                        .append(" hardware=").append(hardwareToString(hw)).append('\n');

                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                boolean textureSupported = map != null && map.getOutputSizes(SurfaceTexture.class) != null;
                out.append("SurfaceTextureOutputSupported=").append(textureSupported).append('\n');
            } catch (Throwable t) {
                out.append("characteristics ERROR: ").append(describe(t)).append('\n');
            }

            CameraRun run = openCameraAndSession(manager, cameraId, surface, out);
            if (run == null || run.device == null || run.session == null) {
                out.append("CAMERA ").append(cameraId).append(" skipped: open/session failed.\n");
                closeRun(run);
                continue;
            }

            try {
                for (Candidate candidate : candidates) {
                    probeCandidate(run.device, run.session, surface, candidate, out);
                }
            } finally {
                closeRun(run);
            }
        }

        surface.release();
        out.append("\nEND OF V8 PROBE\n");
        out.append("Interpretation: setSuccess/buildSuccess/repeatingSuccess are framework-path signals;\n");
        out.append("captureStarted/completed show that the request actually entered the repeating stream.\n");
        return out.toString();
    }

    private CameraRun openCameraAndSession(
            android.hardware.camera2.CameraManager manager,
            String cameraId,
            Surface surface,
            StringBuilder out) {
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CameraRun result = new CameraRun();
        final Throwable[] openError = new Throwable[1];

        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    result.device = camera;
                    openLatch.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    result.device = camera;
                    openError[0] = new IllegalStateException("camera disconnected during open");
                    openLatch.countDown();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    result.device = camera;
                    openError[0] = new IllegalStateException("camera open error=" + error);
                    openLatch.countDown();
                }
            }, cameraHandler);

            if (!openLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                out.append("openCamera: TIMEOUT\n");
                return null;
            }
            if (openError[0] != null || result.device == null) {
                out.append("openCamera: ERROR ").append(describe(openError[0])).append('\n');
                return null;
            }
        } catch (Throwable t) {
            out.append("openCamera: EXCEPTION ").append(describe(t)).append('\n');
            return null;
        }

        final CountDownLatch sessionLatch = new CountDownLatch(1);
        final Throwable[] sessionError = new Throwable[1];
        try {
            result.device.createCaptureSession(
                    Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            result.session = session;
                            sessionLatch.countDown();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            sessionError[0] = new IllegalStateException("capture session configure failed");
                            sessionLatch.countDown();
                        }
                    },
                    cameraHandler);

            if (!sessionLatch.await(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                out.append("createCaptureSession: TIMEOUT\n");
                return null;
            }
            if (sessionError[0] != null || result.session == null) {
                out.append("createCaptureSession: ERROR ").append(describe(sessionError[0])).append('\n');
                return null;
            }
        } catch (Throwable t) {
            out.append("createCaptureSession: EXCEPTION ").append(describe(t)).append('\n');
            return null;
        }

        out.append("camera/session ready\n");
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void probeCandidate(
            CameraDevice device,
            CameraCaptureSession session,
            Surface surface,
            Candidate candidate,
            StringBuilder out) {
        out.append("\n------------------------------------------------------------\n");
        out.append(candidate.name).append('\n');
        out.append("type=").append(candidate.type.getName())
                .append(" value=").append(valueToString(candidate.value))
                .append(" expectedTag=").append(candidate.expectedTag).append('\n');

        CaptureRequest.Builder builder;
        try {
            builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
        } catch (Throwable t) {
            out.append("builderCreation=FAIL ").append(describe(t)).append('\n');
            return;
        }

        // Baseline controls, matching the normal preview path as closely as possible.
        try {
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
        } catch (Throwable ignored) {
        }
        try {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        } catch (Throwable ignored) {
        }
        try {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST);
        } catch (Throwable ignored) {
        }
        try {
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST);
        } catch (Throwable ignored) {
        }
        try {
            builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST);
        } catch (Throwable ignored) {
        }

        CaptureRequest.Key key;
        try {
            key = new CaptureRequest.Key(candidate.name, candidate.type);
        } catch (Throwable t) {
            out.append("keyConstruction=FAIL ").append(describe(t)).append('\n');
            return;
        }

        try {
            Object before = builder.get(key);
            out.append("builderGetBefore=").append(valueToString(before)).append('\n');
        } catch (Throwable t) {
            out.append("builderGetBefore=ERROR ").append(describe(t)).append('\n');
        }

        boolean setSuccess = false;
        try {
            builder.set(key, candidate.value);
            setSuccess = true;
            out.append("setSuccess=YES\n");
        } catch (Throwable t) {
            out.append("setSuccess=NO ").append(describe(t)).append('\n');
        }

        if (!setSuccess) {
            return;
        }

        try {
            Object after = builder.get(key);
            out.append("builderGetAfter=").append(valueToString(after)).append('\n');
        } catch (Throwable t) {
            out.append("builderGetAfter=ERROR ").append(describe(t)).append('\n');
        }

        CaptureRequest request;
        try {
            request = builder.build();
            out.append("buildSuccess=YES\n");
        } catch (Throwable t) {
            out.append("buildSuccess=NO ").append(describe(t)).append('\n');
            return;
        }

        try {
            Object requestValue = request.get(key);
            out.append("requestGet=").append(valueToString(requestValue)).append('\n');
        } catch (Throwable t) {
            out.append("requestGet=ERROR ").append(describe(t)).append('\n');
        }

        boolean keyListedInBuiltRequest = false;
        try {
            for (CaptureRequest.Key<?> builtKey : request.getKeys()) {
                if (candidate.name.equals(builtKey.getName())) {
                    keyListedInBuiltRequest = true;
                    break;
                }
            }
            out.append("builtRequestContainsName=")
                    .append(keyListedInBuiltRequest ? "YES" : "NO")
                    .append('\n');
        } catch (Throwable t) {
            out.append("builtRequestContainsName=ERROR ").append(describe(t)).append('\n');
        }

        ResultStats stats = new ResultStats();
        final CountDownLatch startedLatch = new CountDownLatch(1);

        CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
                stats.started.incrementAndGet();
                startedLatch.countDown();
            }

            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                stats.completed.incrementAndGet();
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                stats.failed.incrementAndGet();
            }
        };

        try {
            int sequenceId = session.setRepeatingRequest(request, callback, cameraHandler);
            out.append("setRepeatingSuccess=YES sequenceId=").append(sequenceId).append('\n');
        } catch (Throwable t) {
            out.append("setRepeatingSuccess=NO ").append(describe(t)).append('\n');
            return;
        }

        try {
            startedLatch.await(REQUEST_SETTLE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            Thread.sleep(REQUEST_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            session.stopRepeating();
        } catch (Throwable t) {
            out.append("stopRepeating=ERROR ").append(describe(t)).append('\n');
        }

        out.append("captureStartedCount=").append(stats.started.get()).append('\n');
        out.append("captureCompletedCount=").append(stats.completed.get()).append('\n');
        out.append("captureFailedCount=").append(stats.failed.get()).append('\n');
    }

    private void closeRun(CameraRun run) {
        if (run == null) return;
        if (run.session != null) {
            try { run.session.stopRepeating(); } catch (Throwable ignored) {}
            try { run.session.close(); } catch (Throwable ignored) {}
        }
        if (run.device != null) {
            try { run.device.close(); } catch (Throwable ignored) {}
        }
    }

    private void appendReport(String text) {
        if (reportView == null) return;
        reportView.append(text);
    }

    private void saveReport(String report) {
        try {
            File file = new File(getExternalFilesDir(null), "Camera2VendorKeyDump-8.txt");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(report.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    private void shareReport() {
        if (lastReport == null || lastReport.isEmpty()) {
            lastReport = reportView != null ? reportView.getText().toString() : "No report yet.";
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Camera2VendorKeyDump-8.txt");
        intent.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(intent, "Share V8 report"));
    }

    private static List<Candidate> createCandidates() {
        ArrayList<Candidate> list = new ArrayList<>();

        // Controls: standard Camera2 key and one known public Motorola vendor key.
        list.add(new Candidate(
                "android.noiseReduction.mode",
                Integer.class,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                "0x000a0000"));
        list.add(new Candidate(
                "com.lenovo.moto.clientapp.is_motcamera2",
                Byte.class,
                (byte) 1,
                "0x809a0000"));

        // Motorola / Qualcomm hidden candidates from the HAL dump + BSG 9.7 custom-request dictionary.
        list.add(new Candidate(
                "com.lenovo.moto.control.wnr_idx",
                Byte.class,
                (byte) 0,
                "<not-established>"));
        list.add(new Candidate(
                "com.lenovo.moto.control.wnr_type",
                Byte.class,
                (byte) 0,
                "<not-established>"));
        list.add(new Candidate(
                "com.lenovo.moto.control.mfnr_number_of_frames",
                Byte.class,
                (byte) 3,
                "0x809b0008"));
        list.add(new Candidate(
                "com.lenovo.moto.control.mfnr_anchor_selection_mode",
                Byte.class,
                (byte) 0,
                "0x809b0009"));
        list.add(new Candidate(
                "com.lenovo.moto.control.mfnr_anchor_selection_algo",
                Byte.class,
                (byte) 0,
                "0x809b000a"));
        list.add(new Candidate(
                "com.lenovo.moto.envinfo.isMfnrEnabled",
                Byte.class,
                (byte) 1,
                "0x809c0006"));
        list.add(new Candidate(
                "org.codeaurora.qcamera3.temporal_denoise.enable",
                Byte.class,
                (byte) 1,
                "0x80230000"));
        list.add(new Candidate(
                "org.codeaurora.qcamera3.temporal_denoise.process_type",
                Integer.class,
                0,
                "0x80230001"));
        list.add(new Candidate(
                "com.lenovo.moto.adrc.enable",
                Byte.class,
                (byte) 1,
                "0x80a00000"));
        list.add(new Candidate(
                "com.lenovo.moto.adrc.gain",
                Float.TYPE,
                1.0f,
                "0x80a00001"));
        list.add(new Candidate(
                "com.lenovo.moto.control.hdrplus",
                Byte.class,
                (byte) 1,
                "0x809b0006"));
        list.add(new Candidate(
                "org.quic.camera.CustomNoiseReduction",
                Byte.class,
                (byte) 1,
                "0x803c0000"));
        list.add(new Candidate(
                "org.quic.camera.CustomNoiseReduction.CustomNoiseReduction",
                Byte.class,
                (byte) 1,
                "0x803c0000"));
        list.add(new Candidate(
                "OEMIFEIQSetting",
                Byte.class,
                (byte) 1,
                "0x80100000"));
        list.add(new Candidate(
                "OEMBPSIQSetting",
                Byte.class,
                (byte) 1,
                "0x80100001"));
        list.add(new Candidate(
                "OEMIPEIQSetting",
                Byte.class,
                (byte) 1,
                "0x80100002"));
        list.add(new Candidate(
                "MFNRTotalNumFrames",
                Integer.class,
                3,
                "0x80150000"));
        list.add(new Candidate(
                "MFNRBlendFrameNum",
                Integer.class,
                1,
                "0x80150001"));

        return list;
    }

    private static String valueToString(Object value) {
        if (value == null) return "<null>";
        if (value instanceof byte[]) return Arrays.toString((byte[]) value);
        if (value instanceof int[]) return Arrays.toString((int[]) value);
        return String.valueOf(value);
    }

    private static String describe(Throwable t) {
        if (t == null) return "<none>";
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getName() + ": " + String.valueOf(root.getMessage());
    }

    private static String facingToString(Integer value) {
        if (value == null) return "<null>";
        if (value == CameraCharacteristics.LENS_FACING_BACK) return "BACK";
        if (value == CameraCharacteristics.LENS_FACING_FRONT) return "FRONT";
        if (value == CameraCharacteristics.LENS_FACING_EXTERNAL) return "EXTERNAL";
        return String.valueOf(value);
    }

    private static String hardwareToString(Integer value) {
        if (value == null) return "<null>";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) return "LIMITED";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) return "FULL";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) return "LEGACY";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return "LEVEL_3";
        return String.valueOf(value);
    }
}
