#include <float.h>
#include <stdio.h>
#include <assert.h>

#include <dlib/hash.h>
#include <dlib/log.h>
#include <dlib/math.h>

#include "gamesys.h"
#include "gamesys_ddf.h"
#include "../gamesys_private.h"

#include "script_sprite.h"

extern "C"
{
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}


namespace dmGameSystem
{
    /*# sprite size (vector3)
     *
     * [READ ONLY] Returns the size of the sprite, not allowing for any additional scaling that may be applied.
     * The type of the property is vector3.
     *
     * @name size
     * @property
     *
     * @examples
     * <p>
     * How to query a sprite's size, either as a vector or selecting a specific dimension:
     * </p>
     * <pre>
     * function init(self)
     *  -- get size from component "sprite"
     * 	local size = go.get("#sprite", "size")
     * 	local sx = go.get("#sprite", "size.x")
     * 	-- do something useful
     * 	assert(size.x == sx)
     * end
     * </pre>
     */
    
    /*# sprite scale (vector3)
     *
     * The non-uniform scale of the sprite. The type of the property is vector3.
     *
     * @name scale
     * @property
     *
     * @examples
     * <p>
     * How to scale a sprite independently along the X and Y axis:
     * </p>
     * <pre>
     * function init(self)
     *  -- Double the y-axis scaling on component "sprite"
     * 	local yscale = go.get("#sprite", "scale.y")
     * 	go.set("#sprite", "scale.y", yscale * 2)
     * end
     * </pre>
     */

    /*# make a sprite flip the animations horizontally or not
     * Which sprite to flip is identified by the URL.
     * If the currently playing animation is flipped by default, flipping it again will make it appear like the original texture.
     *
     * @name sprite.set_hflip
     * @param url the sprite that should flip its animations (url)
     * @param flip if the sprite should flip its animations or not (boolean)
     * @examples
     * <p>
     * How to flip a sprite so it faces the horizontal movement:
     * </p>
     * <pre>
     * function update(self, dt)
     *     -- calculate self.velocity somehow
     *     sprite.set_hflip("#sprite", self.velocity.x < 0)
     * end
     * </pre>
     * <p>It is assumed that the sprite component has id "sprite" and that the original animations faces right.</p>
     */
    int SpriteComp_SetHFlip(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);

        const uint32_t buffer_size = 256;
        uint8_t buffer[buffer_size];
        dmGameSystemDDF::SetFlipHorizontal* request = (dmGameSystemDDF::SetFlipHorizontal*)buffer;

        uint32_t msg_size = sizeof(dmGameSystemDDF::SetFlipHorizontal);

        request->m_Flip = (uint32_t)lua_toboolean(L, 2);

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::SetFlipHorizontal::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::SetFlipHorizontal::m_DDFDescriptor, buffer, msg_size);
        assert(top == lua_gettop(L));
        return 0;
    }

    /*# make a sprite flip the animations vertically or not
     * Which sprite to flip is identified by the URL.
     * If the currently playing animation is flipped by default, flipping it again will make it appear like the original texture.
     *
     * @name sprite.set_vflip
     * @param url the sprite that should flip its animations (url)
     * @param flip if the sprite should flip its animations or not (boolean)
     * @examples
     * <p>
     * How to flip a sprite in a game which negates gravity as a game mechanic:
     * </p>
     * <pre>
     * function update(self, dt)
     *     -- calculate self.up_side_down somehow
     *     sprite.set_vflip("#sprite", self.up_side_down)
     * end
     * </pre>
     * <p>It is assumed that the sprite component has id "sprite" and that the original animations are up-right.</p>
     */
    int SpriteComp_SetVFlip(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);

        const uint32_t buffer_size = 256;
        uint8_t buffer[buffer_size];
        dmGameSystemDDF::SetFlipVertical* request = (dmGameSystemDDF::SetFlipVertical*)buffer;

        uint32_t msg_size = sizeof(dmGameSystemDDF::SetFlipVertical);

        request->m_Flip = (uint32_t)lua_toboolean(L, 2);

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::SetFlipVertical::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::SetFlipVertical::m_DDFDescriptor, buffer, msg_size);
        assert(top == lua_gettop(L));
        return 0;
    }

    /*# set a shader constant for a sprite
     * The constant must be defined in the material assigned to the sprite.
     * Setting a constant through this function will override the value set for that constant in the material.
     * The value will be overridden until sprite.reset_constant is called.
     * Which sprite to set a constant for is identified by the URL.
     *
     * @name sprite.set_constant
     * @param url the sprite that should have a constant set (url)
     * @param name of the constant (string|hash)
     * @param value of the constant (vec4)
     * @examples
     * <p>
     * The following examples assumes that the sprite has id "sprite" and that the default-material in builtins is used.
     * If you assign a custom material to the sprite, you can set the constants defined there in the same manner.
     * </p>
     * <p>
     * How to tint a sprite to red:
     * </p>
     * <pre>
     * function init(self)
     *     sprite.set_constant("#sprite", "tint", vmath.vector4(1, 0, 0, 1))
     * end
     * </pre>
     */
    int SpriteComp_SetConstant(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);

        dmhash_t name_hash;
        if (lua_isstring(L, 2))
        {
            name_hash = dmHashString64(lua_tostring(L, 2));
        }
        else if (dmScript::IsHash(L, 2))
        {
            name_hash = dmScript::CheckHash(L, 2);
        }
        else
        {
            return luaL_error(L, "name must be either a hash or a string");
        }
        Vectormath::Aos::Vector4* value = dmScript::CheckVector4(L, 3);

        const uint32_t buffer_size = 256;
        uint8_t buffer[buffer_size];
        dmGameSystemDDF::SetConstant* request = (dmGameSystemDDF::SetConstant*)buffer;

        uint32_t msg_size = sizeof(dmGameSystemDDF::SetConstant);

        request->m_NameHash = name_hash;
        request->m_Value = *value;

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::SetConstant::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::SetConstant::m_DDFDescriptor, buffer, msg_size);
        assert(top == lua_gettop(L));
        return 0;
    }

    /*# reset a shader constant for a sprite
     * The constant must be defined in the material assigned to the sprite.
     * Resetting a constant through this function implies that the value defined in the material will be used.
     * Which sprite to reset a constant for is identified by the URL.
     *
     * @name sprite.reset_constant
     * @param url the sprite that should have a constant reset (url)
     * @param name of the constant (string|hash)
     * @examples
     * <p>
     * The following examples assumes that the sprite has id "sprite" and that the default-material in builtins is used.
     * If you assign a custom material to the sprite, you can reset the constants defined there in the same manner.
     * </p>
     * <p>
     * How to reset the tinting of a sprite:
     * </p>
     * <pre>
     * function init(self)
     *     sprite.reset_constant("#sprite", "tint")
     * end
     * </pre>
     */
    int SpriteComp_ResetConstant(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);

        dmhash_t name_hash;
        if (lua_isstring(L, 2))
        {
            name_hash = dmHashString64(lua_tostring(L, 2));
        }
        else if (dmScript::IsHash(L, 2))
        {
            name_hash = dmScript::CheckHash(L, 2);
        }
        else
        {
            return luaL_error(L, "name must be either a hash or a string");
        }

        const uint32_t buffer_size = 256;
        uint8_t buffer[buffer_size];
        dmGameSystemDDF::ResetConstant* request = (dmGameSystemDDF::ResetConstant*)buffer;

        uint32_t msg_size = sizeof(dmGameSystemDDF::ResetConstant);

        request->m_NameHash = name_hash;

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::ResetConstant::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::ResetConstant::m_DDFDescriptor, buffer, msg_size);
        assert(top == lua_gettop(L));
        return 0;
    }

    // Docs intentionally left out until we decide to go public with this function
    int SpriteComp_SetScale(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);

        Vectormath::Aos::Vector3* scale = dmScript::CheckVector3(L, 2);

        const uint32_t buffer_size = 256;
        uint8_t buffer[buffer_size];
        dmGameSystemDDF::SetScale* request = (dmGameSystemDDF::SetScale*)buffer;

        uint32_t msg_size = sizeof(dmGameSystemDDF::SetScale);

        request->m_Scale = *scale;

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::SetScale::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::SetScale::m_DDFDescriptor, buffer, msg_size);
        assert(top == lua_gettop(L));
        return 0;
    }

    static const luaL_reg SPRITE_COMP_FUNCTIONS[] =
    {
            {"set_hflip",       SpriteComp_SetHFlip},
            {"set_vflip",       SpriteComp_SetVFlip},
            {"set_constant",    SpriteComp_SetConstant},
            {"reset_constant",  SpriteComp_ResetConstant},
            {"set_scale",       SpriteComp_SetScale},
            {0, 0}
    };

    void ScriptSpriteRegister(const ScriptLibContext& context)
    {
        lua_State* L = context.m_LuaState;
        luaL_register(L, "sprite", SPRITE_COMP_FUNCTIONS);
        lua_pop(L, 1);
    }
}
