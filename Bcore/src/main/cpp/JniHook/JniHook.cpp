//
// Created by Milk on 3/8/21.
//

#include <jni.h>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"
#include "Utils/PointerCheck.h"

static struct {
    int api_level = 0;
    unsigned int art_field_size = 0;
    int art_field_flags_offset = 0;

    unsigned int art_method_size = 0;
    int art_method_flags_offset = 0;
    int art_method_native_offset = 0;

    int class_flags_offset = 0;

    jclass method_utils_class = nullptr;
    jmethodID get_method_desc_id = nullptr;
    jmethodID get_method_declaring_class_id = nullptr;
    jmethodID get_method_name_id = nullptr;

} HookEnv;

static const char *GetMethodDesc(JNIEnv *env, jobject javaMethod) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_desc_id) return "";
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_desc_id,
                                                                      javaMethod));
    if (!desc) return "";
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodDeclaringClass(JNIEnv *env, jobject javaMethod) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_declaring_class_id) return "";
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_declaring_class_id,
                                                                      javaMethod));
    if (!desc) return "";
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodName(JNIEnv *env, jobject javaMethod) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_name_id) return "";
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_name_id,
                                                                      javaMethod));
    if (!desc) return "";
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

inline static uint32_t GetAccessFlags(const char *art_method) {
    if (art_method == nullptr || (uintptr_t) art_method < 0x10000 || HookEnv.art_method_flags_offset == 0) return 0;
    if (!PointerCheck::check(const_cast<char *>(art_method + HookEnv.art_method_flags_offset))) return 0;
    return *reinterpret_cast<const uint32_t *>(art_method + HookEnv.art_method_flags_offset);
}

inline static bool SetAccessFlags(char *art_method, uint32_t flags) {
    if (art_method == nullptr || (uintptr_t) art_method < 0x10000 || HookEnv.art_method_flags_offset == 0) return false;
    if (!PointerCheck::check(art_method + HookEnv.art_method_flags_offset)) return false;
    *reinterpret_cast<uint32_t *>(art_method + HookEnv.art_method_flags_offset) = flags;
    return true;
}

inline static bool AddAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag | flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool ClearAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag & ~flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool HasAccessFlag(char *art_method, uint32_t flag) {
    uint32_t flags = GetAccessFlags(art_method);
    return (flags & flag) == flag;
}

inline static bool ClearFastNativeFlag(char *art_method) {
    // FastNative
    return HookEnv.api_level < __ANDROID_API_P__ && ClearAccessFlag(art_method, kAccFastNative);
}

static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId) {
    if (methodId == nullptr) return nullptr;
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return ((uintptr_t) methodId >= 0x10000 && PointerCheck::check(methodId)) ? methodId : nullptr;
        }
        if (executable != nullptr) {
            jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                artId = nullptr;
            }
            if (artId != nullptr) {
                jobject method = env->ToReflectedMethod(clazz, methodId, true);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    method = nullptr;
                }
                if (method != nullptr) {
                    jlong res = env->GetLongField(method, artId);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    } else if ((uintptr_t) res >= 0x10000 && PointerCheck::check(reinterpret_cast<void *>(res))) {
                        return reinterpret_cast<void *>(res);
                    }
                }
            }
        }
        return ((uintptr_t) methodId >= 0x10000 && PointerCheck::check(methodId)) ? methodId : nullptr;
    } else {
        return ((uintptr_t) methodId >= 0x10000 && PointerCheck::check(methodId)) ? methodId : nullptr;
    }
}

static void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (field == nullptr) return nullptr;
    jclass fieldClass = env->FindClass("java/lang/reflect/Field");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        fieldClass = nullptr;
    }
    if (fieldClass != nullptr) {
        jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            getArtField = nullptr;
        }
        if (getArtField != nullptr) {
            jlong res = env->CallLongMethod(field, getArtField);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            } else if ((uintptr_t) res >= 0x10000 && PointerCheck::check(reinterpret_cast<void *>(res))) {
                return reinterpret_cast<void *>(res);
            }
        }

        jfieldID artFieldField = env->GetFieldID(fieldClass, "artField", "J");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            artFieldField = nullptr;
        }
        if (artFieldField != nullptr) {
            jlong res = env->GetLongField(field, artFieldField);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            } else if ((uintptr_t) res >= 0x10000 && PointerCheck::check(reinterpret_cast<void *>(res))) {
                return reinterpret_cast<void *>(res);
            }
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    jfieldID fId = env->FromReflectedField(field);
    if ((uintptr_t) fId >= 0x10000 && PointerCheck::check(reinterpret_cast<void *>(fId))) {
        return reinterpret_cast<void *>(fId);
    }
    return nullptr;
}

bool CheckFlags(void *artMethod) {
    if (artMethod == nullptr || (uintptr_t) artMethod < 0x10000 || !PointerCheck::check(artMethod) || HookEnv.art_method_flags_offset == 0) {
        return true;
    }
    char *method = static_cast<char *>(artMethod);
    // FastNative
    if (HookEnv.api_level < __ANDROID_API_P__) {
        ClearAccessFlag(method, kAccFastNative);
        if (HookEnv.api_level == __ANDROID_API_O__ || HookEnv.api_level == __ANDROID_API_O_MR1__) {
            AddAccessFlag(method, kAccFastNative);
        }
    }
    return true;
}

bool JniHook::HookJniFun(JNIEnv *env, jobject java_method, void *new_fun,
                         void **orig_fun, bool is_static) {
    const char *class_name = GetMethodDeclaringClass(env, java_method);
    const char *method_name = GetMethodName(env, java_method);
    const char *sign = GetMethodDesc(env, java_method);
    return HookJniFun(env, class_name, method_name, sign, new_fun, orig_fun, is_static);
}

bool
JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign,
                    void *new_fun, void **orig_fun, bool is_static) {
    if (HookEnv.art_method_native_offset == 0) {
        return false;
    }
    jclass clazz = env->FindClass(class_name);
    if (!clazz) {
        ALOGD("findClass fail: %s %s", class_name, method_name);
        env->ExceptionClear();
        return false;
    }
    jmethodID method = nullptr;
    if (is_static) {
        method = env->GetStaticMethodID(clazz, method_name, sign);
    } else {
        method = env->GetMethodID(clazz, method_name, sign);
    }
    if (!method) {
        env->ExceptionClear();
        ALOGD("get method id fail: %s %s", class_name, method_name);
        return false;
    }
    JNINativeMethod gMethods[] = {
            {method_name, sign, (void *) new_fun},
    };

    void *artMethodPtr = GetArtMethod(env, clazz, method);
    if (!artMethodPtr || (uintptr_t) artMethodPtr < 0x10000 || !PointerCheck::check(artMethodPtr)) {
        ALOGD("GetArtMethod fail: %s %s", class_name, method_name);
        return false;
    }
    auto artMethod = reinterpret_cast<uintptr_t *>(artMethodPtr);
    if (!PointerCheck::check(&artMethod[HookEnv.art_method_native_offset])) {
        ALOGD("artMethod offset check fail: %s %s", class_name, method_name);
        return false;
    }
    // read the original entry point before the registration replaces it
    void *orig = reinterpret_cast<void *>(artMethod[HookEnv.art_method_native_offset]);
    // RegisterNatives is the only reliable way to tell whether the target is still a native
    // method: art rejects the registration of a plain java method, and that is exactly what
    // happened to java.io.UnixFileSystem.canonicalize0(String) from android 13 on.
    if (env->RegisterNatives(clazz, gMethods, 1) < 0) {
        // a pending exception aborts the vm on the next jni call
        env->ExceptionClear();
        ALOGD("register natives fail: %s %s", class_name, method_name);
        return false;
    }
    *orig_fun = orig;
    CheckFlags(artMethod);
    ALOGD("register class:%s, method:%s success!", class_name, method_name);
    return true;
}

__attribute__((section (".mytext")))  JNICALL void native_offset
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void native_offset2
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void set_method_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject method) {
    if (method == nullptr || HookEnv.art_method_flags_offset == 0) return;
    jmethodID methodId = env->FromReflectedMethod(method);
    if (methodId == nullptr || (uintptr_t) methodId < 0x10000) return;
    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId));
    if (art_method == nullptr || (uintptr_t) art_method < 0x10000 || !PointerCheck::check(art_method)) return;
    AddAccessFlag(art_method, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(art_method, kAccPublicApi);
    }
}

__attribute__((section (".mytext")))  JNICALL void set_field_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject field) {
    if (field == nullptr || HookEnv.art_field_flags_offset == 0) return;
    char *artField = static_cast<char *>(GetFieldMethod(env, field));
    if (artField == nullptr || (uintptr_t) artField < 0x10000 || !PointerCheck::check(artField)) return;
    uint32_t *access_flags = reinterpret_cast<uint32_t *>(artField + HookEnv.art_field_flags_offset);
    if (!PointerCheck::check(access_flags)) return;
    *access_flags |= kAccPublic;
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        *access_flags |= kAccPublicApi;
    }
    *access_flags &= ~kAccFinal;
}

void registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (!clazz) {
        env->ExceptionClear();
        return;
    }
    JNINativeMethod gMethods[] = {
            {"nativeOffset",  "()V",                                            (void *) native_offset},
            {"nativeOffset2", "()V",                                            (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V", (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",  (void *) set_field_accessible},
    };
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        env->ExceptionClear();
        ALOGE("jni register error.");
    }
}

void JniHook::InitJniHook(JNIEnv *env, int api_level) {
    registerNative(env);
    HookEnv.api_level = api_level;

    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (!clazz) {
        env->ExceptionClear();
        return;
    }
    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");

    jfieldID nativeOffsetFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    jfieldID nativeOffsetField2Id = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");

    void *nativeOffsetField = nullptr;
    void *nativeOffsetField2 = nullptr;
    if (nativeOffsetFieldId && nativeOffsetField2Id) {
        jobject rField1 = env->ToReflectedField(clazz, nativeOffsetFieldId, true);
        jobject rField2 = env->ToReflectedField(clazz, nativeOffsetField2Id, true);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (rField1) nativeOffsetField = GetFieldMethod(env, rField1);
        if (rField2) nativeOffsetField2 = GetFieldMethod(env, rField2);
    }

    bool is_field_valid = (nativeOffsetField != nullptr && 
                           (uintptr_t) nativeOffsetField >= 0x10000 && 
                           PointerCheck::check(nativeOffsetField));
    bool is_field2_valid = (nativeOffsetField2 != nullptr && 
                            (uintptr_t) nativeOffsetField2 >= 0x10000 && 
                            PointerCheck::check(nativeOffsetField2));

    if (is_field_valid && is_field2_valid) {
        size_t f1 = (size_t) nativeOffsetField;
        size_t f2 = (size_t) nativeOffsetField2;
        if (f2 > f1 && (f2 - f1) <= 128) {
            HookEnv.art_field_size = f2 - f1;
        } else if (f1 > f2 && (f1 - f2) <= 128) {
            HookEnv.art_field_size = f1 - f2;
        } else {
            HookEnv.art_field_size = 32;
        }
    } else {
        HookEnv.art_field_size = 0;
    }

    void *nativeOffset = nullptr;
    void *nativeOffset2 = nullptr;
    if (nativeOffsetId && nativeOffset2Id) {
        nativeOffset = GetArtMethod(env, clazz, nativeOffsetId);
        nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id);
    }

    bool is_method_valid = (nativeOffset != nullptr && 
                            (uintptr_t) nativeOffset >= 0x10000 && 
                            PointerCheck::check(nativeOffset));
    bool is_method2_valid = (nativeOffset2 != nullptr && 
                             (uintptr_t) nativeOffset2 >= 0x10000 && 
                             PointerCheck::check(nativeOffset2));

    if (is_method_valid && is_method2_valid) {
        size_t m1 = (size_t) nativeOffset;
        size_t m2 = (size_t) nativeOffset2;
        if (m2 > m1 && (m2 - m1) <= 256) {
            HookEnv.art_method_size = m2 - m1;
        } else if (m1 > m2 && (m1 - m2) <= 256) {
            HookEnv.art_method_size = m1 - m2;
        } else {
            HookEnv.art_method_size = 64;
        }
    } else {
        HookEnv.art_method_size = 0;
    }

    // calc native offset
    if (is_method_valid && HookEnv.art_method_size > 0) {
        auto artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
        for (int i = 0; i < HookEnv.art_method_size; ++i) {
            if (!PointerCheck::check(&artMethod[i])) break;
            if (reinterpret_cast<void *>(artMethod[i]) == native_offset) {
                HookEnv.art_method_native_offset = i;
                break;
            }
        }

        uint32_t flags = 0x0;
        flags = flags | kAccPublic;
        flags = flags | kAccStatic;
        flags = flags | kAccNative;
        flags = flags | kAccFinal;
        if (api_level >= __ANDROID_API_Q__) {
            flags = flags | kAccPublicApi;
        }

        char *start = reinterpret_cast<char *>(artMethod);
        for (int i = 1; i < HookEnv.art_method_size; ++i) {
            if (!PointerCheck::check(start + i * sizeof(uint32_t))) break;
            auto value = *(uint32_t *) (start + i * sizeof(uint32_t));
            if (value == flags) {
                HookEnv.art_method_flags_offset = i * sizeof(uint32_t);
                break;
            }
        }
    }

    if (is_field_valid && HookEnv.art_field_size > 0) {
        uint32_t flags = 0x0;
        flags = flags | kAccPublic;
        flags = flags | kAccStatic;
        flags = flags | kAccFinal;
        if (api_level >= __ANDROID_API_Q__) {
            flags = flags | kAccPublicApi;
        }
        char *fieldStart = reinterpret_cast<char *>(nativeOffsetField);
        for (int i = 1; i < HookEnv.art_field_size; ++i) {
            if (!PointerCheck::check(fieldStart + i * sizeof(int32_t))) break;
            auto value = *(int32_t *) (fieldStart + i * sizeof(int32_t));
            if (value == flags) {
                HookEnv.art_field_flags_offset = i * sizeof(int32_t);
                break;
            }
        }
    }

    HookEnv.method_utils_class = (jclass) env->NewGlobalRef(env->FindClass("top/niunaijun/jnihook/MethodUtils"));
    if (HookEnv.method_utils_class) {
        HookEnv.get_method_desc_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getDesc",
                                                            "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
        HookEnv.get_method_declaring_class_id = env->GetStaticMethodID(HookEnv.method_utils_class,
                                                                       "getDeclaringClass",
                                                                       "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
        HookEnv.get_method_name_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getMethodName",
                                                            "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}
