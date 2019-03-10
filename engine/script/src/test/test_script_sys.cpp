#define JC_TEST_IMPLEMENTATION
#include <jc_test/jc_test.h>

#include "script.h"

#include <dlib/dstrings.h>
#include <dlib/hash.h>
#include <dlib/log.h>
#include <dlib/configfile.h>
#include <resource/resource.h>

extern "C"
{
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

#define PATH_FORMAT "build/default/src/test/%s"

class ScriptSysTest : public jc_test_base_class
{
protected:
    virtual void SetUp()
    {
        dmConfigFile::Result r = dmConfigFile::Load("src/test/test.config", 0, 0, &m_ConfigFile);
        ASSERT_EQ(dmConfigFile::RESULT_OK, r);

        dmResource::NewFactoryParams factory_params;
        m_ResourceFactory = dmResource::NewFactory(&factory_params, ".");
        m_Context = dmScript::NewContext(m_ConfigFile, m_ResourceFactory, true);

        dmScript::Initialize(m_Context);

        L = dmScript::GetLuaState(m_Context);
    }

    virtual void TearDown()
    {
        dmConfigFile::Delete(m_ConfigFile);
        dmResource::DeleteFactory(m_ResourceFactory);
        dmScript::Finalize(m_Context);
        dmScript::DeleteContext(m_Context);
    }

    dmScript::HContext m_Context;
    dmConfigFile::HConfig m_ConfigFile;
    dmResource::HFactory m_ResourceFactory;
    lua_State* L;
};

bool RunFile(lua_State* L, const char* filename)
{
    char path[64];
    DM_SNPRINTF(path, 64, PATH_FORMAT, filename);
    if (luaL_dofile(L, path) != 0)
    {
        dmLogError("%s", lua_tolstring(L, -1, 0));
        return false;
    }
    return true;
}

bool RunString(lua_State* L, const char* script)
{
    if (luaL_dostring(L, script) != 0)
    {
        dmLogError("%s", lua_tolstring(L, -1, 0));
        return false;
    }
    return true;
}

TEST_F(ScriptSysTest, TestSys)
{
    int top = lua_gettop(L);

    ASSERT_TRUE(RunFile(L, "test_sys.luac"));

    lua_getglobal(L, "functions");
    ASSERT_EQ(LUA_TTABLE, lua_type(L, -1));
    lua_getfield(L, -1, "test_sys");
    ASSERT_EQ(LUA_TFUNCTION, lua_type(L, -1));
    int result = dmScript::PCall(L, 0, LUA_MULTRET);
    if (result == LUA_ERRRUN)
    {
        ASSERT_TRUE(false);
    }
    else
    {
        ASSERT_EQ(0, result);
    }
    lua_pop(L, 1);

    ASSERT_EQ(top, lua_gettop(L));
}

int main(int argc, char **argv)
{
    jc_test_init(&argc, argv);

    int ret = JC_TEST_RUN_ALL();
    return ret;
}
