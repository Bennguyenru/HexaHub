#include <dlib/log.h>
#include <script/script.h>
#include <gameroom/gameroom.h>
#include <extension/extension.h>

#include "facebook_private.h"
#include "facebook_analytics.h"

struct GameroomFB
{
    GameroomFB()
    {
        memset(this, 0, sizeof(*this));
        m_Callback = LUA_NOREF;
        m_Self = LUA_NOREF;
        m_MainThread = NULL;
        m_DisableFaceBookEvents = 0;
    }
    int        m_Callback;
    int        m_Self;
    lua_State* m_MainThread;
    int        m_DisableFaceBookEvents;

} g_GameroomFB;

////////////////////////////////////////////////////////////////////////////////
// Aux and callback checking functions & defines
//

static void ClearFBGCallback(lua_State* L)
{
    dmScript::Unref(L, LUA_REGISTRYINDEX, g_GameroomFB.m_Callback);
    dmScript::Unref(L, LUA_REGISTRYINDEX, g_GameroomFB.m_Self);
    g_GameroomFB.m_Callback = LUA_NOREF;
    g_GameroomFB.m_Self = LUA_NOREF;
    g_GameroomFB.m_MainThread = NULL;
}

static bool SetupFBGCallback(lua_State* L)
{
    lua_rawgeti(L, LUA_REGISTRYINDEX, g_GameroomFB.m_Callback);
    lua_rawgeti(L, LUA_REGISTRYINDEX, g_GameroomFB.m_Self);
    lua_pushvalue(L, -1);
    dmScript::SetInstance(L);

    if (!dmScript::IsInstanceValid(L))
    {
        dmLogError("Could not run facebook callback because the instance has been deleted.");
        lua_pop(L, 2);
        return false;
    }

    return true;
}

static bool HasFBGCallback(lua_State* L)
{
    if (g_GameroomFB.m_Callback == LUA_NOREF ||
        g_GameroomFB.m_Self == LUA_NOREF ||
        g_GameroomFB.m_MainThread == NULL) {
        return false;
    }
    return true;
}

////////////////////////////////////////////////////////////////////////////////
// Functions for running callbacks; dialog and login results
//

static void RunLoginResultCallback(lua_State* L, int result, const char* error)
{
    DM_LUA_STACK_CHECK(L, 0);

    if (!HasFBGCallback(L)) {
        dmLogError("No callback set for login result.");
        return;
    }

    if (!SetupFBGCallback(L)) {
        return;
    }

    lua_newtable(L);
    lua_pushnumber(L, result);
    lua_setfield(L, -2, "status");
    if (error) {
        lua_pushstring(L, error);
        lua_setfield(L, -2, "error");
    }

    int ret = lua_pcall(L, 2, 0, 0);
    if (ret != 0) {
        dmLogError("Error running facebook login callback: %s", lua_tostring(L,-1));
        lua_pop(L, 1);
    }
    ClearFBGCallback(L);
}

// Turns a string into a Lua table of strings, splits in the char in 'split'.
static void ParseToTable(lua_State* L, int table_index, const char* str, char split)
{
    int i = 1;
    const char* it = str;
    while (true)
    {
        char c = *it;

        if (c == split || c == '\0')
        {
            lua_pushlstring(L, str, it - str);
            lua_rawseti(L, table_index, i++);
            if (c == '\0') {
                break;
            }

            str = it+1;
        }
        it++;
    }
}

static void RunAppRequestCallback(lua_State* L, const char* request_id, const char* to)
{
    DM_LUA_STACK_CHECK(L, 0);

    if (!HasFBGCallback(L)) {
        dmLogError("No callback set for dialog result.");
        return;
    }

    if (!SetupFBGCallback(L)) {
        return;
    }

    // result table in a apprequest dialog callback looks like this;
    // result = {
    //     request_id = "",
    //     to = {
    //         [1] = "fbid_str",
    //         [2] = "fbid_str",
    //         ...
    //     }
    // }
    lua_newtable(L);
    lua_pushstring(L, request_id);
    lua_setfield(L, -2, "request_id");
    lua_newtable(L);
    if (strlen(to) > 0) {
        ParseToTable(L, lua_gettop(L), to, ',');
    }
    lua_setfield(L, -2, "to");

    int ret = lua_pcall(L, 2, 0, 0);
    if (ret != 0) {
        dmLogError("Error running facebook dialog callback: %s", lua_tostring(L,-1));
        lua_pop(L, 1);
    }

    ClearFBGCallback(L);
}

static void RunFeedCallback(lua_State* L, const char* post_id)
{
    DM_LUA_STACK_CHECK(L, 0);

    if (!HasFBGCallback(L)) {
        dmLogError("No callback set for dialog result.");
        return;
    }

    if (!SetupFBGCallback(L)) {
        return;
    }

    // result table in a feed dialog callback looks like this;
    // result = {
    //     post_id = "post_id_str"
    // }
    lua_newtable(L);
    lua_pushstring(L, post_id);
    lua_setfield(L, -2, "post_id");

    int ret = lua_pcall(L, 2, 0, 0);
    if (ret != 0) {
        dmLogError("Error running facebook dialog callback: %s", lua_tostring(L,-1));
        lua_pop(L, 1);
    }

    ClearFBGCallback(L);
}

static void RunDialogErrorCallback(lua_State* L, const char* error_str)
{
    DM_LUA_STACK_CHECK(L, 0);

    if (!HasFBGCallback(L)) {
        dmLogError("No callback set for dialog result.");
        return;
    }

    if (!SetupFBGCallback(L)) {
        return;
    }

    // Push a table with an "error" field with the error string.
    lua_newtable(L);
    lua_pushstring(L, "error");
    lua_pushstring(L, error_str);
    lua_rawset(L, -3);

    int ret = lua_pcall(L, 2, 0, 0);
    if (ret != 0) {
        dmLogError("Error running facebook dialog callback: %s", lua_tostring(L,-1));
        lua_pop(L, 1);
    }

    ClearFBGCallback(L);
}


////////////////////////////////////////////////////////////////////////////////
// Lua API
//

namespace dmFacebook {

int Facebook_Login(lua_State* L)
{
    if (!dmFBGameroom::CheckGameroomInit()) {
        return 0;
    }
    DM_LUA_STACK_CHECK(L, 0);

    luaL_checktype(L, 1, LUA_TFUNCTION);

    lua_pushvalue(L, 1);
    int callback = dmScript::Ref(L, LUA_REGISTRYINDEX);

    dmScript::GetInstance(L);
    int context = dmScript::Ref(L, LUA_REGISTRYINDEX);

    lua_State* thread = dmScript::GetMainThread(L);

    g_GameroomFB.m_Callback = callback;
    g_GameroomFB.m_Self = context;
    g_GameroomFB.m_MainThread = thread;

    fbg_Login();

    return 0;
}

static void LoginWithScopes(const char** permissions,
    uint32_t permission_count, int callback, int context, lua_State* thread)
{
    fbgLoginScope* login_scopes = (fbgLoginScope*)malloc(permission_count * sizeof(fbgLoginScope));

    size_t i = 0;
    for (uint32_t j = 0; j < permission_count; ++j)
    {
        const char* permission = permissions[j];

        if (strcmp("public_profile", permission) == 0) {
            login_scopes[i++] = fbgLoginScope::public_profile;
        } else if (strcmp("email", permission) == 0) {
            login_scopes[i++] = fbgLoginScope::email;
        } else if (strcmp("user_friends", permission) == 0) {
            login_scopes[i++] = fbgLoginScope::user_friends;
        } else if (strcmp("publish_actions", permission) == 0) {
            login_scopes[i++] = fbgLoginScope::publish_actions;
        }
    }

    fbg_Login_WithScopes(
      i,
      login_scopes
    );

    free(login_scopes);
}

void PlatformFacebookLoginWithReadPermissions(lua_State* L, const char** permissions,
    uint32_t permission_count, int callback, int context, lua_State* thread)
{
    if (!dmFBGameroom::CheckGameroomInit()) {
        return;
    }
    DM_LUA_STACK_CHECK(L, 0);

    g_GameroomFB.m_Callback = callback;
    g_GameroomFB.m_Self = context;
    g_GameroomFB.m_MainThread = thread;
    LoginWithScopes(permissions, permission_count, callback, context, thread);
}

void PlatformFacebookLoginWithPublishPermissions(lua_State* L, const char** permissions,
    uint32_t permission_count, int audience, int callback, int context, lua_State* thread)
{
    if (!dmFBGameroom::CheckGameroomInit()) {
        return;
    }
    DM_LUA_STACK_CHECK(L, 0);

    g_GameroomFB.m_Callback = callback;
    g_GameroomFB.m_Self = context;
    g_GameroomFB.m_MainThread = thread;
    LoginWithScopes(permissions, permission_count, callback, context, thread);
}

int Facebook_AccessToken(lua_State* L)
{
    if (!dmFBGameroom::CheckGameroomInit()) {
        return 0;
    }

    DM_LUA_STACK_CHECK(L, 1);

    // No access token available? Return empty string.
    fbgAccessTokenHandle access_token_handle = fbg_AccessToken_GetActiveAccessToken();
    if (!access_token_handle || !fbg_AccessToken_IsValid(access_token_handle)) {
        lua_pushstring(L, "");
        return 1;
    }

    size_t access_token_size = fbg_AccessToken_GetTokenString(access_token_handle, 0, 0) + 1;
    char* access_token_str = (char*)malloc(access_token_size * sizeof(char));
    fbg_AccessToken_GetTokenString(access_token_handle, access_token_str, access_token_size);
    lua_pushstring(L, access_token_str);
    free(access_token_str);

    return 1;
}

int Facebook_Permissions(lua_State* L)
{
    if (!dmFBGameroom::CheckGameroomInit()) {
        return 0;
    }

    DM_LUA_STACK_CHECK(L, 1);

    lua_newtable(L);

    // If there is no access token, push an empty table.
    fbgAccessTokenHandle access_token_handle = fbg_AccessToken_GetActiveAccessToken();
    if (!access_token_handle || !fbg_AccessToken_IsValid(access_token_handle)) {
        return 1;
    }

    // Initial call to figure out how many permissions we need to allocate for.
    size_t permission_count = fbg_AccessToken_GetPermissions(access_token_handle, 0, 0);

    fbgLoginScope* permissions = (fbgLoginScope*)malloc(permission_count * sizeof(fbgLoginScope));
    fbg_AccessToken_GetPermissions(access_token_handle, permissions, permission_count);

    for (size_t i = 0; i < permission_count; ++i) {
        lua_pushnumber(L, i);
        lua_pushstring(L, fbgLoginScope_ToString(permissions[i]));
        lua_rawset(L, -3);
    }

    free(permissions);

    return 1;
}

// GetTableStringValue and GetTableIntValue are helper functions for Facebook_ShowDialog
// to extract specific fields from a "show_dialog" param table.
static const char* GetTableStringValue(lua_State* L, int table_index, const char* key)
{
    const char* r = 0x0;

    lua_getfield(L, table_index, key);
    if (!lua_isnil(L, -1)) {

        int actual_lua_type = lua_type(L, -1);
        if (actual_lua_type != LUA_TSTRING) {
            dmLogError("Lua conversion expected entry '%s' to be a string but got %s",
                key, lua_typename(L, actual_lua_type));
        } else {
            r = lua_tostring(L, -1);
        }

    }
    lua_pop(L, 1);

    return r;
}

static int GetTableIntValue(lua_State* L, int table_index, const char* key)
{
    int r = 0x0;

    lua_getfield(L, table_index, key);
    if (!lua_isnil(L, -1)) {

        int actual_lua_type = lua_type(L, -1);
        if (actual_lua_type != LUA_TNUMBER) {
            dmLogError("Lua conversion expected entry '%s' to be a number but got %s",
                key, lua_typename(L, actual_lua_type));
        } else {
            r = lua_tointeger(L, -1);
        }

    }
    lua_pop(L, 1);

    return r;
}

int Facebook_ShowDialog(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    if (!dmFBGameroom::CheckGameroomInit()) {
        return 0;
    }

    dmhash_t dialog = dmHashString64(luaL_checkstring(L, 1));
    luaL_checktype(L, 2, LUA_TTABLE);
    luaL_checktype(L, 3, LUA_TFUNCTION);
    lua_pushvalue(L, 3);
    g_GameroomFB.m_Callback = dmScript::Ref(L, LUA_REGISTRYINDEX);

    dmScript::GetInstance(L);
    g_GameroomFB.m_Self = dmScript::Ref(L, LUA_REGISTRYINDEX);
    g_GameroomFB.m_MainThread = dmScript::GetMainThread(L);

    if (dialog == dmHashString64("feed")) {

        // For compatibility, we check if either "caption" or "title" is set.
        const char* content_title = GetTableStringValue(L, 2, "caption");
        if (!content_title) {
            content_title = GetTableStringValue(L, 2, "title");
        }

        fbg_FeedShare(
            GetTableStringValue(L, 2, "to"),
            GetTableStringValue(L, 2, "link"),
            GetTableStringValue(L, 2, "link_title"),
            content_title,
            GetTableStringValue(L, 2, "description"),
            GetTableStringValue(L, 2, "picture"),
            GetTableStringValue(L, 2, "media_source")
        );

    } else if (dialog == dmHashString64("apprequests") || dialog == dmHashString64("apprequest")) {

        int action_type = GetTableIntValue(L, 2, "action_type");
        const char* action = 0x0;
        switch (action_type)
        {
            case dmFacebook::GAMEREQUEST_ACTIONTYPE_SEND:
                action = "send";
            break;
            case dmFacebook::GAMEREQUEST_ACTIONTYPE_ASKFOR:
                action = "askfor";
            break;
            case dmFacebook::GAMEREQUEST_ACTIONTYPE_TURN:
                action = "turn";
            break;
        }

        int filters = GetTableIntValue(L, 2, "filters");
        const char* filters_str = 0x0;
        switch (filters)
        {
            case dmFacebook::GAMEREQUEST_FILTER_APPUSERS:
                filters_str = "app_users";
            break;
            case dmFacebook::GAMEREQUEST_FILTER_APPNONUSERS:
                filters_str = "app_non_users";
            break;
            default:
                filters_str = 0x0;
            break;
        }

        char* to_str = (char*)GetTableStringValue(L, 2, "to");

        // Check if recipients is set, it will override "to" field.
        lua_getfield(L, 2, "recipients");
        int top = lua_gettop(L);
        int has_recipients = lua_istable(L, top);
        if (has_recipients) {
            to_str = (char*)malloc(512);
            dmFacebook::LuaStringCommaArray(L, top, to_str, 512);
        }
        lua_pop(L, 1);

        lua_getfield(L, 2, "exclude_ids");
        top = lua_gettop(L);
        int has_exclude_ids = lua_istable(L, top);
        char* exclude_ids = 0x0;
        if (has_exclude_ids) {
            exclude_ids = (char*)malloc(512);
            dmFacebook::LuaStringCommaArray(L, top, exclude_ids, 512);
        }
        lua_pop(L, 1);

        fbg_AppRequest(
            GetTableStringValue(L, 2, "message"),
            action,
            GetTableStringValue(L, 2, "object_id"),
            to_str,
            filters_str,
            exclude_ids,
            GetTableIntValue(L, 2, "max_recipients"),
            GetTableStringValue(L, 2, "data"),
            GetTableStringValue(L, 2, "title")
        );

        if (has_recipients) {
            free(to_str);
        }

        if (has_exclude_ids) {
            free(exclude_ids);
        }

    } else {
        RunDialogErrorCallback(g_GameroomFB.m_MainThread, "Invalid dialog type");
    }

    return 0;
}

int Facebook_PostEvent(lua_State* L)
{
    if (!dmFBGameroom::CheckGameroomInit()) {
        return 0;
    }
    DM_LUA_STACK_CHECK(L, 0);

    const char* event = dmFacebook::Analytics::GetEvent(L, 1);
    float value_to_sum = (float)luaL_checknumber(L, 2);
    const fbgFormDataHandle form_data_handle = fbg_FormData_CreateNew();

    // Table is an optional argument and should only be parsed if provided.
    if (lua_gettop(L) >= 3)
    {
        // Transform LUA table to a format that can be used by all platforms.
        char* keys[dmFacebook::Analytics::MAX_PARAMS] = { 0 };
        char* values[dmFacebook::Analytics::MAX_PARAMS] = { 0 };
        unsigned int length = dmFacebook::Analytics::MAX_PARAMS;
        dmFacebook::Analytics::GetParameterTable(L, 3, (const char**)keys, (const char**)values, &length);

        // Prepare for Gameroom API
        for (unsigned int i = 0; i < length; ++i)
        {
            fbg_FormData_Set(form_data_handle, keys[i], strlen(keys[i]), values[i], strlen(values[i]));
        }
    }

    fbg_LogAppEventWithValueToSum(
        event,
        form_data_handle,
        value_to_sum
    );

    return 0;
}

// Facebook Gameroom SDK does not have a logout API.
int Facebook_Logout(lua_State* L) {
    return 0;
}

bool PlatformFacebookInitialized()
{
    return dmFBGameroom::CheckGameroomInit();
}

////////////////////////////////////////////////////////////////////////////////
// Deprecated functions, null implementations to keep API compatibility.
//

int Facebook_Me(lua_State* L) { return 0; }
int Facebook_EnableEventUsage(lua_State* L) { return 0; }
int Facebook_DisableEventUsage(lua_State* L) { return 0; }
int Facebook_RequestReadPermissions(lua_State* L) { return 0; }
int Facebook_RequestPublishPermissions(lua_State* L) { return 0; }

} // namespace dmFacebook


////////////////////////////////////////////////////////////////////////////////
// Extension functions
//

static dmExtension::Result AppInitializeFacebook(dmExtension::AppParams* params)
{
    return dmExtension::RESULT_OK;
}

static dmExtension::Result AppFinalizeFacebook(dmExtension::AppParams* params)
{
    return dmExtension::RESULT_OK;
}

static dmExtension::Result InitializeFacebook(dmExtension::Params* params)
{
    const char* iap_provider = dmConfigFile::GetString(params->m_ConfigFile, "windows.iap_provider", 0);
    if (iap_provider != 0x0 && strcmp(iap_provider, "Gameroom") == 0)
    {
        dmFacebook::LuaInit(params->m_L);
    }
    return dmExtension::RESULT_OK;
}

static dmExtension::Result UpdateFacebook(dmExtension::Params* params)
{
    if (!dmFBGameroom::CheckGameroomInit())
    {
        return dmExtension::RESULT_OK;
    }

    lua_State* L = params->m_L;

    fbgMessageHandle message;
    while ((message = dmFBGameroom::PopFacebookMessage()) != NULL) {
        fbgMessageType message_type = fbg_Message_GetType(message);
        switch (message_type) {
            case fbgMessage_AccessToken: {

                fbgAccessTokenHandle access_token = fbg_Message_AccessToken(message);
                if (fbg_AccessToken_IsValid(access_token))
                {
                    RunLoginResultCallback(L, dmFacebook::STATE_OPEN, 0x0);
                } else {
                    RunLoginResultCallback(L, dmFacebook::STATE_CLOSED_LOGIN_FAILED, "Login was cancelled");
                }

            break;
            }
            case fbgMessage_FeedShare: {

                fbgFeedShareHandle feed_share_handle = fbg_Message_FeedShare(message);
                fbid post_id = fbg_FeedShare_GetPostID(feed_share_handle);

                // If the post id is invalid, we interpret it as the dialog was closed
                // since there is no other way to know if it was closed or not.
                if (post_id != invalidRequestID)
                {
                    char post_id_str[128];
                    fbid_ToString((char*)post_id_str, 128, post_id);
                    RunFeedCallback(L, post_id_str);
                } else {
                    RunDialogErrorCallback(L, "Dialog canceled");
                }

            break;
            }
            case fbgMessage_AppRequest: {

                fbgAppRequestHandle app_request = fbg_Message_AppRequest(message);

                // Get app request id
                size_t request_id_size = fbg_AppRequest_GetRequestObjectId(app_request, 0, 0);
                if (request_id_size > 0) {
                    char* request_id = (char*)malloc(request_id_size * sizeof(char));
                    fbg_AppRequest_GetRequestObjectId(app_request, request_id, request_id_size);

                    // Get "to" list
                    size_t to_size = fbg_AppRequest_GetTo(app_request, 0, 0);
                    char* to = (char*)malloc(to_size * sizeof(char));
                    fbg_AppRequest_GetTo(app_request, to, to_size);

                    RunAppRequestCallback(L, request_id, to);

                    free(request_id);
                    free(to);
                } else {
                    RunDialogErrorCallback(L, "Dialog canceled");
                }

            }
            break;
            default:
                dmLogError("Unknown FB message: %u", message_type);
            break;
        }

        fbg_FreeMessage(message);
    }
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(FacebookExt, "Facebook", AppInitializeFacebook, AppFinalizeFacebook, InitializeFacebook, UpdateFacebook, 0, 0)
