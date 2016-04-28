#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>

#include "script.h"

extern "C"
{
#include <lua/lua.h>
#include <lua/lauxlib.h>
}

#include "script_json.h"
#include "script_private.h"

static int min(int a, int b)
{
    return a < b ? a : b;
}

namespace dmScript
{
    #define LIB_NAME "json"

    int JsonToLua(lua_State*L, dmJson::Document* doc, int index)
    {
        const uint32_t buffer_len = 1024;

        if (index >= doc->m_NodeCount)
        {
            return luaL_error(L, "Unexpected JSON index, unable to parse content.");
        }

        const dmJson::Node& n = doc->m_Nodes[index];
        const char* json = doc->m_Json;
        int l = n.m_End - n.m_Start;
        switch (n.m_Type)
        {
        case dmJson::TYPE_PRIMITIVE:
            if (l == 4 && memcmp(json + n.m_Start, "null", 4) == 0)
            {
                lua_pushnil(L);
            }
            else if (l == 4 && memcmp(json + n.m_Start, "true", 4) == 0)
            {
                lua_pushboolean(L, 1);
            }
            else if (l == 5 && memcmp(json + n.m_Start, "false", 5) == 0)
            {
                lua_pushboolean(L, 0);
            }
            else
            {
                char buffer[buffer_len] = { 0 };
                memcpy(buffer, json + n.m_Start, min(buffer_len - 1, l));

                uint32_t bytes_read = 0;
                double value = 0.0f;
                int result = sscanf(buffer, "%lf%n", &value, &bytes_read);
                if (result == 1 && bytes_read == min(buffer_len - 1, l))
                {
                    lua_pushnumber(L, value);
                }
                else
                {
                    return luaL_error(L, "Invalid JSON primitive: %s", buffer);
                }
            }
            return index + 1;

        case dmJson::TYPE_STRING:
            lua_pushlstring(L, json + n.m_Start, l);
            return index + 1;

        case dmJson::TYPE_ARRAY:
            lua_createtable(L, n.m_Size, 0);
            ++index;
            for (int i = 0; i < n.m_Size; ++i)
            {
                index = JsonToLua(L, doc, index);
                lua_rawseti(L, -2, i+1);
            }
            return index;

        case dmJson::TYPE_OBJECT:
            // {1 2 3} is a valid object according to the jsmn parser, we need
            // to protect against this to avoid reading random memory.
            if ((n.m_Size % 2) == 0)
            {
                lua_createtable(L, 0, n.m_Size);
                ++index;
                for (int i = 0; i < n.m_Size; i += 2)
                {
                    index = JsonToLua(L, doc, index);
                    index = JsonToLua(L, doc, index);
                    lua_rawset(L, -3);
                }

                return index;
            }
            else
            {
                char buffer[buffer_len] = { 0 };
                memcpy(buffer, json + n.m_Start, min(buffer_len - 1, l));
                luaL_error(L, "Incomplete JSON object: %s", buffer);
            }
        }

        luaL_error(L, "Unsupported JSON type (%d), unable to parse content.", n.m_Type);
    }

    /*# decode JSON from a string to a lua-table
     * Decode a string of JSON data into a Lua table.
     * A Lua error is raised for syntax errors.
     *
     * @name json.decode
     * @param json json data (string)
     * @return decoded json (table)
     */
    int Json_Decode(lua_State* L)
    {
        int top = lua_gettop(L);
        const char* json = luaL_checkstring(L, 1);
        dmJson::Document doc;
        dmJson::Result r = dmJson::Parse(json, &doc);
        if (r == dmJson::RESULT_OK && doc.m_NodeCount > 0)
        {
            JsonToLua(L, &doc, 0);
            dmJson::Free(&doc);
            assert(top + 1== lua_gettop(L));
            return 1;
        }
        else
        {
            return luaL_error(L, "Failed to parse json '%s' (%d).", json, r);
        }
    }

    static const luaL_reg ScriptJson_methods[] =
    {
        {"decode", Json_Decode},
        {0, 0}
    };

    void InitializeJson(lua_State* L)
    {
        int top = lua_gettop(L);

        lua_pushvalue(L, LUA_GLOBALSINDEX);
        luaL_register(L, LIB_NAME, ScriptJson_methods);
        lua_pop(L, 2);

        assert(top == lua_gettop(L));
    }
}
