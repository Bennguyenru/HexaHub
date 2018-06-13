#include "liveupdate.h"
#include "liveupdate_private.h"
#include <string.h>
#include <stdlib.h>

#include <ddf/ddf.h>

#include <dlib/log.h>
#include <dlib/sys.h>

namespace dmLiveUpdate
{
    Result ResourceResultToLiveupdateResult(dmResource::Result r)
    {
        Result result;
        switch (r)
        {
            case dmResource::RESULT_OK:
                result = RESULT_OK;
                break;
            case dmResource::RESULT_IO_ERROR:
                result = RESULT_INVALID_RESOURCE;
                break;
            case dmResource::RESULT_FORMAT_ERROR:
                result = RESULT_INVALID_RESOURCE;
                break;
            case dmResource::RESULT_VERSION_MISMATCH:
                result = RESULT_VERSION_MISMATCH;
                break;
            case dmResource::RESULT_SIGNATURE_MISMATCH:
                result = RESULT_SIGNATURE_MISMATCH;
                break;
            case dmResource::RESULT_NOT_SUPPORTED:
                result = RESULT_SCHEME_MISMATCH;
                break;
            default:
                result = RESULT_INVALID_RESOURCE;
                break;
        }
        return result;
    }

    struct LiveUpdate
    {
        LiveUpdate()
        {
            memset(this, 0x0, sizeof(*this));
        }

        dmResource::Manifest* m_Manifest;
        dmResource::Manifest* m_Manifests[MAX_MANIFEST_COUNT];
    };

    LiveUpdate g_LiveUpdate;
    /// Resource system factory
    static dmResource::HFactory m_ResourceFactory = 0x0;

    /** ***********************************************************************
     ** LiveUpdate utility functions
     ********************************************************************** **/

    uint32_t GetMissingResources(const dmhash_t urlHash, char*** buffer)
    {
        uint32_t resourceCount = MissingResources(g_LiveUpdate.m_Manifest, urlHash, NULL, 0);
        uint32_t uniqueCount = 0;
        if (resourceCount > 0)
        {
            uint8_t** resources = (uint8_t**) malloc(resourceCount * sizeof(uint8_t*));
            *buffer = (char**) malloc(resourceCount * sizeof(char**));
            MissingResources(g_LiveUpdate.m_Manifest, urlHash, resources, resourceCount);

            dmLiveUpdateDDF::HashAlgorithm algorithm = g_LiveUpdate.m_Manifest->m_DDFData->m_Header.m_ResourceHashAlgorithm;
            uint32_t hexDigestLength = HexDigestLength(algorithm) + 1;
            bool isUnique;
            char* scratch = (char*) malloc(hexDigestLength * sizeof(char*));
            for (uint32_t i = 0; i < resourceCount; ++i)
            {
                isUnique = true;
                dmResource::HashToString(algorithm, resources[i], scratch, hexDigestLength);
                for (uint32_t j = 0; j < uniqueCount; ++j) // only return unique hashes even if there are multiple resource instances in the collectionproxy
                {
                    if (memcmp((*buffer)[j], scratch, hexDigestLength) == 0)
                    {
                        isUnique = false;
                        break;
                    }
                }
                if (isUnique)
                {
                    (*buffer)[uniqueCount] = (char*) malloc(hexDigestLength * sizeof(char*));
                    memcpy((*buffer)[uniqueCount], scratch, hexDigestLength);
                    ++uniqueCount;
                }
            }
            free(scratch);
            free(resources);
        }
        return uniqueCount;
    }

    bool VerifyResource(dmResource::Manifest* manifest, const char* expected, uint32_t expectedLength, const dmResourceArchive::LiveUpdateResource* resource)
    {
        if (manifest == 0x0 || resource->m_Data == 0x0)
        {
            return false;
        }

        bool result = true;
        dmLiveUpdateDDF::HashAlgorithm algorithm = manifest->m_DDFData->m_Header.m_ResourceHashAlgorithm;
        uint32_t digestLength = dmResource::HashLength(algorithm);
        uint8_t* digest = (uint8_t*) malloc(digestLength * sizeof(uint8_t));
        if (digest == 0x0)
        {
            dmLogError("Failed to allocate memory for hash calculation.");
            return false;
        }

        CreateResourceHash(algorithm, (const char*)resource->m_Data, resource->m_Count, digest);

        uint32_t hexDigestLength = digestLength * 2 + 1;
        char* hexDigest = (char*) malloc(hexDigestLength * sizeof(char));
        if (hexDigest == 0x0)
        {
            dmLogError("Failed to allocate memory for hash calculation.");
            free(digest);
            return false;
        }

        dmResource::HashToString(algorithm, digest, hexDigest, hexDigestLength);

        result = dmResource::HashCompare((const uint8_t*)hexDigest, hexDigestLength-1, (const uint8_t*)expected, expectedLength) == dmResource::RESULT_OK;

        free(digest);
        free(hexDigest);
        return result;
    }

    bool VerifyManifestSupportedEngineVersion(dmResource::Manifest* manifest)
    {
        // Calculate running dmengine version SHA1 hash
        dmSys::EngineInfo engine_info;
        dmSys::GetEngineInfo(&engine_info);
        bool engine_version_supported = false;
        uint32_t engine_digest_len = dmResource::HashLength(dmLiveUpdateDDF::HASH_SHA1);
        uint8_t* engine_digest = (uint8_t*) malloc(engine_digest_len * sizeof(uint8_t));
        uint32_t engine_hex_digest_len = engine_digest_len * 2 + 1;
        char* engine_hex_digest = (char*) malloc(engine_hex_digest_len * sizeof(char));

        CreateResourceHash(dmLiveUpdateDDF::HASH_SHA1, engine_info.m_Version, strlen(engine_info.m_Version), engine_digest);
        dmResource::HashToString(dmLiveUpdateDDF::HASH_SHA1, engine_digest, engine_hex_digest, engine_hex_digest_len);

        // Compare manifest supported versions to running dmengine version
        dmLiveUpdateDDF::HashDigest* versions = manifest->m_DDFData->m_EngineVersions.m_Data;
        uint32_t version_hex_digest_len = dmResource::HashLength(dmLiveUpdateDDF::HASH_SHA1) * 2 + 1;
        char* version_hex_digest = (char*)malloc(version_hex_digest_len);
        for (uint32_t i = 0; i < manifest->m_DDFData->m_EngineVersions.m_Count; ++i)
        {
            dmResource::HashToString(dmLiveUpdateDDF::HASH_SHA1, versions[i].m_Data.m_Data, version_hex_digest, versions[i].m_Data.m_Count * 2 + 1);
            if (memcmp(engine_hex_digest, version_hex_digest, engine_hex_digest_len) == 0)
            {
                engine_version_supported = true;
                break;
            }
        }
        free(version_hex_digest);
        free(engine_hex_digest);
        free(engine_digest);

        if (!engine_version_supported)
        {
            dmLogError("Loaded manifest does not support current engine version (%s)", engine_info.m_Version);
        }

        return engine_version_supported;
    }

    Result VerifyManifestSignature(dmResource::Manifest* manifest)
    {
        dmLiveUpdateDDF::HashAlgorithm algorithm = manifest->m_DDFData->m_Header.m_SignatureHashAlgorithm;
        uint32_t digest_len = dmResource::HashLength(algorithm);
        uint8_t* digest = (uint8_t*) malloc(digest_len * sizeof(uint8_t));
        if (digest == 0x0)
        {
            dmLogError("Failed to allocate memory for hash calculation.");
            return RESULT_MEM_ERROR;
        }

        dmLiveUpdate::CreateManifestHash(algorithm, manifest->m_DDF->m_Data.m_Data, manifest->m_DDF->m_Data.m_Count, digest);

        uint32_t hex_digest_len = digest_len * 2 + 1;
        char* hex_digest = (char*) malloc(hex_digest_len * sizeof(char));
        if (hex_digest == 0x0)
        {
            dmLogError("Failed to allocate memory for hash calculation.");
            free(digest);
            return RESULT_MEM_ERROR;
        }

        dmResource::HashToString(algorithm, digest, hex_digest, hex_digest_len);

        Result result = ResourceResultToLiveupdateResult(dmResource::VerifyManifestHash(m_ResourceFactory, manifest, (const uint8_t*)hex_digest, hex_digest_len));

        free(hex_digest);
        free(digest);

        return result;
    }

    Result VerifyManifest(dmResource::Manifest* manifest)
    {
        if (!VerifyManifestSupportedEngineVersion(manifest))
            return RESULT_ENGINE_VERSION_MISMATCH;

        return VerifyManifestSignature(manifest);
    }

    Result ParseManifestBin(uint8_t* manifest_data, size_t manifest_len, dmResource::Manifest* manifest)
    {
        return ResourceResultToLiveupdateResult(dmResource::ParseManifestDDF(manifest_data, manifest_len, manifest));
    }

    Result StoreManifest(dmResource::Manifest* manifest)
    {
        Result res = dmResource::StoreManifest(manifest) == dmResource::RESULT_OK ? RESULT_OK : RESULT_INVALID_RESOURCE;
        return res;
    }

    Result StoreResourceAsync(dmResource::Manifest* manifest, const char* expected_digest, const uint32_t expected_digest_length, const dmResourceArchive::LiveUpdateResource* resource, void (*callback)(StoreResourceCallbackData*), StoreResourceCallbackData& callback_data)
    {
        if (manifest == 0x0 || resource->m_Data == 0x0)
        {
            return RESULT_MEM_ERROR;
        }

        AsyncResourceRequest request;
        request.m_Manifest = manifest;
        request.m_ExpectedResourceDigestLength = expected_digest_length;
        request.m_ExpectedResourceDigest = expected_digest;
        request.m_Resource.Set(*resource);
        request.m_CallbackData = callback_data;
        request.m_Callback = callback;
        bool res = AddAsyncResourceRequest(request);
        return res == true ? RESULT_OK : RESULT_INVALID_RESOURCE;
    }

    Result NewArchiveIndexWithResource(dmResource::Manifest* manifest, const char* expected_digest, const uint32_t expected_digest_length, const dmResourceArchive::LiveUpdateResource* resource, dmResourceArchive::HArchiveIndex& out_new_index)
    {
        out_new_index = 0x0;
        if(!VerifyResource(manifest, expected_digest, expected_digest_length, resource))
        {
            dmLogError("Verification failure for Liveupdate archive for resource: %s", expected_digest);
            return RESULT_INVALID_RESOURCE;
        }

        dmLiveUpdateDDF::HashAlgorithm algorithm = manifest->m_DDFData->m_Header.m_ResourceHashAlgorithm;
        uint32_t digestLength = dmResource::HashLength(algorithm);
        uint8_t* digest = (uint8_t*) malloc(digestLength);
        if(digest == 0x0)
        {
            dmLogError("Failed to allocate memory for hash calculation for resource: %s", expected_digest);
            return RESULT_MEM_ERROR;
        }
        CreateResourceHash(algorithm, (const char*)resource->m_Data, resource->m_Count, digest);

        char proj_id[dmResource::MANIFEST_PROJ_ID_LEN];
        dmResource::HashToString(dmLiveUpdateDDF::HASH_SHA1, manifest->m_DDFData->m_Header.m_ProjectIdentifier.m_Data.m_Data, proj_id, dmResource::MANIFEST_PROJ_ID_LEN);

        dmResource::Result res = dmResource::NewArchiveIndexWithResource(manifest, digest, digestLength, resource, proj_id, out_new_index);
        free(digest);
        return (res == dmResource::RESULT_OK) ? RESULT_OK : RESULT_INVALID_RESOURCE;
    }

    void SetNewArchiveIndex(dmResourceArchive::HArchiveIndexContainer archive_container, dmResourceArchive::HArchiveIndex new_index, bool mem_mapped)
    {
        dmResourceArchive::SetNewArchiveIndex(archive_container, new_index, mem_mapped);
    }

    dmResource::Manifest* GetCurrentManifest()
    {
        return g_LiveUpdate.m_Manifest;
    }

    void Initialize(const dmResource::HFactory factory)
    {
        m_ResourceFactory = factory;
        g_LiveUpdate.m_Manifest = dmResource::GetManifest(factory);
        dmLiveUpdate::AsyncInitialize(factory);
    }

    void Finalize()
    {
        g_LiveUpdate.m_Manifest = 0x0;
        dmLiveUpdate::AsyncFinalize();
    }

    void Update()
    {
        AsyncUpdate();
    }

};
