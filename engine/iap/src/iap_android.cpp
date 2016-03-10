#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <dlib/array.h>
#include <dlib/log.h>
#include <dlib/dstrings.h>
#include <dlib/json.h>
#include <script/script.h>
#include <extension/extension.h>
#include <android_native_app_glue.h>
#include "iap.h"

#define LIB_NAME "iap"

extern struct android_app* g_AndroidApp;

struct IAP;

#define CMD_PRODUCT_RESULT (0)
#define CMD_PURCHASE_RESULT (1)

struct Command
{
    Command()
    {
        memset(this, 0, sizeof(*this));
    }
    uint32_t m_Command;
    int32_t  m_ResponseCode;
    void*    m_Data1;
};

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

struct IAPListener
{
    IAPListener()
    {
        m_L = 0;
        m_Callback = LUA_NOREF;
        m_Self = LUA_NOREF;
    }
    lua_State* m_L;
    int        m_Callback;
    int        m_Self;
};

struct IAP
{
    IAP()
    {
        memset(this, 0, sizeof(*this));
        m_Callback = LUA_NOREF;
        m_Self = LUA_NOREF;
        m_Listener.m_Callback = LUA_NOREF;
        m_Listener.m_Self = LUA_NOREF;
        m_autoFinishTransactions = true;
        m_StoreId = STORE_ID_GOOGLE;
    }
    int                  m_InitCount;
    int                  m_Callback;
    int                  m_Self;
    bool                 m_autoFinishTransactions;
    int                  m_StoreId;
    lua_State*           m_L;
    IAPListener          m_Listener;

    jobject              m_IAP;
    jobject              m_IAPJNI;
    jmethodID            m_List;
    jmethodID            m_Stop;
    jmethodID            m_Buy;
    jmethodID            m_Restore;
    jmethodID            m_ProcessPendingConsumables;
    jmethodID            m_FinishTransaction;
    int                  m_Pipefd[2];
};

IAP g_IAP;

static void VerifyCallback(lua_State* L)
{
    if (g_IAP.m_Callback != LUA_NOREF) {
        dmLogError("Unexpected callback set");
        luaL_unref(L, LUA_REGISTRYINDEX, g_IAP.m_Callback);
        luaL_unref(L, LUA_REGISTRYINDEX, g_IAP.m_Self);
        g_IAP.m_Callback = LUA_NOREF;
        g_IAP.m_Self = LUA_NOREF;
        g_IAP.m_L = 0;
    }
}

int IAP_List(lua_State* L)
{
    int top = lua_gettop(L);
    VerifyCallback(L);

    char buf[1024];
    buf[0] = '\0';

    int i = 0;
    lua_pushnil(L);
    while (lua_next(L, 1) != 0) {
        if (i > 0) {
            dmStrlCat(buf, ",", sizeof(buf));
        }
        const char* p = luaL_checkstring(L, -1);
        dmStrlCat(buf, p, sizeof(buf));
        lua_pop(L, 1);
        ++i;
    }

    luaL_checktype(L, 2, LUA_TFUNCTION);
    lua_pushvalue(L, 2);
    g_IAP.m_Callback = luaL_ref(L, LUA_REGISTRYINDEX);

    dmScript::GetInstance(L);
    g_IAP.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);

    g_IAP.m_L = dmScript::GetMainThread(L);

    JNIEnv* env = Attach();
    jstring products = env->NewStringUTF(buf);
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_List, products, g_IAP.m_IAPJNI);
    env->DeleteLocalRef(products);
    Detach();

    assert(top == lua_gettop(L));
    return 0;
}

int IAP_Buy(lua_State* L)
{
    int top = lua_gettop(L);

    const char* id = luaL_checkstring(L, 1);

    JNIEnv* env = Attach();
    jstring ids = env->NewStringUTF(id);
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_Buy, ids, g_IAP.m_IAPJNI);
    env->DeleteLocalRef(ids);
    Detach();


    assert(top == lua_gettop(L));
    return 0;
}

int IAP_Finish(lua_State* L)
{
    if(g_IAP.m_autoFinishTransactions)
    {
        dmLogWarning("Calling iap.finish when autofinish transactions is enabled. Ignored.");
        return 0;
    }


    int top = lua_gettop(L);

    luaL_checktype(L, 1, LUA_TTABLE);

    lua_getfield(L, -1, "state");
    if (lua_isnumber(L, -1))
    {
        if(lua_tointeger(L, -1) != TRANS_STATE_PURCHASED)
        {
            dmLogError("Invalid transaction state (must be iap.TRANS_STATE_PURCHASED).");
            lua_pop(L, 1);
            assert(top == lua_gettop(L));
            return 0;
        }
    }
    lua_pop(L, 1);

    lua_getfield(L, -1, "receipt");
    if (!lua_isstring(L, -1)) {
        dmLogError("Transaction error. Invalid transaction data, does not contain 'receipt' key.");
        lua_pop(L, 1);
    }
    else
    {
        const char * receipt = lua_tostring(L, -1);
        lua_pop(L, 1);

        JNIEnv* env = Attach();
        jstring receiptUTF = env->NewStringUTF(receipt);
        env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_FinishTransaction, receiptUTF, g_IAP.m_IAPJNI);
        env->DeleteLocalRef(receiptUTF);
        Detach();
    }

    assert(top == lua_gettop(L));
    return 0;
}

int IAP_Restore(lua_State* L)
{
    // TODO: Missing callback here for completion/error
    // See iap_ios.mm

    int top = lua_gettop(L);
    JNIEnv* env = Attach();
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_Restore, g_IAP.m_IAPJNI);
    Detach();

    assert(top == lua_gettop(L));

    lua_pushboolean(L, 1);
    return 1;
}

int IAP_SetListener(lua_State* L)
{
    IAP* iap = &g_IAP;
    luaL_checktype(L, 1, LUA_TFUNCTION);
    lua_pushvalue(L, 1);
    int cb = luaL_ref(L, LUA_REGISTRYINDEX);

    bool had_previous = false;
    if (iap->m_Listener.m_Callback != LUA_NOREF) {
        luaL_unref(iap->m_Listener.m_L, LUA_REGISTRYINDEX, iap->m_Listener.m_Callback);
        luaL_unref(iap->m_Listener.m_L, LUA_REGISTRYINDEX, iap->m_Listener.m_Self);
        had_previous = true;
    }

    iap->m_Listener.m_L = dmScript::GetMainThread(L);
    iap->m_Listener.m_Callback = cb;

    dmScript::GetInstance(L);
    iap->m_Listener.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);

    // On first set listener, trigger process old ones.
    if (!had_previous) {
        JNIEnv* env = Attach();
        env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_ProcessPendingConsumables, g_IAP.m_IAPJNI);
        Detach();
    }
    return 0;
}

int IAP_GetStoreId(lua_State* L)
{
    lua_pushinteger(L, g_IAP.m_StoreId);
    return 1;
}

static const luaL_reg IAP_methods[] =
{
    {"list", IAP_List},
    {"buy", IAP_Buy},
    {"finish", IAP_Finish},
    {"restore", IAP_Restore},
    {"set_listener", IAP_SetListener},
    {"get_store_id", IAP_GetStoreId},
    {0, 0}
};

// NOTE: Copy-paste from script_json
static int ToLua(lua_State*L, dmJson::Document* doc, int index)
{
    const dmJson::Node& n = doc->m_Nodes[index];
    const char* json = doc->m_Json;
    int l = n.m_End - n.m_Start;
    switch (n.m_Type)
    {
    case dmJson::TYPE_PRIMITIVE:
        if (l == 4 && memcmp(json + n.m_Start, "null", 4) == 0) {
            lua_pushnil(L);
        } else if (l == 4 && memcmp(json + n.m_Start, "true", 4) == 0) {
            lua_pushboolean(L, 1);
        } else if (l == 5 && memcmp(json + n.m_Start, "false", 5) == 0) {
            lua_pushboolean(L, 0);
        } else {
            double val = atof(json + n.m_Start);
            lua_pushnumber(L, val);
        }
        return index + 1;

    case dmJson::TYPE_STRING:
        lua_pushlstring(L, json + n.m_Start, l);
        return index + 1;

    case dmJson::TYPE_ARRAY:
        lua_createtable(L, n.m_Size, 0);
        ++index;
        for (int i = 0; i < n.m_Size; ++i) {
            index = ToLua(L, doc, index);
            lua_rawseti(L, -2, i+1);
        }
        return index;

    case dmJson::TYPE_OBJECT:
        lua_createtable(L, 0, n.m_Size);
        ++index;
        for (int i = 0; i < n.m_Size; i += 2) {
            index = ToLua(L, doc, index);
            index = ToLua(L, doc, index);
            lua_rawset(L, -3);
        }

        return index;
    }

    assert(false && "not reached");
    return index;
}

#ifdef __cplusplus
extern "C" {
#endif

static void PushError(lua_State*L, const char* error, int reason)
{
    if (error != 0) {
        lua_newtable(L);
        lua_pushstring(L, "error");
        lua_pushstring(L, error);
        lua_rawset(L, -3);
        lua_pushstring(L, "reason");
        lua_pushinteger(L, reason);
        lua_rawset(L, -3);
    } else {
        lua_pushnil(L);
    }
}

JNIEXPORT void JNICALL Java_com_defold_iap_IapJNI_onProductsResult__ILjava_lang_String_2(JNIEnv* env, jobject, jint responseCode, jstring productList)
{
    const char* pl = 0;
    if (productList)
    {
        pl = env->GetStringUTFChars(productList, 0);
    }

    Command cmd;
    cmd.m_Command = CMD_PRODUCT_RESULT;
    cmd.m_ResponseCode = responseCode;
    if (pl)
    {
        cmd.m_Data1 = strdup(pl);
        env->ReleaseStringUTFChars(productList, pl);
    }
    if (write(g_IAP.m_Pipefd[1], &cmd, sizeof(cmd)) != sizeof(cmd)) {
        dmLogFatal("Failed to write command");
    }
}

JNIEXPORT void JNICALL Java_com_defold_iap_IapJNI_onPurchaseResult__ILjava_lang_String_2(JNIEnv* env, jobject, jint responseCode, jstring purchaseData)
{
    const char* pd = 0;
    if (purchaseData)
    {
        pd = env->GetStringUTFChars(purchaseData, 0);
    }

    Command cmd;
    cmd.m_Command = CMD_PURCHASE_RESULT;
    cmd.m_ResponseCode = responseCode;

    if (pd)
    {
        cmd.m_Data1 = strdup(pd);
        env->ReleaseStringUTFChars(purchaseData, pd);
    }
    if (write(g_IAP.m_Pipefd[1], &cmd, sizeof(cmd)) != sizeof(cmd)) {
        dmLogFatal("Failed to write command");
    }
}

#ifdef __cplusplus
}
#endif

void HandleProductResult(const Command* cmd)
{
    lua_State* L = g_IAP.m_L;
    int top = lua_gettop(L);

    if (g_IAP.m_Callback == LUA_NOREF) {
        dmLogError("No callback set");
        return;
    }

    lua_rawgeti(L, LUA_REGISTRYINDEX, g_IAP.m_Callback);

    // Setup self
    lua_rawgeti(L, LUA_REGISTRYINDEX, g_IAP.m_Self);
    lua_pushvalue(L, -1);
    dmScript::SetInstance(L);

    if (!dmScript::IsInstanceValid(L))
    {
        dmLogError("Could not run IAP callback because the instance has been deleted.");
        lua_pop(L, 2);
        assert(top == lua_gettop(L));
        return;
    }

    if (cmd->m_ResponseCode == BILLING_RESPONSE_RESULT_OK) {
        dmJson::Document doc;
        dmJson::Result r = dmJson::Parse((const char*) cmd->m_Data1, &doc);
        if (r == dmJson::RESULT_OK && doc.m_NodeCount > 0) {
            ToLua(L, &doc, 0);
            lua_pushnil(L);
        } else {
            dmLogError("Failed to parse product response (%d)", r);
            lua_pushnil(L);
            PushError(L, "failed to parse product response", REASON_UNSPECIFIED);
        }
        dmJson::Free(&doc);
    } else {
        dmLogError("IAP error %d", cmd->m_ResponseCode);
        lua_pushnil(L);
        PushError(L, "failed to fetch product", REASON_UNSPECIFIED);
    }

    dmScript::PCall(L, 3, LUA_MULTRET);

    luaL_unref(L, LUA_REGISTRYINDEX, g_IAP.m_Callback);
    luaL_unref(L, LUA_REGISTRYINDEX, g_IAP.m_Self);
    g_IAP.m_Callback = LUA_NOREF;
    g_IAP.m_Self = LUA_NOREF;

    assert(top == lua_gettop(L));
}

void HandlePurchaseResult(const Command* cmd)
{
    lua_State* L = g_IAP.m_Listener.m_L;
    int top = lua_gettop(L);

    if (g_IAP.m_Listener.m_Callback == LUA_NOREF) {
        dmLogError("No callback set");
        return;
    }


    lua_rawgeti(L, LUA_REGISTRYINDEX, g_IAP.m_Listener.m_Callback);

    // Setup self
    lua_rawgeti(L, LUA_REGISTRYINDEX, g_IAP.m_Listener.m_Self);
    lua_pushvalue(L, -1);
    dmScript::SetInstance(L);

    if (!dmScript::IsInstanceValid(L))
    {
        dmLogError("Could not run IAP callback because the instance has been deleted.");
        lua_pop(L, 2);
        assert(top == lua_gettop(L));
        return;
    }

    if (cmd->m_ResponseCode == BILLING_RESPONSE_RESULT_OK) {
        dmJson::Document doc;
        dmJson::Result r = dmJson::Parse((const char*) cmd->m_Data1, &doc);
        if (r == dmJson::RESULT_OK && doc.m_NodeCount > 0) {
            ToLua(L, &doc, 0);
            lua_pushnil(L);
        } else {
            dmLogError("Failed to parse purchase response (%d)", r);
            lua_pushnil(L);
            PushError(L, "failed to parse purchase response", REASON_UNSPECIFIED);
        }
        dmJson::Free(&doc);
    } else if (cmd->m_ResponseCode == BILLING_RESPONSE_RESULT_USER_CANCELED) {
        lua_pushnil(L);
        PushError(L, "user canceled purchase", REASON_USER_CANCELED);
    } else {
        dmLogError("IAP error %d", cmd->m_ResponseCode);
        lua_pushnil(L);
        PushError(L, "failed to buy product", REASON_UNSPECIFIED);
    }

    dmScript::PCall(L, 3, LUA_MULTRET);

    assert(top == lua_gettop(L));
}

static int LooperCallback(int fd, int events, void* data)
{
    IAP* fb = (IAP*)data;
    (void)fb;
    Command cmd;
    if (read(g_IAP.m_Pipefd[0], &cmd, sizeof(cmd)) == sizeof(cmd)) {
        switch (cmd.m_Command)
        {
        case CMD_PRODUCT_RESULT:
            HandleProductResult(&cmd);
            break;
        case CMD_PURCHASE_RESULT:
            HandlePurchaseResult(&cmd);
            break;

        default:
            assert(false);
        }

        if (cmd.m_Data1) {
            free(cmd.m_Data1);
        }
    }
    else {
        dmLogFatal("read error in looper callback");
    }
    return 1;
}

dmExtension::Result InitializeIAP(dmExtension::Params* params)
{
    // TODO: Life-cycle managaemnt is *budget*. No notion of "static initalization"
    // Extend extension functionality with per system initalization?
    if (g_IAP.m_InitCount == 0) {

        int result = pipe(g_IAP.m_Pipefd);
        if (result != 0) {
            dmLogFatal("Could not open pipe for communication: %d", result);
        }

        result = ALooper_addFd(g_AndroidApp->looper, g_IAP.m_Pipefd[0], ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, LooperCallback, &g_IAP);
        if (result != 1) {
            dmLogFatal("Could not add file descriptor to looper: %d", result);
        }

        g_IAP.m_autoFinishTransactions = dmConfigFile::GetInt(params->m_ConfigFile, "iap.auto_finish_transactions", 1) == 1;

        JNIEnv* env = Attach();

        jclass activity_class = env->FindClass("android/app/NativeActivity");
        jmethodID get_class_loader = env->GetMethodID(activity_class,"getClassLoader", "()Ljava/lang/ClassLoader;");
        jobject cls = env->CallObjectMethod(g_AndroidApp->activity->clazz, get_class_loader);
        jclass class_loader = env->FindClass("java/lang/ClassLoader");
        jmethodID find_class = env->GetMethodID(class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

        const char* provider = dmConfigFile::GetString(params->m_ConfigFile, "android.iap_provider", "GooglePlay");
        const char* class_name = "com.defold.iap.IapGooglePlay";

        g_IAP.m_StoreId = STORE_ID_GOOGLE;
        if (!strcmp(provider, "Amazon")) {
            g_IAP.m_StoreId = STORE_ID_AMAZON;
            class_name = "com.defold.iap.IapAmazon";
        }
        else if (strcmp(provider, "GooglePlay")) {
            dmLogWarning("Unknown IAP provider name [%s], defaulting to GooglePlay", provider);
        }

        jstring str_class_name = env->NewStringUTF(class_name);

        jclass iap_class = (jclass)env->CallObjectMethod(cls, find_class, str_class_name);
        env->DeleteLocalRef(str_class_name);

        str_class_name = env->NewStringUTF("com.defold.iap.IapJNI");
        jclass iap_jni_class = (jclass)env->CallObjectMethod(cls, find_class, str_class_name);
        env->DeleteLocalRef(str_class_name);

        g_IAP.m_List = env->GetMethodID(iap_class, "listItems", "(Ljava/lang/String;Lcom/defold/iap/IListProductsListener;)V");
        g_IAP.m_Buy = env->GetMethodID(iap_class, "buy", "(Ljava/lang/String;Lcom/defold/iap/IPurchaseListener;)V");
        g_IAP.m_Restore = env->GetMethodID(iap_class, "restore", "(Lcom/defold/iap/IPurchaseListener;)V");
        g_IAP.m_Stop = env->GetMethodID(iap_class, "stop", "()V");
        g_IAP.m_ProcessPendingConsumables = env->GetMethodID(iap_class, "processPendingConsumables", "(Lcom/defold/iap/IPurchaseListener;)V");
        g_IAP.m_FinishTransaction = env->GetMethodID(iap_class, "finishTransaction", "(Ljava/lang/String;Lcom/defold/iap/IPurchaseListener;)V");

        jmethodID jni_constructor = env->GetMethodID(iap_class, "<init>", "(Landroid/app/Activity;Z)V");
        g_IAP.m_IAP = env->NewGlobalRef(env->NewObject(iap_class, jni_constructor, g_AndroidApp->activity->clazz, g_IAP.m_autoFinishTransactions));

        jni_constructor = env->GetMethodID(iap_jni_class, "<init>", "()V");
        g_IAP.m_IAPJNI = env->NewGlobalRef(env->NewObject(iap_jni_class, jni_constructor));

        Detach();
    }
    g_IAP.m_InitCount++;

    lua_State*L = params->m_L;
    int top = lua_gettop(L);
    luaL_register(L, LIB_NAME, IAP_methods);

#define SETCONSTANT(name) \
        lua_pushnumber(L, (lua_Number) name); \
        lua_setfield(L, -2, #name);\

    SETCONSTANT(TRANS_STATE_PURCHASING)
    SETCONSTANT(TRANS_STATE_PURCHASED)
    SETCONSTANT(TRANS_STATE_FAILED)
    SETCONSTANT(TRANS_STATE_RESTORED)
    SETCONSTANT(TRANS_STATE_UNVERIFIED)

    SETCONSTANT(REASON_UNSPECIFIED)
    SETCONSTANT(REASON_USER_CANCELED)

    SETCONSTANT(STORE_ID_GOOGLE)
    SETCONSTANT(STORE_ID_AMAZON)
    SETCONSTANT(STORE_ID_APPLE)
    SETCONSTANT(STORE_ID_FACEBOOK)

#undef SETCONSTANT

    lua_pop(L, 1);
    assert(top == lua_gettop(L));

    return dmExtension::RESULT_OK;
}

dmExtension::Result FinalizeIAP(dmExtension::Params* params)
{
    --g_IAP.m_InitCount;

    if (params->m_L == g_IAP.m_Listener.m_L && g_IAP.m_Listener.m_Callback != LUA_NOREF) {
        luaL_unref(g_IAP.m_Listener.m_L, LUA_REGISTRYINDEX, g_IAP.m_Listener.m_Callback);
        luaL_unref(g_IAP.m_Listener.m_L, LUA_REGISTRYINDEX, g_IAP.m_Listener.m_Self);
        g_IAP.m_Listener.m_L = 0;
        g_IAP.m_Listener.m_Callback = LUA_NOREF;
        g_IAP.m_Listener.m_Self = LUA_NOREF;
    }

    if (g_IAP.m_InitCount == 0) {
        JNIEnv* env = Attach();
        env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_Stop);
        env->DeleteGlobalRef(g_IAP.m_IAP);
        env->DeleteGlobalRef(g_IAP.m_IAPJNI);
        Detach();
        g_IAP.m_IAP = NULL;

        int result = ALooper_removeFd(g_AndroidApp->looper, g_IAP.m_Pipefd[0]);
        if (result != 1) {
            dmLogFatal("Could not remove fd from looper: %d", result);
        }

        close(g_IAP.m_Pipefd[0]);
        close(g_IAP.m_Pipefd[1]);
    }
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(IAPExt, "IAP", 0, 0, InitializeIAP, 0, 0, FinalizeIAP)
