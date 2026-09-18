package com.edge20pro.camerakeyprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private TextView statusText;
    private TextView outputText;
    private String lastReport = "";
    private File reportFile;

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
    }

    private void runProbe() {
        statusText.setText("Running Camera2 key probe...");
        new Thread(() -> {
            String report;
            try {
                report = buildReport();
                reportFile = saveReport(report);
                lastReport = report;
            } catch (Exception e) {
                report = "ERROR: " + e.getClass().getName() + ": " + e.getMessage();
                lastReport = report;
            }

            String finalReport = report;
            runOnUiThread(() -> {
                outputText.setText(finalReport);
                statusText.setText(reportFile != null
                        ? "Done: " + reportFile.getAbsolutePath()
                        : "Probe failed");
            });
        }).start();
    }

    private String buildReport() throws Exception {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("Edge20Pro Camera2 Vendor Key Probe\n");
        sb.append("Generated: ").append(new java.util.Date()).append('\n');
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Model: ").append(Build.MODEL).append('\n');
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("Target package: com.edge20pro.camerakeyprobe\n\n");

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String[] cameraIds = manager.getCameraIdList();
        List<String> ids = new ArrayList<>();
        Collections.addAll(ids, cameraIds);
        Collections.sort(ids, (a, b) -> {
            try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
            catch (NumberFormatException e) { return a.compareTo(b); }
        });

        sb.append("Camera IDs: ").append(ids).append("\n");
        sb.append("NOTE: IDs 0/1/2/3 are the four physical/logical targets previously identified; all IDs are dumped for correlation.\n\n");

        for (String id : ids) {
            dumpCamera(manager, id, sb);
        }
        return sb.toString();
    }

    private void dumpCamera(CameraManager manager, String id, StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("CAMERA ID ").append(id).append('\n');
        sb.append("============================================================\n");

        try {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);

            sb.append("LensFacing: ").append(facingToString(facing)).append(" (" ).append(facing).append(")\n");
            sb.append("HardwareLevel: ").append(hardwareLevelToString(level)).append(" (" ).append(level).append(")\n");
            sb.append("Capabilities: ").append(arrayToString(caps)).append("\n");

            if (Build.VERSION.SDK_INT >= 28) {
                Set<String> physicalIds = c.getPhysicalCameraIds();
                if (physicalIds != null && !physicalIds.isEmpty()) {
                    sb.append("Physical IDs: ").append(physicalIds).append("\n");
            }

            dumpRequestKeys(c, sb);
            if (Build.VERSION.SDK_INT >= 28) {
                dumpSessionKeys(c, sb);
                dumpPhysicalRequestKeys(c, sb);
            } else {
                sb.append("Session Keys: API < 28, unavailable\n");
                sb.append("Physical Request Keys: API < 28, unavailable\n");
            }

        } catch (SecurityException se) {
            sb.append("SECURITY ERROR: ").append(se.getMessage()).append('\n');
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
        }
        sb.append('\n');
    }

    private void dumpRequestKeys(CameraCharacteristics c, StringBuilder sb) {
        sb.append("-- AVAILABLE CAPTURE REQUEST KEYS --\n");
        try {
            List<CaptureRequest.Key<?>> keys = c.getAvailableCaptureRequestKeys();
            dumpKeys(keys, sb);
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
        }
    }

    private void dumpSessionKeys(CameraCharacteristics c, StringBuilder sb) {
        sb.append("-- AVAILABLE SESSION KEYS --\n");
        try {
            List<CaptureRequest.Key<?>> keys = c.getAvailableSessionKeys();
            dumpKeys(keys, sb);
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
        }
    }

    private void dumpPhysicalRequestKeys(CameraCharacteristics c, StringBuilder sb) {
        sb.append("-- AVAILABLE PHYSICAL CAMERA REQUEST KEYS --\n");
        try {
            List<CaptureRequest.Key<?>> keys = c.getAvailablePhysicalCameraRequestKeys();
            if (keys == null || keys.isEmpty()) {
                sb.append("<none>\n");
            } else {
                dumpKeys(keys, sb);
            }
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
        }
    }

    private void dumpKeys(List<CaptureRequest.Key<?>> keys, StringBuilder sb) {
        if (keys == null || keys.isEmpty()) {
            sb.append("<none>\n");
            return;
        }

        List<CaptureRequest.Key<?>> copy = new ArrayList<>(keys);
        copy.sort(Comparator.comparing(k -> safeName(k)));

        for (CaptureRequest.Key<?> key : copy) {
            String name = safeName(key);
            String type = safeType(key);
            sb.append(isLikelyVendor(name) ? "[VENDOR] " : "[STD]   ");
            sb.append(name).append("    type=").append(type).append('\n');
        }
        sb.append("Count: ").append(copy.size()).append('\n');
    }

    private boolean isLikelyVendor(String name) {
        return !(name.startsWith("android.") || name.startsWith("com.android."));
    }

    private String safeName(CaptureRequest.Key<?> key) {
        try { return key.getName(); }
        catch (Exception e) { return "<name-error>"; }
    }

private String safeType(CaptureRequest.Key<?> key) {
    try {
        // 1. 通过反射获取公开类或父类中的 mKey 私有成员变量 (android.hardware.camera2.impl.CameraMetadataNative.Key)
        Field mKeyField = key.getClass().getDeclaredField("mKey");
        mKeyField.setAccessible(true);
        Object nativeKey = mKeyField.get(key);

        if (nativeKey != null) {
            // 2. 从 nativeKey 中获取真正的 Type 或 Class 属性
            Field typeField = nativeKey.getClass().getDeclaredField("mType");
            typeField.setAccessible(true);
            Object type = typeField.get(nativeKey);

            if (type instanceof Class<?>) {
                return ((Class<?>) type).getName();
            } else if (type != null) {
                return type.toString(); // 处理 ParameterizedType (如 Generic 泛型类型)
            }
        }
    } catch (Exception ignored) {
        // 部分 Android 版本或厂商 SDK 结构不同，退回备用逻辑
    }

    // 3. 备用方案：如果反射失败，尝试解析 getName() 结尾或标注未知
    return "<unknown-type>";
}

    private File saveReport(String report) throws Exception {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) dir = getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create report directory");
        }
        File f = new File(dir, "Camera2VendorKeyDump.txt");
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    private void shareReport() {
        if (lastReport == null || lastReport.isEmpty()) {
            statusText.setText("Run Probe first.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Edge20Pro Camera2 Vendor Key Dump");
        intent.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(intent, "Share Camera2 key report"));
    }

    private String facingToString(Integer v) {
        if (v == null) return "UNKNOWN";
        if (v == CameraCharacteristics.LENS_FACING_BACK) return "BACK";
        if (v == CameraCharacteristics.LENS_FACING_FRONT) return "FRONT";
        if (v == CameraCharacteristics.LENS_FACING_EXTERNAL) return "EXTERNAL";
        return "UNKNOWN";
    }

    private String hardwareLevelToString(Integer v) {
        if (v == null) return "UNKNOWN";
        switch (v) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return "UNKNOWN";
        }
    }

    private String arrayToString(int[] values) {
        if (values == null) return "<null>";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

}
