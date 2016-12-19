#include <stdint.h>
#include <stdio.h>
#include <string>

#include <gtest/gtest.h>
#include <dlib/log.h>
#include <dlib/ssdp.h>
#include <dlib/ssdp_private.h>
#include <dlib/dstrings.h>

namespace
{

    static const char* DEVICE_DESC =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:defold=\"urn:schemas-defold-com:DEFOLD-1-0\">\n"
        "    <specVersion>\n"
        "        <major>1</major>\n"
        "        <minor>0</minor>\n"
        "    </specVersion>\n"
        "    <device>\n"
        "        <deviceType>upnp:rootdevice</deviceType>\n"
        "        <friendlyName>Defold System</friendlyName>\n"
        "        <manufacturer>Defold</manufacturer>\n"
        "        <modelName>Defold Engine 1.0</modelName>\n"
        "        <UDN>%s</UDN>\n"
        "    </device>\n"
        "</root>\n";

    void CreateRandomNumberString(char* string, unsigned int size)
    {
        for (unsigned int i = 0; i < (size - 1); ++i) {
            int ascii = '0' + (rand() % 10);
            string[i] = (char) ascii;
        }

        string[size - 1] = 0x0;
    }

    void CreateRandomUDN(char* string, unsigned int size)
    {
        char deviceId[9] = { 0 };
        CreateRandomNumberString(deviceId, 9);
        DM_SNPRINTF(string, size, "uuid:%s-3d4f-339c-8c4d-f7c6da6771c8", deviceId);
    }

    void CreateDeviceDescriptionXML(char* string, const char* udn, unsigned int size)
    {
        DM_SNPRINTF(string, size, DEVICE_DESC, udn);
    }

    void CreateDeviceDescription(dmSSDP::DeviceDesc* deviceDesc)
    {
        char* id = (char*) calloc(15, sizeof(char));
        char* desc = (char*) calloc(513, sizeof(char));

        CreateRandomNumberString(id, 15);
        CreateRandomUDN(deviceDesc->m_UDN, 43);
        CreateDeviceDescriptionXML(desc, deviceDesc->m_UDN, 513);

        deviceDesc->m_Id = id;
        deviceDesc->m_DeviceType = "upnp:rootdevice";
        deviceDesc->m_DeviceDescription = desc;
    }

    void FreeDeviceDescription(dmSSDP::DeviceDesc* deviceDesc)
    {
        free((void*) deviceDesc->m_Id);
        free((void*) deviceDesc->m_DeviceDescription);
    }


    int GetInterfaces(dmSocket::IfAddr* interfaces, uint32_t size)
    {
        int index = 0;
        uint32_t if_addr_count = 0;
        dmSocket::GetIfAddresses(interfaces, size, &if_addr_count);
        if (if_addr_count > 0)
        {
            for (int i = 0; i < size; ++i)
            {
                if (!dmSocket::Empty(interfaces[i].m_Address))
                {
                    if (interfaces[i].m_Address.m_family == dmSocket::DOMAIN_IPV4)
                    {
                        if (i > index)
                        {
                            interfaces[index] = interfaces[i];
                        }

                        index += 1;
                    }
                }
            }
        }

        return index;
    }

    dmSSDP::SSDP* CreateSSDPInstance()
    {
        dmSSDP::SSDP* instance = new dmSSDP::SSDP();
        dmSSDP::NewParams params;
        dmSSDP::Result actual = dmSSDP::New(&params, &instance);

        return instance;
    }

    void RemoveRegisteredDevice(dmSSDP::SSDP* instance, dmSSDP::DeviceDesc* deviceDesc)
    {
        dmhash_t hashId = dmHashString64(deviceDesc->m_Id);
        dmSSDP::Device** device = instance->m_RegistredEntries.Get(hashId);
        delete *device;
        instance->m_RegistredEntries.Erase(hashId);
    }

};

class dmSSDPTest: public ::testing::Test
{
public:

    virtual void SetUp()
    {
        dmSocket::Initialize();
    }

    virtual void TearDown()
    {
        dmSocket::Finalize();
    }

};


/* -------------------------------------------------------------------------*
 * (Internal functions) Create/Connect new SSDP sockets
 * ------------------------------------------------------------------------ */
TEST_F(dmSSDPTest, NewSocket_IPv4)
{
    dmSocket::Socket instance = dmSSDP::NewSocket(dmSocket::DOMAIN_IPV4);
    ASSERT_NE(dmSocket::INVALID_SOCKET_HANDLE, instance);

    dmSocket::Result actual = dmSocket::Delete(instance);
    ASSERT_EQ(dmSocket::RESULT_OK, actual);
}

TEST_F(dmSSDPTest, NewSocket_IPv6)
{
    dmSocket::Socket instance = dmSSDP::NewSocket(dmSocket::DOMAIN_IPV6);
    ASSERT_NE(dmSocket::INVALID_SOCKET_HANDLE, instance);

    dmSocket::Result actual = dmSocket::Delete(instance);
    ASSERT_EQ(dmSocket::RESULT_OK, actual);
}

TEST_F(dmSSDPTest, Connect)
{
    dmSSDP::SSDP* instance = new dmSSDP::SSDP();
    dmSSDP::Result actual = dmSSDP::Connect(instance);
    ASSERT_EQ(dmSSDP::RESULT_OK, actual);

    // Teardown
    dmSSDP::Disconnect(instance);
    delete instance;
}


/* -------------------------------------------------------------------------*
 * (Exposed function) Create/Delete new SSDP instances
 * ------------------------------------------------------------------------ */
TEST_F(dmSSDPTest, New)
{
    // Setup
    dmSSDP::SSDP* instance = NULL;
    dmSSDP::NewParams params;
    dmSSDP::Result actual = dmSSDP::New(&params, &instance);

    // Test
    ASSERT_EQ(dmSSDP::RESULT_OK, actual);
    ASSERT_EQ(1800, instance->m_MaxAge);
    ASSERT_EQ(1, instance->m_Announce);
    ASSERT_EQ(900, instance->m_AnnounceInterval);
    ASSERT_TRUE(instance->m_HttpServer != NULL);

    // Teardown
    dmHttpServer::Delete(instance->m_HttpServer);
    dmSSDP::Disconnect(instance);
    delete instance;
}

TEST_F(dmSSDPTest, Delete)
{
    // Setup
    dmSSDP::SSDP* instance = CreateSSDPInstance();

    // Test
    dmSSDP::Result actual = Delete(instance);
    ASSERT_EQ(dmSSDP::RESULT_OK, actual);
}


/* -------------------------------------------------------------------------*
 * (Exposed function) Register/Remove device for SSDP instance
 * ------------------------------------------------------------------------ */
TEST_F(dmSSDPTest, RegisterDevice)
{
    // Setup
    dmSSDP::SSDP* instance = CreateSSDPInstance();
    dmSSDP::DeviceDesc deviceDesc;
    CreateDeviceDescription(&deviceDesc);

    // Test
    dmSSDP::Result actual = dmSSDP::RegisterDevice(instance, &deviceDesc);
    ASSERT_EQ(dmSSDP::RESULT_OK, actual);

    actual = dmSSDP::RegisterDevice(instance, &deviceDesc);
    ASSERT_EQ(dmSSDP::RESULT_ALREADY_REGISTRED, actual);

    // Teardown
    RemoveRegisteredDevice(instance, &deviceDesc);
    FreeDeviceDescription(&deviceDesc);
    delete instance;
}

TEST_F(dmSSDPTest, RegisterDevice_MaximumDevices)
{
    // Setup
    dmSSDP::SSDP* instance = CreateSSDPInstance();

    // Test
    dmSSDP::DeviceDesc deviceDescs[32];
    for (unsigned int i = 0; i < 32; ++i)
    {
        CreateDeviceDescription(&deviceDescs[i]);
        dmSSDP::Result actual = dmSSDP::RegisterDevice(instance, &deviceDescs[i]);
        ASSERT_EQ(dmSSDP::RESULT_OK, actual);
    }

    dmSSDP::DeviceDesc deviceDescOverflow;
    CreateDeviceDescription(&deviceDescOverflow);
    dmSSDP::Result actual = dmSSDP::RegisterDevice(instance, &deviceDescOverflow);
    ASSERT_EQ(dmSSDP::RESULT_OUT_OF_RESOURCES, actual);
    FreeDeviceDescription(&deviceDescOverflow);

    // Teardown
    for (unsigned int i = 0; i < 32; ++i)
    {
        RemoveRegisteredDevice(instance, &deviceDescs[i]);
        FreeDeviceDescription(&deviceDescs[i]);
    }
    delete instance;
}

TEST_F(dmSSDPTest, DeregisterDevice)
{
    // Setup
    dmSSDP::SSDP* instance = CreateSSDPInstance();

    dmSSDP::DeviceDesc deviceDesc;
    CreateDeviceDescription(&deviceDesc);

    // Test
    dmSSDP::Result actual = dmSSDP::RegisterDevice(instance, &deviceDesc);
    ASSERT_EQ(dmSSDP::RESULT_OK, actual);

    actual = dmSSDP::DeregisterDevice(instance, deviceDesc.m_Id);
    ASSERT_EQ(dmSSDP::RESULT_OK, actual);

    actual = dmSSDP::DeregisterDevice(instance, deviceDesc.m_Id);
    ASSERT_EQ(dmSSDP::RESULT_NOT_REGISTRED, actual);

    // Teardown
    FreeDeviceDescription(&deviceDesc);
    delete instance;
}

/* -------------------------------------------------------------------------*
 * (Internal functions) Update SSDP instance
 * ------------------------------------------------------------------------ */
TEST_F(dmSSDPTest, UpdateListeningSockets)
{
    // Setup
    dmSSDP::SSDP* instance = CreateSSDPInstance();

    // Test
    dmSocket::IfAddr interfaces[dmSSDP::SSDP_MAX_LOCAL_ADDRESSES] = { 0 };
    uint32_t interface_count = GetInterfaces(interfaces, dmSSDP::SSDP_MAX_LOCAL_ADDRESSES);
    ASSERT_GE(interface_count, 1) << "There are no IPv4 interface(s) available";

    dmSSDP::UpdateListeningSockets(instance, interfaces, interface_count);

    ASSERT_EQ(interface_count, instance->m_LocalAddrCount);

    for (unsigned int i = 0; i < interface_count; ++i)
    {
        ASSERT_EQ(interfaces[i].m_Address, instance->m_LocalAddr[i].m_Address)
            << "An interface has been ignored";
        ASSERT_NE(dmSocket::INVALID_SOCKET_HANDLE, instance->m_LocalAddrSocket[i])
            << "An interface has an invalid socket handle";
        dmSocket::Delete(instance->m_LocalAddrSocket[i]);
    }

    // Teardown
    delete instance;
}

TEST_F(dmSSDPTest, SendAnnounce)
{
    // Setup
    dmSSDP::Result result = dmSSDP::RESULT_OK;
    dmSSDP::SSDP* instance = CreateSSDPInstance();

    dmSSDP::DeviceDesc deviceDesc;
    CreateDeviceDescription(&deviceDesc);
    result = dmSSDP::RegisterDevice(instance, &deviceDesc);
    ASSERT_EQ(dmSSDP::RESULT_OK, result);
    dmSSDP::Device** device = instance->m_RegistredEntries.Get(dmHashString64(deviceDesc.m_Id));

    dmSocket::IfAddr interfaces[dmSSDP::SSDP_MAX_LOCAL_ADDRESSES] = { 0 };
    uint32_t interface_count = GetInterfaces(interfaces, dmSSDP::SSDP_MAX_LOCAL_ADDRESSES);
    dmSSDP::UpdateListeningSockets(instance, interfaces, interface_count);

    // Test
    ASSERT_TRUE(dmSSDP::SendAnnounce(instance, *device, 0));

    // Teardown
    for (unsigned int i = 0; i < interface_count; ++i)
    {
        dmSocket::Delete(instance->m_LocalAddrSocket[i]);
    }
    RemoveRegisteredDevice(instance, &deviceDesc);
    FreeDeviceDescription(&deviceDesc);
    delete instance;
}

TEST_F(dmSSDPTest, SendUnannounce)
{
    // Setup
    dmSSDP::Result result = dmSSDP::RESULT_OK;
    dmSSDP::SSDP* instance = CreateSSDPInstance();

    dmSSDP::DeviceDesc deviceDesc;
    CreateDeviceDescription(&deviceDesc);
    result = dmSSDP::RegisterDevice(instance, &deviceDesc);
    ASSERT_EQ(dmSSDP::RESULT_OK, result);
    dmSSDP::Device** device = instance->m_RegistredEntries.Get(dmHashString64(deviceDesc.m_Id));

    dmSocket::IfAddr interfaces[dmSSDP::SSDP_MAX_LOCAL_ADDRESSES] = { 0 };
    uint32_t interface_count = GetInterfaces(interfaces, dmSSDP::SSDP_MAX_LOCAL_ADDRESSES);
    dmSSDP::UpdateListeningSockets(instance, interfaces, interface_count);

    // Test
    ASSERT_NO_FATAL_FAILURE(dmSSDP::SendUnannounce(instance, *device, 0));

    // Teardown
    for (unsigned int i = 0; i < interface_count; ++i)
    {
        dmSocket::Delete(instance->m_LocalAddrSocket[i]);
    }
    RemoveRegisteredDevice(instance, &deviceDesc);
    FreeDeviceDescription(&deviceDesc);
    delete instance;
}

int main(int argc, char **argv)
{
    srand(time(NULL));
    dmLogSetlevel(DM_LOG_SEVERITY_DEBUG);
    testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}