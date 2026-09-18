package com.edge20pro.camerakeyprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
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

/**
 * Version 3: focuses on the vendor-tag resolution path actually exposed by
 * the Motorola Edge 20 Pro framework.
 *
 * NOTE: this file is intentionally versioned for Library tracking. Because
 * the Activity class is public and named MainActivity, copy it to the project
 * as MainActivity.java when replacing the app source file.
 */
public class MainActivity extends Activity {

    private static final String NATIVE_KEY_CLASS =
            "android.hardware.camera2.impl.CameraMetadataNative$Key";

    private static final String NATIVE_CLASS =
            "android.hardware.camera2.impl.CameraMetadataNative";

    private static final Candidate[] CANDIDATES = new Candidate[] {
            /* Known public vendor key: control test. */
            new Candidate(
                    "com.lenovo.moto.clientapp.is_motcamera2",
                    Byte.class,
                    0x809a0000
            ),

            new Candidate(
                    "com.lenovo.moto.control.mfnr_number_of_frames",
                    Byte.class,
                    0x809b0008
            ),
            new Candidate(
                    "com.lenovo.moto.control.mfnr_anchor_selection_mode",
                    Byte.class,
                    0x809b0009
            ),
            new Candidate(
                    "com.lenovo.moto.control.mfnr_anchor_selection_algo",
                    Byte.class,
                    0x809b000a
            ),
            new Candidate(
                    "com.lenovo.moto.envinfo.isMfnrEnabled",
                    Byte.class,
                    0x809c0006
            ),

            new Candidate(
                    "org.codeaurora.qcamera3.temporal_denoise.enable",
                    Byte.class,
                    0x80230000
            ),
            new Candidate(
                    "org.codeaurora.qcamera3.temporal_denoise.process_type",
                    Integer.class,
                    0x80230001
            ),

            new Candidate(
                    "com.lenovo.moto.adrc.enable",
                    Byte.class,
                    0x80a00000
            ),
            new Candidate(
                    "com.lenovo.moto.adrc.gain",
                    Float.class,
                    0x80a00001
            ),

            new Candidate(
                    "com.lenovo.moto.control.hdrplus",
                    Byte.class,
                    0x809b0006
            ),
            new Candidate(
                    "org.quic.camera.CustomNoiseReduction",
                    Byte.class,
                    0x803c0000
            ),

            new Candidate(
                    "OEMIFEIQSetting",
                    Byte.class,
                    0x80100000
            ),
            new Candidate(
                    "OEMBPSIQSetting",
                    Byte.class,
                    0x80100001
            ),
            new Candidate(
                    "OEMIPEIQSetting",
                    Byte.class,
                    0x80100002
            ),

            new Candidate(
                    "MFNRTotalNumFrames",
                    Integer.class,
                    0x80150000
            ),
            new Candidate(
                    "MFNRBlendFrameNum",
                    Integer.class,
                    0x80150001
            )
    };

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
        statusText.setText("Running vendor cache/tag probe...");

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
                statusText.setText(
                        reportFile != null
                                ? "Done: " + reportFile.getAbsolutePath()
                                : "Probe finished; report save failed"
                );
            });
        }).start();
    }

    private String buildReport() throws Exception {
        StringBuilder sb = new StringBuilder(128 * 1024);

        sb.append("Edge20Pro Camera2 Vendor Cache/Tag Probe\n");
        sb.append("Generated: ").append(new Date()).append('\n');
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Model: ").append(Build.MODEL).append('\n');
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("Target package: com.edge20pro.camerakeyprobe\n\n");

        dumpFrameworkShape(sb);
        sb.append('\n');

        probeStaticNativeMethods(sb);
        sb.append('\n');

        dumpCandidateResolution(sb);
        sb.append('\n');

        dumpPublicCameraKeys(sb);

        return sb.toString();
    }

    private void dumpFrameworkShape(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("FRAMEWORK CLASS SHAPE\n");
        sb.append("============================================================\n");

        inspectClass(sb, NATIVE_CLASS);
        inspectClass(sb, NATIVE_KEY_CLASS);
        inspectConstructors(sb, CaptureRequest.Key.class);

        try {
            Class<?> nativeKeyClass = Class.forName(NATIVE_KEY_CLASS);
            inspectConstructors(sb, nativeKeyClass);
        } catch (Throwable t) {
            appendThrowable(sb, "Native Key constructor inspection failed: ", t);
        }

        /* Confirm the exact runtime classes used by a real public vendor key. */
        try {
            CameraManager manager =
                    (CameraManager) getSystemService(Context.CAMERA_SERVICE);

            if (manager == null) {
                sb.append("CameraManager: null\n");
                return;
            }

            String[] ids = manager.getCameraIdList();
            if (ids == null || ids.length == 0) {
                sb.append("Camera IDs unavailable\n");
                return;
            }

            CameraCharacteristics c =
                    manager.getCameraCharacteristics(ids[0]);

            List<CaptureRequest.Key<?>> keys =
                    c.getAvailableCaptureRequestKeys();

            CaptureRequest.Key<?> sample = findKeyByName(
                    keys,
                    "com.lenovo.moto.clientapp.is_motcamera2"
            );

            if (sample == null && keys != null && !keys.isEmpty()) {
                sample = keys.get(0);
            }

            if (sample != null) {
                Object nativeKey = invokeNoArg(sample, "getNativeKey");

                sb.append("Sample CaptureRequest.Key class: ")
                        .append(sample.getClass().getName())
                        .append('\n');
                sb.append("Sample native Key class: ")
                        .append(nativeKey == null
                                ? "<null>"
                                : nativeKey.getClass().getName())
                        .append('\n');

                if (nativeKey != null) {
                    inspectObjectMethods(
                            sb,
                            nativeKey,
                            "getTag",
                            "cacheTag",
                            "getType",
                            "getTypeReference",
                            "getVendorId",
                            "hasTag"
                    );

                    inspectObjectFields(
                            sb,
                            nativeKey,
                            "mTag",
                            "mHasTag",
                            "mVendorId",
                            "mType",
                            "mTypeReference",
                            "mName",
                            "mFallbackName"
                    );
                }
            }
        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "Runtime class inspection failed: ",
                    t
            );
        }
    }

    private void probeStaticNativeMethods(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("CAMERA METADATA NATIVE METHOD PROBE\n");
        sb.append("============================================================\n");

        Class<?> nativeClass;

        try {
            nativeClass = Class.forName(NATIVE_CLASS);
        } catch (Throwable t) {
            appendThrowable(sb, "CameraMetadataNative lookup failed: ", t);
            return;
        }

        String controlName =
                "com.lenovo.moto.clientapp.is_motcamera2";

        /*
         * Try both historical getTag signatures.
         * If hidden API filtering hides the methods, this section records it.
         */
        probeStaticMethod(
                sb,
                nativeClass,
                "getTag",
                new Class<?>[] {String.class},
                new Object[] {controlName},
                "getTag(String) control test"
        );

        probeStaticMethod(
                sb,
                nativeClass,
                "getTag",
                new Class<?>[] {String.class, long.class},
                new Object[] {controlName, Long.MAX_VALUE},
                "getTag(String,long) control test"
        );

        probeStaticMethod(
                sb,
                nativeClass,
                "getNativeType",
                new Class<?>[] {int.class},
                new Object[] {0x809a0000},
                "getNativeType(int) control test"
        );

        probeStaticMethod(
                sb,
                nativeClass,
                "getNativeType",
                new Class<?>[] {int.class, long.class},
                new Object[] {0x809a0000, Long.MAX_VALUE},
                "getNativeType(int,long) control test"
        );

        probeStaticMethod(
                sb,
                nativeClass,
                "setupGlobalVendorTagDescriptor",
                new Class<?>[] {},
                new Object[] {},
                "setupGlobalVendorTagDescriptor()"
        );

        probeStaticMethod(
                sb,
                nativeClass,
                "nativeSetupGlobalVendorTagDescriptor",
                new Class<?>[] {},
                new Object[] {},
                "nativeSetupGlobalVendorTagDescriptor()"
        );
    }

    private void probeStaticMethod(
            StringBuilder sb,
            Class<?> clazz,
            String methodName,
            Class<?>[] parameterTypes,
            Object[] args,
            String label
    ) {
        sb.append(label).append(": ");

        Method method;

        try {
            method = clazz.getDeclaredMethod(
                    methodName,
                    parameterTypes
            );
        } catch (NoSuchMethodException e) {
            try {
                method = clazz.getMethod(
                        methodName,
                        parameterTypes
                );
            } catch (NoSuchMethodException e2) {
                sb.append("NOT FOUND\n");
                return;
            } catch (Throwable t) {
                sb.append("LOOKUP FAILED: ");
                appendThrowable(sb, "", t);
                return;
            }
        } catch (Throwable t) {
            sb.append("LOOKUP FAILED: ");
            appendThrowable(sb, "", t);
            return;
        }

        sb.append("FOUND ")
                .append(methodSignature(method))
                .append("\n");

        try {
            method.setAccessible(true);

            Object value = method.invoke(null, args);

            sb.append("  result: ")
                    .append(valueDescription(value))
                    .append('\n');

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            sb.append("  invocation failed: ");
            appendThrowable(
                    sb,
                    "",
                    cause != null ? cause : e
            );

        } catch (Throwable t) {
            sb.append("  invocation failed: ");
            appendThrowable(sb, "", t);
        }
    }

    private void dumpCandidateResolution(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("CANDIDATE / CACHE-TAG RESOLUTION\n");
        sb.append("============================================================\n");
        sb.append("The first candidate is a known public Lenovo vendor key and is\n");
        sb.append("used as a control. Hidden candidates are tested with the exact\n");
        sb.append("same CaptureRequest.Key(String, Class) construction path.\n");
        sb.append("Then cacheTag(expectedTag) is attempted on the native key.\n");
        sb.append("A successful cacheTag test proves Java can manufacture a request\n");
        sb.append("key with the HAL tag even when name->tag lookup is unavailable.\n\n");

        for (Candidate candidate : CANDIDATES) {
            probeCandidate(sb, candidate);
        }
    }

    private void probeCandidate(
            StringBuilder sb,
            Candidate candidate
    ) {
        sb.append("------------------------------------------------------------\n");
        sb.append(candidate.name).append('\n');
        sb.append("Expected Java type: ")
                .append(candidate.type.getName())
                .append('\n');
        sb.append("Expected HAL tag: ")
                .append(hex(candidate.expectedTag))
                .append('\n');

        CaptureRequest.Key<?> requestKey;

        try {
            Constructor<CaptureRequest.Key> constructor =
                    CaptureRequest.Key.class.getConstructor(
                            String.class,
                            Class.class
                    );

            requestKey = constructor.newInstance(
                    candidate.name,
                    candidate.type
            );

            sb.append("2-arg CaptureRequest.Key constructor: SUCCESS\n");

        } catch (Throwable t) {
            sb.append("2-arg CaptureRequest.Key constructor: FAILED\n");
            appendThrowable(sb, "  ", t);
            return;
        }

        Object nativeKey =
                invokeNoArg(requestKey, "getNativeKey");

        if (nativeKey == null) {
            sb.append("nativeKey: <null>\n");
            return;
        }

        sb.append("nativeKey class: ")
                .append(nativeKey.getClass().getName())
                .append('\n');

        Object before =
                invokeNoArg(nativeKey, "getTag");

        sb.append("getTag() before cache: ")
                .append(formatTag(before))
                .append('\n');

        Object vendorId =
                invokeNoArg(nativeKey, "getVendorId");

        sb.append("getVendorId(): ")
                .append(valueDescription(vendorId))
                .append('\n');

        Object hasTag =
                invokeNoArg(nativeKey, "hasTag");

        sb.append("hasTag() before cache: ")
                .append(valueDescription(hasTag))
                .append('\n');

        /* Direct static registry lookup, if this firmware exposes it. */
        probeCandidateStaticLookup(
                sb,
                candidate
        );

        /* This is the new critical test. */
        boolean cacheSuccess =
                invokeCacheTag(
                        sb,
                        nativeKey,
                        candidate.expectedTag
                );

        if (!cacheSuccess) {
            sb.append(
                    "RESULT: cacheTag() unavailable or blocked.\n"
            );
            return;
        }

        Object after =
                invokeNoArg(nativeKey, "getTag");

        sb.append("getTag() after cache: ")
                .append(formatTag(after))
                .append('\n');

        Object hasTagAfter =
                invokeNoArg(nativeKey, "hasTag");

        sb.append("hasTag() after cache: ")
                .append(valueDescription(hasTagAfter))
                .append('\n');

        if (after instanceof Integer
                && ((Integer) after) == candidate.expectedTag) {
            sb.append(
                    "RESULT: MANUAL TAG CACHE SUCCESS\n"
            );
        } else {
            sb.append(
                    "RESULT: cacheTag invoked but tag verification failed\n"
            );
        }
    }

    private void probeCandidateStaticLookup(
            StringBuilder sb,
            Candidate candidate
    ) {
        try {
            Class<?> nativeClass =
                    Class.forName(NATIVE_CLASS);

            Method oneArg = findDeclaredOrPublicMethod(
                    nativeClass,
                    "getTag",
                    String.class
            );

            if (oneArg != null) {
                oneArg.setAccessible(true);

                try {
                    Object value = oneArg.invoke(
                            null,
                            candidate.name
                    );

                    sb.append("static getTag(String): ")
                            .append(formatTag(value))
                            .append('\n');

                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    sb.append("static getTag(String): exception ");
                    appendThrowable(
                            sb,
                            "",
                            cause != null ? cause : e
                    );
                }
            } else {
                sb.append(
                        "static getTag(String): NOT FOUND\n"
                );
            }

            Method twoArg = findDeclaredOrPublicMethod(
                    nativeClass,
                    "getTag",
                    String.class,
                    long.class
            );

            if (twoArg != null) {
                twoArg.setAccessible(true);

                try {
                    Object value = twoArg.invoke(
                            null,
                            candidate.name,
                            Long.MAX_VALUE
                    );

                    sb.append("static getTag(String,long): ")
                            .append(formatTag(value))
                            .append('\n');

                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    sb.append(
                            "static getTag(String,long): exception "
                    );
                    appendThrowable(
                            sb,
                            "",
                            cause != null ? cause : e
                    );
                }
            } else {
                sb.append(
                        "static getTag(String,long): NOT FOUND\n"
                );
            }

        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "Static candidate lookup failed: ",
                    t
            );
        }
    }

    private boolean invokeCacheTag(
            StringBuilder sb,
            Object nativeKey,
            int tag
    ) {
        Method method =
                findDeclaredOrPublicMethod(
                        nativeKey.getClass(),
                        "cacheTag",
                        int.class
                );

        if (method == null) {
            sb.append("cacheTag(int): NOT FOUND\n");
            return false;
        }

        sb.append("cacheTag(int): FOUND ")
                .append(methodSignature(method))
                .append('\n');

        try {
            method.setAccessible(true);
            method.invoke(nativeKey, tag);
            sb.append("cacheTag invocation: SUCCESS\n");
            return true;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            sb.append("cacheTag invocation: FAILED\n");
            appendThrowable(
                    sb,
                    "  ",
                    cause != null ? cause : e
            );
            return false;
        } catch (Throwable t) {
            sb.append("cacheTag invocation: FAILED\n");
            appendThrowable(sb, "  ", t);
            return false;
        }
    }

    private void dumpPublicCameraKeys(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("PUBLIC CAMERA2 REQUEST KEYS\n");
        sb.append("============================================================\n");

        try {
            CameraManager manager =
                    (CameraManager) getSystemService(Context.CAMERA_SERVICE);

            if (manager == null) {
                sb.append("CameraManager: null\n");
                return;
            }

            List<String> ids = new ArrayList<>();
            String[] rawIds = manager.getCameraIdList();

            if (rawIds != null) {
                Collections.addAll(ids, rawIds);
            }

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
                sb.append("\n-- CAMERA ")
                        .append(id)
                        .append(" --\n");

                CameraCharacteristics c =
                        manager.getCameraCharacteristics(id);

                Integer facing =
                        c.get(CameraCharacteristics.LENS_FACING);

                Integer level =
                        c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

                sb.append("LensFacing: ")
                        .append(facingToString(facing))
                        .append(" hardware=")
                        .append(hardwareLevelToString(level))
                        .append('\n');

                List<CaptureRequest.Key<?>> keys =
                        c.getAvailableCaptureRequestKeys();

                dumpKeys(keys, sb);
            }

        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "Public key dump failed: ",
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
                        this::safeName
                )
        );

        for (CaptureRequest.Key<?> key : copy) {
            Object nativeKey =
                    invokeNoArg(key, "getNativeKey");

            Object tag =
                    nativeKey == null
                            ? null
                            : invokeNoArg(nativeKey, "getTag");

            sb.append(
                    isVendor(safeName(key))
                            ? "[VENDOR] "
                            : "[STD]   "
            )
                    .append(safeName(key))
                    .append("    tag=")
                    .append(formatTag(tag))
                    .append('\n');
        }

        sb.append("Count: ")
                .append(copy.size())
                .append('\n');
    }

    private CaptureRequest.Key<?> findKeyByName(
            List<CaptureRequest.Key<?>> keys,
            String name
    ) {
        if (keys == null) {
            return null;
        }

        for (CaptureRequest.Key<?> key : keys) {
            if (name.equals(safeName(key))) {
                return key;
            }
        }

        return null;
    }

    private void inspectClass(
            StringBuilder sb,
            String className
    ) {
        sb.append("Class: ")
                .append(className)
                .append('\n');

        try {
            Class<?> clazz =
                    Class.forName(className);

            List<String> signatures =
                    new ArrayList<>();

            Class<?> current = clazz;

            while (current != null) {
                try {
                    for (Method method :
                            current.getDeclaredMethods()) {

                        String n =
                                method.getName().toLowerCase();

                        if (n.contains("vendor")
                                || n.contains("tag")
                                || n.contains("type")
                                || n.contains("metadata")
                                || n.contains("key")) {

                            signatures.add(
                                    methodSignature(method)
                            );
                        }
                    }
                } catch (Throwable ignored) {
                    // Continue superclass inspection.
                }

                current =
                        current.getSuperclass();
            }

            Set<String> unique =
                    new HashSet<>(signatures);

            List<String> sorted =
                    new ArrayList<>(unique);

            Collections.sort(sorted);

            for (String signature : sorted) {
                sb.append("  ")
                        .append(signature)
                        .append('\n');
            }

        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "  inspection failed: ",
                    t
            );
        }
    }

    private void inspectConstructors(
            StringBuilder sb,
            Class<?> clazz
    ) {
        sb.append("Constructors: ")
                .append(clazz.getName())
                .append('\n');

        try {
            for (Constructor<?> constructor :
                    clazz.getDeclaredConstructors()) {

                sb.append("  ")
                        .append(
                                Modifier.toString(
                                        constructor.getModifiers()
                                )
                        )
                        .append(' ')
                        .append(clazz.getSimpleName())
                        .append('(');

                Class<?>[] params =
                        constructor.getParameterTypes();

                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }

                    sb.append(
                            params[i].getName()
                    );
                }

                sb.append(")\n");
            }

        } catch (Throwable t) {
            appendThrowable(
                    sb,
                    "  constructor inspection failed: ",
                    t
            );
        }
    }

    private void inspectObjectMethods(
            StringBuilder sb,
            Object object,
            String... wanted
    ) {
        Set<String> wantedSet =
                new HashSet<>(Arrays.asList(wanted));

        Set<String> seen =
                new HashSet<>();

        Class<?> current =
                object.getClass();

        while (current != null) {
            try {
                for (Method method :
                        current.getDeclaredMethods()) {

                    if (!wantedSet.contains(method.getName())) {
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
            } catch (Throwable ignored) {
            }

            current =
                    current.getSuperclass();
        }
    }

    private void inspectObjectFields(
            StringBuilder sb,
            Object object,
            String... wanted
    ) {
        Set<String> wantedSet =
                new HashSet<>(Arrays.asList(wanted));

        Set<String> seen =
                new HashSet<>();

        Class<?> current =
                object.getClass();

        while (current != null) {
            try {
                for (Field field :
                        current.getDeclaredFields()) {

                    if (!wantedSet.contains(field.getName())) {
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
            } catch (Throwable ignored) {
            }

            current =
                    current.getSuperclass();
        }
    }

    private Method findDeclaredOrPublicMethod(
            Class<?> clazz,
            String name,
            Class<?>... params
    ) {
        Class<?> current = clazz;

        while (current != null) {
            try {
                Method method =
                        current.getDeclaredMethod(
                                name,
                                params
                        );

                if (method != null) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Try superclass.
            } catch (Throwable ignored) {
                return null;
            }

            current =
                    current.getSuperclass();
        }

        try {
            return clazz.getMethod(
                    name,
                    params
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object invokeNoArg(
            Object target,
            String methodName
    ) {
        if (target == null) {
            return null;
        }

        Method method =
                findDeclaredOrPublicMethod(
                        target.getClass(),
                        methodName
                );

        if (method == null) {
            return null;
        }

        try {
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String safeName(
            CaptureRequest.Key<?> key
    ) {
        if (key == null) {
            return "<null>";
        }

        try {
            return key.getName();
        } catch (Throwable t) {
            return "<name-error>";
        }
    }

    private boolean isVendor(
            String name
    ) {
        return name != null
                && !name.startsWith("android.")
                && !name.startsWith("com.android.");
    }

    private String formatTag(Object value) {
        if (value instanceof Integer) {
            return hex((Integer) value);
        }

        if (value instanceof Long) {
            return String.format(
                    "0x%016x",
                    (Long) value
            );
        }

        return valueDescription(value);
    }

    private String valueDescription(Object value) {
        if (value == null) {
            return "<null>";
        }

        if (value instanceof Class<?>) {
            return ((Class<?>) value).getName();
        }

        return String.valueOf(value);
    }

    private String methodSignature(Method method) {
        StringBuilder sb =
                new StringBuilder();

        sb.append(
                Modifier.toString(
                        method.getModifiers()
                )
        )
                .append(' ')
                .append(
                        method.getReturnType().getName()
                )
                .append(' ')
                .append(
                        method.getDeclaringClass().getName()
                )
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

    private String hex(int value) {
        return String.format(
                "0x%08x",
                value
        );
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

        if (!dir.exists() && !dir.mkdirs()) {
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
                "Edge20Pro Camera2 Vendor Cache/Tag Probe"
        );
        intent.putExtra(
                Intent.EXTRA_TEXT,
                lastReport
        );

        startActivity(
                Intent.createChooser(
                        intent,
                        "Share Camera2 probe report"
                )
        );
    }

    private String facingToString(Integer value) {
        if (value == null) {
            return "UNKNOWN";
        }

        if (value == CameraCharacteristics.LENS_FACING_BACK) {
            return "BACK";
        }

        if (value == CameraCharacteristics.LENS_FACING_FRONT) {
            return "FRONT";
        }

        if (value == CameraCharacteristics.LENS_FACING_EXTERNAL) {
            return "EXTERNAL";
        }

        return "UNKNOWN";
    }

    private String hardwareLevelToString(Integer value) {
        if (value == null) {
            return "UNKNOWN";
        }

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
            Throwable throwable
    ) {
        if (throwable == null) {
            sb.append(prefix)
                    .append("<null throwable>\n");
            return;
        }

        sb.append(prefix)
                .append(throwable.getClass().getName())
                .append(": ")
                .append(String.valueOf(throwable.getMessage()))
                .append('\n');

        Throwable cause = throwable.getCause();

        if (cause != null && cause != throwable) {
            sb.append(prefix)
                    .append("cause: ");

            appendThrowable(
                    sb,
                    "",
                    cause
            );
        }
    }

    private static final class Candidate {
        final String name;
        final Class<?> type;
        final int expectedTag;

        Candidate(
                String name,
                Class<?> type,
                int expectedTag
        ) {
            this.name = name;
            this.type = type;
            this.expectedTag = expectedTag;
        }
    }
}
