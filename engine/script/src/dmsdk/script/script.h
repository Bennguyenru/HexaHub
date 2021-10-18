// Copyright 2020 The Defold Foundation
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#ifndef DMSDK_SCRIPT_SCRIPT_H
#define DMSDK_SCRIPT_SCRIPT_H

#include <stdint.h>
#include <stdarg.h>

#include <dmsdk/dlib/hash.h>
#include <dmsdk/dlib/message.h>

extern "C"
{
#include <dmsdk/lua/lua.h>
#include <dmsdk/lua/lauxlib.h>
}

namespace Vectormath {
    namespace Aos {
        class Vector3;
        class Vector4;
        class Quat;
        class Matrix4;
    }
}
namespace dmJson {
    struct Document;
}

namespace dmScript
{
    /*# SDK Script API documentation
     *
     * Built-in scripting functions.
     *
     * @document
     * @name Script
     * @namespace dmScript
     * @path engine/dlib/src/dmsdk/script/script.h
     */

    /*#
     * The script context
     * @typedef
     * @name HContext
     */
    typedef struct Context* HContext;

    /**
    * LuaStackCheck struct. Internal
    *
    * LuaStackCheck utility to make sure we check the Lua stack state before leaving a function.
    * m_Diff is the expected difference of the stack size.
    *
    */
    struct LuaStackCheck
    {
        LuaStackCheck(lua_State* L, int diff, const char* filename, int linenumber);
        ~LuaStackCheck();
        void Verify(int diff);
        #if defined(__GNUC__)
        int Error(const char* fmt, ...) __attribute__ ((format (printf, 2, 3)));;
        #else
        int Error(const char* fmt, ...);
        #endif

        /// The Lua state to check
        lua_State* m_L;

        /// Debug info in case of an assert
        const char* m_Filename;
        int m_Linenumber;

        /// The current top of the Lua stack (from lua_gettop())
        int m_Top;
        /// The expected difference in stack size when this sctruct goes out of scope
        int m_Diff;
    };


    /*# helper macro to validate the Lua stack state before leaving a function.
     *
     * Diff is the expected difference of the stack size.
     * If luaL_error, or another function that executes a long-jump, is part of the executed code,
     * the stack guard cannot be guaranteed to execute at the end of the function.
     * In that case you should manually check the stack using `lua_gettop`.
     * In the case of luaL_error, see [ref:DM_LUA_ERROR].
     *
     * @macro
     * @name DM_LUA_STACK_CHECK
     * @param L [type:lua_State*] lua state
     * @param diff [type:int] Number of expected items to be on the Lua stack once this struct goes out of scope
     * @examples
     *
     * ```cpp
     * DM_LUA_STACK_CHECK(L, 1);
     * lua_pushnumber(L, 42);
     * ```
     */
    #define DM_LUA_STACK_CHECK(_L_, _diff_)     dmScript::LuaStackCheck _DM_LuaStackCheck(_L_, _diff_, __FILE__, __LINE__);


    /*# helper macro to validate the Lua stack state and throw a lua error.
     *
     * This macro will verify that the Lua stack size hasn't been changed before
     * throwing a Lua error, which will long-jump out of the current function.
     * This macro can only be used together with [ref:DM_LUA_STACK_CHECK] and should
     * be prefered over manual checking of the stack.
     *
     * @macro
     * @name DM_LUA_ERROR
     * @param fmt [type:const char*] Format string that contains error information.
     * @param args [type:...] Format string args (variable arg list)
     * @examples
     *
     * ```cpp
     * static int ModuleFunc(lua_State* L)
     * {
     *     DM_LUA_STACK_CHECK(L, 1);
     *     if (some_error_check(L))
     *     {
     *         return DM_LUA_ERROR("some error message");
     *     }
     *     lua_pushnumber(L, 42);
     *     return 1;
     * }
     * ```
     */
    #define DM_LUA_ERROR(_fmt_, ...)   _DM_LuaStackCheck.Error(_fmt_,  ##__VA_ARGS__); \


    /*# wrapper for luaL_ref.
     *
     * Creates and returns a reference, in the table at index t, for the object at the
     * top of the stack (and pops the object).
     * It also tracks number of global references kept.
     *
     * @name dmScript::Ref
     * @param L [type:lua_State*] lua state
     * @param table [type:int] table the lua table that stores the references. E.g LUA_REGISTRYINDEX
     * @return reference [type:int] the new reference
     */
    int Ref(lua_State* L, int table);

    /*# wrapper for luaL_unref.
     *
     * Releases reference ref from the table at index t (see luaL_ref).
     * The entry is removed from the table, so that the referred object can be collected.
     * It also decreases the number of global references kept
     *
     * @name dmScript::Unref
     * @param L [type:lua_State*] lua state
     * @param table [type:int] table the lua table that stores the references. E.g LUA_REGISTRYINDEX
     * @param reference [type:int] the reference to the object
     */
    void Unref(lua_State* L, int table, int reference);

    /*#
     * Retrieve current script instance from the global table and place it on the top of the stack, only valid when set.
     * (see [ref:dmScript::GetMainThread])
     * @name dmScript::GetInstance
     * @param L [type:lua_State*] lua state
     */
    void GetInstance(lua_State* L);

    /*#
     * Sets the current script instance
     * Set the value on the top of the stack as the instance into the global table and pops it from the stack.
     * (see [ref:dmScript::GetMainThread])
     * @name dmScript::SetInstance
     * @param L [type:lua_State*] lua state
     */
    void SetInstance(lua_State* L);

    /*#
     * Check if the script instance in the lua state is valid. The instance is assumed to have been previously set by [ref:dmScript::SetInstance].
     * @name dmScript::IsInstanceValid
     * @param L [type:lua_State*] lua state
     * @return boolean [type:bool] Returns true if the instance is valid
     */
    bool IsInstanceValid(lua_State* L);

    /*#
     * Retrieve the main thread lua state from any lua state (main thread or coroutine).
     * @name dmScript::GetMainThread
     * @param L [type:lua_State*] lua state
     * @return lua_State [type:lua_State*] the main thread lua state
     *
     * @examples
     *
     * How to create a Lua callback
     *
     * ```cpp
     * dmScript::LuaCallbackInfo* g_MyCallbackInfo = 0;
     *
     * static void InvokeCallback(dmScript::LuaCallbackInfo* cbk)
     * {
     *     if (!dmScript::IsCallbackValid(cbk))
     *         return;
     *
     *     lua_State* L = dmScript::GetCallbackLuaContext(cbk);
     *     DM_LUA_STACK_CHECK(L, 0)
     *
     *     if (!dmScript::SetupCallback(cbk))
     *     {
     *         dmLogError("Failed to setup callback");
     *         return;
     *     }
     *
     *     lua_pushstring(L, "Hello from extension!");
     *     lua_pushnumber(L, 76);
     *
     *     dmScript::PCall(L, 3, 0); // instance + 2
     *
     *     dmScript::TeardownCallback(cbk);
     * }
     *
     * static int Start(lua_State* L)
     * {
     *     DM_LUA_STACK_CHECK(L, 0);
     *
     *     g_MyCallbackInfo = dmScript::CreateCallback(L, 1);
     *
     *     return 0;
     * }
     *
     * static int Update(lua_State* L)
     * {
     *     DM_LUA_STACK_CHECK(L, 0);
     *
     *     static int count = 0;
     *     if( count++ == 5 )
     *     {
     *         InvokeCallback(g_MyCallbackInfo);
     *         if (g_MyCallbackInfo)
     *             dmScript::DestroyCallback(g_MyCallbackInfo);
     *         g_MyCallbackInfo = 0;
     *     }
     *     return 0;
     * }
     * ```
     */
    lua_State* GetMainThread(lua_State* L);

    /*# get the value at index as a Vectormath::Aos::Vector3*
     * Get the value at index as a Vectormath::Aos::Vector3*
     * @name dmScript::ToVector3
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return v [type:Vectormath::Aos::Vector3*] The pointer to the value, or 0 if not correct type
     */
    Vectormath::Aos::Vector3* ToVector3(lua_State* L, int index);

    /*#
     * Check if the value at #index is a Vectormath::Aos::Vector3*
     * @name dmScript::IsVector3
     * @param L Lua state
     * @param index Index of the value
     * @return true if value at #index is a Vectormath::Aos::Vector3*
     */
    bool IsVector3(lua_State* L, int index);

    /*# push a Vectormath::Aos::Vector3 onto the Lua stack
     *
     * Push a Vectormath::Aos::Vector3 value onto the supplied lua state, will increase the stack by 1.
     * @name dmScript::PushVector3
     * @param L [type:lua_State*] Lua state
     * @param v [type:Vectormath::Aos::Vector3] Vector3 value to push
     */
    void PushVector3(lua_State* L, const Vectormath::Aos::Vector3& v);

    /*# check if the value is a Vectormath::Aos::Vector3
     *
     * Check if the value in the supplied index on the lua stack is a Vectormath::Aos::Vector3.
     * @note throws a luaL_error if it's not the correct type
     * @name dmScript::CheckVector3
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return vector3 [type:Vectormath::Aos::Vector3*] The pointer to the value
     */
    Vectormath::Aos::Vector3* CheckVector3(lua_State* L, int index);

    /*# get the value at index as a Vectormath::Aos::Vector4*
     * Get the value at index as a Vectormath::Aos::Vector4*
     * @name dmScript::ToVector4
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return v [type:Vectormath::Aos::Vector4*] The pointer to the value, or 0 if not correct type
     */
    Vectormath::Aos::Vector4* ToVector4(lua_State* L, int index);

    /*#
     * Check if the value at #index is a Vectormath::Aos::Vector4*
     * @name dmScript::IsVector4
     * @param L Lua state
     * @param index Index of the value
     * @return true if value at #index is a Vectormath::Aos::Vector4*
     */
    bool IsVector4(lua_State* L, int index);

    /*# push a Vectormath::Aos::Vector4 on the stack
     * Push a Vectormath::Aos::Vector4 value onto the supplied lua state, will increase the stack by 1.
     * @name dmScript::PushVector4
     * @param L [type:lua_State*] Lua state
     * @param v [type:Vectormath::Aos::Vector4] Vectormath::Aos::Vector4 value to push
     */
    void PushVector4(lua_State* L, const Vectormath::Aos::Vector4& v);

    /*# check if the value is a Vectormath::Aos::Vector3
     *
     * Check if the value in the supplied index on the lua stack is a Vectormath::Aos::Vector3.
     * @note throws a luaL_error if it's not the correct type
     * @name dmScript::CheckVector4
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return vector4 [type:Vectormath::Aos::Vector4*] The pointer to the value
     */
    Vectormath::Aos::Vector4* CheckVector4(lua_State* L, int index);

    /*# get the value at index as a Vectormath::Aos::Quat*
     * Get the value at index as a Vectormath::Aos::Quat*
     * @name dmScript::ToQuat
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return quat [type:Vectormath::Aos::Quat*] The pointer to the value, or 0 if not correct type
     */
    Vectormath::Aos::Quat* ToQuat(lua_State* L, int index);

    /*#
     * Check if the value at #index is a Vectormath::Aos::Quat*
     * @name dmScript::IsQuat
     * @param L Lua state
     * @param index Index of the value
     * @return true if value at #index is a Vectormath::Aos::Quat*
     */
    bool IsQuat(lua_State* L, int index);

    /*# push a Vectormath::Aos::Quat onto the Lua stack
     * Push a quaternion value onto Lua stack. Will increase the stack by 1.
     * @name dmScript::PushQuat
     * @param L [type:lua_State*] Lua state
     * @param quat [type:Vectormath::Aos::Quat] Vectormath::Aos::Quat value to push
     */
    void PushQuat(lua_State* L, const Vectormath::Aos::Quat& q);

    /*# check if the value is a Vectormath::Aos::Vector3
     *
     * Check if the value in the supplied index on the lua stack is a Vectormath::Aos::Quat.
     * @note throws a luaL_error if it's not the correct type
     * @name dmScript::CheckQuat
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return quat [type:Vectormath::Aos::Quat*] The pointer to the value
     */
    Vectormath::Aos::Quat* CheckQuat(lua_State* L, int index);

    /*# get the value at index as a Vectormath::Aos::Matrix4*
     * Get the value at index as a Vectormath::Aos::Matrix4*
     * @name dmScript::ToMatrix4
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return quat [type:Vectormath::Aos::Matrix4*] The pointer to the value, or 0 if not correct type
     */
    Vectormath::Aos::Matrix4* ToMatrix4(lua_State* L, int index);

    /*#
     * Check if the value at #index is a Vectormath::Aos::Matrix4*
     * @name dmScript::IsMatrix4
     * @param L Lua state
     * @param index Index of the value
     * @return true if value at #index is a Vectormath::Aos::Matrix4*
     */
    bool IsMatrix4(lua_State* L, int index);

    /*# push a Vectormath::Aos::Matrix4 onto the Lua stack
     * Push a matrix4 value onto the Lua stack. Will increase the stack by 1.
     * @name dmScript::PushMatrix4
     * @param L [type:lua_State*] Lua state
     * @param matrix [type:Vectormath::Aos::Matrix4] Vectormath::Aos::Matrix4 value to push
     */
    void PushMatrix4(lua_State* L, const Vectormath::Aos::Matrix4& m);

    /*# check if the value is a Vectormath::Aos::Matrix4
     *
     * Check if the value in the supplied index on the lua stack is a Vectormath::Aos::Matrix4.
     *
     * @note throws a luaL_error if it's not the correct type
     * @name dmScript::CheckMatrix4
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return matrix [type:Vectormath::Aos::Matrix4*] The pointer to the value
     */
    Vectormath::Aos::Matrix4* CheckMatrix4(lua_State* L, int index);

    /*#
     * Check if the value at #index is a hash
     * @name dmScript::IsHash
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return true if the value at #index is a hash
     */
    bool IsHash(lua_State *L, int index);

    /*#
     * Push a hash value onto the supplied lua state, will increase the stack by 1.
     * @name dmScript::PushHash
     * @param L [type:lua_State*] Lua state
     * @param hash [tyoe: dmhash_t] Hash value to push
     */
    void PushHash(lua_State* L, dmhash_t hash);

    /*# get hash value
     * Check if the value in the supplied index on the lua stack is a hash.
     * @name dmScript::CheckHash
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return The hash value
     */
    dmhash_t CheckHash(lua_State* L, int index);

    /*# get hash from hash or string
     * Check if the value in the supplied index on the lua stack is a hash or string.
     * If it is a string, it gets hashed on the fly
     * @name dmScript::CheckHashOrString
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @return The hash value
     */
    dmhash_t CheckHashOrString(lua_State* L, int index);

    /*#
     * Gets as good as possible printable string from a hash or string
     * @name GetStringFromHashOrString
     * @param L [type:lua_State*] Lua state
     * @param index [type:int] Index of the value
     * @param buffer [type: char*] buffer receiving the value
     * @param buffer_length [type: uint32_t] the buffer length
     * @return string [type: const char*] Returns buffer. If buffer is non null, it will always contain a null terminated string. "<unknown>" if the hash could not be looked up.
    */
    const char* GetStringFromHashOrString(lua_State* L, int index, char* buffer, uint32_t bufferlength);

    /*# convert a dmJson::Document to a Lua table
     * Convert a dmJson::Document document to Lua table.
     *
     * @name dmJson::Type
     * @param L [type:lua_State*] lua state
     * @param doc [type:dmJson::Document] JSON document
     * @param index [type:int] index of JSON node
     * @param error_str_out [type:char*] if an error is encountered, the error string is written to this argument
     * @param error_str_size [type:size_t] size of error_str_out
     * @return int [type:int] <0 if it fails. >=0 if it succeeds.
     */
    int JsonToLua(lua_State* L, dmJson::Document* doc, int index, char* error_str_out, size_t error_str_size);


    /*# callback info struct
     * callback info struct that will hold the relevant info needed to make a callback into Lua
     * @struct
     * @name dmScript::LuaCallbackInfo
     */
    struct LuaCallbackInfo;

    /*# Register a Lua callback.
     * Stores the current Lua state plus references to the script instance (self) and the callback.
     * Expects SetInstance() to have been called prior to using this method.
     *
     * The allocated data is created on the Lua stack and references are made against the
     * instances own context table.
     *
     * If the callback is not explicitly deleted with DestroyCallback() the references and
     * data will stay around until the script instance is deleted.
     *
     * @name dmScript::CreateCallback
     * @param L Lua state
     * @param index Lua stack index of the function
     * @return Lua callback struct if successful, 0 otherwise
     *
     * @examples
     *
     * ```cpp
     * static int SomeFunction(lua_State* L) // called from Lua
     * {
     *     LuaCallbackInfo* cbk = dmScript::CreateCallback(L, 1);
     *     ... store the callback for later
     * }
     *
     * static void InvokeCallback(LuaCallbackInfo* cbk)
     * {
     *     lua_State* L = dmScript::GetCallbackLuaContext(cbk);
     *     DM_LUA_STACK_CHECK(L, 0);
     *
     *     if (!dmScript::SetupCallback(callback))
     *     {
     *         return;
     *     }
     *
     *     lua_pushstring(L, "hello");
     *
     *     dmScript::PCall(L, 2, 0); // self + # user arguments
     *
     *     dmScript::TeardownCallback(callback);
     *     dmScript::DestroyCallback(cbk); // only do this if you're not using the callback again
     * }
     * ```
     */
    LuaCallbackInfo* CreateCallback(lua_State* L, int index);

    /*# Check if Lua callback is valid.
     * @name dmScript::IsCallbackValid
     * @param cbk Lua callback struct
     */
    bool IsCallbackValid(LuaCallbackInfo* cbk);

    /*# Deletes the Lua callback
     * @name dmScript::DestroyCallback
     * @param cbk Lua callback struct
     */
    void DestroyCallback(LuaCallbackInfo* cbk);

    /*# Gets the Lua context from a callback struct
     * @name dmScript::GetCallbackLuaContext
     * @param cbk Lua callback struct
     * @return L Lua state
     */
    lua_State* GetCallbackLuaContext(LuaCallbackInfo* cbk);


    /*# Setups up the Lua callback prior to a call to dmScript::PCall()
     *  The Lua stack after a successful call:
     * ```
     *    [-4] old instance
     *    [-3] context table
     *    [-2] callback
     *    [-1] self
     * ```
     *  In the event of an unsuccessful call, the Lua stack is unchanged
     *
     * @name dmScript::SetupCallback
     * @param cbk Lua callback struct
     * @return true if the setup was successful
     */
    bool SetupCallback(LuaCallbackInfo* cbk);

    /*# Cleans up the stack after SetupCallback+PCall calls
     * Sets the previous instance
     * Expects Lua stack:
     * ```
     *    [-2] old instance
     *    [-1] context table
     * ```
     * Both values are removed from the stack
     *
     * @name dmScript::TeardownCallback
     * @param cbk Lua callback struct
     */
    void TeardownCallback(LuaCallbackInfo* cbk);

    /*#
     * This function wraps lua_pcall with the addition of specifying an error handler which produces a backtrace.
     * In the case of an error, the error is logged and popped from the stack.
     *
     * @name dmScript::PCall
     * @param L lua state
     * @param nargs number of arguments
     * @param nresult number of results
     * @return error code from pcall
     */
    int PCall(lua_State* L, int nargs, int nresult);


    /*#
     * Creates a reference to the value at top of stack, the ref is done in the
     * current instances context table.
     *
     * Expects SetInstance() to have been set with an value that has a meta table
     * with META_GET_INSTANCE_CONTEXT_TABLE_REF method.
     *
     * @name RefInInstance
     * @param L Lua state
     * @return lua ref to value or LUA_NOREF
     *
     * Lua stack on entry
     *  [-1] value
     *
     * Lua stack on exit
    */
    int RefInInstance(lua_State* L);

    /*#
     * Resolves the value in the supplied index on the lua stack to a URL. It long jumps (calls luaL_error) on failure.
     * It also gets the current (caller) url if the a pointer is passed to `out_default_url`
     * @param L [type:lua_State*] Lua state
     * @param out_url [type:dmMessage::URL*] where to store the result
     * @param out_default_url [type:dmMessage::URL*] default URL used in the resolve, can be 0x0 (not used)
     * @return result [type:int] 0 if successful. Throws Lua error on failure
     */
    int ResolveURL(lua_State* L, int index, dmMessage::URL* out_url, dmMessage::URL* out_default_url);

    /*#
     * Resolves a url in string format into a dmMessage::URL struct.
     *
     * Special handling for:
     * - "." returns the default socket + path
     * - "#" returns default socket + path + fragment
     *
     * @name RefInInstance
     * @param L [type:lua_State*] Lua state
     * @param url [type:const char*] url
     * @param out_url [type:dmMessage::URL*] where to store the result
     * @param default_url [type:dmMessage::URL*] default url
     * @return result [type:dmMessage::Result] dmMessage::RESULT_OK if the conversion succeeded
    */
    dmMessage::Result ResolveURL(lua_State* L, const char* url, dmMessage::URL* out_url, dmMessage::URL* default_url);
}

#endif // DMSDK_SCRIPT_SCRIPT_H
