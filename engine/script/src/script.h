#ifndef DM_SCRIPT_H
#define DM_SCRIPT_H

#include <stdint.h>
#include <dmsdk/script/script.h>

#include <dlib/buffer.h>
#include <dlib/vmath.h>
#include <dlib/hash.h>
#include <dlib/message.h>
#include <dlib/configfile.h>
#include <dlib/json.h>
#include <dlib/log.h>
#include <resource/resource.h>
#include <ddf/ddf.h>

extern "C"
{
#include <lua/lua.h>
#include <lua/lauxlib.h>
}


namespace dmLuaDDF
{
    struct LuaSource;
}

namespace dmScript
{
    typedef struct Context* HContext;
    typedef struct ScriptWorld* HScriptWorld;
    typedef struct ScriptExtension* HScriptExtension;

    enum Result
    {
        RESULT_OK = 0,
        RESULT_LUA_ERROR = -1,
        RESULT_ARGVAL = -2,
        RESULT_MODULE_NOT_LOADED = -3,
    };

    extern const char* META_TABLE_RESOLVE_PATH;
    extern const char* META_TABLE_GET_URL;
    extern const char* META_TABLE_GET_USER_DATA;
    extern const char* META_TABLE_IS_VALID;

    /**
     * Implementor should return a Ref to the instance context table.
     *
     * CAUTION! The implementation should *NOT* create a new ref, it
     * should return an existing one. If it does not have one, it should
     * return LUA_NOREF
     *
     * Lua stack on entry
     *  [-1] instance
     *
     * Lua stack on exit
     *  [-1] ref to instance context table or LUA_NOREF
     *
     */
    extern const char* META_GET_INSTANCE_CONTEXT_TABLE_REF;

    /**
     * Create and return a new context.
     * @param config_file optional config file handle
     * @param factory resource factory
     * @param enable_extensions true if extensions should be initialized for this context
     * @return context
     */
    HContext NewContext(dmConfigFile::HConfig config_file, dmResource::HFactory factory, bool enable_extensions);

    /**
     * Delete an existing context.
     */
    void DeleteContext(HContext context);

    /**
     * Use a ScriptExtension to hook into various callbacks of the script lifetime
     *
     * For callbacks you do not care for, set them to 0x0
     */
    struct ScriptExtension
    {
        // Called when the context has completed Initialize(HContext context)
        void (*Initialize)(HContext context);
        // Called one each game frame
        void (*Update)(HContext context);
        // Called just before the context completes Finalize(HContext context)
        void (*Finalize)(HContext context);
        // Called when a new "world" has been created (a Collection, GUI Scene etc)
        void (*NewScriptWorld)(HScriptWorld script_world);
        // Called just before deleting the script world
        void (*DeleteScriptWorld)(HScriptWorld script_world);
        // Called once a frame for the world, dt is local time delta (affected by slo-mo etc)
        void (*UpdateScriptWorld)(HScriptWorld script_world, float dt);
        // Called when a script instance has been created
        void (*InitializeScriptInstance)(HScriptWorld script_world);
        // Called just before a script instance is deleted
        void (*FinalizeScriptInstance)(HScriptWorld script_world);
    };

    /**
     * Callback used to resolve paths.
     * Implementations of this callback are expected to resolve the path given the user data.
     * @param resolve_user_data user data passed to the callback
     * @param path
     * @param path_size
     * @return hashed resolved path
     */
    typedef dmhash_t (*ResolvePathCallback)(uintptr_t resolve_user_data, const char* path, uint32_t path_size);

    /**
     * Callback used to retrieve url
     * Implementations of this callback are expected to fill out the url appropriately given the lua state.
     * @param L lua state
     * @param out_url Pointer to the url that should be filled with information
     */
    typedef void (*GetURLCallback)(lua_State* L, dmMessage::URL* out_url);

    /**
     * Callback used to validate the state instance, assumed to be fetchable by GetInstance.
     * This callback is mandatory, it must be supplied in order for the IsInstanceValid function to be callable.
     * @param L lua state
     * @return whether the instance is valid or not
     */
    typedef bool (*ValidateInstanceCallback)(lua_State* L);

    /**
     * Callback used to retrieve message user data
     * Implementations of this callback are expected to return appropriate user data given the lua state.
     * @param L lua state
     * @return User data pointer
     */
    typedef uintptr_t (*GetUserDataCallback)(lua_State* L);

    /**
     * DDF to Lua decoder. Useful for custom interpretation of field, e.g. pointers.
     * By convention the decoder can also be responsible to free allocated memory
     * refererred in the message
     * @param L Lua state
     * @param desc DDF descriptor
     * @param data Data to decode
     * @return RESULT_OK on success
     */
    typedef Result (*MessageDecoder)(lua_State* L, const dmDDF::Descriptor* desc, const char* data);

    /**
     * Register the script libraries into the supplied script context.
     * @param context script context
     */
    void Initialize(HContext context);

    /**
     * Register a script extension to the context.
     * The script_extensions lifetime should wrap the lifetime of the context,
     * usually you would supply a pointer to a static instance of the script_extension
     *
     * @param context script context
     * @param script_extension the implementation of the HScriptExtension
     */
    void RegisterScriptExtension(HContext context, HScriptExtension script_extension);

    /**
     * Updates the script extensions initalized in this script context.
     * @param context script contetx
     */
    void Update(HContext context);

    /**
     * Finalize script libraries
     * @param context script context
     */
    void Finalize(HContext context);

    /**
     * Set value by key using in the context table of active HContext
     *
     * Expects Initialize(context) to have been called
     *
     * @param L Lua state
     *
     * Lua stack on entry
     *  [-2] key
     *  [-1] value
     *
     * Lua stack on exit
    */
    void SetContextValue(HContext context);

    /**
     * Get value by key using from context table of active HContext
     *
     * Expects Initialize(context) to have been called
     *
     * @param L Lua state
     *
     * Lua stack on entry
     *  [-1] key
     *
     * Lua stack on exit
     *  [-1] value or LUA_NIL
    */
    void GetContextValue(HContext context);

    /**
     * Retrieve a ddf structure from a lua state.
     * @param L Lua state
     * @param descriptor DDF descriptor
     * @param buffer Buffer that will be written to
     * @param buffer_size Buffer size
     * @param index Index of the table
     * @return Number of bytes used in the buffer
     */
    uint32_t CheckDDF(lua_State* L, const dmDDF::Descriptor* descriptor, char* buffer, uint32_t buffer_size, int index);

    /**
     * Push DDF message to Lua stack
     * @param L Lua state
     * @param descriptor Field descriptor
     * @param data DDF data
     */
    void PushDDF(lua_State*L, const dmDDF::Descriptor* descriptor, const char* data);

    /**
     * Push DDF message to Lua stack
     * @param L Lua state
     * @param descriptor Field descriptor
     * @param pointers_are_offets if pointers are offsets
     * @param data DDF data
     */
    void PushDDF(lua_State*L, const dmDDF::Descriptor* descriptor, const char* data, bool pointers_are_offsets);

    void RegisterDDFDecoder(void* descriptor, MessageDecoder decoder);

    /**
     * Serialize a table to a buffer
     * Supported types: LUA_TBOOLEAN, LUA_TNUMBER, LUA_TSTRING, Point3, Vector3, Vector4 and Quat
     * Keys must be strings
     * @param L Lua state
     * @param buffer Buffer that will be written to
     * @param buffer_size Buffer size
     * @param index Index of the table
     * @return Number of bytes used in buffer
     */
    uint32_t CheckTable(lua_State* L, char* buffer, uint32_t buffer_size, int index);

    /**
     * Push a serialized table to the supplied lua state, will increase the stack by 1.
     * @param L Lua state
     * @param data Buffer with serialized table to push
     * @param data_size Size of buffer of serialized data
     */
    void PushTable(lua_State*L, const char* data, uint32_t data_size);

    /**
     * Check if the value at #index is a hash
     * @param L Lua state
     * @param index Index of the value
     * @return true if the value at #index is a hash
     */
    bool IsHash(lua_State *L, int index);

    /**
     * Push a hash value onto the supplied lua state, will increase the stack by 1.
     * @param L Lua state
     * @param hash Hash value to push
     */
    void PushHash(lua_State* L, dmhash_t hash);

    /**
     * Removes a hash value from the currently known hashes.
     * @param L Lua state
     * @param hash Hash value to release
     */
    void ReleaseHash(lua_State* L, dmhash_t hash);

    /**
     * Check if the value in the supplied index on the lua stack is a hash.
     * @param L Lua state
     * @param index Index of the value
     * @return The hash value
     */
    dmhash_t CheckHash(lua_State* L, int index);

    /**
     * Check if the value in the supplied index on the lua stack is a hash or string.
     * If it is a string, it gets hashed on the fly
     * @param L Lua state
     * @param index Index of the value
     * @return The hash value
     */
    dmhash_t CheckHashOrString(lua_State* L, int index);

    /**
     * Gets as good as possible printable string from a hash or string
     * @return Always a null terminated string. "<unknown>" if the hash could not be looked up.
    */
    const char* GetStringFromHashOrString(lua_State* L, int index, char* buffer, uint32_t bufferlength);

    /**
     * Check if the value at #index is a FloatVector
     * @param L Lua state
     * @param index Index of the value
     * @return true if value at #index is a FloatVector
     */
    bool IsVector(lua_State *L, int index);

    /**
     * Push a FloatVector value onto the supplied lua state, will increase the stack by 1.
     * @param L Lua state
     * @param v Vector3 value to push
     */
    void PushVector(lua_State* L, dmVMath::FloatVector* v);

    /**
     * Check if the value in the supplied index on the lua stack is a Vector.
     * @param L Lua state
     * @param index Index of the value
     * @return The FloatVector value
     */
    dmVMath::FloatVector* CheckVector(lua_State* L, int index);

    /**
     * Check if the value at #index is a vector3
     * @param L Lua state
     * @param index Index of the value
     * @return true if value at #index is a vector3
     */
    bool IsVector3(lua_State *L, int index);

    /**
     * Push a Vector3 value onto the supplied lua state, will increase the stack by 1.
     * @param L Lua state
     * @param v Vector3 value to push
     */
    void PushVector3(lua_State* L, const Vectormath::Aos::Vector3& v);

    /**
     * Check if the value in the supplied index on the lua stack is a Vector3.
     * @param L Lua state
     * @param index Index of the value
     * @return The Vector3 value
     */
    Vectormath::Aos::Vector3* CheckVector3(lua_State* L, int index);

    /**
     * Check if the value at #index is a vector4
     * @param L Lua state
     * @param index Index of the value
     * @return true if value at #index is a vector4
     */
    bool IsVector4(lua_State *L, int index);

    /**
     * Push a Vector4 value onto the supplied lua state, will increase the stack by 1.
     * @param L Lua state
     * @param v Vector4 value to push
     */
    void PushVector4(lua_State* L, const Vectormath::Aos::Vector4& v);

    /**
     * Check if the value in the supplied index on the lua stack is a Vector4.
     * @param L Lua state
     * @param index Index of the value
     * @return The Vector4 value
     */
    Vectormath::Aos::Vector4* CheckVector4(lua_State* L, int index);

    /**
     * Check if the value at #index is a quat
     * @param L Lua state
     * @param index Index of the value
     * @return true if value at #index is a quat
     */
    bool IsQuat(lua_State *L, int index);

    /**
     * Push a quaternion value onto the supplied lua state, will increase the stack by 1.
     * @param L Lua state
     * @param q Quaternion value to push
     */
    void PushQuat(lua_State* L, const Vectormath::Aos::Quat& q);

    /**
     * Check if the value in the supplied index on the lua stack is a quaternion.
     * @param L Lua state
     * @param index Index of the value
     * @return The quat value
     */
    Vectormath::Aos::Quat* CheckQuat(lua_State* L, int index);

    /**
     * Check if the value at #index is a matrix4
     * @param L Lua state
     * @param index Index of the value
     * @return true if value at #index is a matrix4
     */
    bool IsMatrix4(lua_State *L, int index);

    /**
     * Push a matrix4 value onto the supplied lua state, will increase the stack by 1.
     * @param L Lua state
     * @param m Matrix4 value to push
     */
    void PushMatrix4(lua_State* L, const Vectormath::Aos::Matrix4& m);

    /**
     * Check if the value in the supplied index on the lua stack is a matrix4.
     * @param L Lua state
     * @param index Index of the value
     * @return The matrix4 value
     */
    Vectormath::Aos::Matrix4* CheckMatrix4(lua_State* L, int index);

    /**
     * Check if the value at #index is a URL
     * @param L Lua state
     * @param index Index of the value
     * @return true is value at #index is a URL
     */
    bool IsURL(lua_State *L, int index);

    /**
     * Push a URL value onto the supplied lua state, will increase the stack by 1
     * @param L Lua state
     * @param a URL value to push
     */
    void PushURL(lua_State* L, const dmMessage::URL& m);

    /**
     * Check if the value in the supplied index on the lua stack is a URL
     * @param L Lua state
     * @param index Index of the value
     * @return The URL value
     */
    dmMessage::URL* CheckURL(lua_State* L, int index);

    /**
     * Returns the URL of a script currently operating on the given lua state.
     * @param L Lua state
     * @param out_url Pointer to a URL to be written to
     * @return true if a URL could be found
     */
    bool GetURL(lua_State* L, dmMessage::URL* out_url);

    /**
     * Returns the user data of a script currently operating on the given lua state.
     * @param L Lua state
     * @param out_user_data Pointer to a uintptr_t to be written to
     * @return true if the user data could be found
     */
    bool GetUserData(lua_State* L, uintptr_t* out_user_data, const char* user_type);


    /**
     * Returns an identifier that identifies the current instance set by SetInstance().
     * The id is guarranteed to be unique among the currently alive script instances.
     *
     * @param L Lua state
     * @return unique identifier for the instance
     */
    uintptr_t GetInstanceId(lua_State* L);

    /**
     * Create a "world" for this script, normally this matches a GO collection,
     * GUI scene or a RenderWorld.
     *
     * @param context Script context
     * @return the script world
     */
    HScriptWorld NewScriptWorld(HContext context);

    /**
     * Delete the script world
     *
     * @param script_world The script world created with NewScriptWorld
     */
    void DeleteScriptWorld(HScriptWorld script_world);

    /**
     * Update the script world
     *
     * @param script_world the script world created with NewScriptWorld
     * @param dt the delta time in the world in seconds
     */
    void UpdateScriptWorld(HScriptWorld script_world, float dt);

    /**
     * Sets up the instance with associated data, expects SetInstance to have been
     * called prior to this function
     *
     * @param script_world the script world
     */
    void InitializeInstance(HScriptWorld script_world);

    /**
     * Removes the instance associated data, expects SetInstance to have been
     * called prior to this function
     *
     * @param script_world the script world
     */
    void FinalizeInstance(HScriptWorld script_world);

    /**
     * Set value by key using the META_GET_INSTANCE_CONTEXT_TABLE_REF meta table function
     *
     * Expects SetInstance() to have been set with an value that has a meta table
     * with META_GET_INSTANCE_CONTEXT_TABLE_REF method.
     *
     * @param L Lua state
     * @return true if the value could be store under the key
     *
     * Lua stack on entry
     *  [-2] key
     *  [-1] value
     *
     * Lua stack on exit
    */
    bool SetInstanceContextValue(lua_State* L);

    /**
     * Get value by key using the META_GET_INSTANCE_CONTEXT_TABLE_REF meta table function
     *
     * Expects SetInstance() to have been set with an value that has a meta table
     * with META_GET_INSTANCE_CONTEXT_TABLE_REF method.
     *
     * @param L Lua state
     *
     * Lua stack on entry
     *  [-1] key
     *
     * Lua stack on exit
     *  [-1] value or LUA_NIL
    */
    void GetInstanceContextValue(lua_State* L);

    /**
     * Creates a reference to the value at top of stack, the ref is done in the
     * current instances context table.
     *
     * Expects SetInstance() to have been set with an value that has a meta table
     * with META_GET_INSTANCE_CONTEXT_TABLE_REF method.
     *
     * @param L Lua state
     * @return lua ref to value or LUA_NOREF
     *
     * Lua stack on entry
     *  [-1] value
     *
     * Lua stack on exit
    */
    int RefInInstance(lua_State* L);

    /**
     * Deletes the instance local lua reference
     *
     * Expects SetInstance() to have been set with an value that has a meta table
     * with META_GET_INSTANCE_CONTEXT_TABLE_REF method.
     *
     * @param L Lua state
     * @param ref the instance local ref
     *
     * Lua stack on entry
     *
     * Lua stack on exit
     */
    void UnrefInInstance(lua_State* L, int ref);

    /**
     * Resolves the instance local ref and pushes it to top of stack
     *
     * Expects SetInstance() to have been set with an value that has a meta table
     * with META_GET_INSTANCE_CONTEXT_TABLE_REF method.
     *
     * @param L Lua state
     * @param ref the instance local ref
     *
     * Lua stack on entry
     *
     * Lua stack on exit
     *  [-1] value or LUA_NIL
     */
    void ResolveInInstance(lua_State* L, int ref);

    HContext GetScriptWorldContext(HScriptWorld script_world);

    /**
     * Set value by key in the context table associated with the script world
     *
     * @param script_world the script world created with NewScriptWorld
     *
     * Lua stack on entry
     *  [-2] key
     *  [-1] value
     *
     * Lua stack on exit
    */
    void SetScriptWorldContextValue(HScriptWorld script_world);

    /**
     * Get value by key from the context table associated with the script world
     *
     * @param script_world the script world created with NewScriptWorld
     *
     * Lua stack on entry
     *  [-1] key
     *
     * Lua stack on exit
     *  [-1] value
    */
    void GetScriptWorldContextValue(HScriptWorld script_world);

    dmMessage::Result ResolveURL(lua_State* L, const char* url, dmMessage::URL* out_url, dmMessage::URL* default_url);

    /**
     * Attempts to convert the value in the supplied index on the lua stack to a URL. It long jumps (calls luaL_error) on failure.
     * @param L Lua state
     * @param out_url URL to be written to
     * @param out_default_url default URL used in the resolve, can be 0x0 (not used)
     */
    int ResolveURL(lua_State* L, int index, dmMessage::URL* out_url, dmMessage::URL* out_default_url);

    /**
     * Retrieve lua state from the context
     * @param context script context
     * @return lua state
     */
    lua_State* GetLuaState(HContext context);

    dmConfigFile::HConfig GetConfigFile(HContext context);

    /**
     * Add (load) module
     * @param context script context
     * @param source lua script to load
     * @param script_name script-name. Should be in lua require-format, i.e. syntax use for the require statement. e.g. x.y.z without any extension
     * @param resource the resource will be released throught the resource system at finalization
     * @param path_hash hashed path of the originating resource
     * @return RESULT_OK on success
     */
    Result AddModule(HContext context, dmLuaDDF::LuaSource *source, const char *script_name, void* resource, dmhash_t path_hash);

    /**
     * Reload loaded module
     * @param context script context
     * @param source lua source to load
     * @param path_hash hashed path, see AddModule
     * @return RESULT_OK on success
     */
    Result ReloadModule(HContext context, dmLuaDDF::LuaSource *source, dmhash_t path_hash);

    /**
     * Check if a module is loaded
     * @param context script context
     * @param script_name script name, see AddModule
     * @return true if loaded
     */
    bool ModuleLoaded(HContext context, const char* script_name);

    /**
     * Check if a module is loaded by hash
     * @param context script context
     * @param path_hash hashed path, see AddModule
     * @return true if loaded
     */
    bool ModuleLoaded(HContext context, dmhash_t path_hash);


    /**
     * Check if the object at the given index is of the specified user type.
     * @param L lua state
     * @param idx object index
     * @param type user type
     * @return true if the object has the specified type
     */
    bool IsUserType(lua_State* L, int idx, const char* type);

    /**
     * Check if the object at the given index is of the specified user type.
     * This might result in lua errors so it should only be called from within a lua context.
     * @param L lua state
     * @param idx object index
     * @param type user type
     * @param error_message The error message to show if the type doesn't match. Set to null to show a generic type error message.
     * @return the object if it has the specified type, 0 otherwise
     */
    void* CheckUserType(lua_State* L, int idx, const char* type, const char* error_message);

    /**
     * Register a user type along with methods and meta methods.
     * @param L lua state
     * @param name user type name
     * @param methods array of methods
     * @param meta array of meta methods
     */
    void RegisterUserType(lua_State* L, const char* name, const luaL_reg methods[], const luaL_reg meta[]);

    /**
     * This function wraps lua_pcall with the addition of specifying an error handler which produces a backtrace.
     * In the case of an error, the error is logged and popped from the stack.
     * @param L lua state
     * @param nargs number of arguments
     * @param nresult number of results
     * @return error code from pcall
     */
    int PCall(lua_State* L, int nargs, int nresult);

    /**
     * Wraps luaL_loadbuffer but takes dmLuaDDF::LuaSource instead of buffer directly.
     */
    int LuaLoad(lua_State *L, dmLuaDDF::LuaSource* source);

    /**
     * Convert a JSON document to Lua table.
     * @param L lua state
     * @param doc JSON document
     * @param index index of JSON node
     * @param error_str_out if an error is encountered, the error string is written to this argument
     * @param error_str_size size of error_str_out
     * @return index of next JSON node to handle
     */
    int JsonToLua(lua_State*L, dmJson::Document* doc, int index, char* error_str_out, size_t error_str_size);

    /** Gets the number of references currently kept
     * @return the total number of references in the game
    */
    int GetLuaRefCount();

    /** Upon engine restart, the reference count should be reset
    */
    void ClearLuaRefCount();

    /** Gets the memory usage by lua (in kilobytes)
    * @param L lua state
    * @return the number of kilobytes lua uses
    */
    uint32_t GetLuaGCCount(lua_State* L);

    struct LuaCallbackInfo;

    /** Register a Lua callback. Stores the current Lua state plus references to the
     * script instance (self) and the callback Expects SetInstance() to have been called
     * prior to using this method.
     *
     * The allocated data is created on the Lua stack and references are made against the
     * instances own context table.
     *
     * If the callback is not explicitly deleted with DeleteCallback() the references and
     * data will stay around until the script instance is deleted.
     */
    LuaCallbackInfo* CreateCallback(lua_State* L, int callback_stack_index);

    /** Check if Lua callback is valid.
     */
    bool IsValidCallback(LuaCallbackInfo* cbk);

    /** Deletes the Lua callback
     */
    void DeleteCallback(LuaCallbackInfo* cbk);

    /** A helper function for the user to easily push Lua stack arguments prior to invoking the callback
     */
    typedef void (*LuaCallbackUserFn)(lua_State* L, void* user_context);

    /** Invokes a Lua callback. User can pass a custom function for pushing extra Lua arguments
     * to the stack, prior to the call
     * Returns true on success and false on failure. In case of failure, an error will be logged.
     *
     * The function takes care of setting up and restoring the current instance.
     */
    bool InvokeCallback(LuaCallbackInfo* cbk, LuaCallbackUserFn fn, void* user_context);

    /** Information about a function, in which file and at what line it is defined
     * Use with GetLuaFunctionRefInfo
     */
    struct LuaFunctionInfo
    {
        const char* m_FileName;
        const char* m_OptionalName;
        int m_LineNumber;
    };

    /**
     * Get information about where a Lua function is defined
     * @param L lua state
     * @param stack_index which index on the stack that contains the lua function ref
     * @param out_function_info pointer to the function information that is filled out
     * @return true on success, out_function_info is only touched if the function succeeds
     */
    bool GetLuaFunctionRefInfo(lua_State* L, int stack_index, LuaFunctionInfo* out_function_info);

    /**
     * Get an integer value at a specific key in a table.
     * @param L lua state
     * @param table_index Index of table on Lua stack
     * @param key Key string
     * @param default_value Value to return if key is not found
     * @return Integer value at key, or the default value if not found or invalid value type.
     */
    int GetTableIntValue(lua_State* L, int table_index, const char* key, int default_value);

    /**
     * Get an string value at a specific key in a table.
     * @param L lua state
     * @param table_index Index of table on Lua stack
     * @param key Key string
     * @param default_value Value to return if key is not found
     * @return String value at key, or the default value if not found or invalid value type.
     */
    const char* GetTableStringValue(lua_State* L, int table_index, const char* key, const char* default_value);
}


#endif // DM_SCRIPT_H
