//
// Created by Milk on 3/8/21.
//

#include <jni.h>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"

static struct {
    int api_level;
    unsigned int art_field_size;
    int art_field_flags_offset;

    unsigned int art_method_size;
    int art_method_flags_offset;
    int art_method_native_offset;

    int class_flags_offset;

    jclass method_utils_class;
    jmethodID get_method_desc_id;
    jmethodID get_method_declaring_class_id;
    jmethodID get_method_name_id;

} HookEnv;

static const char *GetMethodDesc(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_desc_id,
                                                                      javaMethod));
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodDeclaringClass(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_declaring_class_id,
                                                                      javaMethod));
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodName(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_name_id,
                                                                      javaMethod));
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

inline static uint32_t GetAccessFlags(const char *art_method) {
    return *reinterpret_cast<const uint32_t *>(art_method + HookEnv.art_method_flags_offset);
}

inline static bool SetAccessFlags(char *art_method, uint32_t flags) {
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
            return methodId;
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
                    } else if (res != 0) {
                        return reinterpret_cast<void *>(res);
                    }
                }
            }
        }
        return methodId;
    } else {
        return methodId;
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
            } else if (res != 0) {
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
            } else if (res != 0) {
                return reinterpret_cast<void *>(res);
            }
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return reinterpret_cast<void *>(env->FromReflectedField(field));
}

bool CheckFlags(void *artMethod) {
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
    HookJniFun(env, class_name, method_name, sign, new_fun, orig_fun, is_static);
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
    if (!artMethodPtr) {
        ALOGD("GetArtMethod fail: %s %s", class_name, method_name);
        return false;
    }
    auto artMethod = reinterpret_cast<uintptr_t *>(artMethodPtr);
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
    if (method == nullptr) return;
    jmethodID methodId = env->FromReflectedMethod(method);
    if (methodId == nullptr) return;
    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId));
    if (art_method == nullptr) return;
    AddAccessFlag(art_method, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(art_method, kAccPublicApi);
    }
}

__attribute__((section (".mytext")))  JNICALL void set_field_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject field) {
    if (field == nullptr) return;
    char *artField = static_cast<char *>(GetFieldMethod(env, field));
    if (artField == nullptr) return;
    AddAccessFlag(artField, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(artField, kAccPublicApi);
    }
    ClearAccessFlag(artField, kAccFinal);
}

void registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    JNINativeMethod gMethods[] = {
            {"nativeOffset",  "()V",                                            (void *) native_offset},
            {"nativeOffset2", "()V",                                            (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V", (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",  (void *) set_field_accessible},
    };
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        ALOGE("jni register error.");
    }
}

void JniHook::InitJniHook(JNIEnv *env, int api_level) {
    registerNative(env);
    HookEnv.api_level = api_level;

    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");

    jfieldID nativeOffsetFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    jfieldID nativeOffsetField2Id = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");

    void *nativeOffsetField = GetFieldMethod(env, env->ToReflectedField(clazz, nativeOffsetFieldId,
                                                                        true));
    void *nativeOffsetField2 = GetFieldMethod(env, env->ToReflectedField(clazz, nativeOffsetField2Id,
                                                                         true));
    if (nativeOffsetField != nullptr && nativeOffsetField2 != nullptr) {
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
        HookEnv.art_field_size = 32;
    }

    void *nativeOffset = GetArtMethod(env, clazz, nativeOffsetId);
    void *nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id);
    if (nativeOffset != nullptr && nativeOffset2 != nullptr) {
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
        HookEnv.art_method_size = 64;
    }

    // calc native offset
    if (nativeOffset != nullptr) {
        auto artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
        for (int i = 0; i < HookEnv.art_method_size; ++i) {
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
            auto value = *(uint32_t *) (start + i * sizeof(uint32_t));
            if (value == flags) {
                HookEnv.art_method_flags_offset = i * sizeof(uint32_t);
                break;
            }
        }
    }

    if (nativeOffsetField != nullptr) {
        uint32_t flags = 0x0;
        flags = flags | kAccPublic;
        flags = flags | kAccStatic;
        flags = flags | kAccFinal;
        if (api_level >= __ANDROID_API_Q__) {
            flags = flags | kAccPublicApi;
        }
        char *fieldStart = reinterpret_cast<char *>(nativeOffsetField);
        for (int i = 1; i < HookEnv.art_field_size; ++i) {
            auto value = *(int32_t *) (fieldStart + i * sizeof(int32_t));
            if (value == flags) {
                HookEnv.art_field_flags_offset = i * sizeof(int32_t);
                break;
            }
        }
    }

    HookEnv.method_utils_class = env->FindClass("top/niunaijun/jnihook/MethodUtils");
    HookEnv.get_method_desc_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getDesc",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_declaring_class_id = env->GetStaticMethodID(HookEnv.method_utils_class,
                                                                   "getDeclaringClass",
                                                                   "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_name_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getMethodName",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
}

