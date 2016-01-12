#ifndef DM_GAMESYS_RES_SPINE_MODEL_H
#define DM_GAMESYS_RES_SPINE_MODEL_H

#include <stdint.h>

#include <resource/resource.h>
#include "res_spine_scene.h"
#include "spine_ddf.h"

namespace dmGameSystem
{
    struct SpineModelResource
    {
        dmGameSystemDDF::SpineModelDesc*    m_Model;
        SpineSceneResource*                 m_Scene;
        dmRender::HMaterial                 m_Material;
    };

    dmResource::Result ResSpineModelPreload(const dmResource::ResourcePreloadParams& params);

    dmResource::Result ResSpineModelCreate(const dmResource::ResourceCreateParams& params);

    dmResource::Result ResSpineModelDestroy(const dmResource::ResourceDestroyParams& params);

    dmResource::Result ResSpineModelRecreate(const dmResource::ResourceRecreateParams& params);
}

#endif // DM_GAMESYS_RES_SPINE_MODEL_H
