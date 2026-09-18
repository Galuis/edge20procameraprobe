package com.edge20pro.camerakeyprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
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
import java.lang.reflect.Field;
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

    private volatile String lastReport = "";
    private volatile File reportFile;

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
            } catch (Throwable t) {
                report = "FATAL PROBE ERROR\n";
                appendThrowable(
                        new StringBuilder(report),
                        "",
                        t
                );

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
                    statusText.setText(
                            "Probe finished; report save failed"
                    );
                }
            });

        }).start();
    }

    private String buildReport() throws Exception {
        StringBuilder sb = new StringBuilder(192 * 1024);

        sb.append("Edge20Pro Camera2 Vendor Key Probe\n");
        sb.append("Generated: ").append(new Date()).append('\n');
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Model: ").append(Build.MODEL).append('\n');
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("Target package: com.edge20pro.camerakeyprobe\n\n");

        dumpFrameworkInventory(sb);
        sb.append('\n');

        dumpGlobalVendorEnumeration(sb);
        sb.append('\n');

        dumpCameraCharacteristics(sb);

        return sb.toString();
    }

    /**
     * Enumerate the framework classes/methods we depend on.
     *
     * This is deliberately diagnostic. Motorola may have forked or
     * hidden/removed individual methods.
     */
    private void dumpFrameworkInventory(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("FRAMEWORK REFLECTION INVENTORY\n");
        sb.append("============================================================\n");

        dumpClassMethods(
                sb,
                CameraCharacteristics.class,
                new String[] {
                        "getNativeCopy",
                        "getNativeMetadata",
                        "getKeys",
                        "getAvailableCaptureRequestKeys"
                }
        );

        try {
            Class<?> cameraMetadataClass =
                    Class.forName(
                            "android.hardware.camera2.CameraMetadata"
                    );

            dumpClassMethods(
                    sb,
                    cameraMetadataClass,
                    new String[] {
                            "getNativeCopy",
                            "getNativeMetadata",
                            "getNativeMetadataPtr"
                    }
            );

        } catch (Throwable t) {
            sb.append(
                    "CameraMetadata class lookup failed:\n"
            );
            appendThrowable(sb, "  ", t);
        }

        try {
            Class<?> nativeClass =
                    Class.forName(
                            "android.hardware.camera2.impl.CameraMetadataNative"
                    );

            dumpClassMethods(
                    sb,
                    nativeClass,
                    new String[] {
                            "getAllVendorKeys",
                            "nativeGetAllVendorKeys",
                            "getTag",
                            "getNativeType",
                            "getMetadataPtr"
                    }
            );

            dumpClassFields(
                    sb,
                    nativeClass,
                    new String[] {
                            "mMetadataPtr"
                    }
            );

            Class<?> nativeKeyClass =
                    Class.forName(
                            "android.hardware.camera2.impl.CameraMetadataNative$Key"
                    );

            dumpClassMethods(
                    sb,
                    nativeKeyClass,
                    new String[] {
                            "getName",
                            "getType",
                            "getTypeReference",
                            "getTag",
                            "getVendorId",
                            "getNativeKey"
                    }
            );

            dumpClassFields(
                    sb,
                    nativeKeyClass,
                    new String[] {
                            "mName",
                            "mType",
                            "mTypeReference",
                            "mTag",
                            "mHasTag",
                            "mVendorId"
                    }
            );

        } catch (Throwable t) {
            sb.append(
                    "CameraMetadataNative inspection failed:\n"
            );
            appendThrowable(sb, "  ", t);
        }

        sb.append('\n');

        /*
         * Inspect one real CaptureRequest.Key instance because
         * Motorola can fork the wrapper class independently from
         * CameraMetadataNative.Key.
         */
        try {
            CameraManager manager =
                    (CameraManager) getSystemService(
                            Context.CAMERA_SERVICE
                    );

            if (manager != null) {
                String[] ids = manager.getCameraIdList();

                if (ids != null && ids.length > 0) {
                    CameraCharacteristics c =
                            manager.getCameraCharacteristics(ids[0]);

                    List<CaptureRequest.Key<?>> keys =
                            c.getAvailableCaptureRequestKeys();

                    if (keys != null && !keys.isEmpty()) {
                        CaptureRequest.Key<?> sample = keys.get(0);

                        sb.append(
                                "-- SAMPLE CAPTURE REQUEST KEY CLASS --\n"
                        );

                        sb.append("Class: ")
                                .append(sample.getClass().getName())
                                .append('\n');

                        dumpClassMethods(
                                sb,
                                sample.getClass(),
                                new String[] {
                                        "getName",
                                        "getType",
                                        "getNativeKey",
                                        "getTag",
                                        "getVendorId"
                                }
                        );

                        dumpClassFields(
                                sb,
                                sample.getClass(),
                                new String[] {
                                        "mKey"
                                }
                        );
                    }
                }
            }

        } catch (Throwable t) {
            sb.append(
                    "Sample CaptureRequest.Key inspection failed:\n"
            );
            appendThrowable(sb, "  ", t);
        }
    }

    private void dumpClassMethods(
            StringBuilder sb,
            Class<?> clazz,
            String[] wantedNames
    ) {
        sb.append(
                "Class: "
        )
                .append(clazz.getName())
                .append('\n');

        Set<String> wanted =
                new HashSet<>(
                        Arrays.asList(wantedNames)
                );

        Set<String> seen =
                new HashSet<>();

        Class<?> current = clazz;

        while (current != null) {
            Method[] methods;

            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                sb.append("  getDeclaredMethods failed: ")
                        .append(t.getClass().getName())
                        .append(": ")
                        .append(String.valueOf(t.getMessage()))
                        .append('\n');
                current = current.getSuperclass();
                continue;
            }

            for (Method method : methods) {
                if (!wanted.contains(method.getName())) {
                    continue;
                }

                String signature =
                        methodSignature(method);

                if (seen.add(signature)) {
                    sb.append("  METHOD ")
                            .append(signature)
                            .append('\n');
                }
            }

            current = current.getSuperclass();
        }

        /*
         * Also inspect public inherited methods, because some OEM
         * frameworks can expose methods only from a superclass.
         */
        try {
            for (Method method : clazz.getMethods()) {
                if (!wanted.contains(method.getName())) {
                    continue;
                }

                String signature =
                        methodSignature(method);

                if (seen.add(signature)) {
                    sb.append("  PUBLIC ")
                            .append(signature)
                            .append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("  getMethods failed: ")
                    .append(t.getClass().getName())
                    .append(": ")
                    .append(String.valueOf(t.getMessage()))
                    .append('\n');
        }
    }

    private void dumpClassFields(
            StringBuilder sb,
            Class<?> clazz,
            String[] wantedNames
    ) {
        sb.append(
                "Fields for: "
        )
                .append(clazz.getName())
                .append('\n');

        Set<String> wanted =
                new HashSet<>(
                        Arrays.asList(wantedNames)
                );

        Set<String> seen =
                new HashSet<>();

        Class<?> current = clazz;

        while (current != null) {
            Field[] fields;

            try {
                fields = current.getDeclaredFields();
            } catch (Throwable t) {
                sb.append("  getDeclaredFields failed: ")
                        .append(t.getClass().getName())
                        .append(": ")
                        .append(String.valueOf(t.getMessage()))
                        .append('\n');

                current = current.getSuperclass();
                continue;
            }

            for (Field field : fields) {
                if (!wanted.contains(field.getName())) {
                    continue;
                }

                String descriptor =
                        Modifier.toString(field.getModifiers())
                                + " "
                                + field.getType().getName()
                                + " "
                                + field.getName();

                if (seen.add(descriptor)) {
                    sb.append("  FIELD ")
                            .append(descriptor)
                            .append('\n');
                }
            }

            current = current.getSuperclass();
        }
    }

    private String methodSignature(Method method) {
        StringBuilder sb = new StringBuilder();

        sb.append(
                Modifier.toString(method.getModifiers())
        )
                .append(' ')
                .append(method.getReturnType().getName())
                .append(' ')
                .append(method.getDeclaringClass().getName())
                .append('.')
                .append(method.getName())
                .append('(');

        Class<?>[] params =
                method.getParameterTypes();

        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append(params[i].getName());
        }

        sb.append(')');

        return sb.toString();
    }

    /**
     * Try all known bridges to obtain the real CameraMetadataNative.
     */
    private Object obtainNativeMetadata(
            CameraCharacteristics characteristics,
            StringBuilder sb
    ) {
        sb.append(
                "============================================================\n"
        );

        sb.append(
                "NATIVE METADATA BRIDGE TEST\n"
        );

        sb.append(
                "============================================================\n"
        );

        /*
         * First choice: CameraCharacteristics.getNativeCopy()
         *
         * This exists in AOSP versions relevant to this investigation.
         */
        Object result =
                invokeNoArg(
                        characteristics,
                        "getNativeCopy",
                        sb
                );

        if (result != null) {
            sb.append(
                    "SUCCESS: CameraCharacteristics.getNativeCopy()\n"
            );

            sb.append("Returned class: ")
                    .append(result.getClass().getName())
                    .append('\n');

            return result;
        }

        /*
         * Second choice: inherited CameraMetadata.getNativeMetadata()
         */
        result =
                invokeNoArg(
                        characteristics,
                        "getNativeMetadata",
                        sb
                );

        if (result != null) {
            sb.append(
                    "SUCCESS: CameraMetadata.getNativeMetadata()\n"
            );

            sb.append("Returned class: ")
                    .append(result.getClass().getName())
                    .append('\n');

            return result;
        }

        /*
         * Third choice: private field mProperties in CameraCharacteristics.
         *
         * This is diagnostic fallback only.
         */
        result =
                readField(
                        characteristics,
                        "mProperties",
                        sb
                );

        if (result != null) {
            sb.append(
                    "SUCCESS: CameraCharacteristics.mProperties\n"
            );

            sb.append("Returned class: ")
                    .append(result.getClass().getName())
                    .append('\n');

            return result;
        }

        /*
         * Fourth choice: inherited CameraMetadata.mNativeInstance.
         *
         * Current AOSP stores the native object here.
         */
        result =
                readField(
                        characteristics,
                        "mNativeInstance",
                        sb
                );

        if (result != null) {
            sb.append(
                    "SUCCESS: CameraMetadata.mNativeInstance\n"
            );

            sb.append("Returned class: ")
                    .append(result.getClass().getName())
                    .append('\n');

            return result;
        }

        sb.append(
                "FAILED: no native metadata bridge available\n"
        );

        return null;
    }

    private void dumpGlobalVendorEnumeration(
            StringBuilder sb
    ) {
        sb.append(
                "============================================================\n"
        );

        sb.append(
                "ALL DEFINED VENDOR KEY ENUMERATION\n"
        );

        sb.append(
                "============================================================\n"
        );

        CameraManager manager =
                (CameraManager) getSystemService(
                        Context.CAMERA_SERVICE
                );

        if (manager == null) {
            sb.append("CameraManager: null\n");
            return;
        }

        String[] ids;

        try {
            ids = manager.getCameraIdList();
        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "getCameraIdList failed: ",
                    t
            );
            return;
        }

        if (ids == null || ids.length == 0) {
            sb.append("No camera IDs available\n");
            return;
        }

        CameraCharacteristics c;

        try {
            c =
                    manager.getCameraCharacteristics(
                            ids[0]
                    );
        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "getCameraCharacteristics failed: ",
                    t
            );
            return;
        }

        Object nativeMetadata =
                obtainNativeMetadata(
                        c,
                        sb
                );

        if (nativeMetadata == null) {
            sb.append(
                    "Vendor enumeration skipped because native metadata "
                            + "object could not be obtained.\n"
            );
            return;
        }

        Class<?> nativeClass =
                nativeMetadata.getClass();

        sb.append('\n');

        dumpAllVendorKeyClass(
                sb,
                nativeMetadata,
                nativeClass,
                CaptureRequest.Key.class,
                "VENDOR CAPTURE REQUEST KEYS"
        );

        sb.append('\n');

        dumpAllVendorKeyClass(
                sb,
                nativeMetadata,
                nativeClass,
                CaptureResult.Key.class,
                "VENDOR CAPTURE RESULT KEYS"
        );

        sb.append('\n');

        dumpAllVendorKeyClass(
                sb,
                nativeMetadata,
                nativeClass,
                CameraCharacteristics.Key.class,
                "VENDOR CAMERA CHARACTERISTIC KEYS"
        );
    }

    private void dumpAllVendorKeyClass(
            StringBuilder sb,
            Object nativeMetadata,
            Class<?> nativeClass,
            Class<?> keyClass,
            String title
    ) {
        sb.append(
                "-- "
        )
                .append(title)
                .append(" --\n");

        Method method =
                findVendorEnumerationMethod(
                        nativeClass
                );

        if (method == null) {
            sb.append(
                    "getAllVendorKeys(Class): NOT FOUND\n"
            );

            /*
             * Print ALL candidate methods containing Vendor/Key.
             * This is important for OEM forks.
             */
            dumpRelatedMethods(
                    sb,
                    nativeClass
            );

            return;
        }

        sb.append(
                "Using: "
        )
                .append(
                        methodSignature(method)
                )
                .append('\n');

        try {
            Object result;

            if (Modifier.isStatic(
                    method.getModifiers()
            )) {
                result =
                        method.invoke(
                                null,
                                keyClass
                        );
            } else {
                result =
                        method.invoke(
                                nativeMetadata,
                                keyClass
                        );
            }

            if (result == null) {
                sb.append("<null>\n");
                return;
            }

            if (!(result instanceof List<?>)) {
                sb.append(
                        "Unexpected return type: "
                )
                        .append(
                                result.getClass().getName()
                        )
                        .append('\n');

                return;
            }

            List<?> list =
                    (List<?>) result;

            if (list.isEmpty()) {
                sb.append("<none>\n");
                return;
            }

            List<Object> copy =
                    new ArrayList<>(list);

            copy.sort(
                    Comparator.comparing(
                            this::keyName
                    )
            );

            int priorityCount = 0;

            for (Object key : copy) {
                String name =
                        keyName(key);

                boolean priority =
                        HIGH_PRIORITY_NAMES.contains(
                                name
                        );

                if (priority) {
                    priorityCount++;
                }

                sb.append(
                        priority
                                ? "[HIGH] "
                                : "[VENDOR] "
                );

                sb.append(name)
                        .append("    type=")
                        .append(keyType(key))
                        .append("    tag=")
                        .append(keyTag(key))
                        .append("    vendorId=")
                        .append(keyVendorId(key));

                if (priority) {
                    sb.append(
                            "    <<< PRIORITY CANDIDATE"
                    );
                }

                sb.append('\n');
            }

            sb.append(
                    "Count: "
            )
                    .append(copy.size())
                    .append('\n');

            sb.append(
                    "Priority candidate hits: "
            )
                    .append(priorityCount)
                    .append('\n');

        } catch (InvocationTargetException e) {
            sb.append(
                    "InvocationTargetException\n"
            );

            Throwable cause =
                    e.getCause();

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
            appendThrowable(
                    sb,
                    "",
                    t
            );
        }
    }

    private Method findVendorEnumerationMethod(
            Class<?> nativeClass
    ) {
        /*
         * Search declared methods first.
         */
        Class<?> current = nativeClass;

        while (current != null) {
            try {
                for (Method method :
                        current.getDeclaredMethods()) {

                    if (!"getAllVendorKeys".equals(
                            method.getName()
                    )) {
                        continue;
                    }

                    Class<?>[] params =
                            method.getParameterTypes();

                    if (params.length == 1
                            && params[0] == Class.class) {
                        return method;
                    }
                }

            } catch (Throwable ignored) {
                // Continue superclass search.
            }

            current =
                    current.getSuperclass();
        }

        /*
         * Then search public inherited methods.
         */
        try {
            for (Method method :
                    nativeClass.getMethods()) {

                if (!"getAllVendorKeys".equals(
                        method.getName()
                )) {
                    continue;
                }

                Class<?>[] params =
                        method.getParameterTypes();

                if (params.length == 1
                        && params[0] == Class.class) {
                    return method;
                }
            }

        } catch (Throwable ignored) {
        }

        return null;
    }

    private void dumpRelatedMethods(
            StringBuilder sb,
            Class<?> nativeClass
    ) {
        sb.append(
                "Related methods in CameraMetadataNative:\n"
        );

        Set<String> seen =
                new HashSet<>();

        Class<?> current =
                nativeClass;

        while (current != null) {
            try {
                for (Method method :
                        current.getDeclaredMethods()) {

                    String name =
                            method.getName()
                                    .toLowerCase();

                    if (!name.contains("vendor")
                            && !name.contains("metadata")
                            && !name.contains("tag")
                            && !name.contains("key")) {
                        continue;
                    }

                    String signature =
                            methodSignature(method);

                    if (seen.add(signature)) {
                        sb.append("  ")
                                .append(signature)
                                .append('\n');
                    }
                }
            } catch (Throwable ignored) {
            }

            current =
                    current.getSuperclass();
        }
    }

    private void dumpCameraCharacteristics(
            StringBuilder sb
    ) throws Exception {

        sb.append(
                "============================================================\n"
        );

        sb.append(
                "CAMERA CHARACTERISTICS\n"
        );

        sb.append(
                "============================================================\n"
        );

        CameraManager manager =
                (CameraManager) getSystemService(
                        Context.CAMERA_SERVICE
                );

        if (manager == null) {
            throw new IllegalStateException(
                    "CameraManager is null"
            );
        }

        String[] cameraIds =
                manager.getCameraIdList();

        if (cameraIds == null) {
            sb.append(
                    "Camera IDs: <null>\n"
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
                "\n------------------------------------------------------------\n"
        );

        sb.append("CAMERA ID ")
                .append(id)
                .append('\n');

        sb.append(
                "------------------------------------------------------------\n"
        );

        try {
            CameraCharacteristics c =
                    manager.getCameraCharacteristics(
                            id
                    );

            Integer facing =
                    c.get(
                            CameraCharacteristics.LENS_FACING
                    );

            Integer level =
                    c.get(
                            CameraCharacteristics
                                    .INFO_SUPPORTED_HARDWARE_LEVEL
                    );

            int[] capabilities =
                    c.get(
                            CameraCharacteristics
                                    .REQUEST_AVAILABLE_CAPABILITIES
                    );

            sb.append("LensFacing: ")
                    .append(
                            facingToString(facing)
                    )
                    .append(" (")
                    .append(String.valueOf(facing))
                    .append(")\n");

            sb.append("HardwareLevel: ")
                    .append(
                            hardwareLevelToString(level)
                    )
                    .append(" (")
                    .append(String.valueOf(level))
                    .append(")\n");

            sb.append("Capabilities: ")
                    .append(
                            arrayToString(
                                    capabilities
                            )
                    )
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

            dumpRequestKeys(
                    c,
                    sb
            );

            if (Build.VERSION.SDK_INT >= 28) {
                dumpSessionKeys(
                        c,
                        sb
                );

                dumpPhysicalRequestKeys(
                        c,
                        sb
                );
            }

        } catch (SecurityException e) {
            sb.append(
                    "SECURITY ERROR: "
            )
                    .append(
                            String.valueOf(
                                    e.getMessage()
                            )
                    )
                    .append('\n');

        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "ERROR: ",
                    t
            );
        }
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

            dumpKeys(
                    keys,
                    sb
            );

        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "ERROR: ",
                    t
            );
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

            dumpKeys(
                    keys,
                    sb
            );

        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "ERROR: ",
                    t
            );
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
                dumpKeys(
                        keys,
                        sb
                );
            }

        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "ERROR: ",
                    t
            );
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

            sb.append(
                    isLikelyVendor(name)
                            ? "[VENDOR] "
                            : "[STD]   "
            );

            sb.append(name)
                    .append("    type=")
                    .append(
                            keyType(key)
                    )
                    .append("    tag=")
                    .append(
                            keyTag(key)
                    )
                    .append("    vendorId=")
                    .append(
                            keyVendorId(key)
                    )
                    .append('\n');
        }

        sb.append("Count: ")
                .append(copy.size())
                .append('\n');
    }

    private String safeRequestKeyName(
            CaptureRequest.Key<?> key
    ) {
        return keyName(key);
    }

    private String keyName(Object key) {
        if (key == null) {
            return "<null-key>";
        }

        /*
         * First choice: getName() on wrapper/native key.
         */
        Object value =
                invokeNoArg(
                        key,
                        "getName",
                        null
                );

        if (value != null) {
            return String.valueOf(value);
        }

        /*
         * Fallback: getNativeKey() -> getName().
         */
        Object nativeKey =
                getNativeKey(
                        key
                );

        if (nativeKey != null) {
            value =
                    invokeNoArg(
                            nativeKey,
                            "getName",
                            null
                    );

            if (value != null) {
                return String.valueOf(value);
            }

            Object field =
                    readField(
                            nativeKey,
                            "mName",
                            null
                    );

            if (field != null) {
                return String.valueOf(field);
            }
        }

        return "<name-unavailable>";
    }

    private String keyType(Object key) {
        Object nativeKey =
                getNativeKey(key);

        if (nativeKey != null) {
            /*
             * Preferred path on AOSP.
             */
            Object value =
                    invokeNoArg(
                            nativeKey,
                            "getType",
                            null
                    );

            if (value instanceof Class<?>) {
                return ((Class<?>) value).getName();
            }

            if (value != null) {
                return String.valueOf(value);
            }

            /*
             * OEM fallback: inspect mType directly.
             */
            value =
                    readField(
                            nativeKey,
                            "mType",
                            null
                    );

            if (value instanceof Class<?>) {
                return ((Class<?>) value).getName();
            }

            if (value != null) {
                return String.valueOf(value);
            }

            value =
                    readField(
                            nativeKey,
                            "mTypeReference",
                            null
                    );

            if (value != null) {
                return String.valueOf(value);
            }
        }

        /*
         * Some framework forks may place getType directly on wrapper.
         */
        Object wrapperType =
                invokeNoArg(
                        key,
                        "getType",
                        null
                );

        if (wrapperType instanceof Class<?>) {
            return ((Class<?>) wrapperType).getName();
        }

        if (wrapperType != null) {
            return String.valueOf(wrapperType);
        }

        return "<type-unavailable>";
    }

    private String keyTag(Object key) {
        Object nativeKey =
                getNativeKey(key);

        if (nativeKey == null) {
            return "<native-key-unavailable>";
        }

        Object tag =
                invokeNoArg(
                        nativeKey,
                        "getTag",
                        null
                );

        if (tag instanceof Integer) {
            return String.format(
                    "0x%08x",
                    (Integer) tag
            );
        }

        /*
         * Check cached fields.
         */
        Object hasTag =
                readField(
                        nativeKey,
                        "mHasTag",
                        null
                );

        Object fieldTag =
                readField(
                        nativeKey,
                        "mTag",
                        null
                );

        if (fieldTag instanceof Integer
                && (!(hasTag instanceof Boolean)
                || (Boolean) hasTag)) {

            return String.format(
                    "0x%08x",
                    (Integer) fieldTag
            );
        }

        /*
         * Final fallback: CameraMetadataNative.getTag(name,vendorId)
         */
        String name =
                keyName(key);

        long vendorId =
                keyVendorIdAsLong(key);

        if (!"<name-unavailable>".equals(name)) {
            Integer resolved =
                    resolveTagStatic(
                            name,
                            vendorId
                    );

            if (resolved != null) {
                return String.format(
                        "0x%08x",
                        resolved
                );
            }
        }

        return "<tag-unavailable>";
    }

    private String keyVendorId(Object key) {
        long value =
                keyVendorIdAsLong(key);

        if (value == Long.MIN_VALUE) {
            return "<vendorId-unavailable>";
        }

        return String.format(
                "0x%016x",
                value
        );
    }

    private long keyVendorIdAsLong(
            Object key
    ) {
        Object nativeKey =
                getNativeKey(key);

        Object value =
                invokeNoArg(
                        nativeKey,
                        "getVendorId",
                        null
                );

        if (value instanceof Long) {
            return (Long) value;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        value =
                readField(
                        nativeKey,
                        "mVendorId",
                        null
                );

        if (value instanceof Long) {
            return (Long) value;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        /*
         * Framework default used by AOSP when no explicit vendor ID
         * is stored in the key.
         */
        return Long.MIN_VALUE;
    }

    private Object getNativeKey(
            Object key
    ) {
        if (key == null) {
            return null;
        }

        Object nativeKey =
                invokeNoArg(
                        key,
                        "getNativeKey",
                        null
                );

        if (nativeKey != null) {
            return nativeKey;
        }

        return readField(
                key,
                "mKey",
                null
        );
    }

    private Integer resolveTagStatic(
            String name,
            long vendorId
    ) {
        if (name == null) {
            return null;
        }

        try {
            Class<?> nativeClass =
                    Class.forName(
                            "android.hardware.camera2.impl.CameraMetadataNative"
                    );

            /*
             * Search the vendor-aware 2-argument form first.
             */
            Method twoArg =
                    findMethod(
                            nativeClass,
                            "getTag",
                            2
                    );

            if (twoArg != null) {
                Object value =
                        twoArg.invoke(
                                null,
                                name,
                                vendorId == Long.MIN_VALUE
                                        ? Long.MAX_VALUE
                                        : vendorId
                        );

                if (value instanceof Integer) {
                    return (Integer) value;
                }
            }

            /*
             * Older framework: getTag(String)
             */
            Method oneArg =
                    findMethod(
                            nativeClass,
                            "getTag",
                            1
                    );

            if (oneArg != null) {
                Object value =
                        oneArg.invoke(
                                null,
                                name
                        );

                if (value instanceof Integer) {
                    return (Integer) value;
                }
            }

        } catch (Throwable ignored) {
        }

        return null;
    }

    private Method findMethod(
            Class<?> startClass,
            String methodName,
            int parameterCount
    ) {
        Class<?> current =
                startClass;

        while (current != null) {
            try {
                for (Method method :
                        current.getDeclaredMethods()) {

                    if (!methodName.equals(
                            method.getName()
                    )) {
                        continue;
                    }

                    if (method.getParameterTypes()
                            .length
                            == parameterCount) {

                        return method;
                    }
                }
            } catch (Throwable ignored) {
            }

            current =
                    current.getSuperclass();
        }

        try {
            for (Method method :
                    startClass.getMethods()) {

                if (!methodName.equals(
                        method.getName()
                )) {
                    continue;
                }

                if (method.getParameterTypes()
                        .length
                        == parameterCount) {

                    return method;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private Object invokeNoArg(
            Object target,
            String methodName,
            StringBuilder optionalLog
    ) {
        if (target == null) {
            return null;
        }

        Method method =
                findMethod(
                        target.getClass(),
                        methodName,
                        0
                );

        if (method == null) {
            if (optionalLog != null) {
                optionalLog.append(
                        methodName
                )
                        .append(
                                "(): NOT FOUND on "
                        )
                        .append(
                                target.getClass().getName()
                        )
                        .append('\n');
            }

            return null;
        }

        try {
            if (!method.canAccess(
                    Modifier.isStatic(method.getModifiers())
                            ? null
                            : target
            )) {
                method.setAccessible(true);
            }

            return method.invoke(
                    Modifier.isStatic(
                            method.getModifiers()
                    )
                            ? null
                            : target
            );

        } catch (Throwable t) {
            if (optionalLog != null) {
                optionalLog.append(
                        methodName
                )
                        .append(
                                "() invocation failed: "
                        )
                        .append(
                                t.getClass().getName()
                        )
                        .append(": ")
                        .append(
                                String.valueOf(
                                        t.getMessage()
                                )
                        )
                        .append('\n');
            }

            return null;
        }
    }

    private Object readField(
            Object target,
            String fieldName,
            StringBuilder optionalLog
    ) {
        if (target == null) {
            return null;
        }

        Class<?> current =
                target.getClass();

        while (current != null) {
            try {
                Field field =
                        current.getDeclaredField(
                                fieldName
                        );

                field.setAccessible(true);

                return field.get(target);

            } catch (NoSuchFieldException e) {
                current =
                        current.getSuperclass();

            } catch (Throwable t) {
                if (optionalLog != null) {
                    optionalLog.append(
                            "Field "
                    )
                            .append(fieldName)
                            .append(
                                    " read failed on "
                            )
                            .append(
                                    target.getClass()
                                            .getName()
                            )
                            .append(": ")
                            .append(
                                    t.getClass()
                                            .getName()
                            )
                            .append(": ")
                            .append(
                                    String.valueOf(
                                            t.getMessage()
                                    )
                            )
                            .append('\n');
                }

                return null;
            }
        }

        if (optionalLog != null) {
            optionalLog.append(
                    "Field "
            )
                    .append(fieldName)
                    .append(" not found on ")
                    .append(
                            target.getClass()
                                    .getName()
                    )
                    .append('\n');
        }

        return null;
    }

    private boolean isLikelyVendor(
            String name
    ) {
        if (name == null
                || name.isEmpty()) {
            return false;
        }

        return !name.startsWith("android.")
                && !name.startsWith(
                        "com.android."
                );
    }

    private String facingToString(
            Integer value
    ) {
        if (value == null) {
            return "UNKNOWN";
        }

        if (value
                == CameraCharacteristics
                        .LENS_FACING_BACK) {
            return "BACK";
        }

        if (value
                == CameraCharacteristics
                        .LENS_FACING_FRONT) {
            return "FRONT";
        }

        if (value
                == CameraCharacteristics
                        .LENS_FACING_EXTERNAL) {
            return "EXTERNAL";
        }

        return "UNKNOWN";
    }

    private String hardwareLevelToString(
            Integer value
    ) {
        if (value == null) {
            return "UNKNOWN";
        }

        switch (value) {
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

        for (int i = 0;
             i < values.length;
             i++) {

            if (i > 0) {
                sb.append(',');
            }

            sb.append(values[i]);
        }

        sb.append(']');

        return sb.toString();
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

        intent.setType(
                "text/plain"
        );

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

    private void appendThrowable(
            StringBuilder sb,
            String prefix,
            Throwable throwable
    ) {
        sb.append(prefix)
                .append(
                        throwable.getClass()
                                .getName()
                )
                .append(": ")
                .append(
                        String.valueOf(
                                throwable.getMessage()
                        )
                )
                .append('\n');

        Throwable cause =
                throwable.getCause();

        if (cause != null
                && cause != throwable) {

            sb.append(prefix)
                    .append("cause: ");

            appendThrowable(
                    sb,
                    "",
                    cause
            );
        }
    }
}
