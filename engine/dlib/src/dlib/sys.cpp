#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>
#include "sys.h"
#include "log.h"
#include "dstrings.h"
#include "path.h"

#ifdef _WIN32
#include <Shlobj.h>
#include <Shellapi.h>
#include <io.h>
#include <direct.h>
#else
#include <unistd.h>
#include <sys/utsname.h>
#endif

#include <sys/types.h>
#include <sys/stat.h>

#ifndef S_ISREG
#define S_ISREG(mode) (((mode)&S_IFMT) == S_IFREG)
#endif

#ifdef __MACH__
#include <CoreFoundation/CFBundle.h>
#if !defined(__arm__) && !defined(__arm64__)
#include <Carbon/Carbon.h>
#endif
#endif

#ifdef __ANDROID__
#include <jni.h>
#include <android_native_app_glue.h>
#include <sys/types.h>
#include <android/asset_manager.h>
// By convention we have a global variable called g_AndroidApp
// This is currently created in glfw..
// Application life-cycle should perhaps be part of dlib instead
extern struct android_app* __attribute__((weak)) g_AndroidApp ;
#endif

#ifdef __EMSCRIPTEN__
#include <emscripten.h>

// Implemented in library_sys.js
extern "C" const char* dmSysGetUserPersistentDataRoot();
extern "C" void dmSysPumpMessageQueue();

#endif


namespace dmSys
{
    EngineInfo g_EngineInfo;

    #define DM_SYS_NATIVE_TO_RESULT_CASE(x) case E##x: return RESULT_##x

    static Result NativeToResult(int r)
    {
        switch (r)
        {
            DM_SYS_NATIVE_TO_RESULT_CASE(PERM);
            DM_SYS_NATIVE_TO_RESULT_CASE(NOENT);
            DM_SYS_NATIVE_TO_RESULT_CASE(SRCH);
            DM_SYS_NATIVE_TO_RESULT_CASE(INTR);
            DM_SYS_NATIVE_TO_RESULT_CASE(IO);
            DM_SYS_NATIVE_TO_RESULT_CASE(NXIO);
            DM_SYS_NATIVE_TO_RESULT_CASE(2BIG);
            DM_SYS_NATIVE_TO_RESULT_CASE(NOEXEC);
            DM_SYS_NATIVE_TO_RESULT_CASE(BADF);
            DM_SYS_NATIVE_TO_RESULT_CASE(CHILD);
            DM_SYS_NATIVE_TO_RESULT_CASE(DEADLK);
            DM_SYS_NATIVE_TO_RESULT_CASE(NOMEM);
            DM_SYS_NATIVE_TO_RESULT_CASE(ACCES);
            DM_SYS_NATIVE_TO_RESULT_CASE(FAULT);
            DM_SYS_NATIVE_TO_RESULT_CASE(BUSY);
            DM_SYS_NATIVE_TO_RESULT_CASE(EXIST);
            DM_SYS_NATIVE_TO_RESULT_CASE(XDEV);
            DM_SYS_NATIVE_TO_RESULT_CASE(NODEV);
            DM_SYS_NATIVE_TO_RESULT_CASE(NOTDIR);
            DM_SYS_NATIVE_TO_RESULT_CASE(ISDIR);
            DM_SYS_NATIVE_TO_RESULT_CASE(INVAL);
            DM_SYS_NATIVE_TO_RESULT_CASE(NFILE);
            DM_SYS_NATIVE_TO_RESULT_CASE(MFILE);
            DM_SYS_NATIVE_TO_RESULT_CASE(NOTTY);
#ifndef _WIN32
            DM_SYS_NATIVE_TO_RESULT_CASE(TXTBSY);
#endif
            DM_SYS_NATIVE_TO_RESULT_CASE(FBIG);
            DM_SYS_NATIVE_TO_RESULT_CASE(NOSPC);
            DM_SYS_NATIVE_TO_RESULT_CASE(SPIPE);
            DM_SYS_NATIVE_TO_RESULT_CASE(ROFS);
            DM_SYS_NATIVE_TO_RESULT_CASE(MLINK);
            DM_SYS_NATIVE_TO_RESULT_CASE(PIPE);
        }

        dmLogError("Unknown result code %d\n", r);
        return RESULT_UNKNOWN;
    }
    #undef DM_SYS_NATIVE_TO_RESULT_CASE

    Result Rmdir(const char* path)
    {
        int ret = rmdir(path);
        if (ret == 0)
            return RESULT_OK;
        else
            return NativeToResult(errno);
    }

    Result Mkdir(const char* path, uint32_t mode)
    {
#ifdef _WIN32
        int ret = mkdir(path);
#else
        int ret = mkdir(path, (mode_t) mode);
#endif
        if (ret == 0)
            return RESULT_OK;
        else
            return NativeToResult(errno);
    }

    Result Unlink(const char* path)
    {
        int ret = unlink(path);
        if (ret == 0)
            return RESULT_OK;
        else
            return NativeToResult(errno);
    }

#if defined(__ANDROID__)

    void SetNetworkConnectivityHost(const char* host) { }

    NetworkConnectivity GetNetworkConnectivity()
    {
        ANativeActivity* activity = g_AndroidApp->activity;
        JNIEnv* env = 0;
        activity->vm->AttachCurrentThread( &env, 0);

        jclass def_activity_class = env->GetObjectClass(activity->clazz);
        jmethodID get_connectivity_method = env->GetMethodID(def_activity_class, "getConnectivity", "()I");
        NetworkConnectivity ret;
        int reti = (int)env->CallIntMethod(g_AndroidApp->activity->clazz, get_connectivity_method);
        ret = (NetworkConnectivity)reti;

        activity->vm->DetachCurrentThread();

        return ret;
    }

#elif !defined(__MACH__) // OS X and iOS implementations in sys_cocoa.mm

    void SetNetworkConnectivityHost(const char* host) { }

    NetworkConnectivity GetNetworkConnectivity()
    {
        return NETWORK_CONNECTED;
    }

#endif

#if defined(__MACH__)

#if !defined(__arm__) && !defined(__arm64__)
    // NOTE: iOS implementation in sys_cocoa.mm
    Result GetApplicationSupportPath(const char* application_name, char* path, uint32_t path_len)
    {
        FSRef file;
        FSFindFolder(kUserDomain, kApplicationSupportFolderType, kCreateFolder, &file);
        FSRefMakePath(&file, (UInt8*) path, path_len);
        if (dmStrlCat(path, "/", path_len) >= path_len)
            return RESULT_INVAL;
        if (dmStrlCat(path, application_name, path_len) >= path_len)
            return RESULT_INVAL;
        Result r =  Mkdir(path, 0755);
        if (r == RESULT_EXIST)
            return RESULT_OK;
        else
            return r;
    }
#endif

#elif defined(_WIN32)
    Result GetApplicationSupportPath(const char* application_name, char* path, uint32_t path_len)
    {
        char tmp_path[MAX_PATH];

        if(SUCCEEDED(SHGetFolderPath(NULL,
                                     CSIDL_APPDATA | CSIDL_FLAG_CREATE,
                                     NULL,
                                     0,
                                     tmp_path)))
        {
            if (dmStrlCpy(path, tmp_path, path_len) >= path_len)
                return RESULT_INVAL;
            if (dmStrlCat(path, "\\", path_len) >= path_len)
                return RESULT_INVAL;
            if (dmStrlCat(path, application_name, path_len) >= path_len)
                return RESULT_INVAL;
            Result r =  Mkdir(path, 0755);
            if (r == RESULT_EXIST)
                return RESULT_OK;
            else
                return r;
        }
        else
        {
            return RESULT_UNKNOWN;
        }
    }

    Result OpenURL(const char* url)
    {
        int ret = (int) ShellExecute(NULL, "open", url, NULL, NULL, SW_SHOWNORMAL);
        if (ret == 32)
        {
            return RESULT_OK;
        }
        else
        {
            return RESULT_UNKNOWN;
        }
    }

#elif defined(__ANDROID__)

    Result GetApplicationSupportPath(const char* application_name, char* path, uint32_t path_len)
    {
        ANativeActivity* activity = g_AndroidApp->activity;
        JNIEnv* env = 0;
        activity->vm->AttachCurrentThread( &env, 0);

        jclass activity_class = env->FindClass("android/app/NativeActivity");
        jmethodID get_files_dir_method = env->GetMethodID(activity_class, "getFilesDir", "()Ljava/io/File;");
        jobject files_dir_obj = env->CallObjectMethod(activity->clazz, get_files_dir_method);
        jclass file_class = env->FindClass("java/io/File");
        jmethodID getPathMethod = env->GetMethodID(file_class, "getPath", "()Ljava/lang/String;");
        jstring path_obj = (jstring) env->CallObjectMethod(files_dir_obj, getPathMethod);

        Result res = RESULT_OK;

        if (path_obj) {
            const char* filesDir = env->GetStringUTFChars(path_obj, NULL);

            if (dmStrlCpy(path, filesDir, path_len) >= path_len) {
                res = RESULT_INVAL;
            }
            env->ReleaseStringUTFChars(path_obj, filesDir);
        } else {
            res = RESULT_UNKNOWN;
        }
        activity->vm->DetachCurrentThread();
        return res;
    }

    Result OpenURL(const char* url)
    {
        if (*url == 0x0)
        {
            return RESULT_INVAL;
        }
        ANativeActivity* activity = g_AndroidApp->activity;
        JNIEnv* env = 0;
        activity->vm->AttachCurrentThread( &env, 0);

        jclass uri_class = env->FindClass("android/net/Uri");
        jstring str_url = env->NewStringUTF(url);
        jmethodID parse_method = env->GetStaticMethodID(uri_class, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
        jobject uri = env->CallStaticObjectMethod(uri_class, parse_method, str_url);
        env->DeleteLocalRef(str_url);
        if (uri == NULL)
        {
            activity->vm->DetachCurrentThread();
            return RESULT_UNKNOWN;
        }

        jclass intent_class = env->FindClass("android/content/Intent");
        jfieldID action_view_field = env->GetStaticFieldID(intent_class, "ACTION_VIEW", "Ljava/lang/String;");
        jobject str_action_view = env->GetStaticObjectField(intent_class, action_view_field);
        jmethodID intent_constructor = env->GetMethodID(intent_class, "<init>", "(Ljava/lang/String;Landroid/net/Uri;)V");
        jobject intent = env->NewObject(intent_class, intent_constructor, str_action_view, uri);
        if (intent == NULL)
        {
            activity->vm->DetachCurrentThread();
            return RESULT_UNKNOWN;
        }

        jclass activity_class = env->FindClass("android/app/NativeActivity");
        jmethodID start_activity_method = env->GetMethodID(activity_class, "startActivity", "(Landroid/content/Intent;)V");
        env->CallVoidMethod(activity->clazz, start_activity_method, intent);
        jthrowable exception = env->ExceptionOccurred();
        env->ExceptionClear();
        if (exception != NULL)
        {
            activity->vm->DetachCurrentThread();
            return RESULT_UNKNOWN;
        }

        activity->vm->DetachCurrentThread();
        return RESULT_OK;
    }


#elif defined(__EMSCRIPTEN__)

    Result GetApplicationSupportPath(const char* application_name, char* path, uint32_t path_len)
    {
        const char* const DeviceMount = dmSysGetUserPersistentDataRoot();
        if (0 < strlen(DeviceMount))
        {
            if (dmStrlCpy(path, DeviceMount, path_len) >= path_len)
                return RESULT_INVAL;
            if (dmStrlCat(path, "/", path_len) >= path_len)
                return RESULT_INVAL;
        } else {
            path[0] = '\0';
        }
        if (dmStrlCat(path, ".", path_len) >= path_len)
            return RESULT_INVAL;
        if (dmStrlCat(path, application_name, path_len) >= path_len)
            return RESULT_INVAL;
        Result r =  Mkdir(path, 0755);
        if (r == RESULT_EXIST)
            return RESULT_OK;
        else
            return r;
    }

    Result OpenURL(const char* url)
    {
        return RESULT_UNKNOWN;
    }

#elif defined(__linux__)
    Result GetApplicationSupportPath(const char* application_name, char* path, uint32_t path_len)
    {
        char* home = getenv("HOME");
        if (!home)
            return RESULT_UNKNOWN;

        if (dmStrlCpy(path, home, path_len) >= path_len)
            return RESULT_INVAL;
        if (dmStrlCat(path, "/", path_len) >= path_len)
            return RESULT_INVAL;
        if (dmStrlCat(path, ".", path_len) >= path_len)
            return RESULT_INVAL;
        if (dmStrlCat(path, application_name, path_len) >= path_len)
            return RESULT_INVAL;
        Result r =  Mkdir(path, 0755);
        if (r == RESULT_EXIST)
            return RESULT_OK;
        else
            return r;
    }

    Result OpenURL(const char* url)
    {
        char buf[1024];
        DM_SNPRINTF(buf, 1024, "xdg-open %s", url);
        int ret = system(buf);
        if (ret == 0)
        {
            return RESULT_OK;
        }
        else
        {
            return RESULT_UNKNOWN;
        }
    }

#elif defined(__AVM2__)
    Result GetApplicationSupportPath(const char* application_name, char* path, uint32_t path_len)
    {
        // TODO: Hack
        dmStrlCpy(path, ".", path_len);
        return RESULT_OK;
    }

    Result OpenURL(const char* url)
    {
        // TODO:
        return RESULT_UNKNOWN;
    }

#endif

    Result GetResourcesPath(int argc, char* argv[], char* path, uint32_t path_len)
    {
        assert(path_len > 0);
        path[0] = '\0';
#if defined(__MACH__)
        CFBundleRef mainBundle = CFBundleGetMainBundle();
        if (!mainBundle)
        {
            dmLogFatal("Unable to get main bundle");
            return RESULT_UNKNOWN;
        }
        CFURLRef resourceURL = CFBundleCopyResourcesDirectoryURL(mainBundle);

        if(resourceURL)
        {
            CFURLGetFileSystemRepresentation(resourceURL, true, (UInt8*) path, path_len);
            CFRelease(resourceURL);
            return RESULT_OK;
        }
        else
        {
            dmLogFatal("Unable to locate bundle resource directory");
            return RESULT_UNKNOWN;
        }
#else
        dmPath::Dirname(argv[0], path, path_len);
        return RESULT_OK;
#endif
    }

#if ((defined(__arm__) || defined(__arm64__)) && defined(__MACH__))
    // NOTE: iOS implementation in sys_cocoa.mm

#elif defined(__ANDROID__)
    Result GetLogPath(char* path, uint32_t path_len)
    {
        ANativeActivity* activity = g_AndroidApp->activity;
        JNIEnv* env = 0;
        activity->vm->AttachCurrentThread( &env, 0);
        Result res = RESULT_OK;

        jclass activity_class = env->FindClass("android/app/NativeActivity");
        jmethodID get_files_dir_method = env->GetMethodID(activity_class, "getExternalFilesDir", "(Ljava/lang/String;)Ljava/io/File;");
        jobject files_dir_obj = env->CallObjectMethod(activity->clazz, get_files_dir_method, 0);
        if (!files_dir_obj) {
            dmLogError("Failed to get log directory. Is android.permission.WRITE_EXTERNAL_STORAGE set in AndroidManifest.xml?");
            res = RESULT_UNKNOWN;
        } else {
            jclass file_class = env->FindClass("java/io/File");
            jmethodID getPathMethod = env->GetMethodID(file_class, "getPath", "()Ljava/lang/String;");
            jstring path_obj = (jstring) env->CallObjectMethod(files_dir_obj, getPathMethod);
            if (path_obj) {
                const char* filesDir = env->GetStringUTFChars(path_obj, NULL);
                if (dmStrlCpy(path, filesDir, path_len) >= path_len) {
                    res = RESULT_INVAL;
                }
                env->ReleaseStringUTFChars(path_obj, filesDir);
            } else {
                res = RESULT_UNKNOWN;
            }
        }
        activity->vm->DetachCurrentThread();
        return res;
    }

#else
    // Default
    Result GetLogPath(char* path, uint32_t path_len)
    {
        if (dmStrlCpy(path, ".", path_len) >= path_len)
            return RESULT_INVAL;

        return RESULT_OK;
    }
#endif

    void FillLanguageTerritory(const char* lang, struct SystemInfo* info)
    {
        const char* default_lang = "en_US";

        if (strlen(lang) < 5) {
            dmLogWarning("Unknown language format: '%s'", lang);
            lang = default_lang;
        } else if(lang[2] != '_') {
            dmLogWarning("Unknown language format: '%s'", lang);
            lang = default_lang;
        }

        info->m_Language[0] = lang[0];
        info->m_Language[1] = lang[1];
        info->m_Language[2] = '\0';
        info->m_DeviceLanguage[0] = lang[0];
        info->m_DeviceLanguage[1] = lang[1];
        info->m_DeviceLanguage[2] = '\0';
        info->m_Territory[0] = lang[3];
        info->m_Territory[1] = lang[4];
        info->m_Territory[2] = '\0';
    }

    void FillTimeZone(struct SystemInfo* info)
    {
#if defined(_WIN32)
        // tm_gmtoff not available on windows..
        TIME_ZONE_INFORMATION t;
        GetTimeZoneInformation(&t);
        info->m_GmtOffset = -t.Bias;
#else
        time_t t;
        time(&t);
        struct tm* lt = localtime(&t);
        info->m_GmtOffset = lt->tm_gmtoff / 60;
#endif
    }

#if (defined(__linux__) && !defined(__ANDROID__)) || defined(__AVM2__) || defined(__EMSCRIPTEN__)
    void GetSystemInfo(SystemInfo* info)
    {
        memset(info, 0, sizeof(*info));
        struct utsname uts;
        uname(&uts);

        dmStrlCpy(info->m_SystemName, uts.sysname, sizeof(info->m_SystemName));
        dmStrlCpy(info->m_SystemVersion, uts.release, sizeof(info->m_SystemVersion));
        info->m_DeviceModel[0] = '\0';

        const char* lang = getenv("LANG");
        const char* default_lang = "en_US";

        if (!lang) {
            dmLogWarning("Variable LANG not set");
            lang = default_lang;
        }
        FillLanguageTerritory(lang, info);
        FillTimeZone(info);
    }

#elif defined(__ANDROID__)

    void GetSystemInfo(SystemInfo* info)
    {
        memset(info, 0, sizeof(*info));
        dmStrlCpy(info->m_SystemName, "Android", sizeof(info->m_SystemName));

        ANativeActivity* activity = g_AndroidApp->activity;
        JNIEnv* env = 0;
        activity->vm->AttachCurrentThread( &env, 0);

        jclass locale_class = env->FindClass("java/util/Locale");
        jmethodID get_default_method = env->GetStaticMethodID(locale_class, "getDefault", "()Ljava/util/Locale;");
        jmethodID get_country_method = env->GetMethodID(locale_class, "getCountry", "()Ljava/lang/String;");
        jmethodID get_language_method = env->GetMethodID(locale_class, "getLanguage", "()Ljava/lang/String;");
        jobject locale = (jobject) env->CallStaticObjectMethod(locale_class, get_default_method);
        jstring countryObj = (jstring) env->CallObjectMethod(locale, get_country_method);
        jstring languageObj = (jstring) env->CallObjectMethod(locale, get_language_method);

        if (countryObj) {
            const char* country = env->GetStringUTFChars(countryObj, NULL);
            dmStrlCpy(info->m_Territory, country, sizeof(info->m_Territory));
            env->ReleaseStringUTFChars(countryObj, country);
        }
        if (languageObj) {
            const char* language = env->GetStringUTFChars(languageObj, NULL);
            dmStrlCpy(info->m_Language, language, sizeof(info->m_Language));
            dmStrlCpy(info->m_DeviceLanguage, language, sizeof(info->m_DeviceLanguage));
            env->ReleaseStringUTFChars(languageObj, language);
        }

        jclass build_class = env->FindClass("android/os/Build");
        jstring manufacturerObj = (jstring) env->GetStaticObjectField(build_class, env->GetStaticFieldID(build_class, "MANUFACTURER", "Ljava/lang/String;"));
        jstring modelObj = (jstring) env->GetStaticObjectField(build_class, env->GetStaticFieldID(build_class, "MODEL", "Ljava/lang/String;"));

        jclass build_version_class = env->FindClass("android/os/Build$VERSION");
        jstring releaseObj = (jstring) env->GetStaticObjectField(build_version_class, env->GetStaticFieldID(build_version_class, "RELEASE", "Ljava/lang/String;"));

        if (manufacturerObj) {
            const char* manufacturer = env->GetStringUTFChars(manufacturerObj, NULL);
            dmStrlCpy(info->m_Manufacturer, manufacturer, sizeof(info->m_Manufacturer));
            env->ReleaseStringUTFChars(manufacturerObj, manufacturer);
        }
        if (modelObj) {
            const char* model = env->GetStringUTFChars(modelObj, NULL);
            dmStrlCpy(info->m_DeviceModel, model, sizeof(info->m_DeviceModel));
            env->ReleaseStringUTFChars(modelObj, model);
        }
        if (releaseObj) {
            const char* release = env->GetStringUTFChars(releaseObj, NULL);
            dmStrlCpy(info->m_SystemVersion, release, sizeof(info->m_SystemVersion));
            env->ReleaseStringUTFChars(releaseObj, release);
        }

        jclass activity_class = env->FindClass("android/app/NativeActivity");
        jmethodID get_content_resolver_method = env->GetMethodID(activity_class, "getContentResolver", "()Landroid/content/ContentResolver;");
        jobject content_resolver = env->CallObjectMethod(activity->clazz, get_content_resolver_method);

        jclass secure_class = env->FindClass("android/provider/Settings$Secure");
        if (secure_class) {
            jstring android_id_string = env->NewStringUTF("android_id");
            jmethodID get_string_method = env->GetStaticMethodID(secure_class, "getString", "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;");
            jstring android_id_obj = (jstring) env->CallStaticObjectMethod(secure_class, get_string_method, content_resolver, android_id_string);
            env->DeleteLocalRef(android_id_string);
            if (android_id_obj) {
                const char* android_id = env->GetStringUTFChars(android_id_obj, NULL);
                dmStrlCpy(info->m_DeviceIdentifier, android_id, sizeof(info->m_DeviceIdentifier));
                env->ReleaseStringUTFChars(android_id_obj, android_id);
            }
        } else {
            dmLogWarning("Unable to get 'android.id'. Is permission android.permission.READ_PHONE_STATE set?")
        }

        jclass def_activity_class = env->GetObjectClass(g_AndroidApp->activity->clazz);
        jmethodID getAdId = env->GetMethodID(def_activity_class, "getAdId", "()Ljava/lang/String;");
        jstring val = (jstring) env->CallObjectMethod(g_AndroidApp->activity->clazz, getAdId);
        if (val)
        {
            const char *id = env->GetStringUTFChars(val, NULL);
            dmStrlCpy(info->m_AdIdentifier, id, sizeof(info->m_AdIdentifier));
            env->ReleaseStringUTFChars(val, id);
            env->DeleteLocalRef(val);
        }

        jmethodID get_limit_ad_tracking = env->GetMethodID(def_activity_class, "getLimitAdTracking", "()Z");
        info->m_AdTrackingEnabled = !env->CallBooleanMethod(g_AndroidApp->activity->clazz, get_limit_ad_tracking);

        activity->vm->DetachCurrentThread();
    }
#elif defined(_WIN32)
    typedef int (WINAPI *PGETUSERDEFAULTLOCALENAME)(LPWSTR, int);

    void GetSystemInfo(SystemInfo* info)
    {
        memset(info, 0, sizeof(*info));
        PGETUSERDEFAULTLOCALENAME GetUserDefaultLocaleName = (PGETUSERDEFAULTLOCALENAME)GetProcAddress(GetModuleHandle("kernel32.dll"), "GetUserDefaultLocaleName");
        dmStrlCpy(info->m_DeviceModel, "", sizeof(info->m_DeviceModel));
        dmStrlCpy(info->m_SystemName, "Windows", sizeof(info->m_SystemName));
        OSVERSIONINFOA version_info;
        version_info.dwOSVersionInfoSize = sizeof(version_info);
        GetVersionEx(&version_info);

        const int max_len = 256;
        char lang[max_len];
        dmStrlCpy(lang, "en-US", max_len);

        DM_SNPRINTF(info->m_SystemVersion, sizeof(info->m_SystemVersion), "%d.%d", version_info.dwMajorVersion, version_info.dwMinorVersion);
        if (GetUserDefaultLocaleName) {
            // Only availble on >= Vista
            wchar_t tmp[max_len];
            GetUserDefaultLocaleName(tmp, max_len);
            WideCharToMultiByte(CP_UTF8, 0, tmp, -1, lang, max_len, 0, 0);
        }
        char* index = strchr(lang, '-');
        if (index) {
            *index = '_';
        }
        FillLanguageTerritory(lang, info);
        FillTimeZone(info);
    }
#endif

    void GetEngineInfo(EngineInfo* info)
    {
        *info = g_EngineInfo;
    }

    void SetEngineInfo(EngineInfoParam& info)
    {
        size_t copied = dmStrlCpy(g_EngineInfo.m_Version, info.m_Version, sizeof(g_EngineInfo.m_Version));
        assert(copied < sizeof(g_EngineInfo.m_Version));
        copied = dmStrlCpy(g_EngineInfo.m_VersionSHA1, info.m_VersionSHA1, sizeof(g_EngineInfo.m_VersionSHA1));
        assert(copied < sizeof(g_EngineInfo.m_VersionSHA1));
      }

#if (__ANDROID__)
    bool GetApplicationInfo(const char* id, ApplicationInfo* info)
    {
        memset(info, 0, sizeof(*info));

        ANativeActivity* activity = g_AndroidApp->activity;
        JNIEnv* env = 0;
        activity->vm->AttachCurrentThread( &env, 0);

        jclass native_activity_class = env->FindClass("android/app/NativeActivity");
        jmethodID methodID_func = env->GetMethodID(native_activity_class, "getPackageManager", "()Landroid/content/pm/PackageManager;");
        jobject package_manager = env->CallObjectMethod(activity->clazz, methodID_func);
        jclass pm_class = env->GetObjectClass(package_manager);
        jmethodID methodID_pm = env->GetMethodID(pm_class, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
        jstring str_url = env->NewStringUTF(id);
        env->CallObjectMethod(package_manager, methodID_pm, str_url);
        jthrowable exception = env->ExceptionOccurred();
        env->ExceptionClear();
        env->DeleteLocalRef(str_url);
        activity->vm->DetachCurrentThread();

        bool installed = exception == NULL;
        info->m_Installed = installed;
        return installed;
    }
#elif !defined(__MACH__) // OS X and iOS implementations in sys_cocoa.mm
    bool GetApplicationInfo(const char* id, ApplicationInfo* info)
    {
        memset(info, 0, sizeof(*info));
        return false;
    }
#endif

#ifdef __ANDROID__
    const char* FixAndroidResourcePath(const char* path)
    {
        // Fix path for android.
        // We always try to have a path-root and '.'
        // for current directory. Assets on Android
        // are always loaded with relative path from assets
        // E.g. The relative path to /assets/file is file

        if (strncmp(path, "./", 2) == 0) {
            path += 2;
        }
        while (*path == '/') {
            ++path;
        }
        return path;
    }
#endif

    bool ResourceExists(const char* path)
    {
#ifdef __ANDROID__
        AAssetManager* am = g_AndroidApp->activity->assetManager;
        AAsset* asset = AAssetManager_open(am, path, AASSET_MODE_RANDOM);
        if (asset) {
            AAsset_close(asset);
            return true;
        } else {
            return false;
        }
#else
        struct stat file_stat;
        return stat(path, &file_stat) == 0;
#endif
    }

    Result ResourceSize(const char* path, uint32_t* resource_size)
    {
#ifdef __ANDROID__
        path = FixAndroidResourcePath(path);
        AAssetManager* am = g_AndroidApp->activity->assetManager;
        AAsset* asset = AAssetManager_open(am, path, AASSET_MODE_RANDOM);
        if (asset) {
            *resource_size = (uint32_t) AAsset_getLength(asset);

            AAsset_close(asset);
            return RESULT_OK;
        } else {
            return RESULT_NOENT;
        }
#else
        struct stat file_stat;
        if (stat(path, &file_stat) == 0) {

            if (!S_ISREG(file_stat.st_mode)) {
                return RESULT_NOENT;
            }
            *resource_size = (uint32_t) file_stat.st_size;
            return RESULT_OK;
        } else {
            return RESULT_NOENT;
        }
#endif
    }

    Result LoadResource(const char* path, void* buffer, uint32_t buffer_size, uint32_t* resource_size)
    {
        *resource_size = 0;
#ifdef __ANDROID__
        path = FixAndroidResourcePath(path);

        AAssetManager* am = g_AndroidApp->activity->assetManager;
        // NOTE: Is AASSET_MODE_BUFFER is much faster than AASSET_MODE_RANDOM.
        AAsset* asset = AAssetManager_open(am, path, AASSET_MODE_BUFFER);
        if (asset) {
            uint32_t asset_size = (uint32_t) AAsset_getLength(asset);
            if (asset_size > buffer_size) {
                AAsset_close(asset);
                return RESULT_INVAL;
            }
            int nread = AAsset_read(asset, buffer, asset_size);
            AAsset_close(asset);
            if (nread != asset_size) {
                return RESULT_IO;
            }
            *resource_size = asset_size;
            return RESULT_OK;
        } else {
            return RESULT_NOENT;
        }
#else
        struct stat file_stat;
        if (stat(path, &file_stat) == 0) {
            if (!S_ISREG(file_stat.st_mode)) {
                return RESULT_NOENT;
            }
            if ((uint32_t) file_stat.st_size > buffer_size) {
                return RESULT_INVAL;
            }
            FILE* f = fopen(path, "rb");
            size_t nread = fread(buffer, 1, file_stat.st_size, f);
            fclose(f);
            if (nread != (size_t) file_stat.st_size) {
                return RESULT_IO;
            }
            *resource_size = file_stat.st_size;
            return RESULT_OK;
        } else {
            return NativeToResult(errno);
        }
#endif
    }


    void PumpMessageQueue() {
#if defined(__EMSCRIPTEN__)
        dmSysPumpMessageQueue();
#endif
    }

}
