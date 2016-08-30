#include <float.h>
#include <stdio.h>
#include <assert.h>

#include <dlib/hash.h>
#include <dlib/log.h>
#include <dlib/math.h>
#include <particle/particle.h>
#include <graphics/graphics.h>
#include <render/render.h>

#include "../gamesys.h"
#include "gamesys_ddf.h"
#include "../gamesys_private.h"

#include "resources/res_particlefx.h"

#include "script_particlefx.h"

extern "C"
{
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

namespace dmGameSystem
{
    struct EmitterStateChangedData
    {
        EmitterStateChangedData()
        {
            memset(this, 0, sizeof(*this));
        }

        dmhash_t m_ComponentId;
        int m_LuaCallbackRef;
        int m_LuaSelfRef;
        lua_State* m_L;
    };

    void EmitterStateChangedCallback(uint32_t num_awake_emitters, dmhash_t emitter_id, int emitter_state, void* user_data /*contains componentId, luaFuncRef, luaSelfRef, luaStatePtr*/)
    {
        dmhash_t component_id = *(dmhash_t*)(user_data);
        int lua_callback_ref = *(int*)((char*)user_data + sizeof(dmhash_t));
        int lua_self_ref = *(int*)((char*)user_data + sizeof(dmhash_t) + sizeof(int));
        lua_State* L = (lua_State*) (*(intptr_t*)((char*)user_data + sizeof(dmhash_t) + sizeof(int) + sizeof(int)));

        int arg_count = 4;

        // push callback reference onto stack
        lua_rawgeti(L, LUA_REGISTRYINDEX, lua_callback_ref);
        
        lua_rawgeti(L, LUA_REGISTRYINDEX, lua_self_ref);
        dmScript::PushHash(L, component_id);
        dmScript::PushHash(L, emitter_id);
        lua_pushnumber(L, emitter_state);

        int ret = dmScript::PCall(L, arg_count, LUA_MULTRET);
        if(ret != 0)
        {
            dmLogError("error calling particle emitter callback, error: %s", lua_tostring(L, -1));
        }

        // The last emitter belonging to this particlefx har gone to sleep, release resources.
        if(num_awake_emitters == 0 && emitter_state == 0 /*EMITTER_STATE_SLEEPING*/)
        {
            lua_unref(L, lua_callback_ref);
            delete[] (char*)user_data;
        }
    }

    /*# ParticleFX documentation
     *
     * ParticleFX documentation
     * @name
     * @package
     */

    /*# start playing a particle FX
     * Particle FX started this way need to be manually stopped through particlefx.stop.
     * Which particle FX to play is identified by the URL.
     *
     * @name particlefx.play
     * @param url the particle fx that should start playing (url)
     * @params [emitter_state_cb] optional callback that will be called when an emitter
     * attached to this particlefx changes state.
     * @examples
     * <p>
     * How to play a particle fx when a game object is created. 
     * The callback receives the hash of the path to the particlefx, the hash of the id
     * of the emitter, and the new state of the emitter.
     * </p>
     * <pre>
     * local function emitter_state_cb(self, particlefx_url, emitter_id, state)
     *    print(particlefx_url)
     *    print(emitter_id)
     *    print(state)
     * end
     * function init(self)
     *     particlefx.play("#particlefx", emitter_state_cb)
     * end
     * </pre>
     */
    int ParticleFX_Play(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);

        if (top < 1)
        {
            return luaL_error(L, "particlefx.play expects atleast URL as parameter");
        }
        
        char* msg_buf = 0x0;
        uint32_t msg_size = 0;

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        sender.m_Function = 0;
        receiver.m_Function = 0;

        EmitterStateChangedData data;

        if (top > 1 && !lua_isnil(L, 2))
        {
            int callback = luaL_ref(L, LUA_REGISTRYINDEX); // pops value from lua stack
            lua_pushnil(L); // push nil to lua stack to restore stack size (necessary? or just ditch the assert below?)

            dmScript::GetInstance(L);
            int self = luaL_ref(L, LUA_REGISTRYINDEX);
            
            data.m_ComponentId = receiver.m_Path; // maybe, maybe not correct
            data.m_LuaCallbackRef = callback;
            data.m_LuaSelfRef = self;
            data.m_L = L;

            dmParticle::EmitterStateChanged fun;
            fun = EmitterStateChangedCallback;

            msg_size = sizeof(dmParticle::EmitterStateChanged) + sizeof(EmitterStateChangedData);
            msg_buf = new char[msg_size];
            memcpy(msg_buf, &fun, sizeof(dmParticle::EmitterStateChanged));
            memcpy(msg_buf + sizeof(dmParticle::EmitterStateChanged), &data, sizeof(EmitterStateChangedData));
        }

        dmMessage::Post(
            &sender, 
            &receiver, 
            dmGameSystemDDF::PlayParticleFX::m_DDFDescriptor->m_NameHash, 
            (uintptr_t)instance, 
            (uintptr_t)dmGameSystemDDF::PlayParticleFX::m_DDFDescriptor, 
            (void*)msg_buf, 
            msg_size);

        delete[] msg_buf;

        assert(top == lua_gettop(L));
        return 0;
    }

    /*# stop playing a particle fx
     * Stopping a particle FX does not remove the already spawned particles.
     * Which particle fx to stop is identified by the URL.
     *
     * @name particlefx.stop
     * @param url the particle fx that should stop playing (url)
     * @examples
     * <p>
     * How to stop a particle fx when a game object is deleted:
     * </p>
     * <pre>
     * function final(self)
     *     particlefx.stop("#particlefx")
     * end
     * </pre>
     */
    int ParticleFX_Stop(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);

        if (top != 1)
        {
            return luaL_error(L, "particlefx.stop only takes a URL as parameter");
        }
        dmGameSystemDDF::StopParticleFX msg;
        uint32_t msg_size = sizeof(dmGameSystemDDF::StopParticleFX);

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::StopParticleFX::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::StopParticleFX::m_DDFDescriptor, (void*)&msg, msg_size);
        assert(top == lua_gettop(L));
        return 0;
    }

    /*# set a shader constant for a particle FX emitter
     * The constant must be defined in the material assigned to the emitter.
     * Setting a constant through this function will override the value set for that constant in the material.
     * The value will be overridden until particlefx.reset_constant is called.
     * Which particle FX to set a constant for is identified by the URL.
     *
     * @name particlefx.set_constant
     * @param url the particle FX that should have a constant set (url)
     * @param emitter_id the id of the emitter (string|hash)
     * @param name the name of the constant (string|hash)
     * @param value the value of the constant (vec4)
     * @examples
     * <p>
     * The following examples assumes that the particle FX has id "particlefx", contains an emitter with id "emitter" and that the default-material in builtins is used.
     * If you assign a custom material to the emitter, you can set the constants defined there in the same manner.
     * </p>
     * <p>
     * How to tint particles from an emitter red:
     * </p>
     * <pre>
     * function init(self)
     *     particlefx.set_constant("#particlefx", "emitter", "tint", vmath.vector4(1, 0, 0, 1))
     * end
     * </pre>
     */
    int ParticleFX_SetConstant(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);

        dmhash_t emitter_id = dmScript::CheckHashOrString(L, 2);
        dmhash_t name_hash = dmScript::CheckHashOrString(L, 3);
        Vectormath::Aos::Vector4* value = dmScript::CheckVector4(L, 4);

        dmGameSystemDDF::SetConstantParticleFX msg;
        msg.m_EmitterId = emitter_id;
        msg.m_NameHash = name_hash;
        msg.m_Value = *value;

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::SetConstantParticleFX::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::SetConstantParticleFX::m_DDFDescriptor, &msg, sizeof(msg));
        assert(top == lua_gettop(L));
        return 0;
    }

    /*# reset a shader constant for a particle FX emitter
     * The constant must be defined in the material assigned to the emitter.
     * Resetting a constant through this function implies that the value defined in the material will be used.
     * Which particle FX to reset a constant for is identified by the URL.
     *
     * @name particlefx.reset_constant
     * @param url the particle FX that should have a constant reset (url)
     * @param emitter_id the id of the emitter (string|hash)
     * @param name the name of the constant (string|hash)
     * @examples
     * <p>
     * The following examples assumes that the particle FX has id "particlefx", contains an emitter with id "emitter" and that the default-material in builtins is used.
     * If you assign a custom material to the emitter, you can reset the constants defined there in the same manner.
     * </p>
     * <p>
     * How to reset the tinting of particles from an emitter:
     * </p>
     * <pre>
     * function init(self)
     *     particlefx.reset_constant("#particlefx", "emitter", "tint")
     * end
     * </pre>
     */
    int ParticleFX_ResetConstant(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);
        dmhash_t emitter_id = dmScript::CheckHashOrString(L, 2);
        dmhash_t name_hash = dmScript::CheckHashOrString(L, 3);

        dmGameSystemDDF::ResetConstantParticleFX msg;
        msg.m_EmitterId = emitter_id;
        msg.m_NameHash = name_hash;

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::ResetConstantParticleFX::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::ResetConstantParticleFX::m_DDFDescriptor, &msg, sizeof(msg));
        assert(top == lua_gettop(L));
        return 0;
    }

    static const luaL_reg PARTICLEFX_FUNCTIONS[] =
    {
        {"play",            ParticleFX_Play},
        {"stop",            ParticleFX_Stop},
        {"set_constant",    ParticleFX_SetConstant},
        {"reset_constant",  ParticleFX_ResetConstant},
        {0, 0}
    };

    void ScriptParticleFXRegister(const ScriptLibContext& context)
    {
        lua_State* L = context.m_LuaState;
        int top = lua_gettop(L);
        luaL_register(L, "particlefx", PARTICLEFX_FUNCTIONS);

        #define SETCONSTANT(name, val) \
        lua_pushnumber(L, (lua_Number) val); \
        lua_setfield(L, -2, #name);\

        SETCONSTANT(EMITTER_STATE_SLEEPING, 0); // dmParticle::EmitterState::EMITTER_STATE_SLEEPING
        SETCONSTANT(EMITTER_STATE_PRESPAWN, 1); // dmParticle::EmitterState::EMITTER_STATE_PRESPAWN
        SETCONSTANT(EMITTER_STATE_SPAWNING, 2); // dmParticle::EmitterState::EMITTER_STATE_SPAWNING
        SETCONSTANT(EMITTER_STATE_POSTSPAWN, 3); // dmParticle::EmitterState::EMITTER_STATE_POSTSPAWN

        #undef SETCONSTANT

        lua_pop(L, 1);
        assert(top == lua_gettop(L));
    }
}
