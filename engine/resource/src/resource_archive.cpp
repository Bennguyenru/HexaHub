#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "resource_archive.h"
#include <dlib/lz4.h>
#include <dlib/log.h>
#include <dlib/crypt.h>
#include <dlib/path.h>
#include <dlib/sys.h>

// Maximum hash length convention. This size should large enough.
// If this length changes the VERSION needs to be bumped.
#define DMRESOURCE_MAX_HASH (64) // Equivalent to 512 bits

#if defined(__linux__) || defined(__MACH__) || defined(__EMSCRIPTEN__) || defined(__AVM2__)
#include <netinet/in.h>
#elif defined(_WIN32)
#include <winsock2.h>
#else
#error "Unsupported platform"
#endif

namespace dmResourceArchive
{
    const static uint64_t FILE_LOADED_INDICATOR = 1337;
    const char* KEY = "aQj8CScgNP4VsfXK";

    enum EntryFlag
    {
        ENTRY_FLAG_ENCRYPTED = 1 << 0,
        ENTRY_FLAG_LIVEUPDATE_DATA = 1 << 1, // resource added via liveupdate
    };

    /* NOTE
        When an entry is added, both a hash and an EntryData
        instance must be added. This means that hashes and entrydatas
        must be shifted to make room, and the entrydata-
        offset (and entry count) must be updated to match this.
        Take penalty of sorted insert for fast binary search in runtime
    */
    struct ArchiveIndex
    {
        ArchiveIndex()
        {
            memset(this, 0, sizeof(ArchiveIndex));
        }

        uint32_t m_Version;
        uint32_t m_Pad;
        uint64_t m_Userdata;
        uint32_t m_EntryDataCount;
        uint32_t m_EntryDataOffset;
        uint32_t m_HashOffset;
        uint32_t m_HashLength;
    };

    struct ArchiveIndexContainer
    {
        ArchiveIndexContainer()
        {
            memset(this, 0, sizeof(ArchiveIndexContainer));
        }

        ArchiveIndex* m_ArchiveIndex; // this could be mem-mapped or loaded into memory from file
        bool m_IsMemMapped;

        /// Used if the archive is loaded from file
        uint8_t* m_Hashes;
        EntryData* m_Entries;
        uint8_t* m_ResourceData;
        FILE* m_FileResourceData;

        /// Resources acquired with LiveUpdate
        FILE* m_LiveUpdateFileResourceData;
    };

    Result WrapArchiveBuffer(const void* index_buffer, uint32_t index_buffer_size, const void* resource_data, FILE* lu_resource_data, HArchiveIndexContainer* archive)
    {
        *archive = new ArchiveIndexContainer;
        (*archive)->m_IsMemMapped = true;
        ArchiveIndex* a = (ArchiveIndex*) index_buffer;
        uint32_t version = htonl(a->m_Version);
        if (version != VERSION)
        {
            return RESULT_VERSION_MISMATCH;
        }
        (*archive)->m_ResourceData = (uint8_t*)resource_data;
        (*archive)->m_LiveUpdateFileResourceData = lu_resource_data;
        (*archive)->m_ArchiveIndex = a;
        return RESULT_OK;
    }

    void CleanupResources(FILE* index_file, FILE* data_file, FILE* lu_data_file, ArchiveIndexContainer* archive)
    {
        if (index_file)
        {
            fclose(index_file);
        }

        if (data_file)
        {
            fclose(data_file);
        }

        if (lu_data_file)
        {
            fclose(lu_data_file);
        }

        if (archive)
        {
            if (archive->m_ArchiveIndex)
            {
                delete archive->m_ArchiveIndex;
            }

            delete archive;
        }
    }

    Result LoadArchive(const char* index_file_path, const char* lu_data_file_path, HArchiveIndexContainer* archive)
    {
        uint32_t filename_count = 0;
        while (true)
        {
            if (index_file_path[filename_count] == '\0')
                break;
            if (filename_count >= DMPATH_MAX_PATH)
                return RESULT_IO_ERROR;

            ++filename_count;
        }

        char data_file_path[DMPATH_MAX_PATH];
        uint32_t entry_count = 0, entry_offset = 0, hash_len = 0, hash_offset = 0, hash_total_size = 0, entries_total_size = 0;
        FILE* f_index = fopen(index_file_path, "rb");
        FILE* f_data = 0;
        FILE* f_lu_data = 0;

        ArchiveIndexContainer* aic = 0;
        ArchiveIndex* ai = 0;

        Result r = RESULT_OK;
        *archive = 0;

        if (!f_index)
        {
            CleanupResources(f_index, f_data, f_lu_data, aic);
            return RESULT_IO_ERROR;
        }

        aic = new ArchiveIndexContainer;
        aic->m_IsMemMapped = false;

        ai = new ArchiveIndex;
        if (fread(ai, 1, sizeof(ArchiveIndex), f_index) != sizeof(ArchiveIndex))
        {
            CleanupResources(f_index, f_data, f_lu_data, aic);
            return RESULT_IO_ERROR;
        }

        if(htonl(ai->m_Version) != VERSION)
        {
            CleanupResources(f_index, f_data, f_lu_data, aic);
            return RESULT_VERSION_MISMATCH;
        }

        entry_count = htonl(ai->m_EntryDataCount);
        entry_offset = htonl(ai->m_EntryDataOffset);
        hash_len = htonl(ai->m_HashLength);
        hash_offset = htonl(ai->m_HashOffset);

        fseek(f_index, hash_offset, SEEK_SET);
        aic->m_Hashes = new uint8_t[entry_count * DMRESOURCE_MAX_HASH];
        hash_total_size = entry_count * DMRESOURCE_MAX_HASH;
        if (fread(aic->m_Hashes, 1, hash_total_size, f_index) != hash_total_size)
        {
            CleanupResources(f_index, f_data, f_lu_data, aic);
            return RESULT_IO_ERROR;
        }

        fseek(f_index, entry_offset, SEEK_SET);
        aic->m_Entries = new EntryData[entry_count];
        entries_total_size = entry_count * sizeof(EntryData);
        if (fread(aic->m_Entries, 1, entries_total_size, f_index) != entries_total_size)
        {
            CleanupResources(f_index, f_data, f_lu_data, aic);
            return RESULT_IO_ERROR;
        }

        // Mark that this archive was loaded from file, and not memory-mapped
        ai->m_Userdata = FILE_LOADED_INDICATOR;

        // Open file for resource acquired through liveupdate
        // Assumes file already exists if a path to it is supplied
        if (lu_data_file_path != 0x0)
        {
            f_lu_data = fopen(lu_data_file_path, "rb+");

            if (!f_lu_data)
            {
                CleanupResources(f_index, f_data, f_lu_data, aic);
                return RESULT_IO_ERROR;
            }
        }

        // Data file has same path and filename as index file, but extension .arcd instead of .arci.
        memcpy(&data_file_path, index_file_path, filename_count+1); // copy NULL terminator as well
        data_file_path[filename_count-1] = 'd';
        f_data = fopen(data_file_path, "rb");

        if (!f_data)
        {
            CleanupResources(f_index, f_data, f_lu_data, aic);
            return RESULT_IO_ERROR;
        }

        aic->m_FileResourceData = f_data;
        aic->m_ArchiveIndex = ai;
        *archive = aic;

        return r;
    }

    void Delete(HArchiveIndexContainer archive)
    {
        if (archive->m_Entries)
        {
            delete[] archive->m_Entries;
        }

        if (archive->m_Hashes)
        {
            delete[] archive->m_Hashes;
        }

        if (archive->m_FileResourceData)
        {
            fclose(archive->m_FileResourceData);
        }

        if (archive->m_LiveUpdateFileResourceData)
        {
            fclose(archive->m_LiveUpdateFileResourceData);
        }

        if (!archive->m_IsMemMapped)
        {
            delete archive->m_ArchiveIndex;
        }

        delete archive;
    }

    Result CalcInsertionIndex(HArchiveIndexContainer archive, const uint8_t* hash_digest, int& index)
    {
        uint8_t* hashes = 0;
        uint32_t hashes_offset = htonl(archive->m_ArchiveIndex->m_HashOffset);
        
        hashes = (archive->m_IsMemMapped) ? (uint8_t*)(uintptr_t(archive->m_ArchiveIndex) + hashes_offset) : archive->m_Hashes;

        int first = 0;
        int last = htonl(archive->m_ArchiveIndex->m_EntryDataCount);
        int mid = first - (last + first) / 2;
        while (first <= last)
        {
            mid = first - (last + first) / 2;
            uint8_t* h = (hashes + DMRESOURCE_MAX_HASH * mid);

            int cmp = memcmp(hash_digest, h, htonl(archive->m_ArchiveIndex->m_HashLength));
            if (cmp == 0)
            {
                // attemping to insert an already inserted resource
                dmLogWarning("Resource already stored");
                return RESULT_UNKNOWN;
            }
            else if (cmp > 0)
            {
                first = mid+1;
            }
            else if (cmp < 0)
            {
                last = mid-1;
            }
        }

        return RESULT_OK;
    }

    void DeepCopyArchiveIndex(ArchiveIndex*& dst, ArchiveIndexContainer* src, bool alloc_extra_entry)
    {
        ArchiveIndex* ai = src->m_ArchiveIndex;
        uint32_t hash_digests_size = htonl(ai->m_EntryDataCount) * DMRESOURCE_MAX_HASH;
        uint32_t entry_datas_size = (htonl(ai->m_EntryDataCount) * sizeof(EntryData));
        uint32_t single_entry_size = DMRESOURCE_MAX_HASH + sizeof(EntryData);
        uint32_t size_to_alloc = sizeof(ArchiveIndex) + hash_digests_size + entry_datas_size;

        if (alloc_extra_entry)
        {
            size_to_alloc += single_entry_size;
        }

        dst = (ArchiveIndex*)new uint8_t[size_to_alloc];

        if (!src->m_IsMemMapped)
        {
            memcpy(dst, ai, sizeof(ArchiveIndex)); // copy header data

            uint8_t* cursor =  (uint8_t*)(dst + sizeof(ArchiveIndex)); // step cursor to hash digests array
            memcpy(cursor, src->m_Hashes, hash_digests_size);

            if (alloc_extra_entry)
            {
                cursor = (uint8_t*)(cursor + DMRESOURCE_MAX_HASH);
            }

            cursor = (uint8_t*)(cursor + hash_digests_size); // step cursor to entry data array
            memcpy(cursor, src->m_Entries, entry_datas_size);
        }
        else
        {
            memcpy(dst, ai, size_to_alloc);
        }

        if (alloc_extra_entry)
        {
            dst->m_EntryDataOffset = dst->m_EntryDataOffset + DMRESOURCE_MAX_HASH;
        }
    }

    Result InsertResource(HArchiveIndexContainer archive, const uint8_t* hash_digest, uint32_t hash_digest_len, const uint8_t* buf, uint32_t buf_len, const char* proj_id)
    {
        Result result = RESULT_OK;

        char app_support_path[DMPATH_MAX_PATH];
        char lu_index_path[DMPATH_MAX_PATH];
        
        dmSys::GetApplicationSupportPath(proj_id, app_support_path, DMPATH_MAX_PATH);
        dmPath::Concat(app_support_path, "liveupdate.arci", lu_index_path, DMPATH_MAX_PATH);
        //dmLogInfo("InsertResource, index path: %s", lu_index_path);
        bool resource_exists = dmSys::ResourceExists(lu_index_path);

        uint8_t* hashes = (uint8_t*)(uintptr_t(archive->m_ArchiveIndex) + htonl(archive->m_ArchiveIndex->m_HashOffset));
        EntryData* entries = (EntryData*)(uintptr_t(archive->m_ArchiveIndex) + htonl(archive->m_ArchiveIndex->m_EntryDataOffset));

        // binary search for where to insert hash
        int idx = 0;
        Result index_result = CalcInsertionIndex(archive, hash_digest, idx);

        if (index_result != RESULT_OK)
        {
            dmLogError("Could not calculate valid resource insertion index");
            return index_result;
        }

        //dmLogInfo("Calculated insertion index: %i", idx);

        if (!resource_exists)
        {
            char lu_data_path[DMPATH_MAX_PATH];
            // Data file has same path and filename as index file, but extension .arcd instead of .arci.
            memcpy(&lu_data_path, lu_index_path, strlen(lu_index_path)+1); // copy NULL terminator as well
            lu_data_path[strlen(lu_index_path)-1] = 'd';

            FILE* f_lu_data = fopen(lu_data_path, "wb+");
            if (!f_lu_data)
            {
                dmLogError("Failed to create liveupdate resource file");
            }
            
            archive->m_FileResourceData = f_lu_data;
        }

        // Make deep-copy. Operate on this and only overwrite when done inserting
        ArchiveIndex* ai_temp = 0x0;
        DeepCopyArchiveIndex(ai_temp, archive, true);

        // From now on we only work on ai_temp until done
        hashes = (uint8_t*)(uintptr_t(ai_temp) + htonl(ai_temp->m_HashOffset));
        entries = (EntryData*)(uintptr_t(ai_temp) + htonl(ai_temp->m_EntryDataOffset));

        // 'idx' is pos where new hash should be placed
        // Shift hashes after index idx down
        uint8_t* hash_mid = hashes + DMRESOURCE_MAX_HASH * idx;
        uint8_t* hash_shift_dst = hash_mid + DMRESOURCE_MAX_HASH;
        memmove(hash_shift_dst, hash_mid, DMRESOURCE_MAX_HASH);
        memcpy(hash_mid, hash_digest, hash_digest_len);

        // Shift entry datas
        uint8_t* entries_mid = (uint8_t*)(uintptr_t(entries) + sizeof(EntryData) * idx);
        uint8_t* entries_shift_dst = (uint8_t*)(uintptr_t(entries) + sizeof(EntryData) * idx);
        memmove(entries_shift_dst, entries_mid, sizeof(EntryData));
        EntryData e;

        // Write buf to resource file
        fseek(archive->m_FileResourceData, 0, SEEK_END);
        uint32_t offs = (uint32_t)ftell(archive->m_FileResourceData);
        size_t bytes_written = fwrite(buf, 1, buf_len, archive->m_FileResourceData);
        if (bytes_written != buf_len)
        {
            dmLogError("All bytes not written for resource, bytes written: %zu, resource size: %u", bytes_written, buf_len);
            delete ai_temp;
            return RESULT_IO_ERROR;
        }

        // Create entrydata and copy it to temp index
        EntryData entry;
        entry.m_ResourceDataOffset = offs;
        entry.m_ResourceSize = buf_len;
        entry.m_ResourceCompressedSize = 0xFFFFFFFF; // TODO assume uncompressed?
        entry.m_Flags |= ENTRY_FLAG_LIVEUPDATE_DATA; // TODO always unencrypted?
        memcpy(&entry, entries_mid, sizeof(EntryData));

        if (!archive->m_IsMemMapped)
        {
            delete archive->m_ArchiveIndex;
        }

        ai_temp->m_EntryDataCount = ntohl(htonl(ai_temp->m_EntryDataCount) + 1);
        archive->m_ArchiveIndex = ai_temp;

        // Since we store data sequentially when doing the deep-copy we want to access it in that fashion
        archive->m_IsMemMapped = true;

        // Overwrite LU index file
        // TODO should write to temp-file and overwrite on success, see Sys_Save in script_sys.cpp for example
        FILE* f_lu_index = fopen(lu_index_path, "wb");
        if (!f_lu_index)
        {
            dmLogError("Failed to create liveupdate index file");
        }
        uint32_t entry_count = htonl(archive->m_ArchiveIndex->m_EntryDataCount);
        uint32_t total_size = sizeof(ArchiveIndex) + entry_count * (DMRESOURCE_MAX_HASH + sizeof(EntryData));
        if (fwrite(archive->m_ArchiveIndex, 1, total_size, f_lu_index) != total_size)
        {
            fclose(f_lu_index);
            dmLogError("Failed to write liveupdate index file");
            return RESULT_IO_ERROR;
        }
        fclose(f_lu_index);


        // - Copy archive index (file or mem-mapped?) into memory
        // - Shift all entries down sizeof(DMRESOURCE_MAX_HASH) bytes
        // - (Shift+) sorted insert hash_digest to archive hashes.
        // - Update archive entry offset value, and entry count
        // - add buf to archive->m_LiveupdateResourceData or m_FileLiveupdateResourceData dep. on memmapped or not
        // - once we know offset in resource buf, create entry_data instance, (shift+) insert at
        //   same index as hash was inserted.
        // - write back to temp file, if write successful overwrite original index file

        return result;
    }

    Result FindEntry(HArchiveIndexContainer archive, const uint8_t* hash, EntryData* entry)
    {
        uint32_t entry_count = htonl(archive->m_ArchiveIndex->m_EntryDataCount);
        uint32_t entry_offset = htonl(archive->m_ArchiveIndex->m_EntryDataOffset);
        uint32_t hash_offset = htonl(archive->m_ArchiveIndex->m_HashOffset);
        uint32_t hash_len = htonl(archive->m_ArchiveIndex->m_HashLength);
        uint8_t* hashes = 0;
        EntryData* entries = 0;

        // If archive is loaded from file use the member arrays for hashes and entries, otherwise read with mem offsets.
        if (!archive->m_IsMemMapped)
        {
            hashes = archive->m_Hashes;
            entries = archive->m_Entries;
        }
        else
        {
            hashes = (uint8_t*)(uintptr_t(archive->m_ArchiveIndex) + hash_offset);
            entries = (EntryData*)(uintptr_t(archive->m_ArchiveIndex) + entry_offset);
        }

        // Search for hash with binary search (entries are sorted on hash)
        int first = 0;
        int last = (int)entry_count-1;
        while (first <= last)
        {
            int mid = first + (last - first) / 2;
            uint8_t* h = (hashes + DMRESOURCE_MAX_HASH * mid);

            int cmp = memcmp(hash, h, hash_len);
            if (cmp == 0)
            {
                if (entry != NULL)
                {
                    EntryData* e = &entries[mid];
                    entry->m_ResourceDataOffset = htonl(e->m_ResourceDataOffset);
                    entry->m_ResourceSize = htonl(e->m_ResourceSize);
                    entry->m_ResourceCompressedSize = htonl(e->m_ResourceCompressedSize);
                    entry->m_Flags = htonl(e->m_Flags);
                }

                return RESULT_OK;
            }
            else if (cmp > 0)
            {
                first = mid+1;
            }
            else if (cmp < 0)
            {
                last = mid-1;
            }
        }

        return RESULT_NOT_FOUND;
    }

    Result Read(HArchiveIndexContainer archive, EntryData* entry_data, void* buffer)
    {
        uint32_t size = entry_data->m_ResourceSize;
        uint32_t compressed_size = entry_data->m_ResourceCompressedSize;

        if (entry_data->m_Flags & ENTRY_FLAG_LIVEUPDATE_DATA)
        {
            // LiveUpdate resources are never mem-mapped
            fseek(archive->m_LiveUpdateFileResourceData, entry_data->m_ResourceDataOffset, SEEK_SET);
            if (fread(buffer, 1, size, archive->m_LiveUpdateFileResourceData) == size)
            {
                return RESULT_OK;
            }
            else
            {
                return RESULT_OUTBUFFER_TOO_SMALL;
            }
        }

        if (!archive->m_IsMemMapped)
        {
            fseek(archive->m_FileResourceData, entry_data->m_ResourceDataOffset, SEEK_SET);
            if (compressed_size != 0xFFFFFFFF) // resource is compressed
            {
                char *compressed_buf = (char*)malloc(compressed_size);
                if (!compressed_buf)
                {
                    return RESULT_MEM_ERROR;
                }

                if (fread(compressed_buf, 1, compressed_size, archive->m_FileResourceData) != compressed_size)
                {
                    free(compressed_buf);
                    return RESULT_IO_ERROR;
                }

                if(entry_data->m_Flags & ENTRY_FLAG_ENCRYPTED)
                {
                    dmCrypt::Result cr = dmCrypt::Decrypt(dmCrypt::ALGORITHM_XTEA, (uint8_t*) compressed_buf, compressed_size, (const uint8_t*) KEY, strlen(KEY));
                    if (cr != dmCrypt::RESULT_OK)
                    {
                        free(compressed_buf);
                        return RESULT_UNKNOWN;
                    }
                }

                dmLZ4::Result r = dmLZ4::DecompressBufferFast(compressed_buf, compressed_size, buffer, size);
                free(compressed_buf);

                if (r == dmLZ4::RESULT_OK)
                {
                    return RESULT_OK;
                }
                else
                {
                    return RESULT_OUTBUFFER_TOO_SMALL;
                }
            }
            else
            {
                // Entry is uncompressed
                if (fread(buffer, 1, size, archive->m_FileResourceData) == size)
                {
                    dmCrypt::Result cr = dmCrypt::RESULT_OK;
                    if (entry_data->m_Flags & ENTRY_FLAG_ENCRYPTED)
                    {
                        cr = dmCrypt::Decrypt(dmCrypt::ALGORITHM_XTEA, (uint8_t*) buffer, size, (const uint8_t*) KEY, strlen(KEY));
                    }
                    return (cr == dmCrypt::RESULT_OK) ? RESULT_OK : RESULT_UNKNOWN;
                }
                else
                {
                    return RESULT_OUTBUFFER_TOO_SMALL;
                }
            }
        }
        else
        {
            Result ret;

            void* r = (void*) ((uintptr_t(archive->m_ResourceData) + entry_data->m_ResourceDataOffset));
            void* decrypted = r;

            if (entry_data->m_Flags & ENTRY_FLAG_ENCRYPTED)
            {
                uint32_t bufsize = (compressed_size != 0xFFFFFFFF) ? compressed_size : size;
                decrypted = (uint8_t*) malloc(bufsize);
                memcpy(decrypted, r, bufsize);
                dmCrypt::Result cr = dmCrypt::Decrypt(dmCrypt::ALGORITHM_XTEA, (uint8_t*) decrypted, bufsize, (const uint8_t*) KEY, strlen(KEY));
                if (cr != dmCrypt::RESULT_OK)
                {
                    free(decrypted);
                    return RESULT_UNKNOWN;
                }
            }

            if (compressed_size != 0xFFFFFFFF)
            {
                // Entry is compressed
                dmLZ4::Result result = dmLZ4::DecompressBufferFast(decrypted, compressed_size, buffer, size);
                if (result == dmLZ4::RESULT_OK)
                {
                    ret = RESULT_OK;
                }
                else
                {
                    ret = RESULT_OUTBUFFER_TOO_SMALL;
                }
            }
            else
            {
                // Entry is uncompressed
                memcpy(buffer, decrypted, size);
                ret = RESULT_OK;
            }

            // if needed aux buffer
            if (decrypted != r)
            {
                free(decrypted);
            }

            return ret;
        }
    }

    uint32_t GetEntryCount(HArchiveIndexContainer archive)
    {
        return htonl(archive->m_ArchiveIndex->m_EntryDataCount);
    }
}  // namespace dmResourceArchive
