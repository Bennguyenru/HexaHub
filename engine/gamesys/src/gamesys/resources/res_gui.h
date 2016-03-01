#ifndef DM_GAMESYSTEM_RES_GUI_H
#define DM_GAMESYSTEM_RES_GUI_H

#include <dlib/array.h>
#include <resource/resource.h>
#include <gameobject/gameobject.h>
#include <render/font_renderer.h>
#include <gui/gui.h>
#include "../proto/gui_ddf.h"
#include "res_textureset.h"

namespace dmGameSystem
{
    struct GuiSceneTextureSetResource
    {
        TextureSetResource*  m_TextureSet;
        dmGraphics::HTexture m_Texture;
    };

    struct GuiSceneResource
    {
        dmGuiDDF::SceneDesc*            m_SceneDesc;
        dmGui::HScript                  m_Script;
        dmArray<dmRender::HFontMap>     m_FontMaps;
        dmArray<GuiSceneTextureSetResource> m_GuiTextureSets;
        const char*                     m_Path;
        dmGui::HContext                 m_GuiContext;
        dmRender::HMaterial             m_Material;
    };

    dmResource::Result ResPreloadSceneDesc(const dmResource::ResourcePreloadParams& params);

    dmResource::Result ResCreateSceneDesc(const dmResource::ResourceCreateParams& params);

    dmResource::Result ResDestroySceneDesc(const dmResource::ResourceDestroyParams& params);

    dmResource::Result ResRecreateSceneDesc(const dmResource::ResourceRecreateParams& params);

    dmResource::Result ResPreloadGuiScript(const dmResource::ResourcePreloadParams& params);

    dmResource::Result ResCreateGuiScript(const dmResource::ResourceCreateParams& params);

    dmResource::Result ResDestroyGuiScript(const dmResource::ResourceDestroyParams& params);

    dmResource::Result ResRecreateGuiScript(const dmResource::ResourceRecreateParams& params);

}

#endif // DM_GAMESYSTEM_RES_GUI_H
