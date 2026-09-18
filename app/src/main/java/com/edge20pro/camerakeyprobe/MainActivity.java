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

public class MainActivity extends Activity {

    private static final long NO_TAG = Long.MIN_VALUE;

    /**
     * Names and types come from the previously collected Edge 20 Pro
     * vendor-tag/HAL dump. Expected tags are included only where the
     * earlier dump established them.
     */
    private static final Candidate[] CANDIDATES = new Candidate[] {
            new Candidate("com.lenovo.moto.control.wnr_idx", Byte.class, null),
            new Candidate("com.lenovo.moto.control.wnr_type", Byte.class, null),

            new Candidate("com.lenovo.moto.control.mfnr_number_of_frames", Byte.class, 0x809b0008),
            new Candidate("com.lenovo.moto.control.mfnr_anchor_selection_mode", Byte.class, 0x809b0009),
            new Candidate("com.lenovo.moto.control.mfnr_anchor_selection_algo", Byte.class, 0x809b000a),
            new Candidate("com.lenovo.moto.envinfo.isMfnrEnabled", Byte.class, 0x809c0006),

            new Candidate("org.codeaurora.qcamera3.temporal_denoise.enable", Byte.class, 0x80230000),
            new Candidate("org.codeaurora.qcamera3.temporal_denoise.process_type", Integer.class, 0x80230001),

            new Candidate("com.lenovo.moto.adrc.enable", Byte.class, 0x80a00000),
            new Candidate("com.lenovo.moto.adrc.gain", Float.class, 0x80a00001),

            new Candidate("com.lenovo.moto.control.hdrplus", Byte.class, 0x809b0006),

            new Candidate("org.quic.camera.CustomNoiseReduction", Byte.class, 0x803c0000),

            new Candidate("OEMIFEIQSetting", Byte.class, 0x80100000),
            new Candidate("OEMBPSIQSetting", Byte.class, 0x80100001),
            new Candidate("OEMIPEIQSetting", Byte.class, 0x80100002),

            new Candidate("MFNRTotalNumFrames", Integer.class, 0x80150000),
            new Candidate("MFNRBlendFrameNum", Integer.class, 0x80150001)
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
        statusText.setText("Running vendor registry probe...");

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
                statusText.setText(reportFile != null
                        ? "Done: " + reportFile.getAbsolutePath()
                        : "Probe finished; report save failed");
            });
        }).start();
    }

    private String buildReport() throws Exception {
        StringBuilder sb = new StringBuilder(128 * 1024);

        sb.append("Edge20Pro Camera2 Vendor Registry Probe\n");
        sb.append("Generated: ").append(new Date()).append('\n');
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Model: ").append(Build.MODEL).append('\n');
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("Target package: com.edge20pro.camerakeyprobe\n\n");

        dumpFrameworkShape(sb);
        sb.append('\n');
        dumpCandidateResolution(sb);
        sb.append('\n');
        dumpPublicCameraKeys(sb);

        return sb.toString();
    }

    private void dumpFrameworkShape(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("FRAMEWORK API / REFLECTION SHAPE\n");
        sb.append("============================================================\n");

        inspectClass(sb, "android.hardware.camera2.impl.CameraMetadataNative");
        inspectClass(sb, "android.hardware.camera2.impl.CameraMetadataNative$Key");
        inspectClass(sb, "android.hardware.camera2.CaptureRequest$Key");
        inspectConstructors(sb, CaptureRequest.Key.class);

        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                sb.append("CameraManager: null\n");
                return;
            }

            String[] ids = manager.getCameraIdList();
            if (ids == null || ids.length == 0) {
                sb.append("No camera IDs\n");
                return;
            }

            CameraCharacteristics c = manager.getCameraCharacteristics(ids[0]);
            List<CaptureRequest.Key<?>> keys = c.getAvailableCaptureRequestKeys();

            if (keys != null && !keys.isEmpty()) {
                CaptureRequest.Key<?> sample = keys.get(0);
                Object nativeKey = invokeNoArg(sample, "getNativeKey");

                sb.append("Sample CaptureRequest.Key class: ")
                        .append(sample.getClass().getName()).append('\n');
                sb.append("Sample native key class: ")
                        .append(nativeKey == null ? "<null>" : nativeKey.getClass().getName())
                        .append('\n');

                if (nativeKey != null) {
                    inspectObjectMethods(sb, nativeKey,
                            "getTag", "getType", "getTypeReference", "getVendorId", "hasTag");
                    inspectObjectFields(sb, nativeKey,
                            "mTag", "mHasTag", "mVendorId", "mType", "mTypeReference", "mName");
                }
            }
        } catch (Throwable t) {
            appendThrowable(sb, "Sample key inspection failed: ", t);
        }
    }

    private void dumpCandidateResolution(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("CANDIDATE NAME -> TAG RESOLUTION\n");
        sb.append("============================================================\n");
        sb.append("Purpose: determine whether the Motorola vendor-tag registry can resolve\n");
        sb.append("the hidden names already established by the earlier HAL/vendor dump.\n");
        sb.append("A successful resolution is not yet proof that a key is accepted by\n");
        sb.append("the Camera2 request whitelist; it only proves name->tag resolution.\n\n");

        for (Candidate candidate : CANDIDATES) {
            probeCandidate(sb, candidate);
        }
    }

    private void probeCandidate(StringBuilder sb, Candidate candidate) {
        sb.append("------------------------------------------------------------\n");
        sb.append(candidate.name).append('\n');
        sb.append("Expected Java type: ")
                .append(candidate.type.getName()).append('\n');

        if (candidate.expectedTag != null) {
            sb.append("Expected HAL tag: ")
                    .append(hex(candidate.expectedTag)).append('\n');
        } else {
            sb.append("Expected HAL tag: <not established>\n");
        }

        boolean anySuccess = false;

        /*
         * Strategy A: hidden/vendor constructor (String, Class, long).
         * This is the framework path used by AOSP vendor extensions.
         */
        anySuccess |= tryConstructAndResolve(
                sb,
                candidate,
                new Class<?>[] {String.class, Class.class, long.class},
                new Object[] {candidate.name, candidate.type, Long.MAX_VALUE},
                "3-arg constructor, vendorId=Long.MAX_VALUE"
        );

        /*
         * Some older Qualcomm/Android camera stacks use the 2-arg Key
         * constructor with the default vendor-id behavior.
         */
        anySuccess |= tryConstructAndResolve(
                sb,
                candidate,
                new Class<?>[] {String.class, Class.class},
                new Object[] {candidate.name, candidate.type},
                "2-arg constructor"
        );

        /*
         * Try vendorId=0 as a diagnostic. This is not assumed to be the
         * device's actual provider ID; it simply checks whether this fork
         * uses a zero provider ID for its registry.
         */
        anySuccess |= tryConstructAndResolve(
                sb,
                candidate,
                new Class<?>[] {String.class, Class.class, long.class},
                new Object[] {candidate.name, candidate.type, 0L},
                "3-arg constructor, vendorId=0"
        );

        if (!anySuccess) {
            sb.append("RESULT: no candidate form resolved a numeric tag.\n");
        }
    }

    private boolean tryConstructAndResolve(
            StringBuilder sb,
            Candidate candidate,
            Class<?>[] parameterTypes,
            Object[] args,
            String label
    ) {
        Constructor<?> constructor = null;

        try {
            constructor = CaptureRequest.Key.class.getDeclaredConstructor(parameterTypes);
        } catch (Throwable ignored) {
            try {
                constructor = CaptureRequest.Key.class.getConstructor(parameterTypes);
            } catch (Throwable ignoredAgain) {
                sb.append(label).append(": constructor NOT FOUND\n");
                return false;
            }
        }

        try {
            constructor.setAccessible(true);
            Object key = constructor.newInstance(args);

            Object nativeKey = invokeNoArg(key, "getNativeKey");
            if (nativeKey == null) {
                sb.append(label).append(": constructed, nativeKey=<null>\n");
                return false;
            }

            Object tagObject = invokeNoArg(nativeKey, "getTag");
            if (!(tagObject instanceof Integer)) {
                sb.append(label).append(": constructed, getTag() returned ")
                        .append(valueDescription(tagObject)).append('\n');
                return false;
            }

            int tag = (Integer) tagObject;

            sb.append(label)
                    .append(": SUCCESS tag=")
                    .append(hex(tag));

            if (candidate.expectedTag != null) {
                sb.append(" matchExpected=")
                        .append(tag == candidate.expectedTag ? "YES" : "NO");
            }

            sb.append('\n');

            /* Try to obtain type/vendor ID from the actual constructed key. */
            Object resolvedType = invokeNoArg(nativeKey, "getType");
            Object vendorId = invokeNoArg(nativeKey, "getVendorId");

            sb.append("  resolved type: ")
                    .append(valueDescription(resolvedType)).append('\n');
            sb.append("  resolved vendorId: ")
                    .append(valueDescription(vendorId)).append('\n');

            return true;

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            sb.append(label).append(": invocation failed: ");
            appendThrowable(sb, "", cause != null ? cause : e);
            return false;
        } catch (Throwable t) {
            sb.append(label).append(": failed: ");
            appendThrowable(sb, "", t);
            return false;
        }
    }

    private void dumpPublicCameraKeys(StringBuilder sb) {
        sb.append("============================================================\n");
        sb.append("PUBLIC CAMERA2 REQUEST KEYS\n");
        sb.append("============================================================\n");

        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
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
                    return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                } catch (NumberFormatException e) {
                    return a.compareTo(b);
                }
            });

            sb.append("Camera IDs: ").append(ids).append('\n');

            for (String id : ids) {
                sb.append("\n-- CAMERA ").append(id).append(" --\n");
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

                sb.append("LensFacing: ")
                        .append(facingToString(facing))
                        .append(" hardware=")
                        .append(hardwareLevelToString(level))
                        .append('\n');

                List<CaptureRequest.Key<?>> keys = c.getAvailableCaptureRequestKeys();
                dumpKeys(keys, sb);
            }
        } catch (Throwable t) {
            appendThrowable(sb, "Public key dump failed: ", t);
        }
    }

    private void dumpKeys(List<CaptureRequest.Key<?>> keys, StringBuilder sb) {
        if (keys == null || keys.isEmpty()) {
            sb.append("<none>\n");
            return;
        }

        List<CaptureRequest.Key<?>> copy = new ArrayList<>(keys);
        copy.sort(Comparator.comparing(this::safeName));

        for (CaptureRequest.Key<?> key : copy) {
            Object nativeKey = invokeNoArg(key, "getNativeKey");
            Object tag = nativeKey == null ? null : invokeNoArg(nativeKey, "getTag");

            sb.append(isVendor(safeName(key)) ? "[VENDOR] " : "[STD]   ")
                    .append(safeName(key))
                    .append("    tag=")
                    .append(formatTagObject(tag))
                    .append('\n');
        }

        sb.append("Count: ").append(copy.size()).append('\n');
    }

    private void inspectClass(StringBuilder sb, String className) {
        sb.append("Class: ").append(className).append('\n');

        try {
            Class<?> clazz = Class.forName(className);
            List<String> signatures = new ArrayList<>();

            Class<?> current = clazz;
            while (current != null) {
                try {
                    for (Method method : current.getDeclaredMethods()) {
                        String n = method.getName().toLowerCase();
                        if (n.contains("vendor") || n.contains("tag")
                                || n.contains("type") || n.contains("metadata")
                                || n.contains("key")) {
                            signatures.add(methodSignature(method));
                        }
                    }
                } catch (Throwable ignored) {
                }
                current = current.getSuperclass();
            }

            Collections.sort(signatures);
            for (String signature : new HashSet<>(signatures)) {
                sb.append("  ").append(signature).append('\n');
            }
        } catch (Throwable t) {
            appendThrowable(sb, "  inspection failed: ", t);
        }
    }

    private void inspectConstructors(StringBuilder sb, Class<?> clazz) {
        sb.append("Constructors: ").append(clazz.getName()).append('\n');
        try {
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                sb.append("  ")
                        .append(Modifier.toString(constructor.getModifiers()))
                        .append(' ')
                        .append(clazz.getSimpleName())
                        .append('(');

                Class<?>[] params = constructor.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getName());
                }
                sb.append(")\n");
            }
        } catch (Throwable t) {
            appendThrowable(sb, "  constructor inspection failed: ", t);
        }
    }

    private void inspectObjectMethods(
            StringBuilder sb,
            Object object,
            String... wanted
    ) {
        Set<String> wantedSet = new HashSet<>(Arrays.asList(wanted));
        Set<String> seen = new HashSet<>();

        Class<?> current = object.getClass();
        while (current != null) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (!wantedSet.contains(method.getName())) continue;
                    String signature = methodSignature(method);
                    if (seen.add(signature)) {
                        sb.append("  METHOD ").append(signature).append('\n');
                    }
                }
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
    }

    private void inspectObjectFields(
            StringBuilder sb,
            Object object,
            String... wanted
    ) {
        Set<String> wantedSet = new HashSet<>(Arrays.asList(wanted));
        Set<String> seen = new HashSet<>();

        Class<?> current = object.getClass();
        while (current != null) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (!wantedSet.contains(field.getName())) continue;
                    String descriptor = Modifier.toString(field.getModifiers())
                            + " " + field.getType().getName()
                            + " " + field.getName();
                    if (seen.add(descriptor)) {
                        sb.append("  FIELD ").append(descriptor).append('\n');
                    }
                }
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
    }

    private String methodSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(Modifier.toString(method.getModifiers()))
                .append(' ')
                .append(method.getReturnType().getName())
                .append(' ')
                .append(method.getDeclaringClass().getName())
                .append('.')
                .append(method.getName())
                .append('(');

        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getName());
        }

        sb.append(')');
        return sb.toString();
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;

        Method method = findMethod(target.getClass(), methodName);
        if (method == null) return null;

        try {
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Method findMethod(Class<?> startClass, String name) {
        Class<?> current = startClass;

        while (current != null) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (name.equals(method.getName())
                            && method.getParameterTypes().length == 0) {
                        return method;
                    }
                }
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }

        try {
            for (Method method : startClass.getMethods()) {
                if (name.equals(method.getName())
                        && method.getParameterTypes().length == 0) {
                    return method;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private String safeName(CaptureRequest.Key<?> key) {
        if (key == null) return "<null>";
        try {
            return key.getName();
        } catch (Throwable t) {
            return "<name-error>";
        }
    }

    private boolean isVendor(String name) {
        return name != null
                && !name.startsWith("android.")
                && !name.startsWith("com.android.");
    }

    private String formatTagObject(Object value) {
        if (value instanceof Integer) {
            return hex((Integer) value);
        }
        return valueDescription(value);
    }

    private String valueDescription(Object value) {
        if (value == null) return "<null>";
        if (value instanceof Class<?>) return ((Class<?>) value).getName();
        return String.valueOf(value);
    }

    private String hex(int value) {
        return String.format("0x%08x", value);
    }

    private File saveReport(String report) throws Exception {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) dir = getFilesDir();

        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create report directory");
        }

        File file = new File(dir, "Camera2VendorKeyDump.txt");
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
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
        intent.putExtra(Intent.EXTRA_SUBJECT,
                "Edge20Pro Camera2 Vendor Registry Probe");
        intent.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(intent, "Share Camera2 probe report"));
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
            Throwable throwable
    ) {
        if (throwable == null) {
            sb.append(prefix).append("<null throwable>\n");
            return;
        }

        sb.append(prefix)
                .append(throwable.getClass().getName())
                .append(": ")
                .append(String.valueOf(throwable.getMessage()))
                .append('\n');

        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            sb.append(prefix).append("cause: ");
            appendThrowable(sb, "", cause);
        }
    }

    private static final class Candidate {
        final String name;
        final Class<?> type;
        final Integer expectedTag;

        Candidate(String name, Class<?> type, Integer expectedTag) {
            this.name = name;
            this.type = type;
            this.expectedTag = expectedTag;
        }
    }
}
