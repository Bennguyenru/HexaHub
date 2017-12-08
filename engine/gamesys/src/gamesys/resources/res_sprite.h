#ifndef DM_GAMESYS_RES_SPRITE_H
#define DM_GAMESYS_RES_SPRITE_H

#include <stdint.h>

#include <dlib/hash.h>
#include <resource/resource.h>

#include "sprite_ddf.h"

#include "res_textureset.h"

namespace dmGameSystem
{
    struct SpriteResource
    {
        dmGameSystemDDF::SpriteDesc* m_DDF;
        dmhash_t m_DefaultAnimation;
        TextureSetResource* m_TextureSet;
        dmGraphics::HTexture m_Textures[dmRender::RenderObject::MAX_TEXTURE_COUNT];
        dmRender::HMaterial m_Material;
    };

    dmResource::Result ResSpritePreload(const dmResource::ResourcePreloadParams& params);

    dmResource::Result ResSpriteCreate(const dmResource::ResourceCreateParams& params);

    dmResource::Result ResSpriteDestroy(const dmResource::ResourceDestroyParams& params);

    dmResource::Result ResSpriteRecreate(const dmResource::ResourceRecreateParams& params);
}

#endif // DM_GAMESYS_RES_SPRITE_H
