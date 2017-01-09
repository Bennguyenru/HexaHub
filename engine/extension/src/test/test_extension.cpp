#include <stdlib.h>
#include <gtest/gtest.h>
#include "../extension.h"
#include "test_extension.h"

extern "C"
{
#include "lua/lua.h"
#include "lua/lauxlib.h"
}

// Extension in a separate library. See comment in test_extension_lib.cpp

static dmGraphics::NativeHandles CreateDummyHandles()
{
    dmGraphics::NativeHandles native_handles;
    native_handles.m_Dummy = (void*)TEST_EXTENSION_DUMMY_HANDLE;
    return native_handles;
}

extern int g_TestAppInitCount;
extern int g_TestAppEventCount;

TEST(dmExtension, Basic)
{
    dmExtension::AppParams appparams;
    appparams.m_NativeHandles = CreateDummyHandles();
    ASSERT_EQ(0, g_TestAppInitCount);
    ASSERT_EQ(dmExtension::RESULT_OK, dmExtension::AppInitialize(&appparams));
    ASSERT_EQ(1, g_TestAppInitCount);
    ASSERT_STREQ("test", dmExtension::GetFirstExtension()->m_Name);
    ASSERT_EQ(0, dmExtension::GetFirstExtension()->m_Next);

    dmExtension::Params params;
    dmExtension::Event event;
    event.m_Event = dmExtension::EVENT_ID_ACTIVATEAPP;
    dmExtension::DispatchEvent(&params, &event);
    ASSERT_EQ(1, g_TestAppEventCount);
    event.m_Event = dmExtension::EVENT_ID_DEACTIVATEAPP;
    dmExtension::DispatchEvent(&params, &event);
    ASSERT_EQ(0, g_TestAppEventCount);

    dmExtension::AppFinalize(&appparams);
    ASSERT_EQ(0, g_TestAppInitCount);
}

int main(int argc, char **argv)
{
    testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}
