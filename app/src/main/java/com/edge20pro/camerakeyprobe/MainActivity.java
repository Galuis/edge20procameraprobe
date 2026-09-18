package com.edge20pro.camerakeyprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    /*
     * High-priority vendor names from the previous Edge 20 Pro
     * HAL/vendor-tag investigation.
     */
    private static final List<String> HIGH_PRIORITY_NAMES = Arrays.asList(
            "com.lenovo.moto.control.wnr_idx",
            "com.lenovo.moto.control.wnr_type",

            "com.lenovo.moto.control.mfnr_number_of_frames",
            "com.lenovo.moto.control.mfnr_anchor_selection_mode",
            "com.lenovo.moto.control.mfnr_anchor_selection_algo",
            "com.lenovo.moto.envinfo.isMfnrEnabled",

            "org.codeaurora.qcamera3.temporal_denoise.enable",
            "org.codeaurora.qcamera3.temporal_denoise.process_type",

            "com.lenovo.moto.adrc.enable",
            "com.lenovo.moto.adrc.gain",

            "com.lenovo.moto.control.hdrplus",

            "org.quic.camera.CustomNoiseReduction",

            "OEMIFEIQSetting",
            "OEMBPSIQSetting",
            "OEMIPEIQSetting",

            "MFNRTotalNumFrames",
            "MFNRBlendFrameNum"
    );

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
        statusText.setText("Running Camera2 vendor-key probe...");

        new Thread(() -> {
            String report;

            try {
                report = buildReport();
                lastReport = report;

                try {
                    reportFile = saveReport(report);
                } catch (Exception saveError) {
                    reportFile = null;

                    StringBuilder tmp = new StringBuilder(report);
                    tmp.append("\n============================================================\n");
                    tmp.append("REPORT SAVE ERROR\n");
                    tmp.append("============================================================\n");
                    tmp.append(saveError.getClass().getName())
                            .append(": ")
                            .append(String.valueOf(saveError.getMessage()))
                            .append('\n');

                    report = tmp.toString();
                    lastReport = report;
                }

            } catch (Exception e) {
                report = "ERROR: "
                        + e.getClass().getName()
                        + ": "
                        + String.valueOf(e.getMessage());

                lastReport = report;
                reportFile = null;
            }

            final String finalReport = report;

            runOnUiThread(() -> {
                outputText.setText(finalReport);

                if (reportFile != null) {
                    statusText.setText(
                            "Done: " + reportFile.getAbsolutePath()
                    );
                } else {
                    statusText.setText(
                            "Probe finished without saved file"
                    );
                }
            });
        }).start();
    }

    private String buildReport() throws Exception {
        StringBuilder sb = new StringBuilder(128 * 1024);

        sb.append("Edge20Pro Camera2 Vendor Key Probe\n");
        sb.append("Generated: ").append(new Date()).append('\n');
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Model: ").append(Build.MODEL).append('\n');
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("Target package: com.edge20pro.camerakeyprobe\n\n");

        dumpFrameworkReflectionCapabilities(sb);
        sb.append('\n');

        dumpGlobalVendorKeys(sb);
        sb.append('\n');

        dumpCameras(sb);

        return sb.toString();
    }

    private void dumpFrameworkReflectionCapabilities(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("FRAMEWORK REFLECTION SELF-CHECK\n");
        sb.append("============================================================\n");

        sb.append("CameraCharacteristics class: ")
                .append(CameraCharacteristics.class.getName())
                .append('\n');

        sb.append("CameraMetadata class: ")
                .append(CameraMetadata.class.getName())
                .append('\n');

        try {
            Method m = CameraMetadata.class.getMethod(
                    "getNativeMetadata"
            );

            sb.append(
                    "CameraMetadata.getNativeMetadata(): FOUND\n"
            );

            sb.append("  modifiers: ")
                    .append(Modifier.toString(m.getModifiers()))
                    .append('\n');

        } catch (Throwable t) {
            sb.append(
                    "CameraMetadata.getNativeMetadata(): FAILED\n"
            );
            appendThrowable(sb, "  ", t);
        }

        try {
            Class<?> nativeClass = Class.forName(
                    "android.hardware.camera2.impl.CameraMetadataNative"
            );

            sb.append(
                    "CameraMetadataNative: FOUND\n"
            );

            boolean foundVendor = false;
            boolean foundStatic = false;
            boolean foundInstance = false;

            for (Method method : nativeClass.getMethods()) {
                if (!"getAllVendorKeys".equals(method.getName())) {
                    continue;
                }

                Class<?>[] params = method.getParameterTypes();

                if (params.length == 1
                        && params[0] == Class.class) {

                    foundVendor = true;

                    if (Modifier.isStatic(method.getModifiers())) {
                        foundStatic = true;
                    } else {
                        foundInstance = true;
                    }
                }
            }

            sb.append("CameraMetadataNative.getAllVendorKeys(Class): ")
                    .append(foundVendor ? "FOUND" : "NOT FOUND")
                    .append('\n');

            sb.append("  static form: ")
                    .append(foundStatic)
                    .append('\n');

            sb.append("  instance form: ")
                    .append(foundInstance)
                    .append('\n');

        } catch (Throwable t) {
            sb.append("CameraMetadataNative: FAILED\n");
            appendThrowable(sb, "  ", t);
        }

        sb.append('\n');
    }

    private void dumpGlobalVendorKeys(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("ALL DEFINED VENDOR KEYS\n");
        sb.append("============================================================\n");

        try {
            CameraManager manager = (CameraManager)
                    getSystemService(Context.CAMERA_SERVICE);

            if (manager == null) {
                sb.append("CameraManager: null\n");
                return;
            }

            String[] ids = manager.getCameraIdList();

            if (ids == null || ids.length == 0) {
                sb.append("Camera IDs unavailable\n");
                return;
            }

            CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics(ids[0]);

            Method getNativeMetadata =
                    findPublicMethod(
                            characteristics.getClass(),
                            "getNativeMetadata"
                    );

            if (getNativeMetadata == null) {
                sb.append(
                        "getNativeMetadata(): NOT FOUND\n"
                );
                return;
            }

            Object nativeMetadata =
                    getNativeMetadata.invoke(characteristics);

            if (nativeMetadata == null) {
                sb.append(
                        "getNativeMetadata(): returned null\n"
                );
                return;
            }

            Class<?> nativeClass =
                    nativeMetadata.getClass();

            sb.append("Native metadata class: ")
                    .append(nativeClass.getName())
                    .append('\n');

            dumpAllVendorKeyClass(
                    sb,
                    nativeMetadata,
                    nativeClass,
                    CaptureRequest.Key.class,
                    "VENDOR CAPTURE REQUEST KEYS"
            );

            dumpAllVendorKeyClass(
                    sb,
                    nativeMetadata,
                    nativeClass,
                    CaptureResult.Key.class,
                    "VENDOR CAPTURE RESULT KEYS"
            );

            dumpAllVendorKeyClass(
                    sb,
                    nativeMetadata,
                    nativeClass,
                    CameraCharacteristics.Key.class,
                    "VENDOR CAMERA CHARACTERISTIC KEYS"
            );

        } catch (Throwable t) {
            sb.append(
                    "ALL VENDOR KEY ENUMERATION FAILED\n"
            );
            appendThrowable(sb, "", t);
        }
    }

    private void dumpAllVendorKeyClass(
            StringBuilder sb,
            Object nativeMetadata,
            Class<?> nativeClass,
            Class<?> keyClass,
            String sectionTitle
    ) {
        sb.append('\n');
        sb.append("-- ")
                .append(sectionTitle)
                .append(" --\n");

        try {
            Method vendorMethod =
                    findGetAllVendorKeysMethod(nativeClass);

            if (vendorMethod == null) {
                sb.append(
                        "getAllVendorKeys(Class): NOT FOUND\n"
                );
                return;
            }

            Object result;

            if (Modifier.isStatic(vendorMethod.getModifiers())) {
                result = vendorMethod.invoke(
                        null,
                        keyClass
                );
            } else {
                result = vendorMethod.invoke(
                        nativeMetadata,
                        keyClass
                );
            }

            if (result == null) {
                sb.append("<null>\n");
                return;
            }

            if (!(result instanceof List<?>)) {
                sb.append("Unexpected return type: ")
                        .append(result.getClass().getName())
                        .append('\n');
                return;
            }

            List<?> list = (List<?>) result;

            if (list.isEmpty()) {
                sb.append("<none>\n");
                return;
            }

            List<Object> copy =
                    new ArrayList<>(list);

            copy.sort((a, b) ->
                    keyName(a).compareTo(keyName(b)));

            int priorityHits = 0;

            for (Object key : copy) {
                String name = keyName(key);
                String type = keyType(key);
                String tag = keyTag(key);
                String vendorId = keyVendorId(key);

                boolean priority =
                        HIGH_PRIORITY_NAMES.contains(name);

                if (priority) {
                    priorityHits++;
                }

                sb.append(
                        priority
                                ? "[HIGH] "
                                : "[VENDOR] "
                );

                sb.append(name);

                sb.append("    type=")
                        .append(type);

                sb.append("    tag=")
                        .append(tag);

                sb.append("    vendorId=")
                        .append(vendorId);

                if (priority) {
                    sb.append(
                            "    <<< PRIORITY CANDIDATE"
                    );
                }

                sb.append('\n');
            }

            sb.append("Count: ")
                    .append(copy.size())
                    .append('\n');

            sb.append("Priority candidate hits: ")
                    .append(priorityHits)
                    .append('\n');

        } catch (InvocationTargetException e) {
            sb.append(
                    "InvocationTargetException\n"
            );

            Throwable cause = e.getCause();

            if (cause != null) {
                appendThrowable(
                        sb,
                        "  cause: ",
                        cause
                );
            } else {
                appendThrowable(
                        sb,
                        "  ",
                        e
                );
            }

        } catch (Throwable t) {
            appendThrowable(sb, "", t);
        }
    }

    private void dumpCameras(StringBuilder sb)
            throws Exception {

        sb.append("============================================================\n");
        sb.append("CAMERA CHARACTERISTICS\n");
        sb.append("============================================================\n");

        CameraManager manager = (CameraManager)
                getSystemService(Context.CAMERA_SERVICE);

        if (manager == null) {
            throw new IllegalStateException(
                    "CameraManager is null"
            );
        }

        String[] cameraIds =
                manager.getCameraIdList();

        if (cameraIds == null) {
            sb.append(
                    "Camera ID list: <null>\n"
            );
            return;
        }

        List<String> ids =
                new ArrayList<>();

        Collections.addAll(
                ids,
                cameraIds
        );

        ids.sort((a, b) -> {
            try {
                return Integer.compare(
                        Integer.parseInt(a),
                        Integer.parseInt(b)
                );
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        });

        sb.append("Camera IDs: ")
                .append(ids)
                .append('\n');

        sb.append(
                "NOTE: IDs 0/1/2/3 are the four "
                        + "physical/logical targets previously "
                        + "identified; all IDs are dumped for "
                        + "correlation.\n\n"
        );

        for (String id : ids) {
            dumpCamera(
                    manager,
                    id,
                    sb
            );
        }
    }

    private void dumpCamera(
            CameraManager manager,
            String id,
            StringBuilder sb
    ) {
        sb.append(
                "------------------------------------------------------------\n"
        );

        sb.append("CAMERA ID ")
                .append(id)
                .append('\n');

        sb.append(
                "------------------------------------------------------------\n"
        );

        try {
            CameraCharacteristics c =
                    manager.getCameraCharacteristics(id);

            Integer facing =
                    c.get(
                            CameraCharacteristics.LENS_FACING
                    );

            Integer level =
                    c.get(
                            CameraCharacteristics
                                    .INFO_SUPPORTED_HARDWARE_LEVEL
                    );

            int[] caps =
                    c.get(
                            CameraCharacteristics
                                    .REQUEST_AVAILABLE_CAPABILITIES
                    );

            sb.append("LensFacing: ")
                    .append(facingToString(facing))
                    .append(" (")
                    .append(String.valueOf(facing))
                    .append(")\n");

            sb.append("HardwareLevel: ")
                    .append(hardwareLevelToString(level))
                    .append(" (")
                    .append(String.valueOf(level))
                    .append(")\n");

            sb.append("Capabilities: ")
                    .append(arrayToString(caps))
                    .append('\n');

            if (Build.VERSION.SDK_INT >= 28) {
                Set<String> physicalIds =
                        c.getPhysicalCameraIds();

                if (physicalIds != null
                        && !physicalIds.isEmpty()) {

                    sb.append("Physical IDs: ")
                            .append(physicalIds)
                            .append('\n');
                }
            }

            dumpRequestKeys(c, sb);

            if (Build.VERSION.SDK_INT >= 28) {
                dumpSessionKeys(c, sb);
                dumpPhysicalRequestKeys(c, sb);
            } else {
                sb.append(
                        "Session Keys: API < 28, unavailable\n"
                );

                sb.append(
                        "Physical Request Keys: API < 28, unavailable\n"
                );
            }

            dumpVisiblePriorityMatches(c, sb);

        } catch (SecurityException e) {
            sb.append("SECURITY ERROR: ")
                    .append(String.valueOf(e.getMessage()))
                    .append('\n');

        } catch (Exception e) {
            sb.append("ERROR: ")
                    .append(e.getClass().getName())
                    .append(": ")
                    .append(String.valueOf(e.getMessage()))
                    .append('\n');
        }

        sb.append('\n');
    }

    private void dumpRequestKeys(
            CameraCharacteristics c,
            StringBuilder sb
    ) {
        sb.append(
                "-- AVAILABLE CAPTURE REQUEST KEYS --\n"
        );

        try {
            List<CaptureRequest.Key<?>> keys =
                    c.getAvailableCaptureRequestKeys();

            dumpKeys(keys, sb);

        } catch (Exception e) {
            sb.append("ERROR: ")
                    .append(e.getClass().getName())
                    .append(": ")
                    .append(String.valueOf(e.getMessage()))
                    .append('\n');
        }
    }

    private void dumpSessionKeys(
            CameraCharacteristics c,
            StringBuilder sb
    ) {
        sb.append(
                "-- AVAILABLE SESSION KEYS --\n"
        );

        try {
            List<CaptureRequest.Key<?>> keys =
                    c.getAvailableSessionKeys();

            dumpKeys(keys, sb);

        } catch (Exception e) {
            sb.append("ERROR: ")
                    .append(e.getClass().getName())
                    .append(": ")
                    .append(String.valueOf(e.getMessage()))
                    .append('\n');
        }
    }

    private void dumpPhysicalRequestKeys(
            CameraCharacteristics c,
            StringBuilder sb
    ) {
        sb.append(
                "-- AVAILABLE PHYSICAL CAMERA REQUEST KEYS --\n"
        );

        try {
            List<CaptureRequest.Key<?>> keys =
                    c.getAvailablePhysicalCameraRequestKeys();

            if (keys == null || keys.isEmpty()) {
                sb.append("<none>\n");
            } else {
                dumpKeys(keys, sb);
            }

        } catch (Exception e) {
            sb.append("ERROR: ")
                    .append(e.getClass().getName())
                    .append(": ")
                    .append(String.valueOf(e.getMessage()))
                    .append('\n');
        }
    }

    private void dumpKeys(
            List<CaptureRequest.Key<?>> keys,
            StringBuilder sb
    ) {
        if (keys == null || keys.isEmpty()) {
            sb.append("<none>\n");
            return;
        }

        List<CaptureRequest.Key<?>> copy =
                new ArrayList<>(keys);

        copy.sort(
                Comparator.comparing(
                        this::safeRequestKeyName
                )
        );

        for (CaptureRequest.Key<?> key : copy) {
            String name =
                    safeRequestKeyName(key);

            boolean vendor =
                    isLikelyVendor(name);

            sb.append(
                    vendor
                            ? "[VENDOR] "
                            : "[STD]   "
            );

            sb.append(name);

            sb.append("    type=")
                    .append(
                            safeRequestKeyType(key)
                    );

            sb.append('\n');
        }

        sb.append("Count: ")
                .append(copy.size())
                .append('\n');
    }

    private void dumpVisiblePriorityMatches(
            CameraCharacteristics c,
            StringBuilder sb
    ) {
        sb.append(
                "-- PRIORITY NAMES VISIBLE TO THIS CAMERA --\n"
        );

        try {
            List<CaptureRequest.Key<?>> keys =
                    c.getAvailableCaptureRequestKeys();

            if (keys == null || keys.isEmpty()) {
                sb.append("<none>\n");
                return;
            }

            Set<String> names =
                    new HashSet<>();

            for (CaptureRequest.Key<?> key : keys) {
                names.add(
                        safeRequestKeyName(key)
                );
            }

            int hits = 0;

            for (String candidate :
                    HIGH_PRIORITY_NAMES) {

                if (names.contains(candidate)) {
                    sb.append("[VISIBLE] ")
                            .append(candidate)
                            .append('\n');

                    hits++;
                }
            }

            if (hits == 0) {
                sb.append("<none>\n");
            }

            sb.append(
                    "Priority visible count: "
            )
                    .append(hits)
                    .append('\n');

        } catch (Exception e) {
            appendThrowable(sb, "", e);
        }
    }

    private String safeRequestKeyName(
            CaptureRequest.Key<?> key
    ) {
        if (key == null) {
            return "<null-key>";
        }

        try {
            return key.getName();
        } catch (Throwable t) {
            return "<name-error:"
                    + t.getClass().getSimpleName()
                    + ">";
        }
    }

    private String safeRequestKeyType(
            CaptureRequest.Key<?> key
    ) {
        return keyType(key);
    }

    private String keyName(Object key) {
        if (key == null) {
            return "<null-key>";
        }

        try {
            Method getName =
                    findPublicMethod(
                            key.getClass(),
                            "getName"
                    );

            if (getName == null) {
                return "<getName-not-found>";
            }

            return String.valueOf(
                    getName.invoke(key)
            );

        } catch (Throwable t) {
            return "<name-error:"
                    + t.getClass().getSimpleName()
                    + ">";
        }
    }

    private String keyType(Object key) {
        if (key == null) {
            return "<null>";
        }

        try {
            Method getNativeKey =
                    findPublicMethod(
                            key.getClass(),
                            "getNativeKey"
                    );

            if (getNativeKey == null) {
                return "<getNativeKey-not-found>";
            }

            Object nativeKey =
                    getNativeKey.invoke(key);

            if (nativeKey == null) {
                return "<native-key-null>";
            }

            Method getType =
                    findPublicMethod(
                            nativeKey.getClass(),
                            "getType"
                    );

            if (getType == null) {
                return "<getType-not-found>";
            }

            Object type =
                    getType.invoke(nativeKey);

            if (type instanceof Class<?>) {
                return ((Class<?>) type).getName();
            }

            return String.valueOf(type);

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();

            if (cause != null) {
                return "<type-error:"
                        + cause.getClass().getSimpleName()
                        + ">";
            }

            return "<type-error:InvocationTargetException>";

        } catch (Throwable t) {
            return "<type-error:"
                    + t.getClass().getSimpleName()
                    + ">";
        }
    }

    private String keyTag(Object key) {
        if (key == null) {
            return "<null>";
        }

        try {
            Method getNativeKey =
                    findPublicMethod(
                            key.getClass(),
                            "getNativeKey"
                    );

            if (getNativeKey == null) {
                return "<getNativeKey-not-found>";
            }

            Object nativeKey =
                    getNativeKey.invoke(key);

            if (nativeKey == null) {
                return "<native-key-null>";
            }

            Method getTag =
                    findPublicMethod(
                            nativeKey.getClass(),
                            "getTag"
                    );

            if (getTag == null) {
                return "<getTag-not-found>";
            }

            Object tag =
                    getTag.invoke(nativeKey);

            if (tag instanceof Integer) {
                return String.format(
                        "0x%08x",
                        ((Integer) tag)
                );
            }

            return String.valueOf(tag);

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();

            if (cause != null) {
                return "<tag-error:"
                        + cause.getClass().getSimpleName()
                        + ">";
            }

            return "<tag-error:InvocationTargetException>";

        } catch (Throwable t) {
            return "<tag-error:"
                    + t.getClass().getSimpleName()
                    + ">";
        }
    }

    private String keyVendorId(Object key) {
        if (key == null) {
            return "<null>";
        }

        try {
            Method getVendorId =
                    findPublicMethod(
                            key.getClass(),
                            "getVendorId"
                    );

            if (getVendorId == null) {
                return "<getVendorId-not-found>";
            }

            Object vendorId =
                    getVendorId.invoke(key);

            if (vendorId instanceof Long) {
                return String.format(
                        "0x%016x",
                        ((Long) vendorId)
                );
            }

            return String.valueOf(vendorId);

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();

            if (cause != null) {
                return "<vendorId-error:"
                        + cause.getClass().getSimpleName()
                        + ">";
            }

            return "<vendorId-error:InvocationTargetException>";

        } catch (Throwable t) {
            return "<vendorId-error:"
                    + t.getClass().getSimpleName()
                    + ">";
        }
    }

    private Method findGetAllVendorKeysMethod(
            Class<?> nativeClass
    ) {
        for (Method method :
                nativeClass.getMethods()) {

            if (!"getAllVendorKeys".equals(
                    method.getName())) {
                continue;
            }

            Class<?>[] params =
                    method.getParameterTypes();

            if (params.length == 1
                    && params[0] == Class.class) {
                return method;
            }
        }

        return null;
    }

    private Method findPublicMethod(
            Class<?> startClass,
            String methodName
    ) {
        Class<?> current = startClass;

        while (current != null) {
            try {
                Method method =
                        current.getMethod(methodName);

                if (method != null) {
                    return method;
                }

            } catch (NoSuchMethodException ignored) {
                // Try superclass.
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private boolean isLikelyVendor(
            String name
    ) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        return !name.startsWith("android.")
                && !name.startsWith("com.android.");
    }

    private File saveReport(
            String report
    ) throws Exception {

        File dir =
                getExternalFilesDir(
                        Environment.DIRECTORY_DOCUMENTS
                );

        if (dir == null) {
            dir = getFilesDir();
        }

        if (!dir.exists()
                && !dir.mkdirs()) {

            throw new IllegalStateException(
                    "Unable to create report directory"
            );
        }

        File file =
                new File(
                        dir,
                        "Camera2VendorKeyDump.txt"
                );

        try (FileOutputStream out =
                     new FileOutputStream(
                             file,
                             false
                     )) {

            out.write(
                    report.getBytes(
                            StandardCharsets.UTF_8
                    )
            );
        }

        return file;
    }

    private void shareReport() {
        if (lastReport == null
                || lastReport.isEmpty()) {

            statusText.setText(
                    "Run Probe first."
            );
            return;
        }

        Intent intent =
                new Intent(Intent.ACTION_SEND);

        intent.setType("text/plain");

        intent.putExtra(
                Intent.EXTRA_SUBJECT,
                "Edge20Pro Camera2 Vendor Key Dump"
        );

        intent.putExtra(
                Intent.EXTRA_TEXT,
                lastReport
        );

        startActivity(
                Intent.createChooser(
                        intent,
                        "Share Camera2 key report"
                )
        );
    }

    private String facingToString(
            Integer v
    ) {
        if (v == null) {
            return "UNKNOWN";
        }

        if (v == CameraCharacteristics.LENS_FACING_BACK) {
            return "BACK";
        }

        if (v == CameraCharacteristics.LENS_FACING_FRONT) {
            return "FRONT";
        }

        if (v == CameraCharacteristics.LENS_FACING_EXTERNAL) {
            return "EXTERNAL";
        }

        return "UNKNOWN";
    }

    private String hardwareLevelToString(
            Integer v
    ) {
        if (v == null) {
            return "UNKNOWN";
        }

        switch (v) {
            case CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";

            case CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";

            case CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY";

            case CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3";

            case CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                return "EXTERNAL";

            default:
                return "UNKNOWN";
        }
    }

    private String arrayToString(
            int[] values
    ) {
        if (values == null) {
            return "<null>";
        }

        StringBuilder sb =
                new StringBuilder("[");

        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }

            sb.append(values[i]);
        }

        return sb.append(']').toString();
    }

    private void appendThrowable(
            StringBuilder sb,
            String prefix,
            Throwable t
    ) {
        sb.append(prefix)
                .append(t.getClass().getName())
                .append(": ")
                .append(String.valueOf(t.getMessage()))
                .append('\n');
    }
}
