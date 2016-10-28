#ifndef DM_GAMESYS_COMP_GUI_H
#define DM_GAMESYS_COMP_GUI_H

#include <stdint.h>

#include <gui/gui.h>
#include <gameobject/gameobject.h>
#include <render/render.h>

namespace dmGameSystem
{
    extern dmRender::HRenderType g_GuiRenderType;

    struct GuiComponent
    {
        dmGui::HScene           m_Scene;
        dmGameObject::HInstance m_Instance;
        uint8_t                 m_ComponentIndex;
        uint8_t                 m_Enabled : 1;
        uint8_t                 m_AddedToUpdate : 1;
    };

    struct BoxVertex
    {
        inline BoxVertex() {}
        inline BoxVertex(const Vectormath::Aos::Vector4& p, float u, float v, uint32_t color)
        {
            SetPosition(p);
            SetUV(u, v);
            SetColor(color);
        }

        inline void SetPosition(const Vectormath::Aos::Vector4& p)
        {
            m_Position[0] = p.getX();
            m_Position[1] = p.getY();
            m_Position[2] = p.getZ();
        }

        inline void SetUV(float u, float v)
        {
            m_UV[0] = u;
            m_UV[1] = v;
        }

        inline void SetColor(uint32_t color)
        {
            m_Color = color;
        }

        float    m_Position[3];
        float    m_UV[2];
        uint32_t m_Color;
    };

    struct GuiRenderObject
    {
        dmRender::RenderObject m_RenderObject;
        uint32_t m_SortOrder;
    };

    struct GuiWorld
    {
        dmArray<GuiRenderObject>         m_GuiRenderObjects;
        dmArray<GuiComponent*>           m_Components;
        dmGraphics::HVertexDeclaration   m_VertexDeclaration;
        dmGraphics::HVertexBuffer        m_VertexBuffer;
        dmArray<BoxVertex>               m_ClientVertexBuffer;
        dmGraphics::HTexture             m_WhiteTexture;
        dmRig::HRigContext               m_RigContext;
    };

    dmGameObject::CreateResult CompGuiNewWorld(const dmGameObject::ComponentNewWorldParams& params);

    dmGameObject::CreateResult CompGuiDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params);

    dmGameObject::CreateResult CompGuiCreate(const dmGameObject::ComponentCreateParams& params);

    dmGameObject::CreateResult CompGuiDestroy(const dmGameObject::ComponentDestroyParams& params);

    dmGameObject::CreateResult CompGuiInit(const dmGameObject::ComponentInitParams& params);

    dmGameObject::CreateResult CompGuiFinal(const dmGameObject::ComponentFinalParams& params);

    dmGameObject::CreateResult CompGuiAddToUpdate(const dmGameObject::ComponentAddToUpdateParams& params);

    dmGameObject::UpdateResult CompGuiUpdate(const dmGameObject::ComponentsUpdateParams& params);

    dmGameObject::UpdateResult CompGuiRender(const dmGameObject::ComponentsRenderParams& params);

    dmGameObject::UpdateResult CompGuiOnMessage(const dmGameObject::ComponentOnMessageParams& params);

    dmGameObject::InputResult CompGuiOnInput(const dmGameObject::ComponentOnInputParams& params);

    void CompGuiOnReload(const dmGameObject::ComponentOnReloadParams& params);
}

#endif // DM_GAMESYS_COMP_GUI_H
