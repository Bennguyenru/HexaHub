#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <sys/stat.h>

#if defined(__linux__) || defined(__MACH__)
#  include <sys/errno.h>
#elif defined(_WIN32)
#  include <errno.h>
#endif

#ifdef _WIN32
#include <direct.h>
#endif

#include <dlib/dstrings.h>
#include <dlib/sys.h>
#include <dlib/log.h>
#include <dlib/socket.h>
#include <resource/resource.h>
#include "script.h"

#include <string.h>
extern "C"
{
#include <lua/lua.h>
#include <lua/lauxlib.h>
}

#include "script_private.h"

namespace dmScript
{
#define LIB_NAME "sys"

    const uint32_t MAX_BUFFER_SIZE =  128 * 1024;

    /*# saves a lua table to a file stored on disk
     * The table can later be loaded by <code>sys.load</code>.
     * Use <code>sys.get_save_file</code> to obtain a valid location for the file.
     * Internally, this function uses a workspace buffer sized output file sized 128kb. This size reflects the output file size which must not exceed this limit.
     * Additionally, the total number of rows that any one table may contain is limited to 65536 (i.e. a 16 bit range). When tables are used to represent arrays, the values of
     * keys are permitted to fall within a 32 bit range, supporting sparse arrays, however the limit on the total number of rows remains in effect.
     *
     * @name sys.save
     * @param filename file to write to (string)
     * @param table lua table to save (table)
     * @return a boolean indicating if the table could be saved or not (boolean)
     * @examples
     * <p>
     * Save data:
     * </p>
     * <pre>
     * local my_table = {}
     * table.add(my_table, "my_value")
     * local my_file_path = sys.get_save_file("my_game", "my_file")
     * if not sys.save(my_file_path, my_table) then
     *     -- Alert user that the data could not be saved
     * end
     * </pre>
     * <p>
     * And load it at a later time, e.g. next game session:
     * </p>
     * <pre>
     * local my_file_path = sys.get_save_file("my_game", "my_file")
     * local my_table = sys.load(my_file_path)
     * if not next(my_table) then
     *     -- empty table
     * end
     * </pre>
     */
    int Sys_Save(lua_State* L)
    {
        char buffer[MAX_BUFFER_SIZE];
        const char* filename = luaL_checkstring(L, 1);
        luaL_checktype(L, 2, LUA_TTABLE);
        uint32_t n_used = CheckTable(L, buffer, sizeof(buffer), 2);
        FILE* file = fopen(filename, "wb");
        if (file != 0x0)
        {
            bool result = fwrite(buffer, 1, n_used, file) == n_used;
            fclose(file);
            if (result)
            {
                lua_pushboolean(L, result);
                return 1;
            }
        }
        return luaL_error(L, "Could not write to the file %s.", filename);
    }

    /*# loads a lua table from a file on disk
     * If the file exists, it must have been created by <code>sys.save</code> to be loaded.
     *
     * @name sys.load
     * @param filename file to read from (string)
     * @return loaded lua table, which is empty if the file could not be found (table)
     */
    int Sys_Load(lua_State* L)
    {
        //Char arrays function stack are not guaranteed to be 4byte aligned in linux. An union with an int add this guarantee.
        union {uint32_t dummy_align; char buffer[MAX_BUFFER_SIZE];};
        const char* filename = luaL_checkstring(L, 1);
        FILE* file = fopen(filename, "rb");
        if (file == 0x0)
        {
            lua_newtable(L);
            return 1;
        }
        fread(buffer, 1, sizeof(buffer), file);
        bool file_size_ok = feof(file) != 0;
        bool result = ferror(file) == 0 && file_size_ok;
        fclose(file);
        if (result)
        {
            PushTable(L, buffer);
            return 1;
        }
        else
        {
            if(file_size_ok)
                return luaL_error(L, "Could not read from the file %s.", filename);
            else
                return luaL_error(L, "File size exceeding size limit of %dkb: %s.", MAX_BUFFER_SIZE/1024, filename);
        }
    }

    /*# gets the save-file path
     * The save-file path is operating system specific and is typically located under the users home directory.
     *
     * @name sys.get_save_file
     * @param application_id user defined id of the application, which helps define the location of the save-file (string)
     * @param file_name file-name to get path for (string)
     * @return path to save-file (string)
     */
    int Sys_GetSaveFile(lua_State* L)
    {
        const char* application_id = luaL_checkstring(L, 1);

        char app_support_path[1024];
        dmSys::Result r = dmSys::GetApplicationSupportPath(application_id, app_support_path, sizeof(app_support_path));
        if (r != dmSys::RESULT_OK)
        {
            luaL_error(L, "Unable to locate application support path (%d)", r);
        }

        const char* filename = luaL_checkstring(L, 2);
        char* dm_home = getenv("DM_SAVE_HOME");

        // Higher priority
        if (dm_home)
        {
            dmStrlCpy(app_support_path, dm_home, sizeof(app_support_path));
        }

        dmStrlCat(app_support_path, "/", sizeof(app_support_path));
        dmStrlCat(app_support_path, filename, sizeof(app_support_path));
        lua_pushstring(L, app_support_path);

        return 1;
    }

    /*# get config value
     * Get config value from the game.project configuration file.
     *
     * @name sys.get_config
     * @param key key to get value for. The syntax is SECTION.KEY
     * @return config value as a string. nil if the config key doesn't exists
     * @examples
     * <p>
     * Get display width
     * </p>
     * <pre>
     * local width = tonumber(sys.get_config("display.width"))
     * </pre>
     */

    /*# get config value with default value
     * Get config value from the game.project configuration file with default value
     *
     * @name sys.get_config
     * @param key key to get value for. The syntax is SECTION.KEY
     * @param default_value default value to return if the value doens't exists
     * @return config value as a string. default_value if the config key doesn't exists
     * @examples
     * <p>
     * Get user config value
     * </p>
     * <pre>
     * local speed = tonumber(sys.get_config("my_game.speed", "10.23"))
     * </pre>
     */
    int Sys_GetConfig(lua_State* L)
    {
        int top = lua_gettop(L);

        const char* key = luaL_checkstring(L, 1);
        const char* default_value = 0;
        if (lua_isstring(L, 2))
        {
            default_value = lua_tostring(L, 2);
        }

        lua_getglobal(L, SCRIPT_CONTEXT);
        Context* context = (Context*) (dmConfigFile::HConfig)lua_touserdata(L, -1);
        dmConfigFile::HConfig config_file = 0;
        if (context)
        {
            config_file = context->m_ConfigFile;
        }

        lua_pop(L, 1);

        const char* value;
        if (config_file)
            value = dmConfigFile::GetString(config_file, key, default_value);
        else
            value = 0;

        if (value)
        {
            lua_pushstring(L, value);
        }
        else
        {
            lua_pushnil(L);
        }

        assert(top + 1 == lua_gettop(L));

        return 1;
    }

    /*# open url in default application
     * Open URL in default application, typically a browser
     *
     * @name sys.open_url
     * @param url url to open
     * @return a boolean indicating if the url could be opened or not
     */
    int Sys_OpenURL(lua_State* L)
    {
        const char* url = luaL_checkstring(L, 1);

        dmSys::Result r = dmSys::OpenURL(url);
        lua_pushboolean(L, r == dmSys::RESULT_OK);
        return 1;
    }

    /*# loads resource from game data
     *
     *
     * @name sys.load_resource
     * @param filename resource to load (string)
     * @return loaded lua table, which is empty if the file could not be found (table)
     */
    int Sys_LoadResource(lua_State* L)
    {
        int top = lua_gettop(L);
        const char* filename = luaL_checkstring(L, 1);

        lua_getglobal(L, SCRIPT_CONTEXT);
        Context* context = (Context*) (dmConfigFile::HConfig)lua_touserdata(L, -1);
        lua_pop(L, 1);

        void* resource;
        uint32_t resource_size;
        dmResource::Result r = dmResource::GetRaw(context->m_ResourceFactory, filename, &resource, &resource_size);
        if (r != dmResource::RESULT_OK) {
            dmLogWarning("Failed to load resource: %s (%d)", filename, r);
            lua_pushnil(L);
        } else {
            lua_pushlstring(L, (const char*) resource, resource_size);
            free(resource);
        }
        assert(top + 1 == lua_gettop(L));
        return 1;
    }

    /*# get system information
     * returns a table with the following members:
     * device_model, system_name, system_version, language, territory, gmt_offset (minutes), device_ident, ad_ident and ad_tracking_enabled.
     * model is currently only available on iOS and Android.
     * language is in ISO-639 format (two characters) and territory in
     * ISO-3166 format (two characters)
     * device_ident is "identifierForVendor" on iOS, "android_id" on Android and empty string on all other platforms
     *
     * @name sys.get_sys_info
     * @return table with system information
     */
    int Sys_GetSysInfo(lua_State* L)
    {
        int top = lua_gettop(L);

        dmSys::SystemInfo info;
        dmSys::GetSystemInfo(&info);

        lua_newtable(L);
        lua_pushliteral(L, "device_model");
        lua_pushstring(L, info.m_DeviceModel);
        lua_rawset(L, -3);
        lua_pushliteral(L, "manufacturer");
        lua_pushstring(L, info.m_Manufacturer);
        lua_rawset(L, -3);
        lua_pushliteral(L, "system_name");
        lua_pushstring(L, info.m_SystemName);
        lua_rawset(L, -3);
        lua_pushliteral(L, "system_version");
        lua_pushstring(L, info.m_SystemVersion);
        lua_rawset(L, -3);
        lua_pushliteral(L, "language");
        lua_pushstring(L, info.m_Language);
        lua_rawset(L, -3);
        lua_pushliteral(L, "device_language");
        lua_pushstring(L, info.m_DeviceLanguage);
        lua_rawset(L, -3);
        lua_pushliteral(L, "territory");
        lua_pushstring(L, info.m_Territory);
        lua_rawset(L, -3);
        lua_pushliteral(L, "gmt_offset");
        lua_pushinteger(L, info.m_GmtOffset);
        lua_rawset(L, -3);
        lua_pushliteral(L, "device_ident");
        lua_pushstring(L, info.m_DeviceIdentifier);
        lua_rawset(L, -3);
        lua_pushliteral(L, "ad_ident");
        lua_pushstring(L, info.m_AdIdentifier);
        lua_rawset(L, -3);
        lua_pushliteral(L, "ad_tracking_enabled");
        lua_pushboolean(L, info.m_AdTrackingEnabled);
        lua_rawset(L, -3);

        assert(top + 1 == lua_gettop(L));
        return 1;
    }

    /*# enumerate network cards
     * returns an array of tables with the following members:
     * name, address (ip-string), mac (hardware address, colon separated string), up (bool), running (bool). NOTE: ip and mac might be nil if not available
     *
     * @name sys.get_ifaddrs
     * @return an array of tables
     */
    int Sys_GetIfaddrs(lua_State* L)
    {
        int top = lua_gettop(L);
        const uint32_t max_count = 16;
        dmSocket::IfAddr addresses[max_count];

        uint32_t count = 0;
        dmSocket::GetIfAddresses(addresses, max_count, &count);
        lua_createtable(L, count, 0);
        for (uint32_t i = 0; i < count; ++i) {
            dmSocket::IfAddr* ifa = &addresses[i];
            lua_newtable(L);

            lua_pushliteral(L, "name");
            lua_pushstring(L, ifa->m_Name);
            lua_rawset(L, -3);

            lua_pushliteral(L, "address");
            if (ifa->m_Flags & dmSocket::FLAGS_INET) {
                char* ip = dmSocket::AddressToIPString(ifa->m_Address);
                lua_pushstring(L, ip);
                free(ip);
            } else {
                lua_pushnil(L);
            }
            lua_rawset(L, -3);

            lua_pushliteral(L, "mac");
            if (ifa->m_Flags & dmSocket::FLAGS_LINK) {
                char tmp[64];
                DM_SNPRINTF(tmp, sizeof(tmp), "%02x:%02x:%02x:%02x:%02x:%02x",
                        ifa->m_MacAddress[0],
                        ifa->m_MacAddress[1],
                        ifa->m_MacAddress[2],
                        ifa->m_MacAddress[3],
                        ifa->m_MacAddress[4],
                        ifa->m_MacAddress[5]);
                lua_pushstring(L, tmp);
            } else {
                lua_pushnil(L);
            }
            lua_rawset(L, -3);

            lua_pushliteral(L, "up");
            lua_pushboolean(L, (ifa->m_Flags & dmSocket::FLAGS_UP) != 0);
            lua_rawset(L, -3);

            lua_pushliteral(L, "running");
            lua_pushboolean(L, (ifa->m_Flags & dmSocket::FLAGS_RUNNING) != 0);
            lua_rawset(L, -3);

            lua_rawseti(L, -2, i + 1);
        }

        assert(top + 1 == lua_gettop(L));
        return 1;
    }

    static const luaL_reg ScriptSys_methods[] =
    {
        {"save", Sys_Save},
        {"load", Sys_Load},
        {"get_save_file", Sys_GetSaveFile},
        {"get_config", Sys_GetConfig},
        {"open_url", Sys_OpenURL},
        {"load_resource", Sys_LoadResource},
        {"get_sys_info", Sys_GetSysInfo},
        {"get_ifaddrs", Sys_GetIfaddrs},
        {0, 0}
    };

    void InitializeSys(lua_State* L)
    {
        int top = lua_gettop(L);

        lua_pushvalue(L, LUA_GLOBALSINDEX);
        luaL_register(L, LIB_NAME, ScriptSys_methods);
        lua_pop(L, 2);

        assert(top == lua_gettop(L));
    }
}
