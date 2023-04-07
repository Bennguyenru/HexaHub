// Copyright 2020-2023 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#ifndef DM_RESOURCE_PROVIDER_ARCHIVE_H
#define DM_RESOURCE_PROVIDER_ARCHIVE_H

#include "../resource_archive.h"

namespace dmResource
{
    struct Manifest;
}

namespace dmResourceProvider
{
    struct ArchiveLoader;
    typedef void* HArchiveInternal;
};

namespace dmResourceProviderArchive
{
    dmResourceProvider::Result CreateArchive(
                uint8_t* manifest_data, uint32_t manifest_data_len,
                uint8_t* index_data, uint32_t index_data_len,
                uint8_t* archive_data, uint32_t archive_data_len,
                dmResourceProvider::HArchiveInternal* out_internal);

    // try to remove this dependency!
    dmResourceArchive::Result LoadManifest(const char* path, dmResource::Manifest** out);

    // Should really be private!

    // Finds an entry in a single archive
    dmResourceArchive::Result FindEntryInArchive(dmResourceArchive::HArchiveIndexContainer archive, const uint8_t* hash, uint32_t hash_len, dmResourceArchive::EntryData* entry);

    // Reads an entry from a single archive
    dmResourceArchive::Result ReadEntryFromArchive(dmResourceArchive::HArchiveIndexContainer archive, const uint8_t* hash, uint32_t hash_len, const dmResourceArchive::EntryData* entry, void* buffer);

}

#endif // DM_RESOURCE_PROVIDER_ARCHIVE_H
