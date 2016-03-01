#ifndef DM_GAMEOBJECT_RES_COLLECTION_H
#define DM_GAMEOBJECT_RES_COLLECTION_H

#include <stdint.h>

#include <resource/resource.h>

namespace dmGameObject
{
    dmResource::Result ResCollectionPreload(const dmResource::ResourcePreloadParams& params);

    dmResource::Result ResCollectionCreate(const dmResource::ResourceCreateParams& params);

    dmResource::Result ResCollectionDestroy(const dmResource::ResourceDestroyParams& params);
}

#endif // DM_GAMEOBJECT_RES_COLLECTION_H
