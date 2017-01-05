#include <stdint.h>
#include <gtest/gtest.h>
#include "../resource.h"
#include "../resource_archive.h"

// new file format, generated test data
extern unsigned char RESOURCES_ARCI[];
extern uint32_t RESOURCES_ARCI_SIZE;
extern unsigned char RESOURCES_ARCD[];
extern uint32_t RESOURCES_ARCD_SIZE;
extern unsigned char RESOURCES_DMANIFEST[];
extern uint32_t RESOURCES_DMANIFEST_SIZE;

extern unsigned char RESOURCES_COMPRESSED_ARCI[];
extern uint32_t RESOURCES_COMPRESSED_ARCI_SIZE;
extern unsigned char RESOURCES_COMPRESSED_ARCD[];
extern uint32_t RESOURCES_COMPRESSED_ARCD_SIZE;
extern unsigned char RESOURCES_COMPRESSED_DMANIFEST[];
extern uint32_t RESOURCES_COMPRESSED_DMANIFEST_SIZE;

static const char* hashes[] = { "awesome hash here2", "awesome hash here5", "awesome hash here3", "awesome hash here4", "awesome hash here1" };
static const char* hash_not_found = "awesome hash NOT here";
static const char* names[] = { "/archive_data/file4.adc", "/archive_data/file1.adc", "/archive_data/file3.adc", "/archive_data/file2.adc", "/archive_data/file5.scriptc" };
static const char* data[] = { "file4_datafile4_datafile4_data", "file1_datafile1_datafile1_data", "file3_data", "file2_datafile2_datafile2_data", "stuff to test encryption" };

static const uint64_t path_hash[]       = { 0x1db7f0530911b1ce, 0x731d3cc48697dfe4, 0x8417331f14a42e4b, 0xb4870d43513879ba, 0xe1f97b41134ff4a6 };
static const char* path_name[]          = { "/archive_data/file4.adc", "/archive_data/file5.scriptc", "/archive_data/file1.adc", "/archive_data/file3.adc", "/archive_data/file2.adc" };
static const char* content[]            = {
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "stuff to test encryption",
    "file1_datafile1_datafile1_data",
    "file3_data",
    "file2_datafile2_datafile2_data"
};
static const uint8_t content_hash[][20] = {
    { 127U, 144U,   0U,  37U, 122U,  73U,  24U, 215U,   7U,  38U,  85U, 234U,  70U, 133U,  64U, 205U, 203U, 212U,  46U,  12U },
    {  95U, 158U,  27U, 108U, 112U,  93U, 159U, 220U, 188U,  65U, 128U,  98U, 243U, 234U,  63U, 106U,  51U, 100U,   9U,  20U },
    { 225U, 251U, 249U, 131U,  22U, 226U, 178U, 216U, 248U, 181U, 222U, 168U, 119U, 247U,  11U,  53U, 176U,  14U,  43U, 170U },
    {   3U,  86U, 172U, 159U, 110U, 187U, 139U, 211U, 219U,   5U, 203U, 115U, 150U,  43U, 182U, 252U, 136U, 228U, 122U, 181U },
    {  69U,  26U,  15U, 239U, 138U, 110U, 167U, 120U, 214U,  38U, 144U, 200U,  19U, 102U,  63U,  48U, 173U,  41U,  21U,  66U }
};
static const uint8_t compressed_content_hash[][20] = {
    { 206U, 246U, 241U, 188U, 170U, 142U,  34U, 244U, 115U,  87U,  65U,  38U,  88U,  34U, 188U,  33U, 144U,  44U,  18U,  46U },
    {  95U, 158U,  27U, 108U, 112U,  93U, 159U, 220U, 188U,  65U, 128U,  98U, 243U, 234U,  63U, 106U,  51U, 100U,   9U,  20U },
    { 110U, 207U, 167U,  68U,  57U, 224U,  20U,  24U, 135U, 248U, 166U, 192U, 197U, 173U,  48U, 150U,   3U,  64U, 180U,  88U },
    {   3U,  86U, 172U, 159U, 110U, 187U, 139U, 211U, 219U,   5U, 203U, 115U, 150U,  43U, 182U, 252U, 136U, 228U, 122U, 181U },
    {  16U, 184U, 254U, 147U, 172U,  48U,  89U, 214U,  29U,  90U, 128U, 156U,  37U,  60U, 100U,  69U, 246U, 252U, 122U,  99U }
};


TEST(dmResourceArchive, ManifestHeader)
{
    dmLiveUpdateDDF::ManifestFile* instance;
    dmResource::Result result = dmResource::ParseManifest(RESOURCES_DMANIFEST, RESOURCES_DMANIFEST_SIZE, instance);
    ASSERT_EQ(dmResource::RESULT_OK, result);

    ASSERT_EQ(dmResource::MANIFEST_MAGIC_NUMBER, instance->m_Data.m_Header.m_MagicNumber);
    ASSERT_EQ(dmResource::MANIFEST_VERSION, instance->m_Data.m_Header.m_Version);

    ASSERT_EQ(dmLiveUpdateDDF::HASH_SHA1, instance->m_Data.m_Header.m_ResourceHashAlgorithm);
    ASSERT_EQ(dmLiveUpdateDDF::HASH_SHA1, instance->m_Data.m_Header.m_SignatureHashAlgorithm);

    ASSERT_EQ(dmLiveUpdateDDF::SIGN_RSA, instance->m_Data.m_Header.m_SignatureSignAlgorithm);

    dmDDF::FreeMessage(instance);
}

TEST(dmResourceArchive, ResourceEntries)
{
    dmLiveUpdateDDF::ManifestFile* instance;
    dmResource::Result result = dmResource::ParseManifest(RESOURCES_DMANIFEST, RESOURCES_DMANIFEST_SIZE, instance);
    ASSERT_EQ(dmResource::RESULT_OK, result);

    ASSERT_EQ(5, instance->m_Data.m_Resources.m_Count);
    for (uint32_t i = 0; i < instance->m_Data.m_Resources.m_Count; ++i) {
        const char* current_path = instance->m_Data.m_Resources.m_Data[i].m_Url;
        uint64_t current_hash = dmHashString64(current_path);

        ASSERT_STRCASEEQ(path_name[i], current_path);
        ASSERT_EQ(path_hash[i], current_hash);

        for (uint32_t n = 0; n < instance->m_Data.m_Resources.m_Data[i].m_Hash.m_Data.m_Count; ++n) {
            uint8_t current_byte = instance->m_Data.m_Resources.m_Data[i].m_Hash.m_Data.m_Data[n];

            ASSERT_EQ(content_hash[i][n], current_byte);
        }
    }

    dmDDF::FreeMessage(instance);
}

TEST(dmResourceArchive, ResourceEntries_Compressed)
{
    dmLiveUpdateDDF::ManifestFile* instance;
    dmResource::Result result = dmResource::ParseManifest(RESOURCES_COMPRESSED_DMANIFEST, RESOURCES_COMPRESSED_DMANIFEST_SIZE, instance);
    ASSERT_EQ(dmResource::RESULT_OK, result);

    ASSERT_EQ(5, instance->m_Data.m_Resources.m_Count);
    for (uint32_t i = 0; i < instance->m_Data.m_Resources.m_Count; ++i) {
        const char* current_path = instance->m_Data.m_Resources.m_Data[i].m_Url;
        uint64_t current_hash = dmHashString64(current_path);

        ASSERT_STRCASEEQ(path_name[i], current_path);
        ASSERT_EQ(path_hash[i], current_hash);

        for (uint32_t n = 0; n < instance->m_Data.m_Resources.m_Data[i].m_Hash.m_Data.m_Count; ++n) {
            uint8_t current_byte = instance->m_Data.m_Resources.m_Data[i].m_Hash.m_Data.m_Data[n];

            ASSERT_EQ(compressed_content_hash[i][n], current_byte);
        }
    }

    dmDDF::FreeMessage(instance);
}

TEST(dmResourceArchive, Wrap)
{
    dmResourceArchive::HArchiveIndexContainer archive = 0;
    dmResourceArchive::Result result = dmResourceArchive::WrapArchiveBuffer2((void*) RESOURCES_ARCI, RESOURCES_ARCI_SIZE, RESOURCES_ARCD, &archive);
    ASSERT_EQ(dmResourceArchive::RESULT_OK, result);
    ASSERT_EQ(5U, dmResourceArchive::GetEntryCount2(archive));

    dmResourceArchive::EntryData entry;
    for (uint32_t i = 0; i < (sizeof(path_hash) / sizeof(path_hash[0])); ++i)
    {
        char buffer[1024 * 1024] = { 0 };
        result = dmResourceArchive::FindEntry2(archive, content_hash[i], &entry);
        ASSERT_EQ(dmResourceArchive::RESULT_OK, result);

        result = dmResourceArchive::Read2(archive, &entry, buffer);
        ASSERT_EQ(dmResourceArchive::RESULT_OK, result);

        ASSERT_EQ(strlen(content[i]), strlen(buffer));
        ASSERT_STRCASEEQ(content[i], buffer);
    }

    uint8_t invalid_hash[] = { 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U };
    result = dmResourceArchive::FindEntry2(archive, invalid_hash, &entry);
    ASSERT_EQ(dmResourceArchive::RESULT_NOT_FOUND, result);
}

TEST(dmResourceArchive, Wrap_Compressed)
{
    dmResourceArchive::HArchiveIndexContainer archive = 0;
    dmResourceArchive::Result result = dmResourceArchive::WrapArchiveBuffer2((void*) RESOURCES_COMPRESSED_ARCI, RESOURCES_COMPRESSED_ARCI_SIZE, (void*) RESOURCES_COMPRESSED_ARCD, &archive);
    ASSERT_EQ(dmResourceArchive::RESULT_OK, result);
    ASSERT_EQ(5U, dmResourceArchive::GetEntryCount2(archive));

    dmResourceArchive::EntryData entry;
    for (uint32_t i = 0; i < (sizeof(path_hash) / sizeof(path_hash[0])); ++i)
    {
        char buffer[1024 * 1024] = { 0 };
        result = dmResourceArchive::FindEntry2(archive, compressed_content_hash[i], &entry);
        ASSERT_EQ(dmResourceArchive::RESULT_OK, result);

        result = dmResourceArchive::Read2(archive, &entry, buffer);
        ASSERT_EQ(dmResourceArchive::RESULT_OK, result);

        ASSERT_EQ(strlen(content[i]), strlen(buffer));
        ASSERT_STRCASEEQ(content[i], buffer);
    }

    uint8_t invalid_hash[] = { 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U };
    result = dmResourceArchive::FindEntry2(archive, invalid_hash, &entry);
    ASSERT_EQ(dmResourceArchive::RESULT_NOT_FOUND, result);
}

TEST(dmResourceArchive, LoadFromDisk)
{
    dmResourceArchive::HArchiveIndexContainer archive = 0;
    const char* archive_path = "build/default/src/test/resources.arci";
    dmResourceArchive::Result result = dmResourceArchive::LoadArchive2(archive_path, &archive);
    ASSERT_EQ(dmResourceArchive::RESULT_OK, result);
    ASSERT_EQ(5U, dmResourceArchive::GetEntryCount2(archive));

    dmResourceArchive::EntryData entry;
    for (uint32_t i = 0; i < sizeof(names)/sizeof(names[0]); ++i)
    {
        char buffer[1024 * 1024] = { 0 };
        result = dmResourceArchive::FindEntry2(archive, content_hash[i], &entry);
        ASSERT_EQ(dmResourceArchive::RESULT_OK, result);

        result = dmResourceArchive::Read2(archive, &entry, buffer);
        ASSERT_EQ(dmResourceArchive::RESULT_OK, result);

        ASSERT_EQ(strlen(content[i]), strlen(buffer));
        ASSERT_STRCASEEQ(content[i], buffer);
    }

    uint8_t invalid_hash[] = { 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U };
    result = dmResourceArchive::FindEntry2(archive, invalid_hash, &entry);
    ASSERT_EQ(dmResourceArchive::RESULT_NOT_FOUND, result);
}

TEST(dmResourceArchive, LoadFromDisk_MissingArchive)
{
    dmResourceArchive::HArchiveIndexContainer archive = 0;
    const char* archive_path = "build/default/src/test/missing-archive.arci";
    dmResourceArchive::Result result = dmResourceArchive::LoadArchive2(archive_path, &archive);
    ASSERT_EQ(dmResourceArchive::RESULT_IO_ERROR, result);
}

TEST(dmResourceArchive, LoadFromDisk_Compressed)
{
    dmResourceArchive::HArchiveIndexContainer archive = 0;
    const char* archive_path = "build/default/src/test/resources_compressed.arci";
    dmResourceArchive::Result result = dmResourceArchive::LoadArchive2(archive_path, &archive);
    ASSERT_EQ(dmResourceArchive::RESULT_OK, result);
    ASSERT_EQ(5U, dmResourceArchive::GetEntryCount2(archive));

    dmResourceArchive::EntryData entry;
    for (uint32_t i = 0; i < sizeof(names)/sizeof(names[0]); ++i)
    {
        char buffer[1024 * 1024] = { 0 };
        result = dmResourceArchive::FindEntry2(archive, compressed_content_hash[i], &entry);
        ASSERT_EQ(dmResourceArchive::RESULT_OK, result);

        result = dmResourceArchive::Read2(archive, &entry, buffer);
        ASSERT_EQ(dmResourceArchive::RESULT_OK, result);

        ASSERT_EQ(strlen(content[i]), strlen(buffer));
        ASSERT_STRCASEEQ(content[i], buffer);
    }

    uint8_t invalid_hash[] = { 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U, 10U };
    result = dmResourceArchive::FindEntry2(archive, invalid_hash, &entry);
    ASSERT_EQ(dmResourceArchive::RESULT_NOT_FOUND, result);
}

int main(int argc, char **argv)
{
    testing::InitGoogleTest(&argc, argv);
    int ret = RUN_ALL_TESTS();
    return ret;
}
