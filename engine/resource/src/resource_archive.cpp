#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "resource_archive.h"
#include <dlib/lz4.h>
#include <dlib/crypt.h>
#include <dlib/log.h> // FOR DEBUGGING ONLY
#include <dlib/path.h>

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
    const static uint32_t VERSION = 4;
    const static uint64_t FILE_LOADED_INDICATOR = 1337;
    const char* KEY = "aQj8CScgNP4VsfXK";

    enum EntryFlag
    {
        ENTRY_FLAG_ENCRYPTED = 1 << 0,
    };

    struct Entry
    {
        uint32_t m_NameOffset;
        uint32_t m_ResourceOffset;
        uint32_t m_ResourceSize;
        // 0xFFFFFFFF if uncompressed
        uint32_t m_ResourceCompressedSize;
        uint32_t m_Flags;
    };

    struct Meta
    {
        char*   m_StringPool;
        Entry*  m_Entries;
        FILE*   m_File;

        Meta()
        {
            m_StringPool = 0;
            m_Entries = 0;
            m_File = 0;
        }

        ~Meta()
        {
            if (m_StringPool)
            {
                delete[] m_StringPool;
            }

            if (m_Entries)
            {
                delete[] m_Entries;
            }

            if (m_File)
            {
                fclose(m_File);
            }
        }

    };

    struct Archive
    {
        uint32_t m_Version;
        uint32_t m_Pad;
        union
        {
            uint64_t m_Userdata;
            Meta*    m_Meta;
        };
        uint32_t m_StringPoolOffset;
        uint32_t m_StringPoolSize;
        uint32_t m_EntryCount;
        uint32_t m_FirstEntryOffset;
    };

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

        uint8_t* m_Hashes;
        EntryData* m_Entries;
        FILE* m_FileResourceData;
    };

    Result WrapArchiveBuffer2(const void* index_buffer, uint32_t index_buffer_size, const void* resource_data, HArchiveIndex* archive)
    {
        ArchiveIndex* a = (ArchiveIndex*) index_buffer;
        uint32_t version = htonl(a->m_Version);
        if (version != VERSION)
        {
            return RESULT_VERSION_MISMATCH;
        }

        a->m_Userdata = (uint64_t) resource_data;

        *archive = a;
        return RESULT_OK;
    }

    Result WrapArchiveBuffer(const void* buffer, uint32_t buffer_size, HArchive* archive)
    {
        Archive* a = (Archive*) buffer;
        uint32_t version = htonl(a->m_Version);
        if (version != VERSION)
        {
            return RESULT_VERSION_MISMATCH;
        }

        *archive = a;
        return RESULT_OK;
    }

    #define READ_AND_BAIL2(p, n) \
        debug_read_count = fread(p, 1, n, f_index);\
        if (debug_read_count != n)\
        {\
            r = RESULT_IO_ERROR;\
            goto bail2;\
        }\

    Result LoadArchive2(const char* path_index, HArchiveIndex* archive)
    {
        uint32_t filename_count = 0;
        while (true)
        {
            if (path_index[filename_count] == '\0')
                break;
            if (filename_count >= DMPATH_MAX_PATH)
                return RESULT_IO_ERROR;

            ++filename_count;
        }

        char path_data[DMPATH_MAX_PATH];
        uint32_t entry_count = 0, entry_offset = 0, hash_len = 0, hash_offset = 0;
        uint32_t debug_read_count = 0;
        FILE* f_index = fopen(path_index, "rb");
        FILE* f_data = 0;
        ArchiveIndex* ai = 0;
        Result r = RESULT_OK;
        *archive = 0;

        dmLogInfo("path_index: %s", path_index);

        if (!f_index)
        {
            r = RESULT_IO_ERROR;
            goto bail2;
        }

        ai = new ArchiveIndex;
        READ_AND_BAIL2(ai, sizeof(ArchiveIndex))
        if(htonl(ai->m_Version) != VERSION)
        {
            r = RESULT_VERSION_MISMATCH;
            goto bail2;
        }

        entry_count = htonl(ai->m_EntryDataCount);
        entry_offset = htonl(ai->m_EntryDataOffset);
        hash_len = htonl(ai->m_HashLength);
        hash_offset = htonl(ai->m_HashOffset);
        //ai->m_ResourceData = 0;

        dmLogInfo("entry_count = %u, entry_offset = %u", entry_count, entry_offset);
        fseek(f_index, hash_offset, SEEK_SET);
        ai->m_Hashes = new uint8_t[entry_count * DMRESOURCE_MAX_HASH];
        READ_AND_BAIL2(ai->m_Hashes, entry_count * DMRESOURCE_MAX_HASH)

        ai->m_Entries = new EntryData[entry_count];
        fseek(f_index, entry_offset, SEEK_SET);
        READ_AND_BAIL2(ai->m_Entries, entry_count * sizeof(EntryData));

        ai->m_Userdata = FILE_LOADED_INDICATOR;

        // TODO check that arcd file exists also?
        // Data file has same path and filename as index file, but extension .arcd instead of .arci.
        memcpy(&path_data, path_index, filename_count+1); // copy NULL terminator as well
        path_data[filename_count-1] = 'd';
        f_data = fopen(path_data, "rb");

        if (!f_data)
        {
            r = RESULT_IO_ERROR;
            goto bail2;
        }

        ai->m_FileResourceData = f_data;
        *archive = ai;

        return r;

bail2:
        if (f_index)
        {
            fclose(f_index);
        }

        if (f_data)
        {
            fclose(f_data);
        }

        if (ai)
        {
            delete ai;
        }

        return r;
    }

#define READ_AND_BAIL(p, n) \
        if (fread(p, 1, n, f) != n)\
        {\
            r = RESULT_IO_ERROR;\
            goto bail;\
        }\

    Result LoadArchive(const char* file_name, HArchive* archive)
    {
        *archive = 0;

        FILE* f = fopen(file_name, "rb");
        Archive* a = 0;
        Meta* meta = 0;
        Result r = RESULT_OK;

        if (!f)
        {
            r = RESULT_IO_ERROR;
            goto bail;
        }

        a = new Archive;

        READ_AND_BAIL(a, sizeof(Archive));
        if (htonl(a->m_Version) != VERSION)
        {
            r = RESULT_VERSION_MISMATCH;
            goto bail;
        }

        meta = new Meta();
        fseek(f, htonl(a->m_StringPoolOffset), SEEK_SET);
        meta->m_StringPool = new char[htonl(a->m_StringPoolSize)];
        READ_AND_BAIL(meta->m_StringPool, htonl(a->m_StringPoolSize));

        fseek(f, htonl(a->m_FirstEntryOffset), SEEK_SET);
        meta->m_Entries = new Entry[htonl(a->m_EntryCount)];
        READ_AND_BAIL(meta->m_Entries, sizeof(Entry) * htonl(a->m_EntryCount));

        *archive = a;
        meta->m_File = f;
        a->m_Meta = meta;

        return r;
bail:
       if (f)
       {
           fclose(f);
       }

       if (a)
       {
           delete a;
       }

       if (meta)
       {
           delete meta;
       }

       return r;
    }

    void Delete2(HArchiveIndex archive)
    {
        // Only cleanup if not mem-mapped
        if (archive->m_Userdata == FILE_LOADED_INDICATOR)
        {
            delete[] archive->m_Entries;
            delete[] archive->m_Hashes;

            if (archive->m_FileResourceData)
            {
                dmLogInfo("Closing resource file");
                fclose(archive->m_FileResourceData);
            }
            delete archive;
        }
    }

    void Delete(HArchive archive)
    {
        if (archive->m_Meta)
        {
            delete archive->m_Meta;
            delete archive;
        }
    }

    Result FindEntry2(HArchiveIndex archive, const uint8_t* hash, EntryData* entry)
    {
        uint32_t entry_count = htonl(archive->m_EntryDataCount), entry_offset = htonl(archive->m_EntryDataOffset);
        uint32_t hash_offset = htonl(archive->m_HashOffset), hash_len = htonl(archive->m_HashLength);
        uint8_t* hashes = 0;
        EntryData* entries = 0;

        /*dmLogInfo("entry_count = %u, entry_offset = %u", entry_count, entry_offset);
        dmLogInfo("hash_len = %u, hash_offset = %u", hash_len, hash_offset);*/

        if (archive->m_Userdata == FILE_LOADED_INDICATOR)
        {
            hashes = archive->m_Hashes;
            entries = archive->m_Entries;
        }
        else
        {
            dmLogInfo("mem-mapped");
            hashes = (uint8_t*)(uintptr_t(archive) + hash_offset);
            entries = (EntryData*)(uintptr_t(archive) + entry_offset);
        }

        // debug print
        /*char* buf = new char[hash_len+1];
        memcpy(buf, hash, hash_len);
        buf[hash_len] = '\0';
        dmLogInfo("looking for hash; %s", buf);
        delete[] buf;

        // debug print all hashes in file
        for (int i = 0; i < entry_count; ++i)
        {
            char* b = new char[hash_len+1];
            memcpy(b, hashes + DMRESOURCE_MAX_HASH * i, hash_len);
            b[hash_len] = '\0';
            dmLogInfo("hash at index %i; %s", i, b);
            delete[] b;
        }*/
        
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
                EntryData* e = &entries[mid];
                entry->m_ResourceDataOffset = htonl(e->m_ResourceDataOffset);
                entry->m_ResourceSize = htonl(e->m_ResourceSize);
                entry->m_ResourceCompressedSize = htonl(e->m_ResourceCompressedSize);
                entry->m_Flags = htonl(e->m_Flags);
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

    Result FindEntry(HArchive archive, const char* name, EntryInfo* entry)
    {
        uint32_t count = htonl(archive->m_EntryCount);
        uint32_t first_offset = htonl(archive->m_FirstEntryOffset);
        uint32_t string_pool_offset = htonl(archive->m_StringPoolOffset);

        Entry* entries;
        const char* string_pool;
        if (archive->m_Meta)
        {
            Meta* meta = archive->m_Meta;
            entries = meta->m_Entries;
            string_pool = meta->m_StringPool;
        }
        else
        {
            entries = (Entry*) (first_offset + uintptr_t(archive));
            string_pool = (const char*) (uintptr_t(archive) + uintptr_t(string_pool_offset));
        }

        int first = 0;
        int last = (int)count-1;
        while( first <= last )
        {
            int mid = first + (last - first) / 2;
            Entry* file_entry = &entries[mid];
            const char* entry_name = (const char*) (htonl(file_entry->m_NameOffset) + string_pool);
            int cmp = strcmp(name, entry_name);
            if( cmp == 0 )
            {
                entry->m_Name = name;
                //entry->m_Resource = (const void*) (htonl(file_entry->m_ResourceOffset) + uintptr_t(archive));
                entry->m_Offset = htonl(file_entry->m_ResourceOffset);
                entry->m_Size = htonl(file_entry->m_ResourceSize);
                entry->m_CompressedSize = htonl(file_entry->m_ResourceCompressedSize);
                entry->m_Flags = htonl(file_entry->m_Flags);
                entry->m_Entry = file_entry;
                return RESULT_OK;
            }
            else if( cmp > 0 )
            {
                first = mid + 1;
            }
            else if( cmp < 0 )
            {
                last = mid - 1;
            }
        }
        return RESULT_NOT_FOUND;
    }

    Result Read2(HArchiveIndex archive, EntryData* entry_data, void* buffer)
    {
        uint32_t size = entry_data->m_ResourceSize;
        uint32_t compressed_size = entry_data->m_ResourceCompressedSize;
        
        if (archive->m_Userdata == FILE_LOADED_INDICATOR)
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

            void* r = (void*) ((uintptr_t(archive->m_Userdata) + entry_data->m_ResourceDataOffset));
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

    Result Read(HArchive archive, EntryInfo* entry_info, void* buffer)
    {
        uint32_t size = entry_info->m_Size;
        uint32_t compressed_size = entry_info->m_CompressedSize;

        if (archive->m_Meta)
        {
            Meta* meta = archive->m_Meta;
            fseek(meta->m_File, entry_info->m_Offset, SEEK_SET);

            if (compressed_size != 0xFFFFFFFF)
            {
                // Entry is compressed
                char *compressed_buf = (char *)malloc(compressed_size);
                if(!compressed_buf)
                {
                    return RESULT_MEM_ERROR;
                }

                if (fread(compressed_buf, 1, compressed_size, meta->m_File) != compressed_size)
                {
                    free(compressed_buf);
                    return RESULT_IO_ERROR;
                }

                if (entry_info->m_Flags & ENTRY_FLAG_ENCRYPTED)
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
                if (fread(buffer, 1, size, meta->m_File) == size)
                {
                    dmCrypt::Result cr = dmCrypt::RESULT_OK;
                    if (entry_info->m_Flags & ENTRY_FLAG_ENCRYPTED)
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
            void* r = (void*) (entry_info->m_Offset + uintptr_t(archive));
            void* decrypted = r;

            if (entry_info->m_Flags & ENTRY_FLAG_ENCRYPTED)
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

            Result ret;
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

    uint32_t GetEntryCount2(HArchiveIndex archive)
    {
        return htonl(archive->m_EntryDataCount);
    }

    uint32_t GetEntryCount(HArchive archive)
    {
        return htonl(archive->m_EntryCount);
    }

}  // namespace dmResourceArchive
