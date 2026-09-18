package com.edge20pro.camerakeyprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Edge20Pro Camera2 Vendor Tag Probe - V4
 *
 * This version deliberately stops using hidden Java CameraMetadataNative APIs.
 * Vendor-tag resolution is delegated to the Android NDK implementation through
 * JNI. The companion native library must export nativeProbeVendorTags().
 *
 * Copy this file over the project's MainActivity.java.
 */
public class MainActivity extends Activity {

    private static final String NATIVE_LIBRARY = "edge20pro_vendor_probe_v4";

    /*
     * Names and HAL tags established by the earlier Edge 20 Pro investigation.
     * A tag of -1 means the numeric tag was not established yet.
     */
    private static final Candidate[] CANDIDATES = new Candidate[] {
            new Candidate("com.lenovo.moto.control.wnr_idx", "byte", -1),
            new Candidate("com.lenovo.moto.control.wnr_type", "byte", -1),

            new Candidate("com.lenovo.moto.control.mfnr_number_of_frames", "byte", 0x809b0008),
            new Candidate("com.lenovo.moto.control.mfnr_anchor_selection_mode", "byte", 0x809b0009),
            new Candidate("com.lenovo.moto.control.mfnr_anchor_selection_algo", "byte", 0x809b000a),
            new Candidate("com.lenovo.moto.envinfo.isMfnrEnabled", "byte", 0x809c0006),

            new Candidate("org.codeaurora.qcamera3.temporal_denoise.enable", "byte", 0x80230000),
            new Candidate("org.codeaurora.qcamera3.temporal_denoise.process_type", "int32", 0x80230001),

            new Candidate("com.lenovo.moto.adrc.enable", "byte", 0x80a00000),
            new Candidate("com.lenovo.moto.adrc.gain", "float", 0x80a00001),

            new Candidate("com.lenovo.moto.control.hdrplus", "byte", 0x809b0006),
            new Candidate("org.quic.camera.CustomNoiseReduction", "byte", 0x803c0000),

            new Candidate("OEMIFEIQSetting", "byte", 0x80100000),
            new Candidate("OEMBPSIQSetting", "byte", 0x80100001),
            new Candidate("OEMIPEIQSetting", "byte", 0x80100002),

            new Candidate("MFNRTotalNumFrames", "int32", 0x80150000),
            new Candidate("MFNRBlendFrameNum", "int32", 0x80150001)
    };

    private TextView statusText;
    private TextView outputText;
    private String lastReport = "";
    private File reportFile;
    private boolean nativeLoaded = false;

    static {
        try {
            System.loadLibrary(NATIVE_LIBRARY);
        } catch (UnsatisfiedLinkError ignored) {
            // Reported explicitly in the UI/report. Java-only build must still succeed.
        }
    }

    /**
     * Native probe implemented by the companion C++ library.
     *
     * The native side is expected to:
     *   1. Create/open CameraManager.
     *   2. Query each camera's ACameraMetadata.
     *   3. Call ACameraMetadata_getTagFromName() for each candidate.
     *   4. Enumerate getAllTags() and report whether resolved tags are present.
     *
     * cameraIds are passed as strings to preserve OEM camera-id semantics.
     */
    private static native String nativeProbeVendorTags(
            String[] cameraIds,
            String[] candidateNames,
            int[] expectedTags
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        outputText = findViewById(R.id.outputText);

        Button runButton = findViewById(R.id.runButton);
        Button shareButton = findViewById(R.id.shareButton);

        runButton.setOnClickListener(v -> runProbe());
        shareButton.setOnClickListener(v -> shareReport());

        nativeLoaded = isNativeLibraryLoaded();
        statusText.setText(
                nativeLoaded
                        ? "NDK vendor-tag probe ready"
                        : "NDK library not loaded"
        );
    }

    private void runProbe() {
        statusText.setText("Running NDK vendor-tag probe...");

        new Thread(() -> {
            String report;

            try {
                report = buildReport();
            } catch (Throwable t) {
                StringBuilder sb = new StringBuilder();
                sb.append("FATAL PROBE ERROR\n");
                appendThrowable(sb, "", t);
                report = sb.toString();
            }

            lastReport = report;

            try {
                reportFile = saveReport(report);
            } catch (Throwable t) {
                reportFile = null;
                StringBuilder sb = new StringBuilder(report);
                sb.append("\n============================================================\n");
                sb.append("REPORT SAVE ERROR\n");
                sb.append("============================================================\n");
                appendThrowable(sb, "", t);
                report = sb.toString();
                lastReport = report;
            }

            final String finalReport = report;

            runOnUiThread(() -> {
                outputText.setText(finalReport);

                if (reportFile != null) {
                    statusText.setText(
                            "Done: " + reportFile.getAbsolutePath()
                    );
                } else {
                    statusText.setText("Probe finished; report save failed");
                }
            });
        }).start();
    }

    private String buildReport() throws Exception {
        StringBuilder sb = new StringBuilder(96 * 1024);

        sb.append("Edge20Pro Camera2 NDK Vendor Tag Probe\n");
        sb.append("Generated: ").append(new Date()).append('\n');
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Model: ").append(Build.MODEL).append('\n');
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("Target package: com.edge20pro.camerakeyprobe\n");
        sb.append("Native library: ").append(NATIVE_LIBRARY).append('\n');
        sb.append("Native library loaded: ").append(nativeLoaded).append("\n\n");

        dumpJavaCameraInventory(sb);

        sb.append("\n============================================================\n");
        sb.append("NDK VENDOR TAG PROBE\n");
        sb.append("============================================================\n");

        if (!nativeLoaded) {
            sb.append("Native library is not loaded.\n");
            sb.append("Expected library: lib")
                    .append(NATIVE_LIBRARY)
                    .append(".so\n");
            sb.append("No native probe was executed.\n");
            return sb.toString();
        }

        CameraManager manager =
                (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        if (manager == null) {
            sb.append("CameraManager: null\n");
            return sb.toString();
        }

        String[] cameraIds = manager.getCameraIdList();
        if (cameraIds == null) {
            sb.append("Camera IDs: <null>\n");
            return sb.toString();
        }

        String[] names = new String[CANDIDATES.length];
        int[] expectedTags = new int[CANDIDATES.length];

        for (int i = 0; i < CANDIDATES.length; i++) {
            names[i] = CANDIDATES[i].name;
            expectedTags[i] = CANDIDATES[i].expectedTag;
        }

        try {
            String nativeReport = nativeProbeVendorTags(
                    cameraIds,
                    names,
                    expectedTags
            );

            if (nativeReport == null) {
                sb.append("nativeProbeVendorTags(): returned null\n");
            } else {
                sb.append(nativeReport);
            }
        } catch (UnsatisfiedLinkError e) {
            nativeLoaded = false;
            sb.append("nativeProbeVendorTags(): UnsatisfiedLinkError\n");
            appendThrowable(sb, "  ", e);
        } catch (Throwable t) {
            sb.append("nativeProbeVendorTags(): FAILED\n");
            appendThrowable(sb, "  ", t);
        }

        return sb.toString();
    }

    private void dumpJavaCameraInventory(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("JAVA CAMERA INVENTORY\n");
        sb.append("============================================================\n");

        try {
            CameraManager manager =
                    (CameraManager) getSystemService(Context.CAMERA_SERVICE);

            if (manager == null) {
                sb.append("CameraManager: null\n");
                return;
            }

            String[] ids = manager.getCameraIdList();
            if (ids == null) {
                sb.append("Camera IDs: <null>\n");
                return;
            }

            List<String> sorted = new ArrayList<>(Arrays.asList(ids));
            Collections.sort(sorted, (a, b) -> {
                try {
                    return Integer.compare(
                            Integer.parseInt(a),
                            Integer.parseInt(b)
                    );
                } catch (NumberFormatException e) {
                    return a.compareTo(b);
                }
            });

            sb.append("Camera IDs: ").append(sorted).append('\n');

            for (String id : sorted) {
                try {
                    CameraCharacteristics c =
                            manager.getCameraCharacteristics(id);

                    Integer facing =
                            c.get(CameraCharacteristics.LENS_FACING);

                    Integer level =
                            c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

                    sb.append("Camera ")
                            .append(id)
                            .append(": facing=")
                            .append(facingToString(facing))
                            .append(" hardware=")
                            .append(hardwareLevelToString(level))
                            .append('\n');

                    if (Build.VERSION.SDK_INT >= 28) {
                        java.util.Set<String> physicalIds =
                                c.getPhysicalCameraIds();

                        if (physicalIds != null && !physicalIds.isEmpty()) {
                            sb.append("  physicalIds=")
                                    .append(physicalIds)
                                    .append('\n');
                        }
                    }
                } catch (Throwable t) {
                    sb.append("Camera ")
                            .append(id)
                            .append(": ERROR ");
                    appendThrowable(sb, "", t);
                }
            }
        } catch (Throwable t) {
            appendThrowable(sb, "Inventory failed: ", t);
        }

        sb.append("\nCandidates: ").append(CANDIDATES.length).append('\n');
        for (Candidate c : CANDIDATES) {
            sb.append("  ")
                    .append(c.name)
                    .append(" expectedType=")
                    .append(c.expectedType)
                    .append(" expectedTag=")
                    .append(formatTag(c.expectedTag))
                    .append('\n');
        }
    }

    private boolean isNativeLibraryLoaded() {
        try {
            /* A second load is idempotent for an already-loaded library. */
            System.loadLibrary(NATIVE_LIBRARY);
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private String formatTag(int tag) {
        if (tag == -1) {
            return "<not-established>";
        }
        return String.format("0x%08x", tag);
    }

    private File saveReport(String report) throws Exception {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) {
            dir = getFilesDir();
        }

        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException(
                    "Unable to create report directory"
            );
        }

        File file = new File(
                dir,
                "Camera2VendorKeyDump-4.txt"
        );

        try (FileOutputStream out =
                     new FileOutputStream(file, false)) {
            out.write(
                    report.getBytes(StandardCharsets.UTF_8)
            );
        }

        return file;
    }

    private void shareReport() {
        if (lastReport == null || lastReport.isEmpty()) {
            statusText.setText("Run Probe first.");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(
                Intent.EXTRA_SUBJECT,
                "Edge20Pro Camera2 NDK Vendor Tag Dump"
        );
        intent.putExtra(Intent.EXTRA_TEXT, lastReport);

        startActivity(
                Intent.createChooser(
                        intent,
                        "Share NDK vendor tag report"
                )
        );
    }

    private String facingToString(Integer value) {
        if (value == null) return "UNKNOWN";
        if (value == CameraCharacteristics.LENS_FACING_BACK) return "BACK";
        if (value == CameraCharacteristics.LENS_FACING_FRONT) return "FRONT";
        if (value == CameraCharacteristics.LENS_FACING_EXTERNAL) return "EXTERNAL";
        return "UNKNOWN";
    }

    private String hardwareLevelToString(Integer value) {
        if (value == null) return "UNKNOWN";

        switch (value) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                return "EXTERNAL";
            default:
                return "UNKNOWN";
        }
    }

    private void appendThrowable(
            StringBuilder sb,
            String prefix,
            Throwable t
    ) {
        if (t == null) {
            sb.append(prefix).append("<null throwable>\n");
            return;
        }

        sb.append(prefix)
                .append(t.getClass().getName())
                .append(": ")
                .append(String.valueOf(t.getMessage()))
                .append('\n');

        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append(prefix).append("cause: ");
            appendThrowable(sb, "", cause);
        }
    }

    private static final class Candidate {
        final String name;
        final String expectedType;
        final int expectedTag;

        Candidate(
                String name,
                String expectedType,
                int expectedTag
        ) {
            this.name = name;
            this.expectedType = expectedType;
            this.expectedTag = expectedTag;
        }
    }
}
