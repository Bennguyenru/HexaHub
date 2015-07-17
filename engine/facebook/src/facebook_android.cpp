#include <jni.h>

#include <assert.h>

#include <extension/extension.h>
#include <dlib/dstrings.h>
#include <dlib/log.h>
#include <dlib/array.h>
#include <dlib/mutex.h>
#include <script/script.h>

#include <pthread.h>
#include <unistd.h>

#include <android_native_app_glue.h>

#define LIB_NAME "facebook"

extern struct android_app* g_AndroidApp;

#define CMD_LOGIN 1
#define CMD_REQUEST_READ 2
#define CMD_REQUEST_PUBLISH 3
#define CMD_DIALOG_COMPLETE 4

enum State {
    STATE_FAILED               = 0,
    STATE_OPEN                 = 1,
    STATE_OPEN_TOKEN_EXTENDED  = 2,
    STATE_CLOSED               = 3,
    STATE_CLOSED_LOGIN_FAILED  = 4,
    STATE_CREATED              = 5,
    STATE_CREATED_TOKEN_LOADED = 6,
    STATE_CREATED_OPENING      = 7,
};

enum GameRequestAction {
    GAMEREQUEST_ACTIONTYPE_NONE   = -1,
    GAMEREQUEST_ACTIONTYPE_SEND   = 0,
    GAMEREQUEST_ACTIONTYPE_ASKFOR = 1,
    GAMEREQUEST_ACTIONTYPE_TURN   = 2,
};

enum GameRequestFilters {
    GAMEREQUEST_FILTER_NONE        = -1,
    GAMEREQUEST_FILTER_APPUSERS    = 0,
    GAMEREQUEST_FILTER_APPNONUSERS = 1,
};

enum Audience {
    AUDIENCE_NONE     = -1,
    AUDIENCE_ONLYME   = 0,
    AUDIENCE_FRIENDS  = 1,
    AUDIENCE_EVERYONE = 2,
};

struct Command
{
    Command()
    {
        memset(this, 0, sizeof(Command));
    }
    uint8_t m_Type;
    uint16_t m_State;
    lua_State* m_L;
    const char* m_Url;
    const char* m_Error;
};

struct Facebook
{
    Facebook()
    {
        memset(this, 0, sizeof(*this));
        m_Callback = LUA_NOREF;
        m_Self = LUA_NOREF;
    }

    jobject m_FB;
    jmethodID m_Login;
    jmethodID m_Logout;
    jmethodID m_IterateMe;
    jmethodID m_IteratePermissions;
    jmethodID m_GetAccessToken;
    jmethodID m_RequestReadPermissions;
    jmethodID m_RequestPublishPermissions;
    jmethodID m_ShowDialog;
    int m_Callback;
    int m_Self;
    int m_RefCount;

    dmMutex::Mutex m_Mutex;
    dmArray<Command> m_CmdQueue;
};

Facebook g_Facebook;

static void PushError(lua_State*L, const char* error)
{
    // Could be extended with error codes etc
    if (error != NULL) {
        lua_newtable(L);
        lua_pushstring(L, "error");
        lua_pushstring(L, error);
        lua_rawset(L, -3);
    } else {
        lua_pushnil(L);
    }
}

static void RunStateCallback(Command* cmd)
{
    if (g_Facebook.m_Callback != LUA_NOREF) {
        lua_State* L = cmd->m_L;

        int state = cmd->m_State;
        const char* error = cmd->m_Error;

        int top = lua_gettop(L);

        int callback = g_Facebook.m_Callback;
        g_Facebook.m_Callback = LUA_NOREF;
        lua_rawgeti(L, LUA_REGISTRYINDEX, callback);

        // Setup self
        lua_rawgeti(L, LUA_REGISTRYINDEX, g_Facebook.m_Self);
        lua_pushvalue(L, -1);
        dmScript::SetInstance(L);

        if (!dmScript::IsInstanceValid(L))
        {
            dmLogError("Could not run facebook callback because the instance has been deleted.");
            lua_pop(L, 2);
            assert(top == lua_gettop(L));
            return;
        }

        lua_pushnumber(L, (lua_Number) state);
        PushError(L, error);

        int ret = dmScript::PCall(L, 3, LUA_MULTRET);
        (void)ret;
        assert(top == lua_gettop(L));
        luaL_unref(L, LUA_REGISTRYINDEX, callback);
    } else {
        dmLogError("No callback set");
    }
}

static void RunCallback(Command* cmd)
{
    if (g_Facebook.m_Callback != LUA_NOREF) {
        lua_State* L = cmd->m_L;
        const char* error = cmd->m_Error;

        int top = lua_gettop(L);

        int callback = g_Facebook.m_Callback;
        lua_rawgeti(L, LUA_REGISTRYINDEX, callback);

        // Setup self
        lua_rawgeti(L, LUA_REGISTRYINDEX, g_Facebook.m_Self);
        lua_pushvalue(L, -1);
        dmScript::SetInstance(L);

        if (!dmScript::IsInstanceValid(L))
        {
            dmLogError("Could not run facebook callback because the instance has been deleted.");
            lua_pop(L, 2);
            assert(top == lua_gettop(L));
            return;
        }

        PushError(L, error);

        int ret = dmScript::PCall(L, 2, LUA_MULTRET);
        (void)ret;
        assert(top == lua_gettop(L));
        luaL_unref(L, LUA_REGISTRYINDEX, callback);
    } else {
        dmLogError("No callback set");
    }
}

static void RunDialogResultCallback(Command* cmd)
{
    if (g_Facebook.m_Callback != LUA_NOREF) {
        lua_State* L = cmd->m_L;
        const char* error = cmd->m_Error;

        int top = lua_gettop(L);

        int callback = g_Facebook.m_Callback;
        lua_rawgeti(L, LUA_REGISTRYINDEX, callback);

        // Setup self
        lua_rawgeti(L, LUA_REGISTRYINDEX, g_Facebook.m_Self);
        lua_pushvalue(L, -1);
        dmScript::SetInstance(L);

        if (!dmScript::IsInstanceValid(L))
        {
            dmLogError("Could not run facebook callback because the instance has been deleted.");
            lua_pop(L, 2);
            assert(top == lua_gettop(L));
            return;
        }

        lua_createtable(L, 0, 1);
        lua_pushliteral(L, "url");
        if (cmd->m_Url) {
            lua_pushstring(L, cmd->m_Url);
        } else {
            lua_pushnil(L);
        }
        lua_rawset(L, -3);

        PushError(L, error);

        int ret = dmScript::PCall(L, 3, LUA_MULTRET);
        (void)ret;
        assert(top == lua_gettop(L));
        luaL_unref(L, LUA_REGISTRYINDEX, callback);
    } else {
        dmLogError("No callback set");
    }
}

void QueueCommand(Command* cmd)
{
    dmMutex::ScopedLock lk(g_Facebook.m_Mutex);
    if (g_Facebook.m_CmdQueue.Full())
    {
        g_Facebook.m_CmdQueue.OffsetCapacity(8);
    }
    g_Facebook.m_CmdQueue.Push(*cmd);
}

const char* StrDup(JNIEnv* env, jstring s)
{
    if (s != NULL)
    {
        const char* str = env->GetStringUTFChars(s, 0);
        const char* dup = strdup(str);
        env->ReleaseStringUTFChars(s, str);
        return dup;
    }
    else
    {
        return 0x0;
    }
}

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL Java_com_dynamo_android_facebook_FacebookJNI_onLogin
  (JNIEnv* env, jobject, jlong userData, jint state, jstring error)
{
    Command cmd;
    cmd.m_Type = CMD_LOGIN;
    cmd.m_State = (int)state;
    cmd.m_L = dmScript::GetMainThread((lua_State*)userData);
    cmd.m_Error = StrDup(env, error);
    QueueCommand(&cmd);
}

JNIEXPORT void JNICALL Java_com_dynamo_android_facebook_FacebookJNI_onRequestRead
  (JNIEnv* env, jobject, jlong userData, jstring error)
{
    Command cmd;
    cmd.m_Type = CMD_REQUEST_READ;
    cmd.m_L = dmScript::GetMainThread((lua_State*)userData);
    cmd.m_Error = StrDup(env, error);
    QueueCommand(&cmd);
}

JNIEXPORT void JNICALL Java_com_dynamo_android_facebook_FacebookJNI_onRequestPublish
  (JNIEnv* env, jobject, jlong userData, jstring error)
{
    Command cmd;
    cmd.m_Type = CMD_REQUEST_PUBLISH;
    cmd.m_L = dmScript::GetMainThread((lua_State*)userData);
    cmd.m_Error = StrDup(env, error);
    QueueCommand(&cmd);
}

JNIEXPORT void JNICALL Java_com_dynamo_android_facebook_FacebookJNI_onDialogComplete
  (JNIEnv *env, jobject, jlong userData, jstring url, jstring error)
{
    Command cmd;
    cmd.m_Type = CMD_DIALOG_COMPLETE;
    cmd.m_L = dmScript::GetMainThread((lua_State*)userData);
    cmd.m_Url = StrDup(env, url);
    cmd.m_Error = StrDup(env, error);
    QueueCommand(&cmd);
}

JNIEXPORT void JNICALL Java_com_dynamo_android_facebook_FacebookJNI_onIterateMeEntry
  (JNIEnv* env, jobject, jlong user_data, jstring key, jstring value)
{
    lua_State* L = (lua_State*)user_data;

    if (key) {
        const char* str_key = env->GetStringUTFChars(key, 0);
        lua_pushstring(L, str_key);
        env->ReleaseStringUTFChars(key, str_key);
    } else {
        lua_pushnil(L);
    }

    if (value) {
        const char* str_value = env->GetStringUTFChars(value, 0);
        lua_pushstring(L, str_value);
        env->ReleaseStringUTFChars(value, str_value);
    } else {
        lua_pushnil(L);
    }
    lua_rawset(L, -3);
}

JNIEXPORT void JNICALL Java_com_dynamo_android_facebook_FacebookJNI_onIteratePermissionsEntry
  (JNIEnv* env, jobject, jlong user_data, jstring permission)
{
    lua_State* L = (lua_State*)user_data;
    int i = lua_objlen(L, -1);

    lua_pushnumber(L, i + 1);

    if (permission) {
        const char* str_permission = env->GetStringUTFChars(permission, 0);
        lua_pushstring(L, str_permission);
        env->ReleaseStringUTFChars(permission, str_permission);
    } else {
        lua_pushnil(L);
    }
    lua_rawset(L, -3);
}

#ifdef __cplusplus
}
#endif

static JNIEnv* Attach()
{
    JNIEnv* env;
    g_AndroidApp->activity->vm->AttachCurrentThread(&env, NULL);
    return env;
}

static void Detach()
{
    g_AndroidApp->activity->vm->DetachCurrentThread();
}

static void VerifyCallback(lua_State* L)
{
    if (g_Facebook.m_Callback != LUA_NOREF) {
        dmLogError("Unexpected callback set");
        luaL_unref(L, LUA_REGISTRYINDEX, g_Facebook.m_Callback);
        luaL_unref(L, LUA_REGISTRYINDEX, g_Facebook.m_Self);
        g_Facebook.m_Callback = LUA_NOREF;
        g_Facebook.m_Self = LUA_NOREF;
    }
}

int Facebook_Login(lua_State* L)
{
    int top = lua_gettop(L);
    VerifyCallback(L);

    luaL_checktype(L, 1, LUA_TFUNCTION);
    lua_pushvalue(L, 1);
    g_Facebook.m_Callback = luaL_ref(L, LUA_REGISTRYINDEX);

    dmScript::GetInstance(L);
    g_Facebook.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);

    JNIEnv* env = Attach();

    env->CallVoidMethod(g_Facebook.m_FB, g_Facebook.m_Login, (jlong)L);

    Detach();

    assert(top == lua_gettop(L));
    return 0;
}

int Facebook_Logout(lua_State* L)
{
    int top = lua_gettop(L);
    VerifyCallback(L);

    JNIEnv* env = Attach();

    env->CallVoidMethod(g_Facebook.m_FB, g_Facebook.m_Logout);

    Detach();

    assert(top == lua_gettop(L));
    return 0;
}

void AppendArray(lua_State* L, char* buffer, uint32_t buffer_size, int idx)
{
    lua_pushnil(L);
    *buffer = 0;
    while (lua_next(L, idx) != 0)
    {
        if (!lua_isstring(L, -1))
            luaL_error(L, "permissions can only be strings (not %s)", lua_typename(L, lua_type(L, -1)));
        if (*buffer != 0)
            dmStrlCat(buffer, ",", buffer_size);
        const char* permission = lua_tostring(L, -1);
        dmStrlCat(buffer, permission, buffer_size);
        lua_pop(L, 1);
    }
}

int Facebook_RequestReadPermissions(lua_State* L)
{
    int top = lua_gettop(L);
    VerifyCallback(L);

    luaL_checktype(L, top-1, LUA_TTABLE);
    luaL_checktype(L, top, LUA_TFUNCTION);
    lua_pushvalue(L, top);
    g_Facebook.m_Callback = luaL_ref(L, LUA_REGISTRYINDEX);

    dmScript::GetInstance(L);
    g_Facebook.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);

    char permissions[512];
    AppendArray(L, permissions, 512, top-1);

    JNIEnv* env = Attach();

    jstring str_permissions = env->NewStringUTF(permissions);
    env->CallVoidMethod(g_Facebook.m_FB, g_Facebook.m_RequestReadPermissions, (jlong)L, str_permissions);
    env->DeleteLocalRef(str_permissions);

    Detach();

    assert(top == lua_gettop(L));
    return 0;
}

int Facebook_RequestPublishPermissions(lua_State* L)
{
    int top = lua_gettop(L);
    VerifyCallback(L);

    luaL_checktype(L, top-2, LUA_TTABLE);
    int audience = luaL_checkinteger(L, top-1);
    luaL_checktype(L, top, LUA_TFUNCTION);
    lua_pushvalue(L, top);
    g_Facebook.m_Callback = luaL_ref(L, LUA_REGISTRYINDEX);

    dmScript::GetInstance(L);
    g_Facebook.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);

    char permissions[512];
    AppendArray(L, permissions, 512, top-2);

    JNIEnv* env = Attach();

    jstring str_permissions = env->NewStringUTF(permissions);
    env->CallVoidMethod(g_Facebook.m_FB, g_Facebook.m_RequestPublishPermissions , (jlong)L, (jint)audience, str_permissions);
    env->DeleteLocalRef(str_permissions);

    Detach();

    assert(top == lua_gettop(L));
    return 0;
}

int Facebook_AccessToken(lua_State* L)
{
    int top = lua_gettop(L);

    JNIEnv* env = Attach();

    jstring str_access_token = (jstring)env->CallObjectMethod(g_Facebook.m_FB, g_Facebook.m_GetAccessToken);

    if (str_access_token) {
        const char* access_token = env->GetStringUTFChars(str_access_token, 0);
        lua_pushstring(L, access_token);
        env->ReleaseStringUTFChars(str_access_token, access_token);
    } else {
        lua_pushnil(L);
    }
    Detach();
    assert(top + 1 == lua_gettop(L));
    return 1;
}

int Facebook_Permissions(lua_State* L)
{
    int top = lua_gettop(L);

    lua_newtable(L);

    JNIEnv* env = Attach();

    env->CallVoidMethod(g_Facebook.m_FB, g_Facebook.m_IteratePermissions, (jlong)L);

    Detach();

    assert(top + 1 == lua_gettop(L));
    return 1;
}

int Facebook_Me(lua_State* L)
{
    int top = lua_gettop(L);

    lua_newtable(L);

    JNIEnv* env = Attach();

    env->CallVoidMethod(g_Facebook.m_FB, g_Facebook.m_IterateMe, (jlong)L);

    Detach();

    assert(top + 1 == lua_gettop(L));
    return 1;
}

int Facebook_ShowDialog(lua_State* L)
{
    int top = lua_gettop(L);
    VerifyCallback(L);

    const char* dialog = luaL_checkstring(L, 1);
    luaL_checktype(L, 2, LUA_TTABLE);
    luaL_checktype(L, 3, LUA_TFUNCTION);
    lua_pushvalue(L, 3);
    g_Facebook.m_Callback = luaL_ref(L, LUA_REGISTRYINDEX);
    dmScript::GetInstance(L);
    g_Facebook.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);

    JNIEnv* env = Attach();

    char params_json[1024];
    params_json[0] = '{';
    params_json[1] = '\0';
    char tmp[256];

    lua_pushnil(L);
    int i = 0;
    while (lua_next(L, 2) != 0) {
        const char* v = luaL_checkstring(L, -1);
        const char* k = luaL_checkstring(L, -2);
        DM_SNPRINTF(tmp, sizeof(tmp), "\"%s\": \"%s\"", k, v);
        if (i > 0) {
            dmStrlCat(params_json, ",", sizeof(params_json));
        }
        dmStrlCat(params_json, tmp, sizeof(params_json));
        lua_pop(L, 1);
        ++i;
    }
    dmStrlCat(params_json, "}", sizeof(params_json));

    jstring str_dialog = env->NewStringUTF(dialog);
    jstring str_params = env->NewStringUTF(params_json);
    env->CallVoidMethod(g_Facebook.m_FB, g_Facebook.m_ShowDialog, (jlong)L, str_dialog, str_params);
    env->DeleteLocalRef(str_dialog);
    env->DeleteLocalRef(str_params);

    Detach();

    assert(top == lua_gettop(L));
    return 0;
}

static const luaL_reg Facebook_methods[] =
{
    {"login", Facebook_Login},
    {"logout", Facebook_Logout},
    {"access_token", Facebook_AccessToken},
    {"permissions", Facebook_Permissions},
    {"request_read_permissions", Facebook_RequestReadPermissions},
    {"request_publish_permissions", Facebook_RequestPublishPermissions},
    {"me", Facebook_Me},
    {"show_dialog", Facebook_ShowDialog},
    {0, 0}
};

dmExtension::Result InitializeFacebook(dmExtension::Params* params)
{
    if (g_Facebook.m_FB == NULL)
    {
        g_Facebook.m_Mutex = dmMutex::New();
        g_Facebook.m_CmdQueue.SetCapacity(8);

        JNIEnv* env = Attach();

        jclass activity_class = env->FindClass("android/app/NativeActivity");
        jmethodID get_class_loader = env->GetMethodID(activity_class,"getClassLoader", "()Ljava/lang/ClassLoader;");
        jobject cls = env->CallObjectMethod(g_AndroidApp->activity->clazz, get_class_loader);
        jclass class_loader = env->FindClass("java/lang/ClassLoader");
        jmethodID find_class = env->GetMethodID(class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring str_class_name = env->NewStringUTF("com.dynamo.android.facebook.FacebookJNI");
        jclass fb_class = (jclass)env->CallObjectMethod(cls, find_class, str_class_name);
        env->DeleteLocalRef(str_class_name);

        g_Facebook.m_Login = env->GetMethodID(fb_class, "login", "(J)V");
        g_Facebook.m_Logout = env->GetMethodID(fb_class, "logout", "()V");
        g_Facebook.m_IterateMe = env->GetMethodID(fb_class, "iterateMe", "(J)V");
        g_Facebook.m_IteratePermissions = env->GetMethodID(fb_class, "iteratePermissions", "(J)V");
        g_Facebook.m_GetAccessToken = env->GetMethodID(fb_class, "getAccessToken", "()Ljava/lang/String;");
        g_Facebook.m_RequestReadPermissions = env->GetMethodID(fb_class, "requestReadPermissions", "(JLjava/lang/String;)V");
        g_Facebook.m_RequestPublishPermissions = env->GetMethodID(fb_class, "requestPublishPermissions", "(JILjava/lang/String;)V");
        g_Facebook.m_ShowDialog = env->GetMethodID(fb_class, "showDialog", "(JLjava/lang/String;Ljava/lang/String;)V");

        // 355198514515820 is HelloFBSample. Default value in order to avoid exceptions
        // Better solution?
        const char* app_id = dmConfigFile::GetString(params->m_ConfigFile, "facebook.appid", "355198514515820");

        jmethodID jni_constructor = env->GetMethodID(fb_class, "<init>", "(Landroid/app/Activity;Ljava/lang/String;)V");
        jstring str_app_id = env->NewStringUTF(app_id);
        g_Facebook.m_FB = env->NewGlobalRef(env->NewObject(fb_class, jni_constructor, g_AndroidApp->activity->clazz, str_app_id));
        env->DeleteLocalRef(str_app_id);

        Detach();
    }

    g_Facebook.m_RefCount++;

    lua_State* L = params->m_L;
    int top = lua_gettop(L);
    luaL_register(L, LIB_NAME, Facebook_methods);

#define SETCONSTANT(name) \
        lua_pushnumber(L, (lua_Number) name); \
        lua_setfield(L, -2, #name);\

    SETCONSTANT(STATE_CREATED);
    SETCONSTANT(STATE_CREATED_TOKEN_LOADED);
    SETCONSTANT(STATE_CREATED_OPENING);
    SETCONSTANT(STATE_OPEN);
    SETCONSTANT(STATE_OPEN_TOKEN_EXTENDED);
    SETCONSTANT(STATE_CLOSED_LOGIN_FAILED);
    SETCONSTANT(STATE_CLOSED);

    SETCONSTANT(GAMEREQUEST_ACTIONTYPE_NONE);
    SETCONSTANT(GAMEREQUEST_ACTIONTYPE_SEND);
    SETCONSTANT(GAMEREQUEST_ACTIONTYPE_ASKFOR);
    SETCONSTANT(GAMEREQUEST_ACTIONTYPE_TURN);

    SETCONSTANT(GAMEREQUEST_FILTER_NONE);
    SETCONSTANT(GAMEREQUEST_FILTER_APPUSERS);
    SETCONSTANT(GAMEREQUEST_FILTER_APPNONUSERS);

    SETCONSTANT(AUDIENCE_NONE);
    SETCONSTANT(AUDIENCE_ONLYME);
    SETCONSTANT(AUDIENCE_FRIENDS);
    SETCONSTANT(AUDIENCE_EVERYONE);


    lua_pop(L, 1);
    assert(top == lua_gettop(L));

    return dmExtension::RESULT_OK;
}

dmExtension::Result UpdateFacebook(dmExtension::Params* params)
{
    {
        dmMutex::ScopedLock lk(g_Facebook.m_Mutex);
        for (uint32_t i=0;i!=g_Facebook.m_CmdQueue.Size();i++)
        {
            Command& cmd = g_Facebook.m_CmdQueue[i];
            if (cmd.m_L != params->m_L)
                continue;

            switch (cmd.m_Type)
            {
                case CMD_LOGIN:
                    RunStateCallback(&cmd);
                    break;
                case CMD_REQUEST_READ:
                case CMD_REQUEST_PUBLISH:
                    RunCallback(&cmd);
                    break;
                case CMD_DIALOG_COMPLETE:
                    RunDialogResultCallback(&cmd);
                    break;
            }
            if (cmd.m_Url != 0x0)
            {
                free((void*)cmd.m_Url);
                cmd.m_Url = 0x0;
            }
            if (cmd.m_Error != 0x0)
            {
                free((void*)cmd.m_Error);
                cmd.m_Error = 0x0;
            }

            g_Facebook.m_CmdQueue.EraseSwap(i--);
        }
    }
    return dmExtension::RESULT_OK;
}

dmExtension::Result FinalizeFacebook(dmExtension::Params* params)
{
    if (g_Facebook.m_FB != NULL)
    {
        if (--g_Facebook.m_RefCount == 0)
        {
            JNIEnv* env = Attach();
            env->DeleteGlobalRef(g_Facebook.m_FB);
            Detach();
            g_Facebook.m_FB = NULL;
            dmMutex::Delete(g_Facebook.m_Mutex);
            memset(&g_Facebook, 0x00, sizeof(Facebook));
        }
    }

    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(FacebookExt, "Facebook", 0, 0, InitializeFacebook, UpdateFacebook, FinalizeFacebook)
