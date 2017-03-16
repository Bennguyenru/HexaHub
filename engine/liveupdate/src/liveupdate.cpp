#include "liveupdate.h"
#include "liveupdate_private.h"
#include <string.h>
#include <stdlib.h>

#include <ddf/ddf.h>

#include <dlib/log.h>

namespace dmLiveUpdate
{

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

            dmLiveUpdateDDF::HashAlgorithm algorithm = g_LiveUpdate.m_Manifest->m_DDF->m_Data.m_Header.m_ResourceHashAlgorithm;
            uint32_t hexDigestLength = HexDigestLength(algorithm) + 1;
            bool isUnique;
            char* scratch = (char*) malloc(hexDigestLength * sizeof(char*));
            for (uint32_t i = 0; i < resourceCount; ++i)
            {
                isUnique = true;
                
                dmResource::HashToString(algorithm, resources[i], scratch, hexDigestLength);
                for (int j = 0; j < uniqueCount; ++j) // only return unique hashes even if there are multiple resource instances in the collectionproxy
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
        dmLiveUpdateDDF::HashAlgorithm algorithm = manifest->m_DDF->m_Data.m_Header.m_ResourceHashAlgorithm;
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

        if (expectedLength == (hexDigestLength - 1))
        {
            for (uint32_t i = 0; i < expectedLength; ++i)
            {
                if (expected[i] != hexDigest[i])
                {
                    result = false;
                    break;
                }
            }
        }
        else
        {
            result = false;
        }

        free(digest);
        free(hexDigest);
        return result;
    }

    bool StoreResource(dmResource::Manifest* manifest, const char* expected, uint32_t expectedLength, const dmResourceArchive::LiveUpdateResource* resource)
    {
        if (manifest == 0x0 || resource->m_Data == 0x0)
        {
            return false;
        }
        
        bool verify = VerifyResource(manifest, expected, expectedLength, resource);
        if (!verify)
        {
            dmLogError("Verification of liveupdate resource failed for expected hash: %s", expected);
            return false;
        }

        bool result = true;
        dmLiveUpdateDDF::HashAlgorithm algorithm = manifest->m_DDF->m_Data.m_Header.m_ResourceHashAlgorithm;
        uint32_t digestLength = dmResource::HashLength(algorithm);
        uint8_t* digest = (uint8_t*) malloc(digestLength * sizeof(uint8_t));
        if (digest == 0x0)
        {
            dmLogError("Failed to allocate memory for hash calculation.");
            return false;
        }

        CreateResourceHash(algorithm, (const char*)resource->m_Data, resource->m_Count, digest);

        // DEBUG 
        uint32_t hexDigestLength = digestLength * 2 + 1;
        char* hexDigest = (char*) malloc(hexDigestLength * sizeof(char));
        dmResource::HashToString(algorithm, digest, hexDigest, hexDigestLength);
        char* slask = new char[hexDigestLength+1];
        memcpy(slask, hexDigest, hexDigestLength);
        slask[hexDigestLength] = '\0';
        dmLogInfo("hash: %s", slask);
        for (int i = 0; i < manifest->m_DDF->m_Data.m_Resources.m_Count; ++i)
        {
           uint8_t* hash = manifest->m_DDF->m_Data.m_Resources[i].m_Hash.m_Data.m_Data;
           int cmp = memcmp(hash, digest, digestLength);
            if (cmp == 0)
           {
               dmLogInfo("URL: %s", manifest->m_DDF->m_Data.m_Resources[i].m_Url);
           }
        }
        // END DEBUG

        char proj_id[dmResource::MANIFEST_PROJ_ID_LEN];
        dmResource::HashToString(dmLiveUpdateDDF::HASH_SHA1, manifest->m_DDF->m_Data.m_Header.m_ProjectIdentifier.m_Data.m_Data, proj_id, dmResource::MANIFEST_PROJ_ID_LEN);
        dmResource::Result res = dmResource::StoreResource(manifest, (const uint8_t*)digest, digestLength, resource, proj_id);

        if (res != dmResource::RESULT_OK)
        {
            dmLogError("Failed to store resource, result: %i", res);
            result = false;
        }

        free(digest);
        return result;
    }

    int AddManifest(dmResource::Manifest* manifest)
    {
        for (int i = 0; i < MAX_MANIFEST_COUNT; ++i)
        {
            if (g_LiveUpdate.m_Manifests[i] == 0x0)
            {
                g_LiveUpdate.m_Manifests[i] = manifest;
                return i;
            }
        }

        return -1;
    }

    dmResource::Manifest* GetManifest(int manifestIndex)
    {
        if (manifestIndex == CURRENT_MANIFEST)
        {
            return g_LiveUpdate.m_Manifest;
        }

        for (int i = 0; i < MAX_MANIFEST_COUNT; ++i)
        {
            if (i == manifestIndex)
            {
                return g_LiveUpdate.m_Manifests[i];
            }
        }

        return 0x0;
    }

    bool RemoveManifest(int manifestIndex)
    {
        if (manifestIndex >= 0 && manifestIndex < MAX_MANIFEST_COUNT)
        {
            if (g_LiveUpdate.m_Manifests[manifestIndex] != 0x0)
            {
                delete g_LiveUpdate.m_Manifests[manifestIndex];
                g_LiveUpdate.m_Manifests[manifestIndex] = 0x0;
                return true;
            }
        }

        return false;
    }

    void Initialize(const dmResource::HFactory factory)
    {
        g_LiveUpdate.m_Manifest = dmResource::GetManifest(factory);
    }

    void Finalize()
    {
        g_LiveUpdate.m_Manifest = 0x0;
    }

};
