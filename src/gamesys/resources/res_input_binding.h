#ifndef DM_GAMESYS_INPUT_BINDING_H
#define DM_GAMESYS_INPUT_BINDING_H

#include <resource/resource.h>
#include <gameobject/gameobject.h>

namespace dmGameSystem
{
    dmResource::CreateResult ResInputBindingCreate(dmResource::HFactory factory,
            void* context,
            const void* buffer, uint32_t buffer_size,
            dmResource::SResourceDescriptor* resource,
            const char* filename);

    dmResource::CreateResult ResInputBindingDestroy(dmResource::HFactory factory,
            void* context,
            dmResource::SResourceDescriptor* resource);

    dmResource::CreateResult ResInputBindingRecreate(dmResource::HFactory factory,
            void* context,
            const void* buffer, uint32_t buffer_size,
            dmResource::SResourceDescriptor* resource,
            const char* filename);
}

#endif // DM_GAMESYS_INPUT_BINDING_H
