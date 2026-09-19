#include <jni.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

#define LOG_TAG "Edge20ProVendorProbeV5"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Candidate {
    std::string name;
    int32_t expectedTag;
};

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

bool tagPresent(const uint32_t* tags, int32_t count, uint32_t wanted) {
    if (tags == nullptr || count <= 0) return false;
    for (int32_t i = 0; i < count; ++i) {
        if (tags[i] == wanted) return true;
    }
    return false;
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
        env->GetIntArrayRegion(array, 0, n, reinterpret_cast<jint*>(out.data()));
    }
    return out;
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
        const std::vector<std::string> candidateNames = toStrings(env, candidateNamesArray);
        const std::vector<int32_t> expectedTags = toInt32(env, expectedTagsArray);

        if (candidateNames.size() != expectedTags.size()) {
            report << "INVALID INPUT: candidateNames size="
                   << candidateNames.size()
                   << " expectedTags size="
                   << expectedTags.size()
                   << "\n";
            return env->NewStringUTF(report.str().c_str());
        }

        report << "NDK API: known vendor-tag ID presence probe\n";
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
                   << " count=" << allTagCount << "\n\n";

            int checkedCount = 0;
            int presentCount = 0;
            int entryOkCount = 0;

            for (size_t i = 0; i < candidateNames.size(); ++i) {
                const std::string& name = candidateNames[i];
                const int32_t expected = expectedTags[i];

                report << "------------------------------------------------------------\n";
                report << name << "\n";

                if (expected < 0) {
                    report << "expectedTag=<not-established>\n";
                    report << "status=SKIPPED\n";
                    continue;
                }

                const uint32_t tag = static_cast<uint32_t>(expected);
                ++checkedCount;

                report << "expectedTag=0x"
                       << std::hex << tag << std::dec << "\n";

                const bool inMetadata =
                        tagPresent(allTags, allTagCount, tag);

                report << "tagPresentInCameraCharacteristics="
                       << (inMetadata ? "YES" : "NO")
                       << "\n";

                if (inMetadata) {
                    ++presentCount;
                }

                ACameraMetadata_const_entry entry{};
                const camera_status_t entryStatus =
                        ACameraMetadata_getConstEntry(
                                metadata,
                                tag,
                                &entry);

                report << "getConstEntry: "
                       << statusName(entryStatus)
                       << " (" << static_cast<int>(entryStatus) << ")\n";

                if (entryStatus == ACAMERA_OK) {
                    ++entryOkCount;

                    report << "metadataType="
                           << typeName(entry.type)
                           << " (" << static_cast<int>(entry.type) << ")\n";

                    report << "valueCount="
                           << entry.count
                           << "\n";
                }
            }

            report << "\nCAMERA SUMMARY\n";
            report << "checkedCount=" << checkedCount << "\n";
            report << "presentCount=" << presentCount << "\n";
            report << "entryOkCount=" << entryOkCount << "\n";

            ACameraMetadata_free(metadata);
            report << '\n';
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
