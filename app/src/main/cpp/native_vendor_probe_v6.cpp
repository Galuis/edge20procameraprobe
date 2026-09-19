#include <jni.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "Edge20ProVendorProbeV7"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string statusName(camera_status_t status) {
    switch (status) {
        case ACAMERA_OK: return "ACAMERA_OK";
        case ACAMERA_ERROR_UNKNOWN: return "ACAMERA_ERROR_UNKNOWN";
        case ACAMERA_ERROR_INVALID_PARAMETER: return "ACAMERA_ERROR_INVALID_PARAMETER";
        case ACAMERA_ERROR_CAMERA_SERVICE: return "ACAMERA_ERROR_CAMERA_SERVICE";
        case ACAMERA_ERROR_METADATA_NOT_FOUND: return "ACAMERA_ERROR_METADATA_NOT_FOUND";
        case ACAMERA_ERROR_NOT_ENOUGH_MEMORY: return "ACAMERA_ERROR_NOT_ENOUGH_MEMORY";
        case ACAMERA_ERROR_UNSUPPORTED_OPERATION: return "ACAMERA_ERROR_UNSUPPORTED_OPERATION";
        case ACAMERA_ERROR_PERMISSION_DENIED: return "ACAMERA_ERROR_PERMISSION_DENIED";
        default: return "STATUS_" + std::to_string(static_cast<int>(status));
    }
}

const char* typeName(uint8_t type) {
    switch (type) {
        case ACAMERA_TYPE_BYTE: return "BYTE";
        case ACAMERA_TYPE_INT32: return "INT32";
        case ACAMERA_TYPE_FLOAT: return "FLOAT";
        case ACAMERA_TYPE_INT64: return "INT64";
        case ACAMERA_TYPE_DOUBLE: return "DOUBLE";
        case ACAMERA_TYPE_RATIONAL: return "RATIONAL";
        default: return "UNKNOWN";
    }
}

std::vector<std::string> toStrings(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) return out;

    const jsize n = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(n));

    for (jsize i = 0; i < n; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (js == nullptr) {
            out.emplace_back();
            continue;
        }

        const char* chars = env->GetStringUTFChars(js, nullptr);
        out.emplace_back(chars != nullptr ? chars : "");
        if (chars != nullptr) env->ReleaseStringUTFChars(js, chars);
        env->DeleteLocalRef(js);
    }
    return out;
}

std::vector<int32_t> toInt32(JNIEnv* env, jintArray array) {
    std::vector<int32_t> out;
    if (array == nullptr) return out;

    const jsize n = env->GetArrayLength(array);
    out.resize(static_cast<size_t>(n));

    if (n > 0) {
        env->GetIntArrayRegion(
                array,
                0,
                n,
                reinterpret_cast<jint*>(out.data()));
    }
    return out;
}

bool containsTag(const std::vector<int32_t>& tags, uint32_t wanted) {
    const int32_t signedWanted = static_cast<int32_t>(wanted);
    return std::find(tags.begin(), tags.end(), signedWanted) != tags.end();
}

int findTagIndex(const std::vector<int32_t>& tags, uint32_t wanted) {
    const int32_t signedWanted = static_cast<int32_t>(wanted);
    const auto it = std::find(tags.begin(), tags.end(), signedWanted);
    if (it == tags.end()) return -1;
    return static_cast<int>(std::distance(tags.begin(), it));
}

camera_status_t readInt32Array(
        const ACameraMetadata* metadata,
        uint32_t metadataTag,
        std::vector<int32_t>* out,
        uint8_t* outType,
        uint32_t* outCount) {
    out->clear();
    if (outType != nullptr) *outType = 0;
    if (outCount != nullptr) *outCount = 0;

    ACameraMetadata_const_entry entry{};
    const camera_status_t status =
            ACameraMetadata_getConstEntry(metadata, metadataTag, &entry);

    if (status != ACAMERA_OK) {
        return status;
    }

    if (outType != nullptr) *outType = entry.type;
    if (outCount != nullptr) *outCount = entry.count;

    if (entry.type != ACAMERA_TYPE_INT32) {
        return ACAMERA_ERROR_UNKNOWN;
    }

    if (entry.count > 0 && entry.data.i32 != nullptr) {
        out->assign(entry.data.i32, entry.data.i32 + entry.count);
    }

    return ACAMERA_OK;
}

void reportAvailableKeyList(
        std::ostringstream& report,
        const ACameraMetadata* metadata,
        uint32_t metadataTag,
        const char* label,
        const std::vector<std::string>& candidateNames,
        const std::vector<int32_t>& expectedTags) {
    std::vector<int32_t> keys;
    uint8_t actualType = 0;
    uint32_t actualCount = 0;

    const camera_status_t status = readInt32Array(
            metadata,
            metadataTag,
            &keys,
            &actualType,
            &actualCount);

    report << "\n[" << label << "]\n";
    report << "status=" << statusName(status)
           << " (" << static_cast<int>(status) << ")\n";

    if (status != ACAMERA_OK) {
        if (actualType != 0) {
            report << "metadataType="
                   << typeName(actualType)
                   << " (" << static_cast<int>(actualType) << ")\n";
        }
        if (actualCount != 0) {
            report << "valueCount=" << actualCount << "\n";
        }
        return;
    }

    report << "metadataType="
           << typeName(actualType)
           << " (" << static_cast<int>(actualType) << ")\n";
    report << "valueCount=" << actualCount << "\n";

    int presentCount = 0;

    for (size_t i = 0; i < candidateNames.size(); ++i) {
        const std::string& name = candidateNames[i];
        const int32_t expected = expectedTags[i];

        report << "------------------------------------------------------------\n";
        report << name << "\n";

        if (expected == -1) {
            report << "expectedTag=<not-established>\n";
            report << "present=SKIPPED\n";
            continue;
        }

        const uint32_t tag = static_cast<uint32_t>(expected);
        const bool present = containsTag(keys, tag);
        const int index = findTagIndex(keys, tag);

        report << "expectedTag=0x"
               << std::hex << tag << std::dec << "\n";
        report << "present=" << (present ? "YES" : "NO") << "\n";
        report << "index=" << index << "\n";

        if (present) ++presentCount;
    }

    report << "presentCount=" << presentCount << "\n";
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_edge20pro_camerakeyprobe_MainActivity_nativeProbeVendorTags(
        JNIEnv* env,
        jclass /* clazz */,
        jobjectArray cameraIdsArray,
        jobjectArray candidateNamesArray,
        jintArray expectedTagsArray) {

    std::ostringstream report;

    try {
        const std::vector<std::string> cameraIds = toStrings(env, cameraIdsArray);
        const std::vector<std::string> candidateNames =
                toStrings(env, candidateNamesArray);
        const std::vector<int32_t> expectedTags =
                toInt32(env, expectedTagsArray);

        if (candidateNames.size() != expectedTags.size()) {
            report << "INVALID INPUT: candidateNames size="
                   << candidateNames.size()
                   << " expectedTags size="
                   << expectedTags.size()
                   << "\n";
            return env->NewStringUTF(report.str().c_str());
        }

        report << "NDK API: AVAILABLE_*_KEYS probe\n";
        report << "Purpose: determine whether known HAL vendor tag IDs are\n";
        report << "listed as Camera2 request/result/session/physical-request keys.\n";
        report << "Camera count: " << cameraIds.size() << "\n";
        report << "Candidate count: " << candidateNames.size() << "\n\n";

        ACameraManager* manager = ACameraManager_create();
        if (manager == nullptr) {
            report << "ACameraManager_create(): FAILED / returned null\n";
            return env->NewStringUTF(report.str().c_str());
        }

        for (const std::string& cameraId : cameraIds) {
            report << "============================================================\n";
            report << "CAMERA " << cameraId << "\n";
            report << "============================================================\n";

            ACameraMetadata* metadata = nullptr;
            const camera_status_t charStatus =
                    ACameraManager_getCameraCharacteristics(
                            manager,
                            cameraId.c_str(),
                            &metadata);

            report << "getCameraCharacteristics: "
                   << statusName(charStatus)
                   << " (" << static_cast<int>(charStatus) << ")\n";

            if (charStatus != ACAMERA_OK || metadata == nullptr) {
                report << "No static metadata for this camera.\n\n";
                continue;
            }

            int32_t allTagCount = 0;
            const uint32_t* allTags = nullptr;
            const camera_status_t allTagsStatus =
                    ACameraMetadata_getAllTags(
                            metadata,
                            &allTagCount,
                            &allTags);

            report << "getAllTags: "
                   << statusName(allTagsStatus)
                   << " (" << static_cast<int>(allTagsStatus) << ")"
                   << " count=" << allTagCount << "\n";

            reportAvailableKeyList(
                    report,
                    metadata,
                    ACAMERA_REQUEST_AVAILABLE_REQUEST_KEYS,
                    "AVAILABLE_REQUEST_KEYS",
                    candidateNames,
                    expectedTags);

            reportAvailableKeyList(
                    report,
                    metadata,
                    ACAMERA_REQUEST_AVAILABLE_SESSION_KEYS,
                    "AVAILABLE_SESSION_KEYS",
                    candidateNames,
                    expectedTags);

            reportAvailableKeyList(
                    report,
                    metadata,
                    ACAMERA_REQUEST_AVAILABLE_RESULT_KEYS,
                    "AVAILABLE_RESULT_KEYS",
                    candidateNames,
                    expectedTags);

            reportAvailableKeyList(
                    report,
                    metadata,
                    ACAMERA_REQUEST_AVAILABLE_CHARACTERISTICS_KEYS,
                    "AVAILABLE_CHARACTERISTICS_KEYS",
                    candidateNames,
                    expectedTags);

            reportAvailableKeyList(
                    report,
                    metadata,
                    ACAMERA_REQUEST_AVAILABLE_PHYSICAL_CAMERA_REQUEST_KEYS,
                    "AVAILABLE_PHYSICAL_CAMERA_REQUEST_KEYS",
                    candidateNames,
                    expectedTags);

            ACameraMetadata_free(metadata);
            report << "\n";
        }

        ACameraManager_delete(manager);
    } catch (const std::exception& e) {
        LOGE("C++ exception: %s", e.what());
        report << "NATIVE C++ EXCEPTION: " << e.what() << "\n";
    } catch (...) {
        LOGE("Unknown C++ exception");
        report << "NATIVE UNKNOWN EXCEPTION\n";
    }

    return env->NewStringUTF(report.str().c_str());
}
