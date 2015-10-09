
#include <dlib/log.h>

#include "resource.h"
#include "resource_archive.h"
#include "resource_private.h"

namespace dmResource
{
    Result MountArchiveInternal(const char* path, dmResourceArchive::HArchive* archive, void** mount_info)
    {
        *mount_info = 0;
        dmResourceArchive::Result r = LoadArchive(path, archive);
        if (r != dmResourceArchive::RESULT_OK)
        {
            return RESULT_RESOURCE_NOT_FOUND;
        }
        return RESULT_OK;
    }
    
    void UnmountArchiveInternal(dmResourceArchive::HArchive archive, void* mount_info)
    {
        dmResourceArchive::Delete(archive);
    }

}