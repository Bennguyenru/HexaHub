#ifndef DM_GAMESYSTEM_RES_GUI_H
#define DM_GAMESYSTEM_RES_GUI_H

#include <dlib/array.h>
#include <resource/resource.h>
#include <gameobject/gameobject.h>
#include <render/font_renderer.h>
#include <gui/gui.h>
#include "../proto/gui_ddf.h"

namespace dmGameSystem
{
    struct GuiSceneResource
    {
        dmGuiDDF::SceneDesc*            m_SceneDesc;
        dmGui::HScript                  m_Script;
        dmArray<dmRender::HFontMap>     m_FontMaps;
        dmArray<dmGraphics::HTexture>   m_Textures;
        const char*                     m_Path;
        dmGui::HContext                 m_GuiContext;
        dmRender::HMaterial             m_Material;
    };

    dmResource::Result ResCreateSceneDesc(dmResource::HFactory factory,
                                          void* context,
                                          const void* buffer, uint32_t buffer_size,
                                          dmResource::SResourceDescriptor* resource,
                                          const char* filename);

    dmResource::Result ResDestroySceneDesc(dmResource::HFactory factory,
                                           void* context,
                                           dmResource::SResourceDescriptor* resource);

    dmResource::Result ResRecreateSceneDesc(dmResource::HFactory factory,
                                          void* context,
                                          const void* buffer, uint32_t buffer_size,
                                          dmResource::SResourceDescriptor* resource,
                                          const char* filename);

    dmResource::Result ResCreateGuiScript(dmResource::HFactory factory,
                                          void* context,
                                          const void* buffer, uint32_t buffer_size,
                                          dmResource::SResourceDescriptor* resource,
                                          const char* filename);

    dmResource::Result ResDestroyGuiScript(dmResource::HFactory factory,
                                           void* context,
                                           dmResource::SResourceDescriptor* resource);

    dmResource::Result ResRecreateGuiScript(dmResource::HFactory factory,
                                          void* context,
                                          const void* buffer, uint32_t buffer_size,
                                          dmResource::SResourceDescriptor* resource,
                                          const char* filename);

}

#endif // DM_GAMESYSTEM_RES_GUI_H
