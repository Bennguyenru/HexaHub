#include <dlib/log.h>
#include <extension/extension.h>
#include <script/script.h>
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <FBSDKCoreKit/FBSDKCoreKit.h>
#import <FBSDKLoginKit/FBSDKLoginKit.h>
#import <FBSDKShareKit/FBSDKShareKit.h>
#import <objc/runtime.h>

#include "facebook_private.h"
#include "facebook_analytics.h"

struct Facebook
{
    Facebook() {
        memset(this, 0, sizeof(*this));
        m_Callback = LUA_NOREF;
        m_Self = LUA_NOREF;
    }

    FBSDKLoginManager *m_Login;
    NSDictionary* m_Me;
    int m_Callback;
    int m_Self;
    int m_DisableFaceBookEvents;
    lua_State* m_MainThread;
    bool m_AccessTokenAvailable;
    bool m_AccessTokenRequested;
    id<UIApplicationDelegate,
       FBSDKSharingDelegate,
       FBSDKAppInviteDialogDelegate,
       FBSDKGameRequestDialogDelegate> m_Delegate;
};

Facebook g_Facebook;

static void UpdateUserData();
static void DoLogin();


static void RunDialogResultCallback(lua_State*L, NSDictionary* result, NSError* error);

// AppDelegate used temporarily to hijack all AppDelegate messages
// An improvment could be to create generic proxy
@interface FacebookAppDelegate : NSObject <UIApplicationDelegate,
    FBSDKSharingDelegate, FBSDKAppInviteDialogDelegate,
    FBSDKGameRequestDialogDelegate>

- (BOOL)application:(UIApplication *)application
                   openURL:(NSURL *)url
                   sourceApplication:(NSString *)sourceApplication
                   annotation:(id)annotation;

- (BOOL)application:(UIApplication *)application
                   didFinishLaunchingWithOptions:(NSDictionary *)launchOptions;
@end

@implementation FacebookAppDelegate
    - (BOOL)application:(UIApplication *)application
                       openURL:(NSURL *)url
                       sourceApplication:(NSString *)sourceApplication
                       annotation:(id)annotation {
        if(!g_Facebook.m_Login)
        {
            return false;
        }
        return [[FBSDKApplicationDelegate sharedInstance] application:application
                                                              openURL:url
                                                    sourceApplication:sourceApplication
                                                           annotation:annotation];
    }

    - (void)applicationDidBecomeActive:(UIApplication *)application {
        if(!g_Facebook.m_Login)
        {
            return;
        }
        if(!g_Facebook.m_DisableFaceBookEvents)
        {
            [FBSDKAppEvents activateApp];
        }

        // At the point of app activation, the currentAccessToken will be available if present.
        // If a token has been requested (through a login call), do the login at this point, or just update the userdata if logged in.
        g_Facebook.m_AccessTokenAvailable = true;
        if(g_Facebook.m_AccessTokenRequested) {
            g_Facebook.m_AccessTokenRequested = false;
            if ([FBSDKAccessToken currentAccessToken]) {
                UpdateUserData();
            } else {
                DoLogin();
            }
        }
    }

    - (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
        if(!g_Facebook.m_Login)
        {
            return false;
        }
        return [[FBSDKApplicationDelegate sharedInstance] application:application
                                        didFinishLaunchingWithOptions:launchOptions];
    }

    // Sharing related methods
    - (void)sharer:(id<FBSDKSharing>)sharer didCompleteWithResults :(NSDictionary *)results {
        if(!g_Facebook.m_Login)
        {
            return;
        }
        if (results != nil) {
            // fix result so it complies with JS result fields
            NSMutableDictionary* new_res = [NSMutableDictionary dictionary];
            if (results[@"postId"]) {
                [new_res setValue:results[@"postId"] forKey:@"post_id"];
            }
            RunDialogResultCallback(g_Facebook.m_MainThread, new_res, 0);
        } else {
            RunDialogResultCallback(g_Facebook.m_MainThread, 0, 0);
        }
    }

    - (void)sharer:(id<FBSDKSharing>)sharer didFailWithError:(NSError *)error {
        if(!g_Facebook.m_Login)
        {
            return;
        }
        RunDialogResultCallback(g_Facebook.m_MainThread, 0, error);
    }

    - (void)sharerDidCancel:(id<FBSDKSharing>)sharer {
        if(!g_Facebook.m_Login)
        {
            return;
        }
        NSMutableDictionary *errorDetail = [NSMutableDictionary dictionary];
        [errorDetail setValue:@"Share dialog was cancelled" forKey:NSLocalizedDescriptionKey];
        RunDialogResultCallback(g_Facebook.m_MainThread, 0, [NSError errorWithDomain:@"facebook" code:0 userInfo:errorDetail]);
    }

    // App invite related methods
    - (void) appInviteDialog: (FBSDKAppInviteDialog *)appInviteDialog didCompleteWithResults:(NSDictionary *)results {
        if(!g_Facebook.m_Login)
        {
            return;
        }
        RunDialogResultCallback(g_Facebook.m_MainThread, results, 0);
    }

    - (void) appInviteDialog: (FBSDKAppInviteDialog *)appInviteDialog didFailWithError:(NSError *)error {
        if(!g_Facebook.m_Login)
        {
            return;
        }
        RunDialogResultCallback(g_Facebook.m_MainThread, 0, error);
    }

    // Game request related methods
    - (void)gameRequestDialog:(FBSDKGameRequestDialog *)gameRequestDialog didCompleteWithResults:(NSDictionary *)results {
        if(!g_Facebook.m_Login)
        {
            return;
        }
        if (results != nil) {
            // fix result so it complies with JS result fields
            NSMutableDictionary* new_res = [NSMutableDictionary dictionaryWithDictionary:@{
                @"to" : [[NSMutableArray alloc] init]
            }];

            for (NSString* key in results) {
                if ([key hasPrefix:@"to"]) {
                    [new_res[@"to"] addObject:results[key]];
                } else {
                    [new_res setObject:results[key] forKey:key];
                }

                // Alias request_id == request,
                // want to have same result fields on both Android & iOS.
                if ([key isEqualToString:@"request"]) {
                    [new_res setObject:results[key] forKey:@"request_id"];
                }
            }
            RunDialogResultCallback(g_Facebook.m_MainThread, new_res, 0);
        } else {
            RunDialogResultCallback(g_Facebook.m_MainThread, 0, 0);
        }
    }

    - (void)gameRequestDialog:(FBSDKGameRequestDialog *)gameRequestDialog didFailWithError:(NSError *)error {
        if(!g_Facebook.m_Login)
        {
            return;
        }
        RunDialogResultCallback(g_Facebook.m_MainThread, 0, error);
    }

    - (void)gameRequestDialogDidCancel:(FBSDKGameRequestDialog *)gameRequestDialog {
        if(!g_Facebook.m_Login)
        {
            return;
        }
        NSMutableDictionary *errorDetail = [NSMutableDictionary dictionary];
        [errorDetail setValue:@"Game request dialog was cancelled" forKey:NSLocalizedDescriptionKey];
        RunDialogResultCallback(g_Facebook.m_MainThread, 0, [NSError errorWithDomain:@"facebook" code:0 userInfo:errorDetail]);
    }


@end

////////////////////////////////////////////////////////////////////////////////
// Helper functions for Lua API

static id LuaToObjC(lua_State* L, int index);
static NSArray* TableToNSArray(lua_State* L, int table_index)
{
    int top = lua_gettop(L);
    NSMutableArray* arr = [[NSMutableArray alloc] init];
    lua_pushnil(L);
    while (lua_next(L, table_index) != 0) {
        [arr addObject: LuaToObjC(L, lua_gettop(L))];
        lua_pop(L, 1);
    }

    assert(top == lua_gettop(L));
    return arr;
}

static id LuaToObjC(lua_State* L, int index)
{
    int top = lua_gettop(L);
    id r = nil;
    int actual_lua_type = lua_type(L, index);

    if (actual_lua_type == LUA_TSTRING) {
        r = [NSString stringWithUTF8String: lua_tostring(L, index)];

    } else if (actual_lua_type == LUA_TTABLE) {
        r = TableToNSArray(L, index);

    } else if (actual_lua_type == LUA_TNUMBER) {
        r = [NSNumber numberWithDouble: lua_tonumber(L, index)];

    } else if (actual_lua_type == LUA_TBOOLEAN) {
        r = [NSNumber numberWithBool:lua_toboolean(L, index)];

    } else {
        dmLogWarning("Unsupported value type '%d'", lua_type(L, index));
    }

    assert(top == lua_gettop(L));
    return r;
}

static id GetTableValue(lua_State* L, int table_index, NSArray* keys, int expected_lua_type)
{
    id r = nil;
    int top = lua_gettop(L);
    for (NSString *key in keys) {
        lua_getfield(L, table_index, [key UTF8String]);
        if (!lua_isnil(L, -1)) {

            int actual_lua_type = lua_type(L, -1);
            if (actual_lua_type != expected_lua_type) {
                dmLogError("Lua conversion expected entry '%s' to be %s but got %s",
                    [key UTF8String], lua_typename(L, expected_lua_type), lua_typename(L, actual_lua_type));
            } else {
                r = LuaToObjC(L, lua_gettop(L));
            }

        }
        lua_pop(L, 1);
    }

    assert(top == lua_gettop(L));
    return r;
}

static void PushError(lua_State*L, NSError* error)
{
    // Could be extended with error codes etc
    if (error != 0) {
        lua_newtable(L);
        lua_pushstring(L, "error");
        lua_pushstring(L, [error.localizedDescription UTF8String]);
        lua_rawset(L, -3);
    } else {
        lua_pushnil(L);
    }
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

static void RunStateCallback(lua_State*L, dmFacebook::State status, NSError* error)
{
    if (g_Facebook.m_Callback != LUA_NOREF) {
        int top = lua_gettop(L);

        lua_rawgeti(L, LUA_REGISTRYINDEX, g_Facebook.m_Callback);
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

        lua_pushnumber(L, (lua_Number) status);
        PushError(L, error);

        int ret = lua_pcall(L, 3, LUA_MULTRET, 0);
        if (ret != 0) {
            dmLogError("Error running facebook callback: %s", lua_tostring(L,-1));
            lua_pop(L, 1);
        }
        assert(top == lua_gettop(L));
        luaL_unref(L, LUA_REGISTRYINDEX, g_Facebook.m_Callback);
        luaL_unref(L, LUA_REGISTRYINDEX, g_Facebook.m_Self);
        g_Facebook.m_Callback = LUA_NOREF;
        g_Facebook.m_Self = LUA_NOREF;
    } else {
        dmLogError("No callback set");
    }
}

static void RunCallback(lua_State*L, NSError* error)
{
    if (g_Facebook.m_Callback != LUA_NOREF) {
        int top = lua_gettop(L);

        lua_rawgeti(L, LUA_REGISTRYINDEX, g_Facebook.m_Callback);
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

        int ret = lua_pcall(L, 2, LUA_MULTRET, 0);
        if (ret != 0) {
            dmLogError("Error running facebook callback: %s", lua_tostring(L,-1));
            lua_pop(L, 1);
        }
        assert(top == lua_gettop(L));
        luaL_unref(L, LUA_REGISTRYINDEX, g_Facebook.m_Callback);
        luaL_unref(L, LUA_REGISTRYINDEX, g_Facebook.m_Self);
        g_Facebook.m_Callback = LUA_NOREF;
        g_Facebook.m_Self = LUA_NOREF;
    } else {
        dmLogError("No callback set");
    }
}

static void ObjCToLua(lua_State*L, id obj)
{
    if ([obj isKindOfClass:[NSString class]]) {
        const char* str = [((NSString*) obj) UTF8String];
        lua_pushstring(L, str);
    } else if ([obj isKindOfClass:[NSNumber class]]) {
        lua_pushnumber(L, [((NSNumber*) obj) doubleValue]);
    } else if ([obj isKindOfClass:[NSNull class]]) {
        lua_pushnil(L);
    } else if ([obj isKindOfClass:[NSDictionary class]]) {
        NSDictionary* dict = (NSDictionary*) obj;
        lua_createtable(L, 0, [dict count]);
        for (NSString* key in dict) {
            lua_pushstring(L, [key UTF8String]);
            id value = [dict objectForKey:key];
            ObjCToLua(L, (NSDictionary*) value);
            lua_rawset(L, -3);
        }
    } else if ([obj isKindOfClass:[NSArray class]]) {
        NSArray* a = (NSArray*) obj;
        lua_createtable(L, [a count], 0);
        for (int i = 0; i < [a count]; ++i) {
            ObjCToLua(L, [a objectAtIndex: i]);
            lua_rawseti(L, -2, i+1);
        }
    } else {
        dmLogWarning("Unsupported value '%s' (%s)", [[obj description] UTF8String], [[[obj class] description] UTF8String]);
        lua_pushnil(L);
    }
}

static void RunDialogResultCallback(lua_State*L, NSDictionary* result, NSError* error)
{
    if (g_Facebook.m_Callback != LUA_NOREF) {
        int top = lua_gettop(L);

        lua_rawgeti(L, LUA_REGISTRYINDEX, g_Facebook.m_Callback);
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

        ObjCToLua(L, result);

        PushError(L, error);

        int ret = lua_pcall(L, 3, LUA_MULTRET, 0);
        if (ret != 0) {
            dmLogError("Error running facebook callback: %s", lua_tostring(L,-1));
            lua_pop(L, 1);
        }
        assert(top == lua_gettop(L));
        luaL_unref(L, LUA_REGISTRYINDEX, g_Facebook.m_Callback);
        luaL_unref(L, LUA_REGISTRYINDEX, g_Facebook.m_Self);
        g_Facebook.m_Callback = LUA_NOREF;
        g_Facebook.m_Self = LUA_NOREF;
    } else {
        dmLogError("No callback set");
    }
}

static void AppendArray(lua_State*L, NSMutableArray* array, int table)
{
    lua_pushnil(L);
    while (lua_next(L, table) != 0) {
        const char* p = luaL_checkstring(L, -1);
        [array addObject: [NSString stringWithUTF8String: p]];
        lua_pop(L, 1);
    }
}

static void UpdateUserData()
{
    // Login successfull, now grab user info
    // In SDK 4+ we explicitly have to set which fields we want,
    // since earlier SDK versions returned these by default.
    NSMutableDictionary *params = [NSMutableDictionary dictionary];
    [params setObject:@"last_name,link,id,gender,email,locale,name,first_name,updated_time" forKey:@"fields"];
    [[[FBSDKGraphRequest alloc] initWithGraphPath:@"me" parameters:params]
    startWithCompletionHandler:^(FBSDKGraphRequestConnection *connection, id graphresult, NSError *error) {

        [g_Facebook.m_Me release];
        if (!error) {
            g_Facebook.m_Me = [[NSDictionary alloc] initWithDictionary: graphresult];
            RunStateCallback(g_Facebook.m_MainThread, dmFacebook::STATE_OPEN, error);
        } else {
            g_Facebook.m_Me = nil;
            dmLogWarning("Failed to fetch user-info: %s", [[error localizedDescription] UTF8String]);
            RunStateCallback(g_Facebook.m_MainThread, dmFacebook::STATE_CLOSED_LOGIN_FAILED, error);
        }

    }];
}

static void DoLogin()
{
    NSMutableArray *permissions = [[NSMutableArray alloc] initWithObjects: @"public_profile", @"email", @"user_friends", nil];
    [g_Facebook.m_Login logInWithReadPermissions: permissions handler:^(FBSDKLoginManagerLoginResult *result, NSError *error) {
        if (error) {
            RunStateCallback(g_Facebook.m_MainThread, dmFacebook::STATE_CLOSED_LOGIN_FAILED, error);
        } else if (result.isCancelled) {
            NSMutableDictionary *errorDetail = [NSMutableDictionary dictionary];
            [errorDetail setValue:@"Login was cancelled" forKey:NSLocalizedDescriptionKey];
            RunStateCallback(g_Facebook.m_MainThread, dmFacebook::STATE_CLOSED_LOGIN_FAILED, [NSError errorWithDomain:@"facebook" code:0 userInfo:errorDetail]);
        } else {
            if ([result.grantedPermissions containsObject:@"public_profile"] &&
                [result.grantedPermissions containsObject:@"email"] &&
                [result.grantedPermissions containsObject:@"user_friends"]) {

                UpdateUserData();

            } else {
                // Note that the user can still be logged in at this point, but with reduced set of permissions.
                // In order to be consistent with other platforms, we consider this to be a failed login.
                NSMutableDictionary *errorDetail = [NSMutableDictionary dictionary];
                [errorDetail setValue:@"Not granted all requested permissions." forKey:NSLocalizedDescriptionKey];
                RunStateCallback(g_Facebook.m_MainThread, dmFacebook::STATE_CLOSED_LOGIN_FAILED, [NSError errorWithDomain:@"facebook" code:0 userInfo:errorDetail]);
            }
        }

    }];
}

static FBSDKDefaultAudience convertDefaultAudience(int fromLuaInt) {
    switch (fromLuaInt) {
        case 2:
            return FBSDKDefaultAudienceOnlyMe;
        case 4:
            return FBSDKDefaultAudienceEveryone;
        case 3:
        default:
            return FBSDKDefaultAudienceFriends;
    }
}

static FBSDKGameRequestActionType convertGameRequestAction(int fromLuaInt) {
    switch (fromLuaInt) {
        case 3:
            return FBSDKGameRequestActionTypeAskFor;
        case 4:
            return FBSDKGameRequestActionTypeTurn;
        case 2:
            return FBSDKGameRequestActionTypeSend;
        case 1:
        default:
            return FBSDKGameRequestActionTypeNone;
    }
}

static FBSDKGameRequestFilter convertGameRequestFilters(int fromLuaInt) {
    switch (fromLuaInt) {
        case 3:
            return FBSDKGameRequestFilterAppNonUsers;
        case 2:
            return FBSDKGameRequestFilterAppUsers;
        case 1:
        default:
            return FBSDKGameRequestFilterNone;
    }
}

////////////////////////////////////////////////////////////////////////////////
// Lua API
//

namespace dmFacebook {

 /*# initiate a Facebook login
 *
 * This function opens a Facebook login dialog allowing the user to log into Facebook
 * with his/her account. This performs a login requesting read permission for:
 * <ul>
 *   <li><code>"public_profile"</code></li>
 *   <li><code>"email"</code></li>
 *   <li><code>"user_friends"</code></li>
 * </ul>
 * The actual permission that the user grants can be retrieved with <code>facebook.permissions()</code>.
 *
 * @name facebook.login
 * @param callback callback function with parameters (self, status, error), when the login attempt is done. (function)
 * @examples
 * <pre>
 * facebook.login(function (self, status, error)
 *     if error or status ~= facebook.STATE_OPEN then
 *         print("Facebook log in error: " .. status)
 *         return
 *     end
 * end)
 * </pre>
 */
int Facebook_Login(lua_State* L)
{
    if(!g_Facebook.m_Login)
    {
        return luaL_error(L, "Facebook module isn't initialized! Did you set the facebook.app_id in game.project?");
    }
    int top = lua_gettop(L);
    VerifyCallback(L);

    luaL_checktype(L, 1, LUA_TFUNCTION);
    lua_pushvalue(L, 1);
    g_Facebook.m_Callback = luaL_ref(L, LUA_REGISTRYINDEX);
    g_Facebook.m_MainThread = dmScript::GetMainThread(L);

    dmScript::GetInstance(L);
    g_Facebook.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);

    if ([FBSDKAccessToken currentAccessToken]) {
        UpdateUserData();
    } else {
        // The accesstoken is not aviablale until app activation (this is where m_AccessTokenAvaliable is set), but this function can be called before then.
        if(g_Facebook.m_AccessTokenAvailable) {
            // there is no accesstoken, call login
            g_Facebook.m_AccessTokenRequested = false;
            DoLogin();
        } else {
            // there is no accesstoken avaialble yet, set the request flag so the login (or user data update) can be done in app activation instead
            g_Facebook.m_AccessTokenRequested = true;
        }
    }

    assert(top == lua_gettop(L));
    return 0;
}

 /*# logout from Facebook
 *
 * Logout from Facebook.
 *
 * @name facebook.logout
 *
 */
int Facebook_Logout(lua_State* L)
{
    if(!g_Facebook.m_Login)
    {
        return luaL_error(L, "Facebook module isn't initialized! Did you set the facebook.app_id in game.project?");
    }
    [g_Facebook.m_Login logOut];
    [g_Facebook.m_Me release];
    g_Facebook.m_Me = 0;
    return 0;
}

/*# logs the user in with the requested read permissions
 *
 * Log in the user on Facebook with the specified read permissions. Check the permissions the user
 * actually granted with <code>facebook.permissions()</code>.
 *
 * @name facebook.request_read_permissions
 * @param permissions a table with the requested permission strings (table)
 * The following strings are valid permission identifiers and are requested by default on login:
 * <ul>
 *   <li><code>"public_profile"</code></li>
 *   <li><code>"email"</code></li>
 *   <li><code>"user_friends"</code></li>
 * </ul>
 * A comprehensive list of permissions can be found at <a href='https://developers.facebook.com/docs/facebook-login/permissions/v2.6'>https://developers.facebook.com/docs/facebook-login/permissions/v2.6</a>
 * @param callback callback function with parameters (self, error) that is called when the permission request dialog is closed. (function)
 * @examples
 * <pre>
 * facebook.request_read_permissions({ "user_friends", "email" }, function (self, error)
 *     if error then
 *         -- Something bad happened
 *         return
 *     end
 * end)
 * </pre>
 */
int Facebook_RequestReadPermissions(lua_State* L)
{
    if(!g_Facebook.m_Login)
    {
        return luaL_error(L, "Facebook module isn't initialized! Did you set the facebook.app_id in game.project?");
    }
    int top = lua_gettop(L);
    VerifyCallback(L);

    luaL_checktype(L, 1, LUA_TTABLE);
    luaL_checktype(L, 2, LUA_TFUNCTION);
    lua_pushvalue(L, 2);
    g_Facebook.m_Callback = luaL_ref(L, LUA_REGISTRYINDEX);

    dmScript::GetInstance(L);
    g_Facebook.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);
    lua_State* main_thread = dmScript::GetMainThread(L);

    NSMutableArray *permissions = [[NSMutableArray alloc] init];
    AppendArray(L, permissions, 1);

    [g_Facebook.m_Login logInWithReadPermissions: permissions handler:^(FBSDKLoginManagerLoginResult *result, NSError *error) {
        RunCallback(main_thread, error);
    }];

    assert(top == lua_gettop(L));
    return 0;
}

/*# logs the user in with the requested publish permissions
 *
 *  Log in the user on Facebook with the specified publish permissions. Check the permissions the user
 *  actually granted with <code>facebook.permissions()</code>.
 *
 * @name facebook.request_publish_permissions
 * @param permissions a table with the requested permissions (table)
 * @param audience (constant|number)
 * <ul>
 *     <li>facebook.AUDIENCE_NONE</li>
 *     <li>facebook.AUDIENCE_ONLYME</li>
 *     <li>facebook.AUDIENCE_FRIENDS</li>
 *     <li>facebook.AUDIENCE_EVERYONE</li>
 * </ul>
 * @param callback callback function with parameters (self, error) that is called when the permission request dialog is closed. (function)
 * @examples
 * <pre>
 * facebook.request_publish_permissions({ "user_friends", "email" }, function (self, error)
 *     if error then
 *         -- Something bad happened
 *         return
 *     end
 * end)
 * </pre>
 */

int Facebook_RequestPublishPermissions(lua_State* L)
{
    if(!g_Facebook.m_Login)
    {
        return luaL_error(L, "Facebook module isn't initialized! Did you set the facebook.app_id in game.project?");
    }
    int top = lua_gettop(L);
    VerifyCallback(L);

    luaL_checktype(L, 1, LUA_TTABLE);
    FBSDKDefaultAudience audience = convertDefaultAudience(luaL_checkinteger(L, 2));
    luaL_checktype(L, 3, LUA_TFUNCTION);
    lua_pushvalue(L, 3);
    g_Facebook.m_Callback = luaL_ref(L, LUA_REGISTRYINDEX);

    dmScript::GetInstance(L);
    g_Facebook.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);
    lua_State* main_thread = dmScript::GetMainThread(L);

    NSMutableArray *permissions = [[NSMutableArray alloc] init];
    AppendArray(L, permissions, 1);

    [g_Facebook.m_Login setDefaultAudience: audience];
    [g_Facebook.m_Login logInWithPublishPermissions: permissions handler:^(FBSDKLoginManagerLoginResult *result, NSError *error) {
        RunCallback(main_thread, error);
    }];

    assert(top == lua_gettop(L));
    return 0;
}

/*# get the current Facebook access token
 *
 * @name facebook.access_token
 * @return the access token (string)
 */

int Facebook_AccessToken(lua_State* L)
{
    if(!g_Facebook.m_Login)
    {
        return luaL_error(L, "Facebook module isn't initialized! Did you set the facebook.app_id in game.project?");
    }
    const char* token = [[[FBSDKAccessToken currentAccessToken] tokenString] UTF8String];
    lua_pushstring(L, token);
    return 1;
}

/*# get the currently granted permissions
 *
 * This function returns a table with all the currently granted permission strings.
 *
 * @name facebook.permissions
 * @return the permissions (table)
 * @examples
 * <pre>
 * for _,permission in ipairs(facebook.permissions()) do
 *     if permission == "user_likes" then
 *         -- "user_likes" granted...
 *         break
 *     end
 * end
 * </pre>
 */
int Facebook_Permissions(lua_State* L)
{
    if(!g_Facebook.m_Login)
    {
        return luaL_error(L, "Facebook module isn't initialized! Did you set the facebook.app_id in game.project?");
    }
    int top = lua_gettop(L);

    lua_newtable(L);
    NSSet* permissions = [[FBSDKAccessToken currentAccessToken] permissions];
    int i = 1;
    for (NSString* p in permissions) {
        lua_pushnumber(L, i);
        lua_pushstring(L, [p UTF8String]);
        lua_rawset(L, -3);
        ++i;
    }

    assert(top + 1 == lua_gettop(L));
    return 1;
}

/*# return a table with "me" user data
 *
 * This function returns a table of user data as requested from the Facebook Graph API
 * "me" path. The user data is fetched during facebook.login().
 *
 * The table contains the following fields:
 *
 * <ul>
 *   <li><code>"name"</code></li>
 *   <li><code>"last_name"</code></li>
 *   <li><code>"first_name"</code></li>
 *   <li><code>"id"</code></li>
 *   <li><code>"email"</code></li>
 *   <li><code>"link"</code></li>
 *   <li><code>"gender"</code></li>
 *   <li><code>"locale"</code></li>
 *   <li><code>"updated_time"</code></li>
 * </ul>
 *
 * @name facebook.me
 * @return table with user data fields (table)
 */
int Facebook_Me(lua_State* L)
{
    if(!g_Facebook.m_Login)
    {
        return luaL_error(L, "Facebook module isn't initialized! Did you set the facebook.app_id in game.project?");
    }
    int top = lua_gettop(L);

    if (g_Facebook.m_Me) {
        lua_newtable(L);
        for (id key in g_Facebook.m_Me) {
            id obj = [g_Facebook.m_Me objectForKey: key];
            if ([obj isKindOfClass:[NSString class]]) {
                lua_pushstring(L, [key UTF8String]);
                lua_pushstring(L, [obj UTF8String]);
                lua_rawset(L, -3);
            }
        }
    } else {
        lua_pushnil(L);
    }

    assert(top + 1 == lua_gettop(L));
    return 1;
}

/*# post an event to Facebook Analytics
 *
 * This function will post an event to Facebook Analytics where it can be used
 * in the Facebook Insights system.
 *
 * @name facebook.post_event
 *
 * @param event (constant|text) An event can either be one of the predefined
 * constants below or a text which can be used to define a custom event that is
 * registered with Facebook Analytics.
 * <ul>
 *     <li>facebook.EVENT_ACHIEVED_LEVEL</li>
 *     <li>facebook.EVENT_ACTIVATED_APP</li>
 *     <li>facebook.EVENT_ADDED_PAYMENT_INFO</li>
 *     <li>facebook.EVENT_ADDED_TO_CART</li>
 *     <li>facebook.EVENT_ADDED_TO_WISHLIST</li>
 *     <li>facebook.EVENT_COMPLETED_REGISTRATION</li>
 *     <li>facebook.EVENT_COMPLETED_TUTORIAL</li>
 *     <li>facebook.EVENT_DEACTIVATED_APP</li>
 *     <li>facebook.EVENT_INITIATED_CHECKOUT</li>
 *     <li>facebook.EVENT_PURCHASED</li>
 *     <li>facebook.EVENT_RATED</li>
 *     <li>facebook.EVENT_SEARCHED</li>
 *     <li>facebook.EVENT_SESSION_INTERRUPTIONS</li>
 *     <li>facebook.EVENT_SPENT_CREDITS</li>
 *     <li>facebook.EVENT_TIME_BETWEEN_SESSIONS</li>
 *     <li>facebook.EVENT_UNLOCKED_ACHIEVEMENT</li>
 *     <li>facebook.EVENT_VIEWED_CONTENT</li>
 * </ul>
 *
 * @param value_to_sum (number) A numeric value for the event. This should
 * represent the value of the event, such as the level achieved, price for an
 * item or number of orcs killed.
 *
 * @param params (table) A table with parameters and their values. A key in the
 * table can either be one of the predefined constants below or a text which
 * can be used to define a custom parameter. Optional argument.
 * <ul>
 *     <li>facebook.PARAM_CONTENT_ID</li>
 *     <li>facebook.PARAM_CONTENT_TYPE</li>
 *     <li>facebook.PARAM_CURRENCY</li>
 *     <li>facebook.PARAM_DESCRIPTION</li>
 *     <li>facebook.PARAM_LEVEL</li>
 *     <li>facebook.PARAM_MAX_RATING_VALUE</li>
 *     <li>facebook.PARAM_NUM_ITEMS</li>
 *     <li>facebook.PARAM_PAYMENT_INFO_AVAILABLE</li>
 *     <li>facebook.PARAM_REGISTRATION_METHOD</li>
 *     <li>facebook.PARAM_SEARCH_STRING</li>
 *     <li>facebook.PARAM_SOURCE_APPLICATION</li>
 *     <li>facebook.PARAM_SUCCESS</li>
 * </ul>
 *
 * @examples
 * <pre>
 * params = {facebook.PARAM_LEVEL = 30, facebook.PARAM_NUM_ITEMS = 2};
 * facebook.post_event(facebook.EVENT_SPENT_CREDITS, 25, params);
 * </pre>
 */
int Facebook_PostEvent(lua_State* L)
{
    int argc = lua_gettop(L);
    const char* event = dmFacebook::Analytics::GetEvent(L, 1);
    double valueToSum = luaL_checknumber(L, 2);

    // Transform LUA table to a format that can be used by all platforms.
    const char* keys[dmFacebook::Analytics::MAX_PARAMS] = { 0 };
    const char* values[dmFacebook::Analytics::MAX_PARAMS] = { 0 };
    unsigned int length = 0;
    // TABLE is an optional argument and should only be parsed if provided.
    if (argc == 3)
    {
        length = dmFacebook::Analytics::MAX_PARAMS;
        dmFacebook::Analytics::GetParameterTable(L, 3, keys, values, &length);
    }

    // Prepare for Objective-C
    NSString* objcEvent = [NSString stringWithCString:event encoding:NSASCIIStringEncoding];
    NSMutableDictionary* params = [NSMutableDictionary dictionary];
    for (unsigned int i = 0; i < length; ++i)
    {
        NSString* objcKey = [NSString stringWithCString:keys[i] encoding:NSASCIIStringEncoding];
        NSString* objcValue = [NSString stringWithCString:values[i] encoding:NSASCIIStringEncoding];

        params[objcKey] = objcValue;
    }

    [FBSDKAppEvents logEvent:objcEvent valueToSum:valueToSum parameters:params];

    return 0;
}

/*# Enable event usage with Facebook Analytics
 *
 * This function will enable event usage for Facebook Analytics which means
 * that Facebook will be able to use event data for ad-tracking.
 *
 * <b>NOTE!</b> Event usage cannot be controlled and is always enabled for the
 * Facebook Canvas platform, therefore this function has no effect on Facebook
 * Canvas.
 *
 * @name facebook.enable_event_usage
 *
 */
int Facebook_EnableEventUsage(lua_State* L)
{
    [FBSDKSettings setLimitEventAndDataUsage:false];

    return 0;
}

/*# Disable event usage with Facebook Analytics
 *
 * This function will disable event usage for Facebook Analytics which means
 * that Facebook won't be able to use event data for ad-tracking. Events will
 * still be sent to Facebook for insights.
 *
 * <b>NOTE!</b> Event usage cannot be controlled and is always enabled for the
 * Facebook Canvas platform, therefore this function has no effect on Facebook
 * Canvas.
 *
 * @name facebook.disable_event_usage
 *
 */
int Facebook_DisableEventUsage(lua_State* L)
{
    [FBSDKSettings setLimitEventAndDataUsage:true];

    return 0;
}

/*# show facebook web dialog
 *
 * Display a Facebook web dialog of the type specified in the <code>dialog</code> parameter.
 * The <code>param</code> table should be set up according to the requirements of each dialog
 * type. Note that some parameters are mandatory. Below is the list of available dialogs and
 * where to find Facebook's developer documentation on parameters and response data.
 *
 * <code>apprequests</code>
 *
 * Shows a Game Request dialog. Game Requests allows players to invite their friends to play a
 * game. Available parameters:
 *
 * <ul>
 *   <li><code>title</code> (string)</li>
 *   <li><code>message</code> (string)</li>
 *   <li><code>action_type</code> (number)</li>
 *   <li><code>filters</code> (number)</li>
 *   <li><code>data</code> (string)</li>
 *   <li><code>object_id</code> (string)</li>
 *   <li><code>suggestions</code> (table)</li>
 *   <li><code>recipients</code> (table)</li>
 *   <li><code>to</code> (string)</li>
 * </ul>
 *
 * On success, the "result" table parameter passed to the callback function will include the following fields:
 * <ul>
 *   <li><code>request_id</code> (string)</li>
 *   <li><code>to</code> (table)</li>
 * </ul>
 *
 * Details for each parameter: <a href='https://developers.facebook.com/docs/games/services/gamerequests/v2.6#dialogparameters'>https://developers.facebook.com/docs/games/services/gamerequests/v2.6#dialogparameters</a>
 *
 * <code>feed</code>
 *
 * The Feed Dialog allows people to publish individual stories to their timeline.
 *
 * <ul>
 *   <li><code>caption</code> (string)</li>
 *   <li><code>description</code> (string)</li>
 *   <li><code>picture</code> (string)</li>
 *   <li><code>link</code> (string)</li>
 *   <li><code>people_ids</code> (table)</li>
 *   <li><code>place_id</code> (string)</li>
 *   <li><code>ref</code> (string)</li>
 * </ul>
 *
 * On success, the "result" table parameter passed to the callback function will include the following fields:
 * <ul>
 *   <li><code>post_id</code> (string)</li>
 * </ul>
 *
 * Details for each parameter: <a href='https://developers.facebook.com/docs/sharing/reference/feed-dialog/v2.6#params'>https://developers.facebook.com/docs/sharing/reference/feed-dialog/v2.6#params</a>
 *
 * <code>appinvite</code>
 *
 * The App Invite dialog is available only on iOS and Android. Note that the <code>url</code> parameter
 * corresponds to the appLinkURL (iOS) and setAppLinkUrl (Android) properties.
 *
 * <ul>
 *   <li><code>url</code> (string)</li>
 *   <li><code>preview_image</code> (string)</li>
 * </ul>
 *
 * Details for each parameter: <a href='https://developers.facebook.com/docs/reference/ios/current/class/FBSDKAppInviteContent/'>https://developers.facebook.com/docs/reference/ios/current/class/FBSDKAppInviteContent/</a>
 *
 * @name facebook.show_dialog
 * @param dialog dialog to show. "apprequests", "feed" or "appinvite" (string)
 * @param param table with dialog parameters (table)
 * @param callback callback function with parameters (self, result, error) that is called when the dialog is closed. Result is table with an url-field set. (function)
 */
int Facebook_ShowDialog(lua_State* L)
{
    if(!g_Facebook.m_Login)
    {
        return luaL_error(L, "Facebook module isn't initialized! Did you set the facebook.app_id in game.project?");
    }
    int top = lua_gettop(L);
    VerifyCallback(L);

    dmhash_t dialog = dmHashString64(luaL_checkstring(L, 1));
    luaL_checktype(L, 2, LUA_TTABLE);
    luaL_checktype(L, 3, LUA_TFUNCTION);
    lua_pushvalue(L, 3);
    g_Facebook.m_Callback = luaL_ref(L, LUA_REGISTRYINDEX);
    dmScript::GetInstance(L);
    g_Facebook.m_Self = luaL_ref(L, LUA_REGISTRYINDEX);
    g_Facebook.m_MainThread = dmScript::GetMainThread(L);

    if (dialog == dmHashString64("feed")) {

        FBSDKShareLinkContent* content = [[FBSDKShareLinkContent alloc] init];
        content.contentTitle       = GetTableValue(L, 2, @[@"caption", @"title"], LUA_TSTRING);
        content.contentDescription = GetTableValue(L, 2, @[@"description"], LUA_TSTRING);
        content.imageURL           = [NSURL URLWithString:GetTableValue(L, 2, @[@"picture"], LUA_TSTRING)];
        content.contentURL         = [NSURL URLWithString:GetTableValue(L, 2, @[@"link"], LUA_TSTRING)];
        content.peopleIDs          = GetTableValue(L, 2, @[@"people_ids"], LUA_TTABLE);
        content.placeID            = GetTableValue(L, 2, @[@"place_id"], LUA_TSTRING);
        content.ref                = GetTableValue(L, 2, @[@"ref"], LUA_TSTRING);

        [FBSDKShareDialog showFromViewController:nil withContent:content delegate:g_Facebook.m_Delegate];

    } else if (dialog == dmHashString64("appinvite")) {

        FBSDKAppInviteContent* content = [[FBSDKAppInviteContent alloc] init];
        content.appLinkURL               = [NSURL URLWithString:GetTableValue(L, 2, @[@"url"], LUA_TSTRING)];
        content.appInvitePreviewImageURL = [NSURL URLWithString:GetTableValue(L, 2, @[@"preview_image_url"], LUA_TSTRING)];

        [FBSDKAppInviteDialog showWithContent:content delegate:g_Facebook.m_Delegate];

    } else if (dialog == dmHashString64("apprequests") || dialog == dmHashString64("apprequest")) {

        FBSDKGameRequestContent* content = [[FBSDKGameRequestContent alloc] init];
        content.title      = GetTableValue(L, 2, @[@"title"], LUA_TSTRING);
        content.message    = GetTableValue(L, 2, @[@"message"], LUA_TSTRING);
        content.actionType = convertGameRequestAction([GetTableValue(L, 2, @[@"action_type"], LUA_TNUMBER) unsignedIntValue]);
        content.filters    = convertGameRequestFilters([GetTableValue(L, 2, @[@"filters"], LUA_TNUMBER) unsignedIntValue]);
        content.data       = GetTableValue(L, 2, @[@"data"], LUA_TSTRING);
        content.objectID   = GetTableValue(L, 2, @[@"object_id"], LUA_TSTRING);

        NSArray* suggestions = GetTableValue(L, 2, @[@"suggestions"], LUA_TTABLE);
        if (suggestions != nil) {
            content.recipientSuggestions = suggestions;
        }

        // comply with JS way of specifying recipients/to
        NSString* to = GetTableValue(L, 2, @[@"to"], LUA_TSTRING);
        if (to != nil && [to respondsToSelector:@selector(componentsSeparatedByString:)]) {
            content.recipients = [to componentsSeparatedByString:@","];
        }
        NSArray* recipients = GetTableValue(L, 2, @[@"recipients"], LUA_TTABLE);
        if (recipients != nil) {
            content.recipients = recipients;
        }

        [FBSDKGameRequestDialog showWithContent:content delegate:g_Facebook.m_Delegate];

    } else {
        NSMutableDictionary *errorDetail = [NSMutableDictionary dictionary];
        [errorDetail setValue:@"Invalid dialog type" forKey:NSLocalizedDescriptionKey];
        NSError* error = [NSError errorWithDomain:@"facebook" code:0 userInfo:errorDetail];

        lua_State* main_thread = dmScript::GetMainThread(L);
        RunDialogResultCallback(main_thread, 0, error);
    }

    assert(top == lua_gettop(L));
    return 0;
}

} // namespace


/*# The Facebook login session is open
 *
 * @name facebook.STATE_OPEN
 * @variable
 */

/*# The Facebook login session has closed because login failed
 *
 * @name facebook.STATE_CLOSED_LOGIN_FAILED
 * @variable
 */

/*# Game Request action type "none" for "apprequests" dialog
 *
 * @name facebook.GAMEREQUEST_ACTIONTYPE_NONE
 * @variable
 */

/*# Game Request action type "send" for "apprequests" dialog
 *
 * @name facebook.GAMEREQUEST_ACTIONTYPE_SEND
 * @variable
 */

/*# Game Request action type "askfor" for "apprequests" dialog
 *
 * @name facebook.GAMEREQUEST_ACTIONTYPE_ASKFOR
 * @variable
 */

/*# Game Request action type "turn" for "apprequests" dialog
 *
 * @name facebook.GAMEREQUEST_ACTIONTYPE_TURN
 * @variable
 */

/*# Gamerequest filter type "none" for "apprequests" dialog
 *
 * @name facebook.GAMEREQUEST_FILTER_NONE
 * @variable
 */

/*# Gamerequest filter type "app_users" for "apprequests" dialog
 *
 * @name facebook.GAMEREQUEST_FILTER_APPUSERS
 * @variable
 */

/*# Gamerequest filter type "app_non_users" for "apprequests" dialog
 *
 * @name facebook.GAMEREQUEST_FILTER_APPNONUSERS
 * @variable
 */

/*# Publish permission to reach no audience.
 *
 * @name facebook.AUDIENCE_NONE
 * @variable
 */

/*# Publish permission to reach only me (private to current user).
 *
 * @name facebook.AUDIENCE_ONLYME
 * @variable
 */

/*# Publish permission to reach user friends.
 *
 * @name facebook.AUDIENCE_FRIENDS
 * @variable
 */

/*# Publish permission to reach everyone.
 *
 * @name facebook.AUDIENCE_EVERYONE
 * @variable
 */

 /*# Log this event when the user has entered their payment info.
  *
  * @name facebook.EVENT_ADDED_PAYMENT_INFO
  * @variable
  */

 /*# Log this event when the user has added an item to their cart. The
  *  value_to_sum passed to facebook.post_event should be the item's price.
  *
  * @name facebook.EVENT_ADDED_TO_CART
  * @variable
  */

 /*# Log this event when the user has added an item to their wishlist. The
  *  value_to_sum passed to facebook.post_event should be the item's price.
  *
  * @name facebook.EVENT_ADDED_TO_WISHLIST
  * @variable
  */

 /*# Log this event when a user has completed registration with the app.
  *
  * @name facebook.EVENT_COMPLETED_REGISTRATION
  * @variable
  */

 /*# Log this event when the user has completed a tutorial in the app.
  *
  * @name facebook.EVENT_COMPLETED_TUTORIAL
  * @variable
  */

 /*# Log this event when the user has entered the checkout process. The
  *  value_to_sum passed to facebook.post_event should be the total price in
  * the cart.
  *
  * @name facebook.EVENT_INITIATED_CHECKOUT
  * @variable
  */

 /*# Log this event when the user has completed a purchase.
  *
  * @name facebook.EVENT_PURCHASED
  * @variable
  */

 /*# Log this event when the user has rated an item in the app. The
  *  value_to_sum  passed to facebook.post_event should be the numeric rating.
  *
  * @name facebook.EVENT_RATED
  * @variable
  */

 /*# Log this event when a user has performed a search within the app.
  *
  * @name facebook.EVENT_SEARCHED
  * @variable
  */

 /*# Log this event when the user has spent app credits. The value_to_sum
  *  passed to facebook.post_event should be the number of credits spent.
  *
  * <p>
  * <b>NOTE!</b> This event is currently an undocumented event in the Facebook
  * SDK.
  * </p>
  *
  * @name facebook.EVENT_SPENT_CREDITS
  * @variable
  */

 /*# Log this event when measuring the time between user sessions.
  *
  * @name facebook.EVENT_TIME_BETWEEN_SESSIONS
  * @variable
  */

 /*# Log this event when the user has unlocked an achievement in the app.
  *
  * @name facebook.EVENT_UNLOCKED_ACHIEVEMENT
  * @variable
  */

 /*# Log this event when a user has viewed a form of content in the app.
  *
  * @name facebook.EVENT_VIEWED_CONTENT
  * @variable
  */

 /*# Parameter key used to specify an ID for the specific piece of content
  *  being logged about. Could be an EAN, article identifier, etc., depending
  *  on the nature of the app.
  *
  * @name facebook.PARAM_CONTENT_ID
  * @variable
  */

 /*# Parameter key used to specify a generic content type/family for the logged
  *  event, e.g. "music", "photo", "video". Options to use will vary based upon
  *  what the app is all about.
  *
  * @name facebook.PARAM_CONTENT_TYPE
  * @variable
  */

 /*# Parameter key used to specify currency used with logged event. E.g. "USD",
  *  "EUR", "GBP". See ISO-4217 for specific values.
  *
  * @name facebook.PARAM_CURRENCY
  * @variable
  */

 /*# Parameter key used to specify a description appropriate to the event being
  *  logged. E.g., the name of the achievement unlocked in the
  *  facebook.EVENT_UNLOCKED_ACHIEVEMENT event.
  *
  * @name facebook.PARAM_DESCRIPTION
  * @variable
  */

 /*# Parameter key used to specify the level achieved.
  *
  * @name facebook.PARAM_LEVEL
  * @variable
  */

 /*# Parameter key used to specify the maximum rating available for the
  *  facebook.EVENT_RATED event. E.g., "5" or "10".
  *
  * @name facebook.PARAM_MAX_RATING_VALUE
  * @variable
  */

 /*# Parameter key used to specify how many items are being processed for an
  *  facebook.EVENT_INITIATED_CHECKOUT or facebook.EVENT_PURCHASED event.
  *
  * @name facebook.PARAM_NUM_ITEMS
  * @variable
  */

 /*# Parameter key used to specify whether payment info is available for the
  *  facebook.EVENT_INITIATED_CHECKOUT event.
  *
  * @name facebook.PARAM_PAYMENT_INFO_AVAILABLE
  * @variable
  */

 /*# Parameter key used to specify method user has used to register for the
  *  app, e.g., "Facebook", "email", "Twitter", etc.
  *
  * @name facebook.PARAM_REGISTRATION_METHOD
  * @variable
  */

 /*# Parameter key used to specify the string provided by the user for a search
  *  operation.
  *
  * @name facebook.PARAM_SEARCH_STRING
  * @variable
  */

 /*# Parameter key used to specify source application package.
  *
  * @name facebook.PARAM_SOURCE_APPLICATION
  * @variable
  */

 /*# Parameter key used to specify whether the activity being logged about was
  *  successful or not.
  *
  * @name facebook.PARAM_SUCCESS
  * @variable
  */


static dmExtension::Result AppInitializeFacebook(dmExtension::AppParams* params)
{
    const char* app_id = dmConfigFile::GetString(params->m_ConfigFile, "facebook.appid", 0);
    if( !app_id )
    {
        dmLogDebug("No facebook.appid. Disabling module");
        return dmExtension::RESULT_OK;
    }
    g_Facebook.m_Delegate = [[FacebookAppDelegate alloc] init];
    dmExtension::RegisterUIApplicationDelegate(g_Facebook.m_Delegate);

    g_Facebook.m_DisableFaceBookEvents = dmConfigFile::GetInt(params->m_ConfigFile, "facebook.disable_events", 0);

    [FBSDKSettings setAppID: [NSString stringWithUTF8String: app_id]];

    g_Facebook.m_Login = [[FBSDKLoginManager alloc] init];

    return dmExtension::RESULT_OK;
}

static dmExtension::Result AppFinalizeFacebook(dmExtension::AppParams* params)
{
    if(!g_Facebook.m_Login)
    {
        return dmExtension::RESULT_OK;
    }

    dmExtension::UnregisterUIApplicationDelegate(g_Facebook.m_Delegate);
    [g_Facebook.m_Login release];
    return dmExtension::RESULT_OK;
}

static dmExtension::Result InitializeFacebook(dmExtension::Params* params)
{
    dmFacebook::LuaInit(params->m_L);
    return dmExtension::RESULT_OK;
}

static dmExtension::Result FinalizeFacebook(dmExtension::Params* params)
{
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(FacebookExt, "Facebook", AppInitializeFacebook, AppFinalizeFacebook, InitializeFacebook, 0, 0, FinalizeFacebook)
