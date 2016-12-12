#include <stdlib.h>
#include <gtest/gtest.h>
#include <dlib/log.h>
#include <dlib/time.h>
#include <dlib/sys.h>
#include <script/script.h>

extern "C"
{
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

#include "../crash.h"

class dmCrashTest : public ::testing::Test
{
    public:

        virtual void SetUp()
        {
            dmCrash::Init("TEST", "0123456789abcdef0123456789abcdef01234567");
        }

        virtual void TearDown()
        {

        }
    private:
};

TEST_F(dmCrashTest, Initialize)
{

}

TEST_F(dmCrashTest, TestLoad)
{
    /*
    dmCrash::WriteDump();

    dmCrash::HDump d = dmCrash::LoadPrevious();
    ASSERT_NE(d, 0);

    dmSys::SystemInfo info;
    dmSys::GetSystemInfo(&info);

    ASSERT_EQ(0, strcmp("TEST", dmCrash::GetSysField(d, dmCrash::SYSFIELD_ENGINE_VERSION)));
    ASSERT_EQ(0, strcmp("0123456789abcdef0123456789abcdef01234567", dmCrash::GetSysField(d, dmCrash::SYSFIELD_ENGINE_HASH)));
    ASSERT_EQ(0, strcmp(info.m_DeviceModel, dmCrash::GetSysField(d, dmCrash::SYSFIELD_DEVICE_MODEL)));
    ASSERT_EQ(0, strcmp(info.m_Manufacturer, dmCrash::GetSysField(d, dmCrash::SYSFIELD_MANUFACTURER)));
    ASSERT_EQ(0, strcmp(info.m_SystemName, dmCrash::GetSysField(d, dmCrash::SYSFIELD_SYSTEM_NAME)));
    ASSERT_EQ(0, strcmp(info.m_SystemVersion, dmCrash::GetSysField(d, dmCrash::SYSFIELD_SYSTEM_VERSION)));
    ASSERT_EQ(0, strcmp(info.m_Language, dmCrash::GetSysField(d, dmCrash::SYSFIELD_LANGUAGE)));
    ASSERT_EQ(0, strcmp(info.m_DeviceLanguage, dmCrash::GetSysField(d, dmCrash::SYSFIELD_DEVICE_LANGUAGE)));
    ASSERT_EQ(0, strcmp(info.m_Territory, dmCrash::GetSysField(d, dmCrash::SYSFIELD_TERRITORY)));

    uint32_t addresses = dmCrash::GetBacktraceAddrCount(d);
    ASSERT_GT(addresses, 4);
    for (uint32_t i=0;i!=addresses;i++)
    {
        ASSERT_NE((void*)0, dmCrash::GetBacktraceAddr(d, i));
    }

    char buf[4096];

    int count = 0;
    for (uint32_t i=0;true;i++)
    {
        const char *name = dmCrash::GetModuleName(d, i);
        void *addr = dmCrash::GetModuleAddr(d, i);
        if (!name)
        {
            break;
        }

        ASSERT_NE((void*) 0, addr);

        // do this just to catch any misbehaving
        strcpy(buf, name);
        count++;
    }

    ASSERT_GT(count, 3);
    */
}

TEST_F(dmCrashTest, TestPurgeCustomPath)
{
    dmCrash::SetFilePath("remove-me");
    dmCrash::Purge();
    dmCrash::WriteDump();
    ASSERT_NE(dmCrash::LoadPrevious(), 0);
    dmCrash::Purge();
    ASSERT_EQ(dmCrash::LoadPrevious(), 0);
}

TEST_F(dmCrashTest, TestPurgeDefaultPath)
{
    dmCrash::Purge();
    dmCrash::WriteDump();
    ASSERT_NE(dmCrash::LoadPrevious(), 0);
    dmCrash::Purge();
    ASSERT_EQ(dmCrash::LoadPrevious(), 0);
}


int main(int argc, char **argv)
{
    testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}
