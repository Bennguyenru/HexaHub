#include <float.h>
#include <stdio.h>
#include <assert.h>

#include <dlib/hash.h>
#include <dlib/log.h>
#include <dlib/math.h>

#include "gamesys.h"
#include "gamesys_ddf.h"
#include "../gamesys_private.h"
#include "../components/comp_factory.h"

#include "script_factory.h"

extern "C"
{
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

namespace dmGameSystem
{
    /*# Factory API documentation
     *
     * Functions for controlling factory components which are used to
     * dynamically spawn game objects into the runtime.
     *
     * @document
     * @name Factory
     * @namespace factory
     */

    /*# Unload resources previously loaded using factory.load
     * The URL identifies the factory component who's prototype's resources should be unloaded.
     *
     * This decreaase the reference count for each resource loaded with factory.load. If reference is zero, the resource is destroyed.
     *
     * Calling this function when the factory is not marked as dynamic loading does nothing.
     *
     * @name factory.unload
     * @param [url] [type:string|hash|url] the factory component to be used
     *
     * @examples
     *
     * How to unload resources of a factory prototype loaded with factory.load
     *
     * ```lua
     * factory.unload("#factory")
     * ```
     */
    int FactoryComp_Unload(lua_State* L)
    {
        int top = lua_gettop(L);
        dmGameObject::HInstance sender_instance = CheckGoInstance(L);
        dmGameObject::HCollection collection = dmGameObject::GetCollection(sender_instance);

        uintptr_t user_data;
        dmMessage::URL receiver;
        dmGameObject::GetComponentUserDataFromLua(L, 1, collection, FACTORY_EXT, &user_data, &receiver, 0);
        FactoryComponent* component = (FactoryComponent*) user_data;

        bool success = dmGameSystem::CompFactoryUnload(collection, component);
        if (!success)
        {
            return luaL_error(L, "Error unloading factory resources");
        }

        assert(top == lua_gettop(L));
        return 0;
    }


    /*# Load resources of a factory prototype into the existing collection.
     * The URL identifies the factory component who's prototype's resources should be loaded.
     *
     * Resources are referenced by the factory component until the existing (parent) collection is destroyed or factory.unload is called.
     *
     * Calling this function when the factory is not marked as dynamic loading does nothing.
     *
     * @name factory.load
     * @param [url] [type:string|hash|url] the factory component to be used
     * @param [complete_function] [type:function(self, result))] function to call when resources are loaded.
     *
     * `self`
     * : [type:object] The current object.
     *
     * `result`
     * : [type:boolean] True if resource were loaded successfully
     *
     * @examples
     *
     * How to load resources of a factory prototype into the existing collection.
     *
     * ```lua
     * factory.load("#factory", function(self, result) end)
     * ```
     */
    int FactoryComp_Load(lua_State* L)
    {
        int top = lua_gettop(L);
        dmGameObject::HInstance sender_instance = CheckGoInstance(L);
        dmGameObject::HCollection collection = dmGameObject::GetCollection(sender_instance);

        if (top < 2 || !lua_isfunction(L, 2))
        {
            return luaL_error(L, "Argument #2 is expected to be completion function.");
        }

        uintptr_t user_data;
        dmMessage::URL receiver;
        dmGameObject::GetComponentUserDataFromLua(L, 1, collection, FACTORY_EXT, &user_data, &receiver, 0);
        FactoryComponent* component = (FactoryComponent*) user_data;

        lua_pushvalue(L, 2);
        component->m_PreloaderCallbackRef = dmScript::Ref(L, LUA_REGISTRYINDEX);
        dmScript::GetInstance(L);
        component->m_PreloaderSelfRef = dmScript::Ref(L, LUA_REGISTRYINDEX);
        dmScript::PushURL(L, receiver);
        component->m_PreloaderURLRef = dmScript::Ref(L, LUA_REGISTRYINDEX);

        bool success = dmGameSystem::CompFactoryLoad(collection, component);
        if (!success)
        {
            dmScript::Unref(L, LUA_REGISTRYINDEX, component->m_PreloaderCallbackRef);
            dmScript::Unref(L, LUA_REGISTRYINDEX, component->m_PreloaderSelfRef);
            dmScript::Unref(L, LUA_REGISTRYINDEX, component->m_PreloaderURLRef);
            return luaL_error(L, "Error loading factory resources");
        }

        assert(top == lua_gettop(L));
        return 0;
    }


    /*# make a factory create a new game object
     *
     * The URL identifies which factory should create the game object.
     * If the game object is created inside of the frame (e.g. from an update callback), the game object will be created instantly, but none of its component will be updated in the same frame.
     *
     * Properties defined in scripts in the created game object can be overridden through the properties-parameter below.
     * See go.property for more information on script properties.
     *
     * @name factory.create
     * @param url [type:string|hash|url] the factory that should create a game object.
     * @param [position] [type:vector3] the position of the new game object, the position of the game object calling `factory.create()` is used by default.
     * @param [rotation] [type:quaternion] the rotation of the new game object, the rotation of the game object calling `factory.create()` is is used by default.
     * @param [properties] [type:table] the properties defined in a script attached to the new game object.
     * @param [scale] [type:number|vector3] the scale of the new game object (must be greater than 0), the scale of the game object containing the factory is used by default
     * @return id [type:hash] the global id of the spawned game object
     * @examples
     *
     * How to create a new game object:
     *
     * ```lua
     * function init(self)
     *     -- create a new game object and provide property values
     *     self.my_created_object = factory.create("#factory", nil, nil, {my_value = 1})
     *     -- communicate with the object
     *     msg.post(self.my_created_object, "hello")
     * end
     * ```
     *
     * And then let the new game object have a script attached:
     *
     * ```lua
     * go.property("my_value", 0)
     *
     * function init(self)
     *     -- do something with self.my_value which is now one
     * end
     * ```
     */
    int FactoryComp_Create(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance sender_instance = CheckGoInstance(L);
        dmGameObject::HCollection collection = dmGameObject::GetCollection(sender_instance);

        uintptr_t user_data;
        dmMessage::URL receiver;
        dmGameObject::GetComponentUserDataFromLua(L, 1, collection, FACTORY_EXT, &user_data, &receiver, 0);
        FactoryComponent* component = (FactoryComponent*) user_data;

        Vectormath::Aos::Point3 position;
        if (top >= 2 && !lua_isnil(L, 2))
        {
            position = Vectormath::Aos::Point3(*dmScript::CheckVector3(L, 2));
        }
        else
        {
            position = dmGameObject::GetWorldPosition(sender_instance);
        }
        Vectormath::Aos::Quat rotation;
        if (top >= 3 && !lua_isnil(L, 3))
        {
            rotation = *dmScript::CheckQuat(L, 3);
        }
        else
        {
            rotation = dmGameObject::GetWorldRotation(sender_instance);
        }
        const uint32_t buffer_size = 512;
        uint8_t DM_ALIGNED(16) buffer[buffer_size];
        uint32_t actual_prop_buffer_size = 0;
        uint8_t* prop_buffer = buffer;
        uint32_t prop_buffer_size = buffer_size;
        bool msg_passing = dmGameObject::GetInstanceFromLua(L) == 0x0;
        if (msg_passing) {
            const uint32_t msg_size = sizeof(dmGameSystemDDF::Create);
            prop_buffer = &(buffer[msg_size]);
            prop_buffer_size -= msg_size;
        }
        if (top >= 4)
        {
            actual_prop_buffer_size = dmScript::CheckTable(L, (char*)prop_buffer, prop_buffer_size, 4);
            if (actual_prop_buffer_size > prop_buffer_size)
                return luaL_error(L, "the properties supplied to factory.create are too many.");
        }

        Vector3 scale;
        if (top >= 5 && !lua_isnil(L, 5))
        {
            if (dmScript::IsVector3(L, 5))
            {
                scale = *dmScript::CheckVector3(L, 5);
            }
            else
            {
                float val = luaL_checknumber(L, 5);
                if (val <= 0.0f)
                {
                    return luaL_error(L, "The scale supplied to factory.create must be greater than 0.");
                }
                scale = Vector3(val, val, val);
            }
        }
        else
        {
            scale = dmGameObject::GetWorldScale(sender_instance);
        }

        uint32_t index = dmGameObject::AcquireInstanceIndex(collection);
        if (index != dmGameObject::INVALID_INSTANCE_POOL_INDEX)
        {
            bool success = true;
            dmhash_t id = dmGameObject::ConstructInstanceId(index);

            if (msg_passing) {
                dmGameSystemDDF::Create* create_msg = (dmGameSystemDDF::Create*)buffer;
                create_msg->m_Id = id;
                create_msg->m_Index = index;
                create_msg->m_Position = position;
                create_msg->m_Rotation = rotation;
                create_msg->m_Scale3 = scale;
                dmMessage::URL sender;
                if (!dmScript::GetURL(L, &sender)) {
                    dmGameObject::ReleaseInstanceIndex(index, collection);
                    return luaL_error(L, "factory.create can not be called from this script type");
                }

                dmMessage::Post(&sender, &receiver, dmGameSystemDDF::Create::m_DDFDescriptor->m_NameHash, (uintptr_t)sender_instance, (uintptr_t)dmGameSystemDDF::Create::m_DDFDescriptor, buffer, sizeof(dmGameSystemDDF::Create) + actual_prop_buffer_size, 0);
            } else {
                dmScript::GetInstance(L);
                int ref = dmScript::Ref(L, LUA_REGISTRYINDEX);
                dmGameObject::HPrototype prototype = CompFactoryGetPrototype(collection, component);
                dmGameObject::HInstance instance = dmGameObject::Spawn(collection, prototype, component->m_Resource->m_FactoryDesc->m_Prototype,
                    id, buffer, actual_prop_buffer_size, position, rotation, scale);
                if (instance != 0x0)
                {
                    dmGameObject::AssignInstanceIndex(index, instance);
                }
                else
                {
                    dmGameObject::ReleaseInstanceIndex(index, collection);
                    success = false;
                }

                lua_rawgeti(L, LUA_REGISTRYINDEX, ref);
                dmScript::SetInstance(L);
                dmScript::Unref(L, LUA_REGISTRYINDEX, ref);
            }

            if (success)
            {
                dmScript::PushHash(L, id);
            }
            else
            {
                lua_pushnil(L);
            }
        }
        else
        {
            dmLogError("factory.create can not create gameobject since the buffer is full.");
            lua_pushnil(L);
        }

        assert(top + 1 == lua_gettop(L));
        return 1;
    }

    static const luaL_reg FACTORY_COMP_FUNCTIONS[] =
    {
        {"create",            FactoryComp_Create},
        {"load",              FactoryComp_Load},
        {"unload",            FactoryComp_Unload},
        {0, 0}
    };


    void ScriptFactoryRegister(const ScriptLibContext& context)
    {
        lua_State* L = context.m_LuaState;
        luaL_register(L, "factory", FACTORY_COMP_FUNCTIONS);
        lua_pop(L, 1);
    }
}
