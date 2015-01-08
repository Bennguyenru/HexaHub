#ifndef DM_GAMESYS_COMP_SPINE_MODEL_H
#define DM_GAMESYS_COMP_SPINE_MODEL_H

#include <stdint.h>
#include <dlib/object_pool.h>
#include <gameobject/gameobject.h>

#include "../resources/res_spine_model.h"

namespace dmGameSystem
{
    using namespace Vectormath::Aos;
    using namespace dmGameSystemDDF;

    union SortKeySpine
    {
        struct
        {
            uint64_t m_Index : 16;  // Index is used to ensure stable sort
            uint64_t m_MixedHash : 32;
            uint64_t m_Z : 16; // Quantified relative z
        };
        uint64_t     m_Key;
    };

    struct SpinePlayer
    {
        /// Currently playing animation
        dmGameSystemDDF::SpineAnimation*    m_Animation;
        dmhash_t                            m_AnimationId;
        /// Playback cursor in the interval [0,duration]
        float                               m_Cursor;
        /// Playback mode
        dmGameObject::Playback              m_Playback;
        /// Whether the animation is currently playing
        uint16_t                             m_Playing : 1;
        /// Whether the animation is playing backwards (e.g. ping pong)
        uint16_t                             m_Backwards : 1;
    };

    struct MeshProperties {
        float m_Color[4];
        uint32_t m_Order;
        bool m_Visible;
    };

    struct SpineModelComponent
    {
        SpinePlayer                 m_Players[2];
        dmGameObject::HInstance     m_Instance;
        dmTransform::Transform      m_Transform;
        Matrix4                     m_World;
        SortKeySpine                m_SortKey;
        // Hash of the m_Resource-pointer. Hash is used to be compatible with 64-bit arch as a 32-bit value is used for sorting
        // See GenerateKeys
        uint32_t                    m_MixedHash;
        dmMessage::URL              m_Listener;
        SpineModelResource*         m_Resource;
        dmArray<dmRender::Constant> m_RenderConstants;
        dmArray<Vector4>            m_PrevRenderConstants;
        /// Animated pose, every transform is local-to-model-space and describes the delta between bind pose and animation
        dmArray<dmTransform::Transform> m_Pose;
        /// Nodes corresponding to the bones
        dmArray<dmhash_t> m_NodeIds;
        /// Animated mesh properties
        dmArray<MeshProperties>     m_MeshProperties;
        /// Currently used mesh
        dmGameSystemDDF::MeshEntry* m_MeshEntry;
        dmhash_t                    m_Skin;
        float                       m_BlendDuration;
        float                       m_BlendTimer;
        uint8_t                     m_ComponentIndex;
        /// Component enablement
        uint8_t                     m_Enabled : 1;
        /// Current player index
        uint8_t                     m_CurrentPlayer : 1;
        /// Whether we are currently X-fading or not
        uint8_t                     m_Blending : 1;
        /// Added to update or not
        uint8_t                     m_AddedToUpdate : 1;
    };

    struct SpineModelVertex
    {
        float x;
        float y;
        float z;
        uint16_t u;
        uint16_t v;
        uint8_t r;
        uint8_t g;
        uint8_t b;
        uint8_t a;
    };

    struct SpineModelWorld
    {
        dmObjectPool<SpineModelComponent*>  m_Components;
        dmArray<dmRender::RenderObject>     m_RenderObjects;
        dmGraphics::HVertexDeclaration      m_VertexDeclaration;
        dmGraphics::HVertexBuffer           m_VertexBuffer;
        dmArray<SpineModelVertex>           m_VertexBufferData;

        dmArray<uint32_t>                   m_RenderSortBuffer;
        dmArray<uint32_t>                   m_DrawOrderToMesh;
        // Temporary scratch array for instances, only used during the creation phase of components
        dmArray<dmGameObject::HInstance>    m_ScratchInstances;
        float                               m_MinZ;
        float                               m_MaxZ;
    };

    dmGameObject::CreateResult CompSpineModelNewWorld(const dmGameObject::ComponentNewWorldParams& params);

    dmGameObject::CreateResult CompSpineModelDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params);

    dmGameObject::CreateResult CompSpineModelCreate(const dmGameObject::ComponentCreateParams& params);

    dmGameObject::CreateResult CompSpineModelDestroy(const dmGameObject::ComponentDestroyParams& params);

    dmGameObject::CreateResult CompSpineModelAddToUpdate(const dmGameObject::ComponentAddToUpdateParams& params);

    dmGameObject::UpdateResult CompSpineModelUpdate(const dmGameObject::ComponentsUpdateParams& params);

    dmGameObject::UpdateResult CompSpineModelOnMessage(const dmGameObject::ComponentOnMessageParams& params);

    void CompSpineModelOnReload(const dmGameObject::ComponentOnReloadParams& params);

    dmGameObject::PropertyResult CompSpineModelGetProperty(const dmGameObject::ComponentGetPropertyParams& params, dmGameObject::PropertyDesc& out_value);

    dmGameObject::PropertyResult CompSpineModelSetProperty(const dmGameObject::ComponentSetPropertyParams& params);
}

#endif // DM_GAMESYS_COMP_SPINE_MODEL_H
