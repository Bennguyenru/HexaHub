
#include <dlib/configfile.h>
#include <dlib/log.h>
#include <ddf/ddf.h>
#include <gameobject/gameobject.h>
#include <render/render.h>
#include <script/script.h>
#include "gamesys.h"
#include "tile_ddf.h"
#include "../gamesys_private.h"
#include "../resources/res_tilegrid.h"
#include "../components/comp_tilegrid.h"
#include "../proto/physics_ddf.h"
#include "script_tilemap.h"

extern "C"
{
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

namespace dmGameSystem
{
    /*# Tilemap API documentation
     *
     * Functions and messages used to manipulate tile map components.
     *
     * @name Tilemap
     * @namespace tilemap
     */

    /*# set a shader constant for a tile map
     * The constant must be defined in the material assigned to the tile map.
     * Setting a constant through this function will override the value set for that constant in the material.
     * The value will be overridden until tilemap.reset_constant is called.
     * Which tile map to set a constant for is identified by the URL.
     *
     * @name tilemap.set_constant
     * @param url the tile map that should have a constant set (url)
     * @param name of the constant (string|hash)
     * @param value of the constant (vec4)
     * @examples
     * <p>
     * The following examples assumes that the tile map has id "tile map" and that the default-material in builtins is used.
     * If you assign a custom material to the tile map, you can set the constants defined there in the same manner.
     * </p>
     * <p>
     * How to tint a tile map to red:
     * </p>
     * <pre>
     * function init(self)
     *     tilemap.set_constant("#tilemap", "tint", vmath.vector4(1, 0, 0, 1))
     * end
     * </pre>
     */
    int TileMap_SetConstant(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);

        dmhash_t name_hash = dmScript::CheckHashOrString(L, 2);
        Vectormath::Aos::Vector4* value = dmScript::CheckVector4(L, 3);

        dmGameSystemDDF::SetConstantTileMap msg;
        msg.m_NameHash = name_hash;
        msg.m_Value = *value;

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::SetConstantTileMap::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::SetConstantTileMap::m_DDFDescriptor, &msg, sizeof(msg), 0);
        assert(top == lua_gettop(L));
        return 0;
    }

    /*# reset a shader constant for a tile map
     * The constant must be defined in the material assigned to the tile map.
     * Resetting a constant through this function implies that the value defined in the material will be used.
     * Which tile map to reset a constant for is identified by the URL.
     *
     * @name tilemap.reset_constant
     * @param url the tile map that should have a constant reset (url)
     * @param name of the constant (string|hash)
     * @examples
     * <p>
     * The following examples assumes that the tile map has id "tilemap" and that the default-material in builtins is used.
     * If you assign a custom material to the tile map, you can reset the constants defined there in the same manner.
     * </p>
     * <p>
     * How to reset the tinting of a tile map:
     * </p>
     * <pre>
     * function init(self)
     *     tilemap.reset_constant("#tilemap", "tint")
     * end
     * </pre>
     */
    int TileMap_ResetConstant(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance instance = CheckGoInstance(L);
        dmhash_t name_hash = dmScript::CheckHashOrString(L, 2);

        dmGameSystemDDF::ResetConstantTileMap msg;
        msg.m_NameHash = name_hash;

        dmMessage::URL receiver;
        dmMessage::URL sender;
        dmScript::ResolveURL(L, 1, &receiver, &sender);

        dmMessage::Post(&sender, &receiver, dmGameSystemDDF::ResetConstantTileMap::m_DDFDescriptor->m_NameHash, (uintptr_t)instance, (uintptr_t)dmGameSystemDDF::ResetConstantTileMap::m_DDFDescriptor, &msg, sizeof(msg), 0);
        assert(top == lua_gettop(L));
        return 0;
    }

    /*# set a tile in a tile map
     * Replace a tile in a tile map with a new tile.
     * The coordinates of the tiles are indexed so that the "first" tile just 
     * above and to the right of origo has coordinates 1,1.
     * Tiles to the left of and below origo are indexed 0, -1, -2 and so forth.
     * 
     * <pre>
     * +-------+-------+------+------+
     * |  0,3  |  1,3  | 1,2  | 3,3  |
     * +-------+-------+------+------+
     * |  0,2  |  1,2  | 2,2  | 3,2  |
     * +-------+-------+------+------+
     * |  0,1  |  1,1  | 2,1  | 3,1  |
     * +-------O-------+------+------+
     * |  0,0  |  1,0  | 2,0  | 3,0  |
     * +-------+-------+------+------+
     * </pre>
     * The coordinates must be within the bounds of the tile map as it were created. That is, it is not
     * possible to extend the size of a tile map by setting tiles outside the edges.
     * To clear a tile, set the tile to number 0. Which tile map and layer to manipulate is identified by
     * the URL and the layer name parameters.
     *
     * @name tilemap.set_tile
     * @param url the tile map (url)
     * @param name of the layer (string|hash)
     * @param x-coordinate of the tile (number)
     * @param y-coordinate of the tile (number)
     * @param new tile to set (number)
     * @param flip_h (optional) if the tile should be horizontally flipped (boolean)
     * @param flip_v (optional) i the tile should be vertically flipped (boolean)
     * @examples
     * <pre>
     * -- Clear the tile under the player.
     * tilemap.set_tile("/level#tilemap", "foreground", self.player_x, self.player_y, 0)
     * </pre>
     */
    int TileMap_SetTile(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance sender_instance = CheckGoInstance(L);
        dmGameObject::HCollection collection = dmGameObject::GetCollection(sender_instance);

        uintptr_t user_data;
        dmMessage::URL receiver;
        dmGameObject::GetComponentUserDataFromLua(L, 1, collection, TILE_MAP_EXT, &user_data, &receiver, 0);
        TileGridComponent* component = (TileGridComponent*) user_data;

        dmhash_t layer_id = dmScript::CheckHashOrString(L, 2);

        uint32_t layer_index = GetLayerIndex(component, layer_id);
        if (layer_index == ~0u)
        {
            dmLogError("Could not find layer %s.", (char*)dmHashReverse64(layer_id, 0x0));
            lua_pushboolean(L, 0);
            assert(top + 1 == lua_gettop(L));
            return 1;
        }

        int x = luaL_checkinteger(L, 3) - 1;
        int y = luaL_checkinteger(L, 4) - 1;

        int min_x, min_y, grid_w, grid_h;
        GetTileGridBounds(component, &min_x, &min_y, &grid_w, &grid_h);

        /*
         * NOTE AND BEWARE: Empty tile is encoded as 0xffffffff
         * That's why tile-index is subtracted by 1
         * See B2GRIDSHAPE_EMPTY_CELL in b2GridShape.h
         */
        uint32_t tile = ((uint16_t) luaL_checkinteger(L, 5)) - 1;

        int32_t cell_x, cell_y;
        GetTileGridCellCoord(component, x, y, cell_x, cell_y);

        if (cell_x < 0 || cell_x >= grid_w || cell_y < 0 || cell_y >= grid_h)
        {
            dmLogError("Could not set the tile since the supplied tile was out of range.");
            lua_pushboolean(L, 0);
            assert(top + 1 == lua_gettop(L));
            return 1;
        }

        bool flip_h = lua_toboolean(L, 6);
        bool flip_v = lua_toboolean(L, 7);
        SetTileGridTile(component, layer_index, cell_x, cell_y, tile, flip_h, flip_v);

        dmMessage::URL sender;
        if (dmScript::GetURL(L, &sender))
        {
            // Broadcast to any collision object components
            // TODO Filter broadcast to only collision objects
            dmPhysicsDDF::SetGridShapeHull set_hull_ddf;
            set_hull_ddf.m_Shape = layer_index;
            set_hull_ddf.m_Column = cell_x;
            set_hull_ddf.m_Row = cell_y;
            set_hull_ddf.m_Hull = tile;
            set_hull_ddf.m_FlipHorizontal = flip_h;
            set_hull_ddf.m_FlipVertical = flip_v;
            dmhash_t message_id = dmPhysicsDDF::SetGridShapeHull::m_DDFDescriptor->m_NameHash;
            uintptr_t descriptor = (uintptr_t)dmPhysicsDDF::SetGridShapeHull::m_DDFDescriptor;
            uint32_t data_size = sizeof(dmPhysicsDDF::SetGridShapeHull);
            receiver.m_Fragment = 0;
            dmMessage::Result result = dmMessage::Post(&sender, &receiver, message_id, 0, descriptor, &set_hull_ddf, data_size, 0);
            if (result != dmMessage::RESULT_OK)
            {
                dmLogError("Could not send %s to components, result: %d.", dmPhysicsDDF::SetGridShapeHull::m_DDFDescriptor->m_Name, result);
            }
        }
        else
        {
            return luaL_error(L, "tilemap.set_tile is not available from this script-type.");
        }

        lua_pushboolean(L, 1);
        assert(top + 1 == lua_gettop(L));
        return 1;
    }

    /*# get a tile from a tile map
     * Get the tile set at the specified position in the tilemap.
     * The position is identified by the tile index starting at origo
     * with index 1, 1. (see <code>tilemap.set_tile()</code>)
     * Which tile map and layer to query is identified by the URL and the 
     * layer name parameters.
     *
     * @name tilemap.get_tile
     * @param url the tile map (url)
     * @param name of the layer (string|hash)
     * @param x-coordinate of the tile (number)
     * @param y-coordinate of the tile (number)
     * @return index of the tile (number)
     * @examples
     * <pre>
     * -- get the tile under the player.
     * local tileno = tilemap.get_tile("/level#tilemap", "foreground", self.player_x, self.player_y)
     * </pre>
     */
    int TileMap_GetTile(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance sender_instance = CheckGoInstance(L);
        dmGameObject::HCollection collection = dmGameObject::GetCollection(sender_instance);

        uintptr_t user_data;
        dmGameObject::GetComponentUserDataFromLua(L, 1, collection, TILE_MAP_EXT, &user_data, 0, 0);
        TileGridComponent* component = (TileGridComponent*) user_data;

        dmhash_t layer_id = dmScript::CheckHashOrString(L, 2);
        uint32_t layer_index = GetLayerIndex(component, layer_id);
        if (layer_index == ~0u)
        {
            dmLogError("Could not find layer %s.", (char*)dmHashReverse64(layer_id, 0x0));
            lua_pushnil(L);
            assert(top + 1 == lua_gettop(L));
            return 1;
        }

        int x = luaL_checkinteger(L, 3) - 1;
        int y = luaL_checkinteger(L, 4) - 1;

        int min_x, min_y, grid_w, grid_h;
        GetTileGridBounds(component, &min_x, &min_y, &grid_w, &grid_h);

        int32_t cell_x, cell_y;
        GetTileGridCellCoord(component, x, y, cell_x, cell_y);

        if (cell_x < 0 || cell_x >= grid_w || cell_y < 0 || cell_y >= grid_h)
        {
            dmLogError("Could not get the tile since the supplied tile was out of range.");
            lua_pushnil(L);
            assert(top + 1 == lua_gettop(L));
            return 1;
        }

        uint16_t cell = GetTileGridTile(component, layer_index, cell_x, cell_y);

        lua_pushinteger(L,  cell);
        assert(top + 1 == lua_gettop(L));
        return 1;
    }

    /*# get the bounds of a tile map
     * Get the bounds for a tile map. This function returns multiple values:
     * The lower left corner index x and y coordinates (1-indexed), 
     * the tile map width and the tile map height. 
     *
     * The resulting values take all tile map layers into account, meaning that
     * the bounds are calculated as if all layers were collapsed into one.
     *
     * @name tilemap.get_bounds
     * @param url the tile map (url)
     * @return x coordinate of the bottom left corner (number)
     * @return y coordinate of the bottom left corner (number)
     * @return number of columns in the tile map (number)
     * @return number of rows in the tile map (number)
     * @examples
     * <pre>
     * -- get the level bounds.
     * local x, y, w, h = tilemap.get_bounds("/level#tilemap")
     * </pre>
     */
    int TileMap_GetBounds(lua_State* L)
    {
        int top = lua_gettop(L);

        dmGameObject::HInstance sender_instance = CheckGoInstance(L);
        dmGameObject::HCollection collection = dmGameObject::GetCollection(sender_instance);

        uintptr_t user_data;
        dmGameObject::GetComponentUserDataFromLua(L, 1, collection, TILE_MAP_EXT, &user_data, 0, 0);
        TileGridComponent* component = (TileGridComponent*) user_data;

        int x, y, w, h;
        GetTileGridBounds(component, &x, &y, &w, &h);

        lua_pushinteger(L, x + 1);
        lua_pushinteger(L, y + 1);
        lua_pushinteger(L, w);
        lua_pushinteger(L, h);

        assert(top + 4 == lua_gettop(L));
        return 4;
    }

    static const luaL_reg TILEMAP_FUNCTIONS[] =
    {
        {"set_constant",    TileMap_SetConstant},
        {"reset_constant",  TileMap_ResetConstant},
        {"set_tile",        TileMap_SetTile},
        {"get_tile",        TileMap_GetTile},
        {"get_bounds",      TileMap_GetBounds},
        {0, 0}
    };

    void ScriptTileMapRegister(const ScriptLibContext& context)
    {
        lua_State* L = context.m_LuaState;
        luaL_register(L, "tilemap", TILEMAP_FUNCTIONS);
        lua_pop(L, 1);
    }
}
