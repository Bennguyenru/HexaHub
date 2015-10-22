#include <assert.h>
#include <string.h>
#include <inttypes.h>

#include <ddf/ddf.h>

#include <dlib/log.h>
#include <dlib/hash.h>
#include <dlib/hashtable.h>
#include <dlib/message.h>
#include <dlib/dstrings.h>
#include <dlib/profile.h>

#include <script/script.h>

#include "gameobject.h"
#include "gameobject_script.h"
#include "gameobject_script_util.h"
#include "gameobject_private.h"
#include "gameobject_props_lua.h"

// Not to pretty to include res_lua.h here but lua-modules
// are released at system shutdown and not on a per parent-resource basis
// as all other resource are. Due to the nature of lua-code and
// code in general, side-effects, we can't "shutdown" a module.
#include "res_lua.h"

extern "C"
{
#include <lua/lua.h>
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

namespace dmGameObject
{
#define SCRIPTINSTANCE "GOScriptInstance"
#define SCRIPT "GOScript"

    using namespace dmPropertiesDDF;

    const char* SCRIPT_INSTANCE_TYPE_NAME = SCRIPTINSTANCE;

    const char* SCRIPT_FUNCTION_NAMES[MAX_SCRIPT_FUNCTION_COUNT] =
    {
        "init",
        "final",
        "update",
        "on_message",
        "on_input",
        "on_reload"
    };

    ScriptWorld::ScriptWorld()
    : m_Instances()
    {
        // TODO: How to configure? It should correspond to collection instance count
        m_Instances.SetCapacity(1024);
    }

    static Script* GetScript(lua_State *L)
    {
        int top = lua_gettop(L);
        (void)top;
        Script* script = 0x0;
        dmScript::GetInstance(L);
        if (dmScript::IsUserType(L, -1, SCRIPT))
        {
            script = (Script*)lua_touserdata(L, -1);
        }
        // Clear stack and return
        lua_pop(L, 1);
        assert(top == lua_gettop(L));
        return script;
    }

    static int ScriptGetURL(lua_State* L)
    {
        dmMessage::URL url;
        dmMessage::ResetURL(url);
        dmScript::PushURL(L, url);
        return 1;
    }

    static int ScriptResolvePath(lua_State* L)
    {
        const char* path = luaL_checkstring(L, 2);
        dmScript::PushHash(L, dmHashString64(path));
        return 1;
    }

    static int ScriptIsValid(lua_State* L)
    {
        Script* script = (Script*)lua_touserdata(L, 1);
        lua_pushboolean(L, script != 0x0 && script->m_LuaModule != 0x0);
        return 1;
    }

    static const luaL_reg Script_methods[] =
    {
        {0,0}
    };

    static const luaL_reg Script_meta[] =
    {
        {dmScript::META_TABLE_GET_URL,      ScriptGetURL},
        {dmScript::META_TABLE_RESOLVE_PATH, ScriptResolvePath},
        {dmScript::META_TABLE_IS_VALID,     ScriptIsValid},
        {0, 0}
    };

    static ScriptInstance* ScriptInstance_Check(lua_State *L, int index)
    {
        return (ScriptInstance*)dmScript::CheckUserType(L, index, SCRIPTINSTANCE);
    }

    static ScriptInstance* ScriptInstance_Check(lua_State *L)
    {
        dmScript::GetInstance(L);
        ScriptInstance* i = ScriptInstance_Check(L, -1);
        lua_pop(L, 1);
        return i;
    }

    static int ScriptInstance_gc (lua_State *L)
    {
        ScriptInstance* i = ScriptInstance_Check(L, 1);
        memset(i, 0, sizeof(*i));
        (void) i;
        assert(i);
        return 0;
    }

    static int ScriptInstance_tostring (lua_State *L)
    {
        lua_pushfstring(L, "GameObject: %p", lua_touserdata(L, 1));
        return 1;
    }

    static int ScriptInstance_index(lua_State *L)
    {
        ScriptInstance* i = ScriptInstance_Check(L, 1);
        (void) i;
        assert(i);

        // Try to find value in instance data
        lua_rawgeti(L, LUA_REGISTRYINDEX, i->m_ScriptDataReference);
        lua_pushvalue(L, 2);
        lua_gettable(L, -2);
        return 1;
    }

    static int ScriptInstance_newindex(lua_State *L)
    {
        int top = lua_gettop(L);

        ScriptInstance* i = ScriptInstance_Check(L, 1);
        (void) i;
        assert(i);

        lua_rawgeti(L, LUA_REGISTRYINDEX, i->m_ScriptDataReference);
        lua_pushvalue(L, 2);
        lua_pushvalue(L, 3);
        lua_settable(L, -3);
        lua_pop(L, 1);

        assert(top == lua_gettop(L));

        return 0;
    }

    static void ScriptInstanceGetURLCB(lua_State* L, dmMessage::URL* out_url)
    {
        dmScript::GetInstance(L);
        ScriptInstance* i = ScriptInstance_Check(L);
        lua_pop(L, 1);
        Instance* instance = i->m_Instance;
        out_url->m_Function = 0;
        out_url->m_Socket = instance->m_Collection->m_ComponentSocket;
        out_url->m_Path = instance->m_Identifier;
        out_url->m_Fragment = instance->m_Prototype->m_Components[i->m_ComponentIndex].m_Id;
    }

    static dmhash_t ScriptInstanceResolvePathCB(uintptr_t resolve_user_data, const char* path, uint32_t path_size) {
        ScriptInstance* i = (ScriptInstance*)resolve_user_data;
        if (path != 0x0 && *path != 0)
        {
            return GetAbsoluteIdentifier(i->m_Instance, path, strlen(path));
        }
        else
        {
            return i->m_Instance->m_Identifier;
        }
    }

    static int ScriptInstanceGetURL(lua_State* L)
    {
        ScriptInstance* i = (ScriptInstance*)lua_touserdata(L, 1);
        Instance* instance = i->m_Instance;
        dmMessage::URL url;
        url.m_Function = 0;
        url.m_Socket = instance->m_Collection->m_ComponentSocket;
        url.m_Path = instance->m_Identifier;
        url.m_Fragment = instance->m_Prototype->m_Components[i->m_ComponentIndex].m_Id;
        dmScript::PushURL(L, url);
        return 1;
    }

    static int ScriptInstanceGetUserData(lua_State* L)
    {
        ScriptInstance* i = (ScriptInstance*)lua_touserdata(L, 1);
        lua_pushlightuserdata(L, i->m_Instance);
        return 1;
    }

    static int ScriptInstanceResolvePath(lua_State* L)
    {
        ScriptInstance* i = (ScriptInstance*)lua_touserdata(L, 1);
        const char* path = luaL_checkstring(L, 2);

        if (path != 0x0 && *path != 0)
        {
            dmScript::PushHash(L, GetAbsoluteIdentifier(i->m_Instance, path, strlen(path)));
        }
        else
        {
            dmScript::PushHash(L, i->m_Instance->m_Identifier);
        }
        return 1;
    }

    static int ScriptInstanceIsValid(lua_State* L)
    {
        ScriptInstance* i = (ScriptInstance*)lua_touserdata(L, 1);
        lua_pushboolean(L, i != 0x0 && i->m_Instance != 0x0);
        return 1;
    }

    static const luaL_reg ScriptInstance_methods[] =
    {
        {0,0}
    };

    static const luaL_reg ScriptInstance_meta[] =
    {
        {"__gc",                            ScriptInstance_gc},
        {"__tostring",                      ScriptInstance_tostring},
        {"__index",                         ScriptInstance_index},
        {"__newindex",                      ScriptInstance_newindex},
        {dmScript::META_TABLE_GET_URL,      ScriptInstanceGetURL},
        {dmScript::META_TABLE_GET_USER_DATA,ScriptInstanceGetUserData},
        {dmScript::META_TABLE_RESOLVE_PATH, ScriptInstanceResolvePath},
        {dmScript::META_TABLE_IS_VALID,     ScriptInstanceIsValid},
        {0, 0}
    };

    /**
     * Get instance utility function helper.
     * The function will use the default "this" instance by default
     * but if lua_gettop(L) == instance_arg, i.e. an instance reference is specified,
     * the argument instance_arg will be resolved to an instance. The function
     * only accepts instances in "this" collection. Otherwise a lua-error will be raised.
     * @param L lua state
     * @param instance_arg lua-arg
     * @return instance handler
     */
    static Instance* ResolveInstance(lua_State* L, int instance_arg)
    {
        ScriptInstance* i = ScriptInstance_Check(L);
        Instance* instance = i->m_Instance;
        if (lua_gettop(L) == instance_arg) {
            dmMessage::URL receiver;
            dmScript::ResolveURL(L, instance_arg, &receiver, 0x0);
            if (receiver.m_Socket != dmGameObject::GetMessageSocket(i->m_Instance->m_Collection))
            {
                luaL_error(L, "function called can only access instances within the same collection.");
            }
            instance = GetInstanceFromIdentifier(instance->m_Collection, receiver.m_Path);
            if (!instance)
            {
                luaL_error(L, "Instance %s not found", lua_tostring(L, instance_arg));
                return 0; // Actually never reached
            }
        }
        return instance;
    }

    static Result GetComponentUserData(HInstance instance, dmhash_t component_id, uint32_t* component_type, uintptr_t* user_data)
    {
        // TODO: We should probably not store user-data sparse.
        // A lot of loops just to find user-data such as the code below
        assert(instance != 0x0);
        const dmArray<Prototype::Component>& components = instance->m_Prototype->m_Components;
        uint32_t n = components.Size();
        uint32_t component_instance_data = 0;
        for (uint32_t i = 0; i < n; ++i)
        {
            const Prototype::Component* component = &components[i];
            if (component->m_Id == component_id)
            {
                if (component->m_Type->m_InstanceHasUserData)
                {
                    *user_data = instance->m_ComponentInstanceUserData[component_instance_data];
                }
                else
                {
                    *user_data = 0;
                }
                *component_type = component->m_TypeIndex;
                return RESULT_OK;
            }

            if (component->m_Type->m_InstanceHasUserData)
            {
                component_instance_data++;
            }
        }

        return RESULT_COMPONENT_NOT_FOUND;
    }

    void GetComponentUserDataFromLua(lua_State* L, int index, HCollection collection, const char* component_ext, uintptr_t* user_data, dmMessage::URL* url, void** world)
    {
        dmMessage::URL sender;
        if (dmScript::GetURL(L, &sender))
        {
            dmMessage::URL receiver;
            dmScript::ResolveURL(L, index, &receiver, &sender);
            if (sender.m_Socket != receiver.m_Socket || sender.m_Socket != dmGameObject::GetMessageSocket(collection))
            {
                luaL_error(L, "function called can only access instances within the same collection.");
                return; // Actually never reached
            }

            HInstance instance = GetInstanceFromIdentifier(collection, receiver.m_Path);
            if (!instance)
            {
                luaL_error(L, "Instance %s not found", lua_tostring(L, index));
                return; // Actually never reached
            }

            uint32_t component_type_index;
            dmGameObject::Result result = GetComponentUserData(instance, receiver.m_Fragment, &component_type_index, user_data);
            if ((component_ext != 0x0 || user_data != 0x0) && result != dmGameObject::RESULT_OK)
            {
                luaL_error(L, "The component could not be found");
                return; // Actually never reached
            }

            if (world != 0) {
                *world = GetWorld(instance->m_Collection, component_type_index);
            }

            if (component_ext != 0x0)
            {
                dmResource::ResourceType resource_type;
                dmResource::Result resource_res = dmResource::GetTypeFromExtension(instance->m_Collection->m_Factory, component_ext, &resource_type);
                if (resource_res != dmResource::RESULT_OK)
                {
                    luaL_error(L, "Component type '%s' not found", component_ext);
                    return; // Actually never reached
                }
                ComponentType* type = &instance->m_Collection->m_Register->m_ComponentTypes[component_type_index];
                if (type->m_ResourceType != resource_type)
                {
                    luaL_error(L, "Component expected to be of type '%s' but was '%s'", component_ext, type->m_Name);
                    return; // Actually never reached
                }
            }
            if (url)
            {
                *url = receiver;
            }

        }
        else
        {
            luaL_error(L, "function called is not available from this script-type.");
            return; // Actually never reached
        }
    }

    HInstance GetInstanceFromLua(lua_State* L) {
        uintptr_t user_data;
        if (dmScript::GetUserData(L, &user_data, SCRIPTINSTANCE)) {
            return (HInstance)user_data;
        } else {
            return 0;
        }
    }

    /*# gets a named property of the specified game object or component
     *
     * @name go.get
     * @param url url of the game object or component having the property (hash|string|url)
     * @param id id of the property to retrieve (hash|string)
     * @return the value of the specified property (number|hash|url|vector3|vector4|quaternion|boolean)
     * @examples
     * <p>Get a property "speed" from a script "player", the property must be declared in the player-script:</p>
     * <pre>
     * go.property("speed", 50)
     * </pre>
     * <p>Then in the calling script (assumed to belong to the same game object, but does not have to):</p>
     * <pre>
     * local speed = go.get("#player", "speed")
     * </pre>
     */
    int Script_Get(lua_State* L)
    {
        ScriptInstance* i = ScriptInstance_Check(L);
        Instance* instance = i->m_Instance;
        dmMessage::URL sender;
        dmScript::GetURL(L, &sender);
        dmMessage::URL target;
        dmScript::ResolveURL(L, 1, &target, &sender);
        if (target.m_Socket != dmGameObject::GetMessageSocket(i->m_Instance->m_Collection))
        {
            return luaL_error(L, "go.get can only access instances within the same collection.");
        }
        dmhash_t property_id = 0;
        if (lua_isstring(L, 2))
        {
            property_id = dmHashString64(lua_tostring(L, 2));
        }
        else
        {
            property_id = dmScript::CheckHash(L, 2);
        }
        dmGameObject::HInstance target_instance = dmGameObject::GetInstanceFromIdentifier(dmGameObject::GetCollection(instance), target.m_Path);
        if (target_instance == 0)
            return luaL_error(L, "Could not find any instance with id '%s'.", (const char*)dmHashReverse64(target.m_Path, 0x0));
        dmGameObject::PropertyDesc property_desc;
        dmGameObject::PropertyResult result = dmGameObject::GetProperty(target_instance, target.m_Fragment, property_id, property_desc);
        switch (result)
        {
        case dmGameObject::PROPERTY_RESULT_OK:
            dmGameObject::LuaPushVar(L, property_desc.m_Variant);
            return 1;
        case dmGameObject::PROPERTY_RESULT_NOT_FOUND:
            return luaL_error(L, "'%s' does not have any property called '%s'", lua_tostring(L, 1), (const char*)dmHashReverse64(property_id, 0x0));
        case dmGameObject::PROPERTY_RESULT_COMP_NOT_FOUND:
            return luaL_error(L, "could not find component '%s' when resolving '%s'", (const char*)dmHashReverse64(target.m_Fragment, 0x0), lua_tostring(L, 1));
        default:
            // Should never happen, programmer error
            return luaL_error(L, "go.get failed with error code %d", result);
        }
    }

    static const char* GetPropertyTypeName(PropertyType type)
    {
        switch (type)
        {
        case PROPERTY_TYPE_NUMBER:
            return "number";
        case PROPERTY_TYPE_HASH:
            return "hash";
        case PROPERTY_TYPE_URL:
            return "msg.url";
        case PROPERTY_TYPE_VECTOR3:
            return "vmath.vector3";
        case PROPERTY_TYPE_VECTOR4:
            return "vmath.vector4";
        case PROPERTY_TYPE_QUAT:
            return "vmath.quat";
        case PROPERTY_TYPE_BOOLEAN:
            return "boolean";
        default:
            return "unknown";
        }
    }

    /*# sets a named property of the specified game object or component
     *
     * @name go.set
     * @param url url of the game object or component having the property (hash|string|url)
     * @param id id of the property to set (hash|string)
     * @param value the value to set (number|hash|url|vector3|vector4|quaternion|boolean)
     * @examples
     * <p>Set a property "speed" of a script "player", the property must be declared in the player-script:</p>
     * <pre>
     * go.property("speed", 50)
     * </pre>
     * <p>Then in the calling script (assumed to belong to the same game object, but does not have to):</p>
     * <pre>
     * go.set("#player", "speed", 100)
     * </pre>
     */
    int Script_Set(lua_State* L)
    {
        ScriptInstance* i = ScriptInstance_Check(L);
        Instance* instance = i->m_Instance;
        dmMessage::URL sender;
        dmScript::GetURL(L, &sender);
        dmMessage::URL target;
        dmScript::ResolveURL(L, 1, &target, &sender);
        if (target.m_Socket != dmGameObject::GetMessageSocket(i->m_Instance->m_Collection))
        {
            luaL_error(L, "go.set can only access instances within the same collection.");
        }
        dmhash_t property_id = 0;
        if (lua_isstring(L, 2))
        {
            property_id = dmHashString64(lua_tostring(L, 2));
        }
        else
        {
            property_id = dmScript::CheckHash(L, 2);
        }
        dmGameObject::PropertyVar property_var;
        dmGameObject::HInstance target_instance = dmGameObject::GetInstanceFromIdentifier(dmGameObject::GetCollection(instance), target.m_Path);
        if (target_instance == 0)
            return luaL_error(L, "could not find any instance with id '%s'.", (const char*)dmHashReverse64(target.m_Path, 0x0));
        dmGameObject::PropertyResult result = dmGameObject::LuaToVar(L, 3, property_var);
        if (result == PROPERTY_RESULT_OK)
        {
            result = dmGameObject::SetProperty(target_instance, target.m_Fragment, property_id, property_var);
        }
        switch (result)
        {
        case dmGameObject::PROPERTY_RESULT_OK:
            return 0;
        case PROPERTY_RESULT_NOT_FOUND:
            {
                lua_pushliteral(L, "");
                lua_pushvalue(L, 1);
                lua_concat(L, 2);
                const char* name = lua_tostring(L, -1);
                lua_pop(L, 1);
                return luaL_error(L, "'%s' does not have any property called '%s'", name, (const char*)dmHashReverse64(property_id, 0x0));
            }
        case PROPERTY_RESULT_UNSUPPORTED_TYPE:
        case PROPERTY_RESULT_TYPE_MISMATCH:
            {
                dmGameObject::PropertyDesc property_desc;
                dmGameObject::GetProperty(target_instance, target.m_Fragment, property_id, property_desc);
                return luaL_error(L, "the property '%s' of '%s' must be a %s", (const char*)dmHashReverse64(property_id, 0x0), lua_tostring(L, 1), GetPropertyTypeName(property_desc.m_Variant.m_Type));
            }
        case dmGameObject::PROPERTY_RESULT_COMP_NOT_FOUND:
            return luaL_error(L, "could not find component '%s' when resolving '%s'", (const char*)dmHashReverse64(target.m_Fragment, 0x0), lua_tostring(L, 1));
        case dmGameObject::PROPERTY_RESULT_UNSUPPORTED_VALUE:
            return luaL_error(L, "go.set failed because the value is unsupported");
        case dmGameObject::PROPERTY_RESULT_UNSUPPORTED_OPERATION:
            return luaL_error(L, "could not perform unsupported operation on '%s'", (const char*)dmHashReverse64(property_id, 0x0));
        default:
            // Should never happen, programmer error
            return luaL_error(L, "go.set failed with error code %d", result);
        }
    }

    /*# gets the position of the instance
     * The position is relative the parent (if any). Use <code>go.get_world_position</code> to retrieve the global world position.
     *
     * @name go.get_position
     * @param [id] optional id of the instance to get the position for, by default the instance of the calling script (hash|string|url)
     * @return instance position (vector3)
     * @examples
     * <p>Get the position of the instance the script is attached to:</p>
     * <pre>
     * local p = go.get_position()
     * </pre>
     * <p>Get the position of another instance "x":</p>
     * <pre>
     * local p = go.get_position("x")
     * </pre>
     */
    int Script_GetPosition(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 1);
        dmScript::PushVector3(L, Vectormath::Aos::Vector3(dmGameObject::GetPosition(instance)));
        return 1;
    }

    /*# gets the rotation of the instance
     * The rotation is relative to the parent (if any). Use <code>go.get_world_rotation</code> to retrieve the global world position.
     *
     * @name go.get_rotation
     * @param [id] optional id of the instance to get the rotation for, by default the instance of the calling script (hash|string|url)
     * @return instance rotation (quaternion)
     * @examples
     * <p>Get the rotation of the instance the script is attached to:</p>
     * <pre>
     * local r = go.get_rotation()
     * </pre>
     * <p>Get the rotation of another instance "x":</p>
     * <pre>
     * local r = go.get_rotation("x")
     * </pre>
     */
    int Script_GetRotation(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 1);
        dmScript::PushQuat(L, dmGameObject::GetRotation(instance));
        return 1;
    }

    /*# gets the uniform scale factor of the instance
     * The uniform scale is relative the parent (if any). Use <code>go.get_world_scale</code> to retrieve the global world scale factor.
     *
     * @name go.get_scale
     * @param [id] optional id of the instance to get the scale for, by default the instance of the calling script (hash|string|url)
     * @return uniform instance scale factor (number)
     * @examples
     * <p>Get the scale of the instance the script is attached to:</p>
     * <pre>
     * local s = go.get_scale()
     * </pre>
     * <p>Get the scale of another instance "x":</p>
     * <pre>
     * local s = go.get_scale("x")
     * </pre>
     */
    int Script_GetScale(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 1);
        lua_pushnumber(L, dmGameObject::GetUniformScale(instance));
        return 1;
    }

    /*# gets the 3D scale factor of the instance
     * The scale is relative the parent (if any). Use <code>go.get_world_scale</code> to retrieve the global world scale factor.
     *
     * @name go.get_scale_vector
     * @param [id] optional id of the instance to get the scale for, by default the instance of the calling script (hash|string|url)
     * @return scale factor (vector3)
     * @examples
     * <p>Get the scale of the instance the script is attached to:</p>
     * <pre>
     * local s = go.get_scale_vector()
     * </pre>
     * <p>Get the scale of another instance "x":</p>
     * <pre>
     * local s = go.get_scale_vector("x")
     * </pre>
     */
    int Script_GetScaleVector(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 1);
        dmScript::PushVector3(L, dmGameObject::GetScale(instance));
        return 1;
    }

    /*# sets the position of the instance
     * The position is relative to the parent (if any). The global world position cannot be manually set.
     *
     * @name go.set_position
     * @param position position to set (vector3)
     * @param [id] optional id of the instance to set the position for, by default the instance of the calling script (hash|string|url)
     * @examples
     * <p>Set the position of the instance the script is attached to:</p>
     * <pre>
     * local p = ...
     * go.set_position(p)
     * </pre>
     * <p>Set the position of another instance "x":</p>
     * <pre>
     * local p = ...
     * go.set_position(p, "x")
     * </pre>
     */
    int Script_SetPosition(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 2);
        Vectormath::Aos::Vector3* v = dmScript::CheckVector3(L, 1);
        dmGameObject::SetPosition(instance, Vectormath::Aos::Point3(*v));
        return 0;
    }

    /*# sets the rotation of the instance
     * The rotation is relative to the parent (if any). The global world rotation cannot be manually set.
     *
     * @name go.set_rotation
     * @param rotation rotation to set (quaternion)
     * @param [id] optional id of the instance to get the rotation for, by default the instance of the calling script (hash|string|url)
     * @examples
     * <p>Set the rotation of the instance the script is attached to:</p>
     * <pre>
     * local r = ...
     * go.set_rotation(r)
     * </pre>
     * <p>Set the rotation of another instance "x":</p>
     * <pre>
     * local r = ...
     * go.set_rotation(r, "x")
     * </pre>
     */
    int Script_SetRotation(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 2);
        Vectormath::Aos::Quat* q = dmScript::CheckQuat(L, 1);
        dmGameObject::SetRotation(instance, *q);
        return 0;
    }

    /*# sets the scale factor of the instance
     * The scale factor is relative to the parent (if any). The global world scale factor cannot be manually set.
     *
     * NOTE! Physics are currently not affected when setting scale from this function.
     *
     * @name go.set_scale
     * @param scale vector or uniform scale factor, must be greater than 0 (number|vector3)
     * @param [id] optional id of the instance to get the scale for, by default the instance of the calling script (hash|string|url)
     * @examples
     * <p>Set the scale of the instance the script is attached to:</p>
     * <pre>
     * local s = vmath.vector3(2.0, 1.0, 1.0)
     * go.set_scale(s)
     * </pre>
     * <p>Set the scale of another instance "x":</p>
     * <pre>
     * local s = 1.2
     * go.set_scale(s, "x")
     * </pre>
     */
    int Script_SetScale(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 2);

        // Supports both vector and number
        if (dmScript::IsVector3(L, 1))
        {
            Vector3 scale = *dmScript::CheckVector3(L, 1);
            if (scale.getX() <= 0.0f || scale.getY() <= 0.0f || scale.getZ() <= 0.0f)
            {
                return luaL_error(L, "Vector passed to go.set_scale contains components that are below or equal to zero");
            }
            dmGameObject::SetScale(instance, scale);
            return 0;
        }

        lua_Number v = luaL_checknumber(L, 1);
        if (v <= 0.0)
        {
            return luaL_error(L, "The scale supplied to go.set_scale must be greater than 0.");
        }
        dmGameObject::SetScale(instance, (float)v);
        return 0;
    }

    /*# gets the instance world position
     * Use <code>go.get_position</code> to retrieve the position relative to the parent.
     *
     * @name go.get_world_position
     * @param [id] optional id of the instance to get the world position for, by default the instance of the calling script (hash|string|url)
     * @return instance world position (vector3)
     * @examples
     * <p>Get the world position of the instance the script is attached to:</p>
     * <pre>
     * local p = go.get_world_position()
     * </pre>
     * <p>Get the world position of another instance "x":</p>
     * <pre>
     * local p = go.get_world_position("x")
     * </pre>
     */
    int Script_GetWorldPosition(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 1);
        dmScript::PushVector3(L, Vectormath::Aos::Vector3(dmGameObject::GetWorldPosition(instance)));
        return 1;
    }

    /*# gets the instance world rotation
     * Use <code>go.get_rotation</code> to retrieve the rotation relative to the parent.
     *
     * @name go.get_world_rotation
     * @param [id] optional id of the instance to get the world rotation for, by default the instance of the calling script (hash|string|url)
     * @return instance world rotation (quaternion)
     * @examples
     * <p>Get the world rotation of the instance the script is attached to:</p>
     * <pre>
     * local r = go.get_world_rotation()
     * </pre>
     * <p>Get the world rotation of another instance "x":</p>
     * <pre>
     * local r = go.get_world_rotation("x")
     * </pre>
     */
    int Script_GetWorldRotation(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 1);
        dmScript::PushQuat(L, dmGameObject::GetWorldRotation(instance));
        return 1;
    }

    /*# gets the instance world scale factor
     * Use <code>go.get_scale</code> to retrieve the scale factor relative to the parent.
     *
     * @name go.get_world_scale
     * @param [id] optional id of the instance to get the world scale for, by default the instance of the calling script (hash|string|url)
     * @return uniform instance world scale factor (number)
     * @examples
     * <p>Get the world scale of the instance the script is attached to:</p>
     * <pre>
     * local s = go.get_world_scale()
     * </pre>
     * <p>Get the world scale of another instance "x":</p>
     * <pre>
     * local s = go.get_world_scale("x")
     * </pre>
     */
    int Script_GetWorldScale(lua_State* L)
    {
        Instance* instance = ResolveInstance(L, 1);
        lua_pushnumber(L, dmGameObject::GetWorldUniformScale(instance));
        return 1;
    }

    /*# gets the id of an instance
     * The instance id is a hash of the absolute path.
     * If <code>path</code> is specified, it can either be absolute or relative to the instance of the calling script.
     * If <code>path</code> is not specified, the id of the instance of the calling script will be returned. See the examples below for more information.
     *
     * @name go.get_id
     * @param [path] path of the instance for which to return the id (string)
     * @return instance id (hash)
     * @examples
     * <p>
     * For the instance with path <code>/my_sub_collection/my_instance</code>, the following calls are equivalent:
     * </p>
     * <pre>
     * local id = go.get_id() -- no path, defaults to the instance of the calling script
     * local id = go.get_id("/my_sub_collection/my_instance") -- absolute path
     * </pre>
     * <p>
     * From a script in another instance in the same collection, i.e. path <code>/my_sub_collection/my_other_instance</code>, the id of the first instance can be retrieved in two ways:
     * </p>
     * <pre>
     * local id = go.get_id("my_instance") -- relative path
     * local id = go.get_id("/my_sub_collection/my_instance") -- absolute path
     * </pre>
     */
    int Script_GetId(lua_State* L)
    {
        ScriptInstance* i = ScriptInstance_Check(L);
        if (lua_gettop(L) > 0)
        {
            const char* ident = luaL_checkstring(L, 1);
            dmScript::PushHash(L, GetAbsoluteIdentifier(i->m_Instance, ident, strlen(ident)));
        }
        else
        {
            dmScript::PushHash(L, i->m_Instance->m_Identifier);
        }
        return 1;
    }

    static lua_State* GetLuaState(ScriptInstance* instance) {
        return instance->m_Script->m_LuaState;
    }

    void LuaCurveRelease(dmEasing::Curve* curve)
    {
        ScriptInstance* script_instance = (ScriptInstance*)curve->userdata1;
        lua_State* L = GetLuaState(script_instance);

        int top = lua_gettop(L);
        (void) top;

        int ref = (int) (((uintptr_t) curve->userdata2) & 0xffffffff);
        luaL_unref(L, LUA_REGISTRYINDEX, ref);

        curve->release_callback = 0x0;
        curve->userdata1 = 0x0;
        curve->userdata2 = 0x0;

        assert(top == lua_gettop(L));
    }

    void LuaAnimationStopped(dmGameObject::HInstance instance, dmhash_t component_id, dmhash_t property_id,
                                        bool finished, void* userdata1, void* userdata2)
    {
        ScriptInstance* script_instance = (ScriptInstance*)userdata1;
        lua_State* L = GetLuaState(script_instance);

        int top = lua_gettop(L);
        (void) top;

        dmMessage::URL url;
        url.m_Socket = instance->m_Collection->m_ComponentSocket;
        url.m_Path = instance->m_Identifier;
        url.m_Fragment = component_id;

        int ref = (int) (((uintptr_t) userdata2) & 0xffffffff);

        if (finished)
        {
            lua_rawgeti(L, LUA_REGISTRYINDEX, ref);
            lua_rawgeti(L, LUA_REGISTRYINDEX, script_instance->m_InstanceReference);
            lua_pushvalue(L, -1);
            dmScript::SetInstance(L);

            dmScript::PushURL(L, url);
            dmScript::PushHash(L, property_id);
            assert(lua_type(L, -4) == LUA_TFUNCTION);
            dmScript::PCall(L, 3, 0);

            lua_pushnil(L);
            dmScript::SetInstance(L);
        }

        lua_unref(L, ref);

        assert(top == lua_gettop(L));
    }

    /*# animates a named property of the specified game object or component
     * <p>
     * This is only supported for numerical properties. If the node property is already being
     * animated, that animation will be canceled and replaced by the new one.
     * </p>
     * <p>
     * If a <code>complete_function</code> (lua function) is specified, that function will be called when the animation has completed.
     * By starting a new animation in that function, several animations can be sequenced together. See the examples for more information.
     * </p>
     * <p>
     * See the <a href="/doc/properties">properties guide</a> for which properties can be animated and how.
     * </p>
     *
     * @name go.animate
     * @param url url of the game object or component having the property (hash|string|url)
     * @param property name of the property to animate (hash|string)
     * @param playback playback mode of the animation (constant)
     * <ul>
     *   <li><code>go.PLAYBACK_ONCE_FORWARD</code></li>
     *   <li><code>go.PLAYBACK_ONCE_BACKWARD</code></li>
     *   <li><code>go.PLAYBACK_ONCE_PINGPONG</code></li>
     *   <li><code>go.PLAYBACK_LOOP_FORWARD</code></li>
     *   <li><code>go.PLAYBACK_LOOP_BACKWARD</code></li>
     *   <li><code>go.PLAYBACK_LOOP_PINGPONG</code></li>
     * </ul>
     * @param to target property value (number|vector3|vector4|quaternion)
     * @param easing easing to use during animation. Either specify a constant, see the <a href="/doc/properties">properties guide</a> for a complete list, or a vmath.vector with a curve. (constant|vector)
     * @param duration duration of the animation in seconds (number)
     * @param [delay] delay before the animation starts in seconds (number)
     * @param [complete_function] function to call when the animation has completed (function)
     * @examples
     * <p>Animate the position of a game object to x = 10 during 1 second, then y = 20 during 1 second:</p>
     * <pre>
     * local function x_done(self, url, property)
     *     go.animate(go.get_id(), "position.y", go.PLAYBACK_ONCE_FORWARD, 20, go.EASING_LINEAR, 1)
     * end
     * function init(self)
     *     go.animate(go.get_id(), "position.x", go.PLAYBACK_ONCE_FORWARD, 10, go.EASING_LINEAR, 1, 0, x_done)
     * end
     * </pre>
     *
     * <p>Animate the y positin of a game object using a crazy custom easing curve:</p>
     * <pre>
     * function init(self)
     *     local values = { 0, 0, 0, 0, 0, 0, 0, 0,
     *                      1, 1, 1, 1, 1, 1, 1, 1,
     *                      0, 0, 0, 0, 0, 0, 0, 0,
     *                      1, 1, 1, 1, 1, 1, 1, 1,
     *                      0, 0, 0, 0, 0, 0, 0, 0,
     *                      1, 1, 1, 1, 1, 1, 1, 1,
     *                      0, 0, 0, 0, 0, 0, 0, 0,
     *                      1, 1, 1, 1, 1, 1, 1, 1 }
     *      local vec = vmath.vector(values)
     *      go.animate("go", "position.y", go.PLAYBACK_LOOP_PINGPONG, 100, vec, 2.0)
     * end
     * </pre>
     */
    int Script_Animate(lua_State* L)
    {
        int top = lua_gettop(L);
        (void)top;

        ScriptInstance* i = ScriptInstance_Check(L);
        Instance* instance = i->m_Instance;
        dmMessage::URL sender;
        dmScript::GetURL(L, &sender);
        dmMessage::URL target;
        dmScript::ResolveURL(L, 1, &target, &sender);
        HCollection collection = dmGameObject::GetCollection(instance);
        if (target.m_Socket != dmGameObject::GetMessageSocket(collection))
        {
            luaL_error(L, "go.animate can only animate instances within the same collection.");
        }
        dmhash_t property_id = 0;
        if (lua_isstring(L, 2))
        {
            property_id = dmHashString64(lua_tostring(L, 2));
        }
        else
        {
            property_id = dmScript::CheckHash(L, 2);
        }
        dmGameObject::HInstance target_instance = dmGameObject::GetInstanceFromIdentifier(collection, target.m_Path);
        if (target_instance == 0)
            return luaL_error(L, "Could not find any instance with id '%s'.", (const char*)dmHashReverse64(target.m_Path, 0x0));
        lua_Integer playback = luaL_checkinteger(L, 3);
        if (playback >= PLAYBACK_COUNT)
            return luaL_error(L, "invalid playback mode when starting an animation");
        dmGameObject::PropertyVar property_var;
        dmGameObject::PropertyResult result = dmGameObject::LuaToVar(L, 4, property_var);
        if (result != PROPERTY_RESULT_OK)
        {
            return luaL_error(L, "only numerical values can be used as target values for animation");
        }

        dmEasing::Curve curve;
        if (lua_isnumber(L, 5))
        {
            curve.type = (dmEasing::Type)luaL_checkinteger(L, 5);
            if (curve.type >= dmEasing::TYPE_COUNT)
                return luaL_error(L, "invalid easing constant");
        }
        else if (dmScript::IsVector(L, 5))
        {
            curve.type = dmEasing::TYPE_FLOAT_VECTOR;
            curve.vector = dmScript::CheckVector(L, 5);

            lua_pushvalue(L, 5);
            curve.release_callback = LuaCurveRelease;
            curve.userdata1 = i;
            curve.userdata2 = (void*)luaL_ref(L, LUA_REGISTRYINDEX);
        }
        else
        {
            return luaL_error(L, "easing must be either a easing constant or a vmath.vector");
        }

        float duration = (float) luaL_checknumber(L, 6);
        float delay = 0.0f;
        if (top > 6)
            delay = (float) luaL_checknumber(L, 7);
        AnimationStopped stopped = 0x0;
        void* userdata1 = i;
        void* userdata2 = 0x0;
        if (top > 7)
        {
            if (lua_isfunction(L, 8))
            {
                stopped = LuaAnimationStopped;
                lua_pushvalue(L, 8);
                userdata2 = (void*)luaL_ref(L, LUA_REGISTRYINDEX);
            }
        }

        result = dmGameObject::Animate(collection, target_instance, target.m_Fragment, property_id,
                (Playback)playback, property_var, curve, duration, delay, stopped, userdata1, userdata2);
        switch (result)
        {
        case dmGameObject::PROPERTY_RESULT_OK:
            break;
        case PROPERTY_RESULT_NOT_FOUND:
            {
                lua_pushliteral(L, "");
                dmScript::PushURL(L, target);
                lua_concat(L, 2);
                const char* name = lua_tostring(L, -1);
                lua_pop(L, 1);
                return luaL_error(L, "'%s' does not have any property called '%s'", name, (const char*)dmHashReverse64(property_id, 0x0));
            }
        case PROPERTY_RESULT_UNSUPPORTED_TYPE:
        case PROPERTY_RESULT_TYPE_MISMATCH:
            {
                lua_pushliteral(L, "");
                dmScript::PushURL(L, target);
                lua_concat(L, 2);
                const char* name = lua_tostring(L, -1);
                lua_pop(L, 1);
                return luaL_error(L, "The property '%s' of '%s' has incorrect type", (const char*)dmHashReverse64(property_id, 0x0), name);
            }
        case dmGameObject::PROPERTY_RESULT_COMP_NOT_FOUND:
            return luaL_error(L, "could not find component '%s' when resolving '%s'", (const char*)dmHashReverse64(target.m_Fragment, 0x0), lua_tostring(L, 1));
        case dmGameObject::PROPERTY_RESULT_UNSUPPORTED_OPERATION:
            {
                lua_pushliteral(L, "");
                dmScript::PushURL(L, target);
                lua_concat(L, 2);
                const char* name = lua_tostring(L, -1);
                lua_pop(L, 1);
                return luaL_error(L, "Animation of the property '%s' of '%s' is unsupported", (const char*)dmHashReverse64(property_id, 0x0), name);
            }
        default:
            // Should never happen, programmer error
            return luaL_error(L, "go.animate failed with error code %d", result);
        }

        assert(lua_gettop(L) == top);
        return 0;
    }

    /*# cancels all animations of the named property of the specified game object or component
     * <p>
     * By calling this function, all stored animations of the given property will be canceled.
     * </p>
     * <p>
     * See the <a href="/doc/properties">properties guide</a> for which properties can be animated and how.
     * </p>
     *
     * @name go.cancel_animations
     * @param url url of the game object or component having the property (hash|string|url)
     * @param property name of the property to animate (hash|string)
     * @examples
     * <p>Cancel the animation of the position of a game object:</p>
     * <pre>
     * go.cancel_animations(go.get_id(), "position")
     * </pre>
     */
    int Script_CancelAnimations(lua_State* L)
    {
        int top = lua_gettop(L);
        (void)top;

        ScriptInstance* i = ScriptInstance_Check(L);
        Instance* instance = i->m_Instance;
        dmMessage::URL sender;
        dmScript::GetURL(L, &sender);
        dmMessage::URL target;
        dmScript::ResolveURL(L, 1, &target, &sender);
        HCollection collection = dmGameObject::GetCollection(instance);
        if (target.m_Socket != dmGameObject::GetMessageSocket(collection))
        {
            luaL_error(L, "go.animate can only animate instances within the same collection.");
        }
        dmhash_t property_id = 0;
        if (lua_isstring(L, 2))
        {
            property_id = dmHashString64(lua_tostring(L, 2));
        }
        else
        {
            property_id = dmScript::CheckHash(L, 2);
        }
        dmGameObject::HInstance target_instance = dmGameObject::GetInstanceFromIdentifier(collection, target.m_Path);
        if (target_instance == 0)
            return luaL_error(L, "Could not find any instance with id '%s'.", (const char*)dmHashReverse64(target.m_Path, 0x0));
        dmGameObject::PropertyResult res = dmGameObject::CancelAnimations(collection, target_instance, target.m_Fragment, property_id);

        switch (res)
        {
        case dmGameObject::PROPERTY_RESULT_OK:
            break;
        case PROPERTY_RESULT_NOT_FOUND:
            {
                lua_pushliteral(L, "");
                dmScript::PushURL(L, target);
                lua_concat(L, 2);
                const char* name = lua_tostring(L, -1);
                lua_pop(L, 1);
                return luaL_error(L, "'%s' does not have any property called '%s'", name, (const char*)dmHashReverse64(property_id, 0x0));
            }
        case PROPERTY_RESULT_UNSUPPORTED_TYPE:
        case PROPERTY_RESULT_TYPE_MISMATCH:
            {
                dmGameObject::PropertyDesc property_desc;
                dmGameObject::GetProperty(target_instance, target.m_Fragment, property_id, property_desc);
                return luaL_error(L, "The property '%s' of '%s' must be of a numerical type", (const char*)dmHashReverse64(property_id, 0x0));
            }
        case dmGameObject::PROPERTY_RESULT_COMP_NOT_FOUND:
            return luaL_error(L, "could not find component '%s' when resolving '%s'", (const char*)dmHashReverse64(target.m_Fragment, 0x0), lua_tostring(L, 1));
        default:
            // Should never happen, programmer error
            return luaL_error(L, "go.cancel_animations failed with error code %d", res);
        }

        assert(lua_gettop(L) == top);
        return 0;
    }

    /*# deletes a game object instance
     * <div>Delete a game object identified by its id.</div>
     *
     * @name go.delete
     * @param [id] optional id of the instance to delete, the instance of the calling script is deleted by default (hash|string|url)
     * @examples
     * <p>
     * This example demonstrates how to delete a game object with the id "my_game_object".
     * </p>
     * <pre>
     * local id = go.get_id("my_game_object") -- retrieve the id of the game object to be deleted
     * go.delete(id) -- delete the game object
     * </pre>
     */
    int Script_Delete(lua_State* L)
    {
        if (lua_gettop(L) >= 1 && lua_type(L, 1) == LUA_TNIL) {
            dmLogWarning("go.delete() invoked with nil and self will be deleted");
        }
        dmGameObject::HInstance instance = ResolveInstance(L, 1);
        if(dmGameObject::IsBone(instance))
        {
            return luaL_error(L, "Can not delete subinstances of spine components. '%s'", (const char*)dmHashReverse64(dmGameObject::GetIdentifier(instance), 0x0));
        }
        dmGameObject::HCollection collection = instance->m_Collection;
        dmGameObject::Delete(collection, instance);
        return 0;
    }

    /*# deletes a set of game object instance
     * <div>Delete all game objects simultaneously as listed in table.
     * The table values (not keys) should be game object ids (hashes).</div>
     *
     * @name go.delete_all
     * @param [ids] table with values of instance ids (hashes) to be deleted
     * @examples
     * <p>
     * An example how to delete game objects listed in a table
     * </p>
     * <pre>
     * -- List the objects to be deleted
     * local ids = { hash("/my_object_1"), hash("/my_object_2"), hash("/my_object_3") }
     * go.delete_all(ids)
     * </pre>
     * <p>
     * An example how to delete game objects spawned via a collectionfactory
     * </p>
     * <pre>
     * -- Spawn a collection of game objects.
     * local ids = collectionfactory.create("#collectionfactory")
     * ...
     * -- Delete all objects listed in the table 'ids'.
     * go.delete_all(ids)
     * </pre>
     */
    int Script_DeleteAll(lua_State* L)
    {
        const int top = lua_gettop(L);
        if (lua_gettop(L) != 1 || !lua_istable(L, 1)) {
            dmLogWarning("go.delete_all() needs a table as its first argument");
            return 0;
        }

        ScriptInstance* i = ScriptInstance_Check(L);
        Instance* instance = i->m_Instance;

        // read table
        lua_pushnil(L);
        while (lua_next(L, 1)) {

            // value should be hashes
            dmMessage::URL receiver;
            dmScript::ResolveURL(L, -1, &receiver, 0x0);
            if (receiver.m_Socket != dmGameObject::GetMessageSocket(i->m_Instance->m_Collection))
            {
                luaL_error(L, "function called can only access instances within the same collection.");
            }

            Instance *todelete = GetInstanceFromIdentifier(instance->m_Collection, receiver.m_Path);
            if (todelete)
            {
                if(dmGameObject::IsBone(todelete))
                {
                    return luaL_error(L, "Can not delete subinstances of spine components. '%s'", (const char*)dmHashReverse64(dmGameObject::GetIdentifier(todelete), 0x0));
                }
                dmGameObject::HCollection collection = todelete->m_Collection;
                dmGameObject::Delete(collection, todelete);
            }
            else
            {
                dmLogWarning("go.delete_all(): instance could not be resolved");
            }

            lua_pop(L, 1);
        }

        assert(top == lua_gettop(L));
        return 0;
    }

    /*# constructs a ray in world space from a position in screen space
     *
     * NOTE! Don't use this function, WIP!
     *
     * @name go.screen_ray
     * @param x x-coordinate of the screen space position (number)
     * @param y y-coordinate of the screen space position (number)
     * @return position and direction of the ray in world space (vector3, vector3)
     */
    int Script_ScreenRay(lua_State* L)
    {
        lua_Number x = luaL_checknumber(L, 1);
        lua_Number y = luaL_checknumber(L, 2);
        // TODO: This temporarily assumes the worldspace is simply screen space
        // Should be fixed in a more robust way.
        Vector3 p((float) x, (float) y, 1.0f);
        Vector3 d(0.0f, 0.0f, -1.0f);
        dmScript::PushVector3(L, p);
        dmScript::PushVector3(L, d);
        return 2;
    }

    /*# define a property to be used throughout the script
     * This function defines a property which can then be used in the script through the self-reference.
     * The properties defined this way are automatically exposed in the editor in game objects and collections which use the script.
     * Note that you can only use this function outside any callback-functions like init and update.
     *
     * @name go.property
     * @param name the name of the property (string)
     * @param value default value of the property. In the case of a url, only the empty constructor msg.url() is allowed. (number|hash|url|vector3|vector4|quaternion)
     * @examples
     * <p>
     * This example demonstrates how to define a property called "health" in a script.
     * The health is decreased whenever someone sends a message called "take_damage" to the script.
     * </p>
     * <pre>
     * go.property("health", 100)
     *
     * function init(self)
     *     -- prints 100 to the output
     *     print(self.health)
     * end
     *
     * function on_message(self, message_id, message, sender)
     *     if message_id == hash("take_damage") then
     *         self.health = self.health - message.damage
     *         print("Ouch! My health is now: " .. self.health)
     *     end
     * end
     * </pre>
     */
    int Script_Property(lua_State* L)
    {
        int top = lua_gettop(L);
        (void)top;

        Script* script = GetScript(L);

        if (script == 0x0)
        {
            return luaL_error(L, "go.property can only be called outside the functions.");
        }

        const char* id = luaL_checkstring(L, 1);
        (void)id;

        bool valid_type = false;
        if (lua_isnumber(L, 2))
        {
            valid_type = true;
        }
        else if (dmScript::IsURL(L, 2))
        {
            valid_type = true;
        }
        else if (dmScript::IsHash(L, 2))
        {
            valid_type = true;
        }
        else if (dmScript::IsVector3(L, 2))
        {
            valid_type = true;
        }
        else if (dmScript::IsVector4(L, 2))
        {
            valid_type = true;
        }
        else if (dmScript::IsQuat(L, 2))
        {
            valid_type = true;
        }
        else if (lua_isboolean(L, 2))
        {
            valid_type = true;
        }
        if (!valid_type)
        {
            return luaL_error(L, "Invalid type (%s) supplied to go.property, must be either a number, boolean, hash, URL, vector3, vector4 or quaternion.", lua_typename(L, lua_type(L, 2)));
        }
        assert(top == lua_gettop(L));
        return 0;
    }

    static const luaL_reg GO_methods[] =
    {
        {"get",                 Script_Get},
        {"set",                 Script_Set},
        {"get_position",        Script_GetPosition},
        {"get_rotation",        Script_GetRotation},
        {"get_scale",           Script_GetScale},
        {"get_scale_vector",    Script_GetScaleVector},
        {"set_position",        Script_SetPosition},
        {"set_rotation",        Script_SetRotation},
        {"set_scale",           Script_SetScale},
        {"get_world_position",  Script_GetWorldPosition},
        {"get_world_rotation",  Script_GetWorldRotation},
        {"get_world_scale",     Script_GetWorldScale},
        {"get_id",              Script_GetId},
        {"animate",             Script_Animate},
        {"cancel_animations",   Script_CancelAnimations},
        {"delete",              Script_Delete},
        {"delete_all",          Script_DeleteAll},
        {"screen_ray",          Script_ScreenRay},
        {"property",            Script_Property},
        {0, 0}
    };

    void InitializeScript(dmScript::HContext context)
    {
        lua_State* L = dmScript::GetLuaState(context);

        int top = lua_gettop(L);
        (void)top;

        dmScript::RegisterUserType(L, SCRIPT, Script_methods, Script_meta);

        dmScript::RegisterUserType(L, SCRIPTINSTANCE, ScriptInstance_methods, ScriptInstance_meta);

        luaL_register(L, "go", GO_methods);

#define SETPLAYBACK(name) \
        lua_pushnumber(L, (lua_Number) PLAYBACK_##name); \
        lua_setfield(L, -2, "PLAYBACK_"#name);\

        SETPLAYBACK(NONE)
        SETPLAYBACK(ONCE_FORWARD)
        SETPLAYBACK(ONCE_BACKWARD)
        SETPLAYBACK(ONCE_PINGPONG)
        SETPLAYBACK(LOOP_FORWARD)
        SETPLAYBACK(LOOP_BACKWARD)
        SETPLAYBACK(LOOP_PINGPONG)

#undef SETPLAYBACK

#define SETEASING(name) \
        lua_pushnumber(L, (lua_Number) dmEasing::TYPE_##name); \
        lua_setfield(L, -2, "EASING_"#name);\

        SETEASING(LINEAR)
        SETEASING(INQUAD)
        SETEASING(OUTQUAD)
        SETEASING(INOUTQUAD)
        SETEASING(OUTINQUAD)
        SETEASING(INCUBIC)
        SETEASING(OUTCUBIC)
        SETEASING(INOUTCUBIC)
        SETEASING(OUTINCUBIC)
        SETEASING(INQUART)
        SETEASING(OUTQUART)
        SETEASING(INOUTQUART)
        SETEASING(OUTINQUART)
        SETEASING(INQUINT)
        SETEASING(OUTQUINT)
        SETEASING(INOUTQUINT)
        SETEASING(OUTINQUINT)
        SETEASING(INSINE)
        SETEASING(OUTSINE)
        SETEASING(INOUTSINE)
        SETEASING(OUTINSINE)
        SETEASING(INEXPO)
        SETEASING(OUTEXPO)
        SETEASING(INOUTEXPO)
        SETEASING(OUTINEXPO)
        SETEASING(INCIRC)
        SETEASING(OUTCIRC)
        SETEASING(INOUTCIRC)
        SETEASING(OUTINCIRC)
        SETEASING(INELASTIC)
        SETEASING(OUTELASTIC)
        SETEASING(INOUTELASTIC)
        SETEASING(OUTINELASTIC)
        SETEASING(INBACK)
        SETEASING(OUTBACK)
        SETEASING(INOUTBACK)
        SETEASING(OUTINBACK)
        SETEASING(INBOUNCE)
        SETEASING(OUTBOUNCE)
        SETEASING(INOUTBOUNCE)
        SETEASING(OUTINBOUNCE)

#undef SETEASING

        lua_pop(L, 1);

        assert(top == lua_gettop(L));
    }

    static bool LoadScript(lua_State* L, dmLuaDDF::LuaSource *source, Script* script)
    {
        for (uint32_t i = 0; i < MAX_SCRIPT_FUNCTION_COUNT; ++i)
            script->m_FunctionReferences[i] = LUA_NOREF;

        bool result = false;
        int top = lua_gettop(L);
        (void) top;

        int ret = dmScript::LuaLoad(L, source);
        if (ret == 0)
        {
            lua_rawgeti(L, LUA_REGISTRYINDEX, script->m_InstanceReference);
            dmScript::SetInstance(L);

            ret = dmScript::PCall(L, 0, LUA_MULTRET);
            if (ret == 0)
            {
                for (uint32_t i = 0; i < MAX_SCRIPT_FUNCTION_COUNT; ++i)
                {
                    lua_getglobal(L, SCRIPT_FUNCTION_NAMES[i]);
                    if (lua_isnil(L, -1) == 0)
                    {
                        if (lua_type(L, -1) == LUA_TFUNCTION)
                        {
                            script->m_FunctionReferences[i] = luaL_ref(L, LUA_REGISTRYINDEX);
                        }
                        else
                        {
                            dmLogError("The global name '%s' in '%s' must be a function.", SCRIPT_FUNCTION_NAMES[i], source->m_Filename);
                            lua_pop(L, 1);
                            goto bail;
                        }
                    }
                    else
                    {
                        script->m_FunctionReferences[i] = LUA_NOREF;
                        lua_pop(L, 1);
                    }
                }
                result = true;
            }
            lua_pushnil(L);
            dmScript::SetInstance(L);
        } else {
            dmLogError("Error running script: %s", lua_tostring(L,-1));
            lua_pop(L, 1);
        }
bail:
        for (uint32_t i = 0; i < MAX_SCRIPT_FUNCTION_COUNT; ++i)
        {
            lua_pushnil(L);
            lua_setglobal(L, SCRIPT_FUNCTION_NAMES[i]);
        }
        assert(top == lua_gettop(L));
        return result;
    }

    static PropertyResult GetPropertyDefault(const HProperties properties, uintptr_t user_data, dmhash_t id, PropertyVar& out_var);

    static void ResetScript(HScript script) {
        memset(script, 0, sizeof(Script));
        for (uint32_t i = 0; i < MAX_SCRIPT_FUNCTION_COUNT; ++i) {
            script->m_FunctionReferences[i] = LUA_NOREF;
        }
        script->m_InstanceReference = LUA_NOREF;
    }

    HScript NewScript(lua_State* L, dmLuaDDF::LuaModule* lua_module)
    {
        Script* script = (Script*)lua_newuserdata(L, sizeof(Script));
        ResetScript(script);
        script->m_LuaState = L;

        lua_pushvalue(L, -1);
        script->m_InstanceReference = luaL_ref(L, LUA_REGISTRYINDEX);

        script->m_PropertySet.m_UserData = (uintptr_t)script;
        script->m_PropertySet.m_GetPropertyCallback = GetPropertyDefault;
        script->m_LuaModule = lua_module;
        luaL_getmetatable(L, SCRIPT);
        lua_setmetatable(L, -2);

        if (!LoadScript(L, &lua_module->m_Source, script))
        {
            DeleteScript(script);
            return 0;
        }

        lua_pop(L, 1);
        return script;
    }

    bool ReloadScript(HScript script, dmLuaDDF::LuaModule* lua_module)
    {
        script->m_LuaModule = lua_module;
        return LoadScript(script->m_LuaState, &lua_module->m_Source, script);
    }

    void DeleteScript(HScript script)
    {
        lua_State* L = script->m_LuaState;
        for (uint32_t i = 0; i < MAX_SCRIPT_FUNCTION_COUNT; ++i)
        {
            if (script->m_FunctionReferences[i] != LUA_NOREF) {
                luaL_unref(L, LUA_REGISTRYINDEX, script->m_FunctionReferences[i]);
            }
        }

        luaL_unref(L, LUA_REGISTRYINDEX, script->m_InstanceReference);
        script->~Script();
        ResetScript(script);
    }

    static PropertyResult GetPropertyDefault(const HProperties properties, uintptr_t user_data, dmhash_t id, PropertyVar& out_var)
    {
        Script* script = (Script*)user_data;
        const PropertyDeclarations* defs = &script->m_LuaModule->m_Properties;
        uint32_t n = defs->m_NumberEntries.m_Count;
        for (uint32_t i = 0; i < n; ++i)
        {
            const PropertyDeclarationEntry& entry = defs->m_NumberEntries[i];
            if (entry.m_Id == id)
            {
                out_var.m_Type = PROPERTY_TYPE_NUMBER;
                out_var.m_Number = defs->m_FloatValues[entry.m_Index];
                return PROPERTY_RESULT_OK;
            }
        }
        n = defs->m_HashEntries.m_Count;
        for (uint32_t i = 0; i < n; ++i)
        {
            const PropertyDeclarationEntry& entry = defs->m_HashEntries[i];
            if (entry.m_Id == id)
            {
                out_var.m_Type = PROPERTY_TYPE_HASH;
                out_var.m_Hash = defs->m_HashValues[entry.m_Index];
                return PROPERTY_RESULT_OK;
            }
        }
        n = defs->m_UrlEntries.m_Count;
        for (uint32_t i = 0; i < n; ++i)
        {
            const PropertyDeclarationEntry& entry = defs->m_UrlEntries[i];
            if (entry.m_Id == id)
            {
                out_var.m_Type = PROPERTY_TYPE_URL;
                dmMessage::URL default_url;
                lua_State* L = (lua_State*)properties->m_ResolvePathUserData;
                properties->m_GetURLCallback(L, &default_url);
                const char* url_string = defs->m_StringValues[entry.m_Index];
                dmMessage::Result result = dmScript::ResolveURL(L, url_string, (dmMessage::URL*) out_var.m_URL, &default_url);
                if (result != dmMessage::RESULT_OK)
                {
                    return PROPERTY_RESULT_INVALID_FORMAT;
                }
                return PROPERTY_RESULT_OK;
            }
        }
        n = defs->m_Vector3Entries.m_Count;
        for (uint32_t i = 0; i < n; ++i)
        {
            const PropertyDeclarationEntry& entry = defs->m_Vector3Entries[i];
            if (entry.m_Id == id)
            {
                out_var.m_Type = PROPERTY_TYPE_VECTOR3;
                const float* v = &defs->m_FloatValues[entry.m_Index];
                out_var.m_V4[0] = v[0];
                out_var.m_V4[1] = v[1];
                out_var.m_V4[2] = v[2];
                return PROPERTY_RESULT_OK;
            }
        }
        n = defs->m_Vector4Entries.m_Count;
        for (uint32_t i = 0; i < n; ++i)
        {
            const PropertyDeclarationEntry& entry = defs->m_Vector4Entries[i];
            if (entry.m_Id == id)
            {
                out_var.m_Type = PROPERTY_TYPE_VECTOR4;
                const float* v = &defs->m_FloatValues[entry.m_Index];
                out_var.m_V4[0] = v[0];
                out_var.m_V4[1] = v[1];
                out_var.m_V4[2] = v[2];
                out_var.m_V4[3] = v[3];
                return PROPERTY_RESULT_OK;
            }
        }
        n = defs->m_QuatEntries.m_Count;
        for (uint32_t i = 0; i < n; ++i)
        {
            const PropertyDeclarationEntry& entry = defs->m_QuatEntries[i];
            if (entry.m_Id == id)
            {
                out_var.m_Type = PROPERTY_TYPE_QUAT;
                const float* v = &defs->m_FloatValues[entry.m_Index];
                out_var.m_V4[0] = v[0];
                out_var.m_V4[1] = v[1];
                out_var.m_V4[2] = v[2];
                out_var.m_V4[3] = v[3];
                return PROPERTY_RESULT_OK;
            }
        }
        n = defs->m_BoolEntries.m_Count;
        for (uint32_t i = 0; i < n; ++i)
        {
            const PropertyDeclarationEntry& entry = defs->m_BoolEntries[i];
            if (entry.m_Id == id)
            {
                out_var.m_Type = PROPERTY_TYPE_BOOLEAN;
                out_var.m_Bool = defs->m_FloatValues[entry.m_Index] != 0.0f;
                return PROPERTY_RESULT_OK;
            }
        }
        return PROPERTY_RESULT_NOT_FOUND;
    }

    static void ResetScriptInstance(HScriptInstance script_instance) {
        memset(script_instance, 0, sizeof(ScriptInstance));
        script_instance->m_InstanceReference = LUA_NOREF;
        script_instance->m_ScriptDataReference = LUA_NOREF;
    }

    HScriptInstance NewScriptInstance(HScript script, HInstance instance, uint8_t component_index)
    {
        lua_State* L = script->m_LuaState;

        int top = lua_gettop(L);
        (void) top;

        ScriptInstance* i = (ScriptInstance *)lua_newuserdata(L, sizeof(ScriptInstance));
        ResetScriptInstance(i);
        i->m_Script = script;

        lua_pushvalue(L, -1);
        i->m_InstanceReference = luaL_ref( L, LUA_REGISTRYINDEX );

        lua_newtable(L);
        i->m_ScriptDataReference = luaL_ref( L, LUA_REGISTRYINDEX );

        i->m_Instance = instance;
        i->m_ComponentIndex = component_index;
        NewPropertiesParams params;
        params.m_ResolvePathCallback = ScriptInstanceResolvePathCB;
        params.m_ResolvePathUserData = (uintptr_t)L;
        params.m_GetURLCallback = ScriptInstanceGetURLCB;
        i->m_Properties = NewProperties(params);
        SetPropertySet(i->m_Properties, PROPERTY_LAYER_DEFAULT, script->m_PropertySet);
        luaL_getmetatable(L, SCRIPTINSTANCE);
        lua_setmetatable(L, -2);

        lua_pop(L, 1);

        assert(top == lua_gettop(L));

        return i;
    }

    void DeleteScriptInstance(HScriptInstance script_instance)
    {
        HCollection collection = script_instance->m_Instance->m_Collection;
        CancelAnimationCallbacks(collection, script_instance);

        lua_State* L = GetLuaState(script_instance);

        int top = lua_gettop(L);
        (void) top;

        luaL_unref(L, LUA_REGISTRYINDEX, script_instance->m_InstanceReference);
        luaL_unref(L, LUA_REGISTRYINDEX, script_instance->m_ScriptDataReference);

        DeleteProperties(script_instance->m_Properties);
        script_instance->~ScriptInstance();
        ResetScriptInstance(script_instance);

        assert(top == lua_gettop(L));
    }

const char* TYPE_NAMES[PROPERTY_TYPE_COUNT] = {
        "number", // PROPERTY_TYPE_NUMBER
        "hash", // PROPERTY_TYPE_HASH
        "msg.url", // PROPERTY_TYPE_URL
        "vmath.vector3", // PROPERTY_TYPE_VECTOR3
        "vmath.vector4", // PROPERTY_TYPE_VECTOR4
        "vmath.quat", // PROPERTY_TYPE_QUAT
        "boolean", // PROPERTY_TYPE_BOOLEAN
};

#define CHECK_PROP_RESULT(key, type, expected_type, result)\
    if (result == PROPERTY_RESULT_OK) {\
        if (type != expected_type) {\
            dmLogError("The property '%s' must be of type '%s'.", key, TYPE_NAMES[expected_type]);\
            result = PROPERTY_RESULT_TYPE_MISMATCH;\
        }\
    }\
    if (result != PROPERTY_RESULT_OK) {\
        return result;\
    }

    PropertyResult PropertiesToLuaTable(HInstance instance, HScript script, const HProperties properties, lua_State* L, int index)
    {
        const PropertyDeclarations* declarations = &script->m_LuaModule->m_Properties;
        PropertyVar var;
        uint32_t count = declarations->m_NumberEntries.m_Count;
        for (uint32_t i = 0; i < count; ++i)
        {
            const PropertyDeclarationEntry& entry = declarations->m_NumberEntries[i];
            PropertyResult result = GetProperty(properties, entry.m_Id, var);
            CHECK_PROP_RESULT(entry.m_Key, var.m_Type, PROPERTY_TYPE_NUMBER, result)
            lua_pushstring(L, entry.m_Key);
            lua_pushnumber(L, var.m_Number);
            lua_settable(L, index - 2);
        }
        count = declarations->m_HashEntries.m_Count;
        for (uint32_t i = 0; i < count; ++i)
        {
            const PropertyDeclarationEntry& entry = declarations->m_HashEntries[i];
            PropertyResult result = GetProperty(properties, entry.m_Id, var);
            CHECK_PROP_RESULT(entry.m_Key, var.m_Type, PROPERTY_TYPE_HASH, result)
            lua_pushstring(L, entry.m_Key);
            dmScript::PushHash(L, var.m_Hash);
            lua_settable(L, index - 2);
        }
        count = declarations->m_UrlEntries.m_Count;
        for (uint32_t i = 0; i < count; ++i)
        {
            /*
             * NOTE/TODO: var above is reused and URL::m_Function must
             * always be zero or a valid lua-reference. By reusing a union-type here, PropertyVar,
             * m_Function could have an invalid value. We could move PropertyVar var inside every
             * loop but the problem and risk is illustrated here.
             */
            var = PropertyVar();
            const PropertyDeclarationEntry& entry = declarations->m_UrlEntries[i];
            PropertyResult result = GetProperty(properties, entry.m_Id, var);
            CHECK_PROP_RESULT(entry.m_Key, var.m_Type, PROPERTY_TYPE_URL, result)
            lua_pushstring(L, entry.m_Key);
            dmMessage::URL* url = (dmMessage::URL*) var.m_URL;
            dmScript::PushURL(L, *url);
            lua_settable(L, index - 2);
        }
        count = declarations->m_Vector3Entries.m_Count;
        for (uint32_t i = 0; i < count; ++i)
        {
            const PropertyDeclarationEntry& entry = declarations->m_Vector3Entries[i];
            PropertyResult result = GetProperty(properties, entry.m_Id, var);
            CHECK_PROP_RESULT(entry.m_Key, var.m_Type, PROPERTY_TYPE_VECTOR3, result)
            lua_pushstring(L, entry.m_Key);
            dmScript::PushVector3(L, Vector3(var.m_V4[0], var.m_V4[1], var.m_V4[2]));
            lua_settable(L, index - 2);
        }
        count = declarations->m_Vector4Entries.m_Count;
        for (uint32_t i = 0; i < count; ++i)
        {
            const PropertyDeclarationEntry& entry = declarations->m_Vector4Entries[i];
            PropertyResult result = GetProperty(properties, entry.m_Id, var);
            CHECK_PROP_RESULT(entry.m_Key, var.m_Type, PROPERTY_TYPE_VECTOR4, result)
            lua_pushstring(L, entry.m_Key);
            dmScript::PushVector4(L, Vector4(var.m_V4[0], var.m_V4[1], var.m_V4[2], var.m_V4[3]));
            lua_settable(L, index - 2);
        }
        count = declarations->m_QuatEntries.m_Count;
        for (uint32_t i = 0; i < count; ++i)
        {
            const PropertyDeclarationEntry& entry = declarations->m_QuatEntries[i];
            PropertyResult result = GetProperty(properties, entry.m_Id, var);
            CHECK_PROP_RESULT(entry.m_Key, var.m_Type, PROPERTY_TYPE_QUAT, result)
            lua_pushstring(L, entry.m_Key);
            dmScript::PushQuat(L, Quat(var.m_V4[0], var.m_V4[1], var.m_V4[2], var.m_V4[3]));
            lua_settable(L, index - 2);
        }
        count = declarations->m_BoolEntries.m_Count;
        for (uint32_t i = 0; i < count; ++i)
        {
            const PropertyDeclarationEntry& entry = declarations->m_BoolEntries[i];
            PropertyResult result = GetProperty(properties, entry.m_Id, var);
            CHECK_PROP_RESULT(entry.m_Key, var.m_Type, PROPERTY_TYPE_BOOLEAN, result)
            lua_pushstring(L, entry.m_Key);
            lua_pushboolean(L, var.m_Bool);
            lua_settable(L, index - 2);
        }
        return PROPERTY_RESULT_OK;
    }

    // Documentation for the scripts

    /*# called when a script component is initialized
     * This is a callback-function, which is called by the engine when a script component is initialized. It can be used
     * to set the initial state of the script.
     *
     * @name init
     * @param self reference to the script state to be used for storing data (script_ref)
     * @examples
     * <pre>
     * function init(self)
     *     -- set up useful data
     *     self.my_value = 1
     * end
     * </pre>
     */

    /*# called when a script component is finalized
     * <div>This is a callback-function, which is called by the engine when a script component is finalized (destroyed). It can
     * be used to e.g. take some last action, report the finalization to other game object instances
     * or release user input focus (see <code>release_input_focus</code>).</div>
     *
     * <div><b>NOTE!</b> Don't call <a href="#go.delete">go.delete</a> from this function or in any <a href="#on_message">on_message</a> resulting from a message posted from this function. This will currently result in undefined behaviour.</div>
     *
     * @name final
     * @param self reference to the script state to be used for storing data (script_ref)
     * @examples
     * <pre>
     * function final(self)
     *     -- report finalization
     *     msg.post("my_friend_instance", "im_dead", {my_stats = self.some_value})
     * end
     * </pre>
     */

    /*# called every frame to update the script component
     * This is a callback-function, which is called by the engine every frame to update the state of a script component.
     * It can be used to perform any kind of game related tasks, e.g. moving the game object instance.
     *
     * @name update
     * @param self reference to the script state to be used for storing data (script_ref)
     * @param dt the time-step of the frame update
     * @examples
     * <p>
     * This example demonstrates how to move a game object instance through the script component:
     * </p>
     * <pre>
     * function init(self)
     *     -- set initial velocity to be 1 along world x-axis
     *     self.my_velocity = vmath.vector3(1, 0, 0)
     * end
     *
     * function update(self, dt)
     *     -- move the game object instance
     *     go.set_position(go.get_position() + dt * self.my_velocity)
     * end
     * </pre>
     */

    /*# called when a message has been sent to the script component
     * <p>
     * This is a callback-function, which is called by the engine whenever a message has been sent to the script component.
     * It can be used to take action on the message, e.g. send a response back to the sender of the message.
     * </p>
     * <p>
     * The <code>message</code> parameter is a table containing the message data. If the message is sent from the engine, the
     * documentation of the message specifies which data is supplied.
     * </p>
     *
     * @name on_message
     * @param self reference to the script state to be used for storing data (script_ref)
     * @param message_id id of the received message (hash)
     * @param message a table containing the message data (table)
     * @param sender address of the sender (url)
     * @examples
     * <p>
     * This example demonstrates how a game object instance, called "a", can communicate with another instance, called "b". It
     * is assumed that both script components of the instances has id "script".
     * </p>
     * <p>
     * Script of instance "a":
     * </p>
     * <pre>
     * function init(self)
     *     -- let b know about some important data
     *     msg.post("b#script", "my_data", {important_value = 1})
     * end
     * </pre>
     * <p>
     * Script of intance "b":
     * </p>
     * <pre>
     * function init(self)
     *     -- store the url of instance "a" for later use, by specifying nil as socket we
     *     -- automatically use our own socket
     *     self.a_url = msg.url(nil, go.get_id("a"), "script")
     * end
     *
     * function on_message(self, message_id, message, sender)
     *     -- check message and sender
     *     if message_id == hash("my_data") and sender == self.a_url then
     *         -- use the data in some way
     *         self.important_value = message.important_value
     *     end
     * end
     * </pre>
     */

    /*# called when user input is received
     * <p>
     * This is a callback-function, which is called by the engine when user input is sent to the game object instance of the script.
     * It can be used to take action on the input, e.g. move the instance according to the input.
     * </p>
     * <p>
     * For an instance to obtain user input, it must first acquire input focuse through the message <code>acquire_input_focus</code>.
     * See the documentation of that message for more information.
     * </p>
     * <p>
     * The <code>action</code> parameter is a table containing data about the input mapped to the <code>action_id</code>.
     * For mapped actions it specifies the value of the input and if it was just pressed or released.
     * Actions are mapped to input in an input_binding-file.
     * </p>
     * <p>
     * Mouse movement is specifically handled and uses <code>nil</code> as its <code>action_id</code>.
     * The <code>action</code> only contains positional parameters in this case, such as x and y of the pointer.
     * </p>
     * <p>
     * Here is a brief description of the available table fields:
     * </p>
     * <table>
     *   <th>Field</th>
     *   <th>Description</th>
     *   <tr><td><code>value</code></td><td>The amount of input given by the user. This is usually 1 for buttons and 0-1 for analogue inputs. This is not present for mouse movement.</td></tr>
     *   <tr><td><code>pressed</code></td><td>If the input was pressed this frame, 0 for false and 1 for true. This is not present for mouse movement.</td></tr>
     *   <tr><td><code>released</code></td><td>If the input was released this frame, 0 for false and 1 for true. This is not present for mouse movement.</td></tr>
     *   <tr><td><code>repeated</code></td><td>If the input was repeated this frame, 0 for false and 1 for true. This is similar to how a key on a keyboard is repeated when you hold it down. This is not present for mouse movement.</td></tr>
     *   <tr><td><code>x</code></td><td>The x value of a pointer device, if present.</td></tr>
     *   <tr><td><code>y</code></td><td>The y value of a pointer device, if present.</td></tr>
     *   <tr><td><code>screen_x</code></td><td>The screen space x value of a pointer device, if present.</td></tr>
     *   <tr><td><code>screen_y</code></td><td>The screen space y value of a pointer device, if present.</td></tr>
     *   <tr><td><code>dx</code></td><td>The change in x value of a pointer device, if present.</td></tr>
     *   <tr><td><code>dy</code></td><td>The change in y value of a pointer device, if present.</td></tr>
     *   <tr><td><code>screen_dx</code></td><td>The change in screen space x value of a pointer device, if present.</td></tr>
     *   <tr><td><code>screen_dy</code></td><td>The change in screen space y value of a pointer device, if present.</td></tr>
     *   <tr><td><code>touch</code></td><td>List of touch input, one element per finger, if present. See table below about touch input</td></tr>
     * </table>
     *
     * <p>
     * Touch input table:
     * </p>
     * <table>
     *   <th>Field</th>
     *   <th>Description</th>
     *   <tr><td><code>pressed</code></td><td>True if the finger was pressed this frame.</td></tr>
     *   <tr><td><code>released</code></td><td>True if the finger was released this frame.</td></tr>
     *   <tr><td><code>tap_count</code></td><td>Number of taps, one for single, two for double-tap, etc</td></tr>
     *   <tr><td><code>x</code></td><td>The x touch location.</td></tr>
     *   <tr><td><code>y</code></td><td>The y touch location.</td></tr>
     *   <tr><td><code>dx</code></td><td>The change in x value.</td></tr>
     *   <tr><td><code>dy</code></td><td>The change in y value.</td></tr>
     *   <tr><td><code>acc_x</code></td><td>Accelerometer x value (if present).</td></tr>
     *   <tr><td><code>acc_y</code></td><td>Accelerometer y value (if present).</td></tr>
     *   <tr><td><code>acc_z</code></td><td>Accelerometer z value (if present).</td></tr>
     * </table>
     *
     * @name on_input
     * @param self reference to the script state to be used for storing data (script_ref)
     * @param action_id id of the received input action, as mapped in the input_binding-file (hash)
     * @param action a table containing the input data, see above for a description (table)
     * @return optional boolean to signal if the input should be consumed (not passed on to others) or not, default is false (boolean)
     * @examples
     * <p>
     * This example demonstrates how a game object instance can be moved as a response to user input.
     * </p>
     * <pre>
     * function init(self)
     *     -- acquire input focus
     *     msg.post(".", "acquire_input_focus")
     *     -- maximum speed the instance can be moved
     *     self.max_speed = 2
     *     -- velocity of the instance, initially zero
     *     self.velocity = vmath.vector3()
     * end
     *
     * function update(self, dt)
     *     -- move the instance
     *     go.set_position(go.get_position() + dt * self.velocity)
     * end
     *
     * function on_input(self, action_id, action)
     *     -- check for movement input
     *     if action_id == hash("right") then
     *         if action.released then -- reset velocity if input was released
     *             self.velocity = vmath.vector3()
     *         else -- update velocity
     *             self.velocity = vmath.vector3(action.value * self.max_speed, 0, 0)
     *         end
     *     end
     * end
     * </pre>
     */

    /*# called when the script component is reloaded
     * <p>
     * This is a callback-function, which is called by the engine when the script component is reloaded, e.g. from the editor.
     * It can be used for live development, e.g. to tweak constants or set up the state properly for the instance.
     * </p>
     *
     * @name on_reload
     * @param self reference to the script state to be used for storing data (script_ref)
     * @examples
     * <p>
     * This example demonstrates how to tweak the speed of a game object instance that is moved on user input.
     * </p>
     * <pre>
     * function init(self)
     *     -- acquire input focus
     *     msg.post(".", "acquire_input_focus")
     *     -- maximum speed the instance can be moved, this value is tweaked in the on_reload function below
     *     self.max_speed = 2
     *     -- velocity of the instance, initially zero
     *     self.velocity = vmath.vector3()
     * end
     *
     * function update(self, dt)
     *     -- move the instance
     *     go.set_position(go.get_position() + dt * self.velocity)
     * end
     *
     * function on_input(self, action_id, action)
     *     -- check for movement input
     *     if action_id == hash("right") then
     *         if action.released then -- reset velocity if input was released
     *             self.velocity = vmath.vector3()
     *         else -- update velocity
     *             self.velocity = vmath.vector3(action.value * self.max_speed, 0, 0)
     *         end
     *     end
     * end
     *
     * function on_reload(self)
     *     -- edit this value and reload the script component
     *     self.max_speed = 100
     * end
     * </pre>
     */

    /*# no playback
     *
     * @name go.PLAYBACK_NONE
     * @variable
     */
    /*# once forward
     *
     * @name go.PLAYBACK_ONCE_FORWARD
     * @variable
     */
    /*# once backward
     *
     * @name go.PLAYBACK_ONCE_BACKWARD
     * @variable
     */
    /*# once ping pong
     *
     * @name go.PLAYBACK_ONCE_PINGPONG
     * @variable
     */
    /*# loop forward
     *
     * @name go.PLAYBACK_LOOP_FORWARD
     * @variable
     */
    /*# loop backward
     *
     * @name go.PLAYBACK_LOOP_BACKWARD
     * @variable
     */
    /*# ping pong loop
     *
     * @name go.PLAYBACK_LOOP_PINGPONG
     * @variable
     */

    /*# linear interpolation
     *
     * @name go.EASING_LINEAR
     * @variable
     */
    /*# in-quadratic
     *
     * @name go.EASING_INQUAD
     * @variable
     */
    /*# out-quadratic
     *
     * @name go.EASING_OUTQUAD
     * @variable
     */
    /*# in-out-quadratic
     *
     * @name go.EASING_INOUTQUAD
     * @variable
     */
    /*# out-in-quadratic
     *
     * @name go.EASING_OUTINQUAD
     * @variable
     */
    /*# in-cubic
     *
     * @name go.EASING_INCUBIC
     * @variable
     */
    /*# out-cubic
     *
     * @name go.EASING_OUTCUBIC
     * @variable
     */
    /*# in-out-cubic
     *
     * @name go.EASING_INOUTCUBIC
     * @variable
     */
    /*# out-in-cubic
     *
     * @name go.EASING_OUTINCUBIC
     * @variable
     */
    /*# in-quartic
     *
     * @name go.EASING_INQUART
     * @variable
     */
    /*# out-quartic
     *
     * @name go.EASING_OUTQUART
     * @variable
     */
    /*# in-out-quartic
     *
     * @name go.EASING_INOUTQUART
     * @variable
     */
    /*# out-in-quartic
     *
     * @name go.EASING_OUTINQUART
     * @variable
     */
    /*# in-quintic
     *
     * @name go.EASING_INQUINT
     * @variable
     */
    /*# out-quintic
     *
     * @name go.EASING_OUTQUINT
     * @variable
     */
    /*# in-out-quintic
     *
     * @name go.EASING_INOUTQUINT
     * @variable
     */
    /*# out-in-quintic
     *
     * @name go.EASING_OUTINQUINT
     * @variable
     */
    /*# in-sine
     *
     * @name go.EASING_INSINE
     * @variable
     */
    /*# out-sine
     *
     * @name go.EASING_OUTSINE
     * @variable
     */
    /*# in-out-sine
     *
     * @name go.EASING_INOUTSINE
     * @variable
     */
    /*# out-in-sine
     *
     * @name go.EASING_OUTINSINE
     * @variable
     */
    /*# in-exponential
     *
     * @name go.EASING_INEXPO
     * @variable
     */
    /*# out-exponential
     *
     * @name go.EASING_OUTEXPO
     * @variable
     */
    /*# in-out-exponential
     *
     * @name go.EASING_INOUTEXPO
     * @variable
     */
    /*# out-in-exponential
     *
     * @name go.EASING_OUTINEXPO
     * @variable
     */
    /*# in-circlic
     *
     * @name go.EASING_INCIRC
     * @variable
     */
    /*# out-circlic
     *
     * @name go.EASING_OUTCIRC
     * @variable
     */
    /*# in-out-circlic
     *
     * @name go.EASING_INOUTCIRC
     * @variable
     */
    /*# out-in-circlic
     *
     * @name go.EASING_OUTINCIRC
     * @variable
     */
    /*# in-elastic
     *
     * @name go.EASING_INELASTIC
     * @variable
     */
    /*# out-elastic
     *
     * @name go.EASING_OUTELASTIC
     * @variable
     */
    /*# in-out-elastic
     *
     * @name go.EASING_INOUTELASTIC
     * @variable
     */
    /*# out-in-elastic
     *
     * @name go.EASING_OUTINELASTIC
     * @variable
     */
    /*# in-back
     *
     * @name go.EASING_INBACK
     * @variable
     */
    /*# out-back
     *
     * @name go.EASING_OUTBACK
     * @variable
     */
    /*# in-out-back
     *
     * @name go.EASING_INOUTBACK
     * @variable
     */
    /*# out-in-back
     *
     * @name go.EASING_OUTINBACK
     * @variable
     */
    /*# in-bounce
     *
     * @name go.EASING_INBOUNCE
     * @variable
     */
    /*# out-bounce
     *
     * @name go.EASING_OUTBOUNCE
     * @variable
     */
    /*# in-out-bounce
     *
     * @name go.EASING_INOUTBOUNCE
     * @variable
     */
    /*# out-in-bounce
     *
     * @name go.EASING_OUTINBOUNCE
     * @variable
     */
}
