#ifndef DM_GAMESYS_RES_MATERIAL_H
#define DM_GAMESYS_RES_MATERIAL_H

#include <stdint.h>

#include <resource/resource.h>

namespace dmGameSystem
{
    dmResource::Result ResMaterialCreate(dmResource::HFactory factory,
            void* context,
            const void* buffer, uint32_t buffer_size,
            void* preload_data,
            dmResource::SResourceDescriptor* resource,
            const char* filename);

    dmResource::Result ResMaterialDestroy(dmResource::HFactory factory,
            void* context,
            dmResource::SResourceDescriptor* resource);

    dmResource::Result ResMaterialRecreate(dmResource::HFactory factory,
            void* context,
            const void* buffer, uint32_t buffer_size,
            dmResource::SResourceDescriptor* resource,
            const char* filename);
}

#endif
