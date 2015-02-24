#include <setjmp.h>
#include <stdlib.h>
#include <dlib/dstrings.h>
#include <dlib/log.h>
#include <dlib/align.h>
#include <dlib/math.h>
#include <gtest/gtest.h>
#include "../script.h"
#include "test/test_ddf.h"

#include "data/table_cos_v0.dat.embed.h"
#include "data/table_sin_v0.dat.embed.h"
#include "data/table_cos_v1.dat.embed.h"
#include "data/table_sin_v1.dat.embed.h"
#include "data/table_v818192.dat.embed.h"

extern "C"
{
#include <lua/lua.h>
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

class LuaTableTest* g_LuaTableTest = 0;

class LuaTableTest : public ::testing::Test
{
protected:
    virtual void SetUp()
    {
        accept_panic = false;
        g_LuaTableTest = this;
        m_Context = dmScript::NewContext(0, 0);
        dmScript::Initialize(m_Context);
        L = dmScript::GetLuaState(m_Context);
        lua_atpanic(L, &AtPanic);
        top = lua_gettop(L);
    }

    static int AtPanic(lua_State *L)
    {
        if (g_LuaTableTest->accept_panic)
            longjmp(g_LuaTableTest->env, 1);
        dmLogError("Unexpected error: %s", lua_tostring(L, -1));
        exit(5);
        return 0;
    }

    virtual void TearDown()
    {
        ASSERT_EQ(top, lua_gettop(L));
        dmScript::Finalize(m_Context);
        dmScript::DeleteContext(m_Context);
        g_LuaTableTest = 0;

    }

    char DM_ALIGNED(16) m_Buf[256];

    bool accept_panic;
    jmp_buf env;
    int top;
    dmScript::HContext m_Context;
    lua_State* L;

};

TEST_F(LuaTableTest, EmptyTable)
{
    lua_newtable(L);
    char buf[8 + 2];
    uint32_t buffer_used = dmScript::CheckTable(L, buf, sizeof(buf), -1);
    // 2 bytes for count
    ASSERT_EQ(10U, buffer_used);
    lua_pop(L, 1);
}

/**
 * A helper function used when validating serialized data in original or v1 format.
 */
typedef double (*TableGenFunc)(double);
int ReadSerializedTable(lua_State* L, uint8_t* source, uint32_t source_length, TableGenFunc fn, int key_stride)
{
    int error = 0;
    const double epsilon = 1.0e-7f;
    char* aligned_buf = (char*)source;

    dmScript::PushTable(L, aligned_buf);

    for (uint32_t i=0; i<0xfff; ++i)
    {
        lua_pushnumber(L, i * key_stride);
        lua_gettable(L, -2);
        EXPECT_EQ(LUA_TNUMBER, lua_type(L, -1));
        if  (LUA_TNUMBER != lua_type(L, -1))
        {
            printf("Invalid key on row %d\n", i);
        }
        double value_read = lua_tonumber(L, -1);
        double value_expected = fn(2.0 * M_PI * (double)i / (double)0xffff);
        double diff = abs(value_read - value_expected);
        EXPECT_GT(epsilon, diff);
        lua_pop(L, 1);

        if (epsilon < diff)
        {
            error = 1;
            break;
        }
    }

    lua_pop(L, 1);

    return error;
}

int ReadUnsupportedVersion(lua_State* L)
{
    dmScript::PushTable(L, (const char*)TABLE_V818192_DAT);
    return 1;
}


// The v0.0 tables were generated with dense keys.
int ReadCosTableDataOriginal(lua_State* L)
{
    return ReadSerializedTable(L, TABLE_COS_V0_DAT, TABLE_COS_V0_DAT_SIZE, cos, 1);
}

int ReadSinTableDataOriginal(lua_State* L)
{
    return ReadSerializedTable(L, TABLE_SIN_V0_DAT, TABLE_SIN_V0_DAT_SIZE, sin, 1);
}

TEST_F(LuaTableTest, AttemptReadUnsupportedVersion)
{
    int result = lua_cpcall(L, ReadUnsupportedVersion, 0x0);
    ASSERT_NE(0, result);
    char str[256];
    DM_SNPRINTF(str, sizeof(str), "Unsupported serialized table data: version = 0x%x (current = 0x%x)", 818192, 1);
    ASSERT_STREQ(str, lua_tostring(L, -1));
    // pop error message
    lua_pop(L, 1);
}

TEST_F(LuaTableTest, VerifyCosTableOriginal)
{
    int result = lua_cpcall(L, ReadCosTableDataOriginal, 0x0);
    ASSERT_EQ(0, result);
}

TEST_F(LuaTableTest, VerifySinTableOriginal)
{
    int result = lua_cpcall(L, ReadSinTableDataOriginal, 0x0);
    ASSERT_EQ(0, result);
}


// The v1 tables were generated with sparse keys: every other integer over the defined range.
int ReadCosTableDataVersion01(lua_State* L)
{
    return ReadSerializedTable(L, TABLE_COS_V1_DAT, TABLE_COS_V1_DAT_SIZE, cos, 2);
}


int ReadSinTableDataVersion01(lua_State* L)
{
    return ReadSerializedTable(L, TABLE_SIN_V1_DAT, TABLE_SIN_V1_DAT_SIZE, sin, 2);
}

TEST_F(LuaTableTest, VerifyCosTable01)
{
    int result = lua_cpcall(L, ReadCosTableDataVersion01, 0x0);
    ASSERT_EQ(0, result);
}

TEST_F(LuaTableTest, VerifySinTable01)
{
    int result = lua_cpcall(L, ReadSinTableDataVersion01, 0x0);
    ASSERT_EQ(0, result);
}

TEST_F(LuaTableTest, TestSerializeLargeNumbers)
{
    uint32_t numbers[] = {0, 0x1234, 0x8765, 0xffff, 0x12345678, 0x7fffffff, 0x87654321, 268435456, 0xffffffff, 0xfffffffe};
    const uint32_t count = sizeof(numbers) / sizeof(uint32_t);

    lua_newtable(L);
    for (uint32_t i=0;i!=count;i++)
    {
        // same key & value
        lua_pushnumber(L, numbers[i]);
        lua_pushnumber(L, numbers[i]);
        lua_settable(L, -3);
    }

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    uint32_t found[count] = { 0 };

    lua_pushnil(L);
    while (lua_next(L, -2) != 0)
    {
        uint32_t key = (uint32_t) lua_tonumber(L, -1);
        uint32_t value = (uint32_t) lua_tonumber(L, -2);
        ASSERT_EQ(key, value);

        for (uint32_t i=0;i!=count;i++)
        {
            if (key == numbers[i])
                found[i]++;
        }

        lua_pop(L, 1);
    }

    for (uint32_t i=0;i!=count;i++)
        ASSERT_EQ(found[i], 1);

    lua_pop(L, 1);
}

// header + count (+ align) + n * element-size (overestimate)
const uint32_t OVERFLOW_BUFFER_SIZE = 8 + 2 + 2 + 0xffff * (sizeof(char) + sizeof(char) + sizeof(char) * 6 + sizeof(lua_Number));
char* g_DynamicBuffer = 0x0;

int ProduceOverflow(lua_State *L)
{
    char* const buf = g_DynamicBuffer;
    char* aligned_buf = (char*)(((intptr_t)buf + sizeof(float)-1) & ~(sizeof(float)-1));
    int size = OVERFLOW_BUFFER_SIZE - (aligned_buf - buf);

    lua_newtable(L);
    // too many iterations
    for (uint32_t i = 0; i <= 0xffff; ++i)
    {
        // key
        lua_pushnumber(L, i);
        // value
        lua_pushnumber(L, i);
        // store pair
        lua_settable(L, -3);
    }
    uint32_t buffer_used = dmScript::CheckTable(L, aligned_buf, size, -1);
    // expect it to fail, avoid warning
    (void)buffer_used;

    return 1;
}

TEST_F(LuaTableTest, Overflow)
{
    g_DynamicBuffer = new char[OVERFLOW_BUFFER_SIZE];

    int result = lua_cpcall(L, ProduceOverflow, 0x0);
    // 2 bytes for count
    ASSERT_NE(0, result);
    char expected_error[64];
    DM_SNPRINTF(expected_error, 64, "too many values in table, %d is max", 0xffff);
    ASSERT_STREQ(expected_error, lua_tostring(L, -1));
    // pop error message
    lua_pop(L, 1);

    delete[] g_DynamicBuffer;
    g_DynamicBuffer = 0x0;
}

const uint32_t IOOB_BUFFER_SIZE = 8 + 2 + 2 + (sizeof(char) + sizeof(char) + 5 * sizeof(char) + sizeof(lua_Number));

int ProduceIndexOutOfBounds(lua_State *L)
{
    char buf[IOOB_BUFFER_SIZE];
    lua_newtable(L);
    // invalid key
    lua_pushnumber(L, 0xffffffffLL+1);
    // value
    lua_pushnumber(L, 0);
    // store pair
    lua_settable(L, -3);
    uint32_t buffer_used = dmScript::CheckTable(L, buf, IOOB_BUFFER_SIZE, -1);
    // expect it to fail, avoid warning
    (void)buffer_used;
    return 1;
}

TEST_F(LuaTableTest, IndexOutOfBounds)
{
    int result = lua_cpcall(L, ProduceIndexOutOfBounds, 0x0);
    // 2 bytes for count
    ASSERT_NE(0, result);
    char expected_error[64];
    DM_SNPRINTF(expected_error, 64, "index out of bounds, max is %d", 0xffffffff);
    ASSERT_STREQ(expected_error, lua_tostring(L, -1));
    // pop error message
    lua_pop(L, 1);
}

TEST_F(LuaTableTest, Table01)
{
    // Create table
    lua_newtable(L);
    lua_pushinteger(L, 123);
    lua_setfield(L, -2, "a");

    lua_pushinteger(L, 456);
    lua_setfield(L, -2, "b");

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_getfield(L, -1, "a");
    ASSERT_EQ(LUA_TNUMBER, lua_type(L, -1));
    ASSERT_EQ(123, lua_tonumber(L, -1));
    lua_pop(L, 1);

    lua_getfield(L, -1, "b");
    ASSERT_EQ(LUA_TNUMBER, lua_type(L, -1));
    ASSERT_EQ(456, lua_tonumber(L, -1));
    lua_pop(L, 1);

    lua_pop(L, 1);

    // Create table again
    lua_newtable(L);
    lua_pushinteger(L, 123);
    lua_setfield(L, -2, "a");
    lua_pushinteger(L, 456);
    lua_setfield(L, -2, "b");

    int ret = setjmp(env);
    if (ret == 0)
    {
        // buffer_user - 1, expect error
        accept_panic = true;
        dmScript::CheckTable(L, m_Buf, buffer_used-1, -1);
        ASSERT_TRUE(0); // Never reached due to error
    }
    else
    {
        lua_pop(L, 1);
    }
}

TEST_F(LuaTableTest, Table02)
{
    // Create table
    lua_newtable(L);
    lua_pushboolean(L, 1);
    lua_setfield(L, -2, "foo");

    lua_pushstring(L, "kalle");
    lua_setfield(L, -2, "foo2");

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_getfield(L, -1, "foo");
    ASSERT_EQ(LUA_TBOOLEAN, lua_type(L, -1));
    ASSERT_EQ(1, lua_toboolean(L, -1));
    lua_pop(L, 1);

    lua_getfield(L, -1, "foo2");
    ASSERT_EQ(LUA_TSTRING, lua_type(L, -1));
    ASSERT_STREQ("kalle", lua_tostring(L, -1));
    lua_pop(L, 1);

    lua_pop(L, 1);

    // Create table again
    lua_newtable(L);
    lua_pushboolean(L, 1);
    lua_setfield(L, -2, "foo");

    lua_pushstring(L, "kalle");
    lua_setfield(L, -2, "foo2");

    int ret = setjmp(env);
    if (ret == 0)
    {
        // buffer_user - 1, expect error
        accept_panic = true;
        dmScript::CheckTable(L, m_Buf, buffer_used-1, -1);
        ASSERT_TRUE(0); // Never reached due to error
    }
    else
    {
        lua_pop(L, 1);
    }
}

TEST_F(LuaTableTest, case1308)
{
    // Create table
    lua_newtable(L);
    lua_pushstring(L, "ab");
    lua_setfield(L, -2, "a");

    lua_newtable(L);
    lua_pushinteger(L, 123);
    lua_setfield(L, -2, "x");

    lua_setfield(L, -2, "t");

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_getfield(L, -1, "a");
    ASSERT_EQ(LUA_TSTRING, lua_type(L, -1));
    ASSERT_STREQ("ab", lua_tostring(L, -1));
    lua_pop(L, 1);

    lua_getfield(L, -1, "t");
    ASSERT_EQ(LUA_TTABLE, lua_type(L, -1));
    lua_getfield(L, -1, "x");
    ASSERT_EQ(LUA_TNUMBER, lua_type(L, -1));
    ASSERT_EQ(123, lua_tonumber(L, -1));
    lua_pop(L, 1);
    lua_pop(L, 1);

    lua_pop(L, 1);
}


TEST_F(LuaTableTest, Vector3)
{
    // Create table
    lua_newtable(L);
    dmScript::PushVector3(L, Vectormath::Aos::Vector3(1,2,3));
    lua_setfield(L, -2, "v");

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_getfield(L, -1, "v");
    ASSERT_TRUE(dmScript::IsVector3(L, -1));
    Vectormath::Aos::Vector3* v = dmScript::CheckVector3(L, -1);
    ASSERT_EQ(1, v->getX());
    ASSERT_EQ(2, v->getY());
    ASSERT_EQ(3, v->getZ());
    lua_pop(L, 1);

    lua_pop(L, 1);
}

TEST_F(LuaTableTest, Vector4)
{
    // Create table
    lua_newtable(L);
    dmScript::PushVector4(L, Vectormath::Aos::Vector4(1,2,3,4));
    lua_setfield(L, -2, "v");

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_getfield(L, -1, "v");
    ASSERT_TRUE(dmScript::IsVector4(L, -1));
    Vectormath::Aos::Vector4* v = dmScript::CheckVector4(L, -1);
    ASSERT_EQ(1, v->getX());
    ASSERT_EQ(2, v->getY());
    ASSERT_EQ(3, v->getZ());
    ASSERT_EQ(4, v->getW());
    lua_pop(L, 1);

    lua_pop(L, 1);
}

TEST_F(LuaTableTest, Quat)
{
    // Create table
    lua_newtable(L);
    dmScript::PushQuat(L, Vectormath::Aos::Quat(1,2,3,4));
    lua_setfield(L, -2, "v");

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_getfield(L, -1, "v");
    ASSERT_TRUE(dmScript::IsQuat(L, -1));
    Vectormath::Aos::Quat* v = dmScript::CheckQuat(L, -1);
    ASSERT_EQ(1, v->getX());
    ASSERT_EQ(2, v->getY());
    ASSERT_EQ(3, v->getZ());
    ASSERT_EQ(4, v->getW());
    lua_pop(L, 1);

    lua_pop(L, 1);
}

TEST_F(LuaTableTest, Matrix4)
{
    // Create table
    lua_newtable(L);
    Vectormath::Aos::Matrix4 m;
    for (uint32_t i = 0; i < 4; ++i)
        for (uint32_t j = 0; j < 4; ++j)
            m.setElem((float) i, (float) j, (float) (i * 4 + j));
    dmScript::PushMatrix4(L, m);
    lua_setfield(L, -2, "v");

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_getfield(L, -1, "v");
    ASSERT_TRUE(dmScript::IsMatrix4(L, -1));
    Vectormath::Aos::Matrix4* v = dmScript::CheckMatrix4(L, -1);
    for (uint32_t i = 0; i < 4; ++i)
        for (uint32_t j = 0; j < 4; ++j)
            ASSERT_EQ(i * 4 + j, v->getElem(i, j));
    lua_pop(L, 1);

    lua_pop(L, 1);
}

TEST_F(LuaTableTest, Hash)
{
    int top = lua_gettop(L);

    // Create table
    lua_newtable(L);
    dmhash_t hash = dmHashString64("test");
    dmScript::PushHash(L, hash);
    lua_setfield(L, -2, "h");

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_getfield(L, -1, "h");
    ASSERT_TRUE(dmScript::IsHash(L, -1));
    dmhash_t hash2 = dmScript::CheckHash(L, -1);
    ASSERT_EQ(hash, hash2);
    lua_pop(L, 1);

    lua_pop(L, 1);

    ASSERT_EQ(top, lua_gettop(L));
}

TEST_F(LuaTableTest, URL)
{
    int top = lua_gettop(L);

    // Create table
    lua_newtable(L);
    dmMessage::URL url = dmMessage::URL();
    url.m_Socket = 1;
    url.m_Path = 2;
    url.m_Fragment = 3;
    dmScript::PushURL(L, url);
    lua_setfield(L, -2, "url");

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_getfield(L, -1, "url");
    ASSERT_TRUE(dmScript::IsURL(L, -1));
    dmMessage::URL url2 = *dmScript::CheckURL(L, -1);
    ASSERT_EQ(url.m_Socket, url2.m_Socket);
    ASSERT_EQ(url.m_Path, url2.m_Path);
    ASSERT_EQ(url.m_Fragment, url2.m_Fragment);
    lua_pop(L, 1);

    lua_pop(L, 1);

    ASSERT_EQ(top, lua_gettop(L));
}

TEST_F(LuaTableTest, MixedKeys)
{
    // Create table
    lua_newtable(L);

    lua_pushnumber(L, 1);
    lua_pushnumber(L, 2);
    lua_settable(L, -3);

    lua_pushstring(L, "key1");
    lua_pushnumber(L, 3);
    lua_settable(L, -3);

    lua_pushnumber(L, 2);
    lua_pushnumber(L, 4);
    lua_settable(L, -3);

    lua_pushstring(L, "key2");
    lua_pushnumber(L, 5);
    lua_settable(L, -3);

    uint32_t buffer_used = dmScript::CheckTable(L, m_Buf, sizeof(m_Buf), -1);
    (void) buffer_used;
    lua_pop(L, 1);

    dmScript::PushTable(L, m_Buf);

    lua_pushnumber(L, 1);
    lua_gettable(L, -2);
    ASSERT_EQ(LUA_TNUMBER, lua_type(L, -1));
    ASSERT_EQ(2, lua_tonumber(L, -1));
    lua_pop(L, 1);

    lua_pushstring(L, "key1");
    lua_gettable(L, -2);
    ASSERT_EQ(LUA_TNUMBER, lua_type(L, -1));
    ASSERT_EQ(3, lua_tonumber(L, -1));
    lua_pop(L, 1);

    lua_pushnumber(L, 2);
    lua_gettable(L, -2);
    ASSERT_EQ(LUA_TNUMBER, lua_type(L, -1));
    ASSERT_EQ(4, lua_tonumber(L, -1));
    lua_pop(L, 1);

    lua_pushstring(L, "key2");
    lua_gettable(L, -2);
    ASSERT_EQ(LUA_TNUMBER, lua_type(L, -1));
    ASSERT_EQ(5, lua_tonumber(L, -1));
    lua_pop(L, 1);

    lua_pop(L, 1);
}

static void RandomString(char* s, int max_len)
{
    int n = rand() % max_len + 1;
    for (int i = 0; i < n; ++i)
    {
        (*s++) = (char)(rand() % 256);
    }
    *s = '\0';
}

#if defined(__GNUC__)
#define NO_INLINE __attribute__ ((noinline))
#elif defined(_MSC_VER)
#define NO_INLINE __declspec(noinline)
#else
#error "Unsupported compiler: cannot specify 'noinline'."
#endif

//This is a helper function for working around an emscripten bug. See comment in the test "LuaTableTest Stress"
NO_INLINE void wrapSetJmp(lua_State *L, jmp_buf &env, char *buf, int buf_size){
    int ret = setjmp(env);
    if (ret == 0)
    {
        uint32_t buffer_used = dmScript::CheckTable(L, buf, buf_size, -1);
        (void) buffer_used;


        dmScript::PushTable(L, buf);
        lua_pop(L, 1);
    }
}

#undef NO_INLINE

TEST_F(LuaTableTest, Stress)
{
    accept_panic = true;

    for (int iter = 0; iter < 100; ++iter)
    {
        for (int buf_size = 0; buf_size < 256; ++buf_size)
        {
            int n = rand() % 15 + 1;

            lua_newtable(L);
            for (int i = 0; i < n; ++i)
            {
                int key_type = rand() % 2;
                if (key_type == 0)
                {
                    char key[12];
                    RandomString(key, 11);
                    lua_pushstring(L, key);
                }
                else if (key_type == 1)
                {
                    lua_pushnumber(L, rand() % (n + 1));
                }
                int value_type = rand() % 3;
                if (value_type == 0)
                {
                    lua_pushboolean(L, 1);
                }
                else if (value_type == 1)
                {
                    lua_pushnumber(L, 123);
                }
                else if (value_type == 2)
                {
                    char value[16];
                    RandomString(value, 15);
                    lua_pushstring(L, value);
                }

                lua_settable(L, -3);
            }
            // Add eight to ensure there is room for the header too.
            char* buf = new char[8 + buf_size];

            // Emscripten fastcomp does not support calling setjmp over and over like in this loop.
            // It requires the function calling setjump not to call setjmp more than 10 times before returning.
            // See emscripten bug https://github.com/kripken/emscripten/issues/2379
            // According on the comments of the task, this seems to be solved in 1.21.1 of emscripten.
            // As soon as that appears in emsdk list, we should upgrade and remove this work around.
            wrapSetJmp(L, env, buf, buf_size);
            lua_pop(L, 1);

            delete[] buf;
        }
    }
}

int main(int argc, char **argv)
{
    testing::InitGoogleTest(&argc, argv);
    int ret = RUN_ALL_TESTS();
    return ret;
}
