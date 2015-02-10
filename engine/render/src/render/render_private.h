#ifndef RENDERINTERNAL_H
#define RENDERINTERNAL_H

#include <vectormath/cpp/vectormath_aos.h>

#include <dlib/array.h>
#include <dlib/message.h>
#include <dlib/hashtable.h>

#include "render.h"

extern "C"
{
#include <lua/lua.h>
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

namespace dmRender
{
    using namespace Vectormath::Aos;

#define DEBUG_3D_NAME "_debug3d"
#define DEBUG_2D_NAME "_debug2d"

    struct Sampler
    {
        dmhash_t m_NameHash;
        int16_t  m_Location;
        int16_t  m_Unit;

        Sampler(dmhash_t name_hash, int16_t location)
            : m_NameHash(name_hash), m_Location(location), m_Unit(-1)
        {
        }
    };

    struct Material
    {

        Material()
        : m_RenderContext(0)
        , m_Program(0)
        , m_VertexProgram(0)
        , m_FragmentProgram(0)
        , m_TagMask(0)
        , m_UserData1(0)
        , m_UserData2(0)
        {
        }

        dmRender::HRenderContext                m_RenderContext;
        dmGraphics::HProgram                    m_Program;
        dmGraphics::HVertexProgram              m_VertexProgram;
        dmGraphics::HFragmentProgram            m_FragmentProgram;
        dmHashTable64<int32_t>                  m_NameHashToLocation;
        dmArray<MaterialConstant>               m_Constants;
        dmArray<Sampler>                        m_Samplers;
        uint32_t                                m_TagMask;
        uint64_t                                m_UserData1;
        uint64_t                                m_UserData2;
    };

    // The order of this enum also defines the order in which the corresponding ROs should be rendered
    enum DebugRenderType
    {
        DEBUG_RENDER_TYPE_FACE_3D,
        DEBUG_RENDER_TYPE_LINE_3D,
        DEBUG_RENDER_TYPE_FACE_2D,
        DEBUG_RENDER_TYPE_LINE_2D,
        MAX_DEBUG_RENDER_TYPE_COUNT
    };

    struct DebugRenderTypeData
    {
        dmRender::RenderObject  m_RenderObject;
        void*                   m_ClientBuffer;
    };

    struct DebugRenderer
    {
        DebugRenderTypeData             m_TypeData[MAX_DEBUG_RENDER_TYPE_COUNT];
        Predicate                       m_3dPredicate;
        Predicate                       m_2dPredicate;
        dmRender::HRenderContext        m_RenderContext;
        dmGraphics::HVertexBuffer       m_VertexBuffer;
        dmGraphics::HVertexDeclaration  m_VertexDeclaration;
        uint32_t                        m_MaxVertexCount;
    };

    struct TextEntry
    {
        StencilTestParams   m_StencilTestParams;
        Matrix4             m_Transform;
        uint32_t            m_StringOffset;
        HFontMap            m_FontMap;
        uint32_t            m_FaceColor;
        uint32_t            m_OutlineColor;
        uint32_t            m_ShadowColor;
        uint32_t            m_Depth;
        uint16_t            m_RenderOrder;
        float               m_Width;
        float               m_Height;
        bool                m_LineBreak;
        int32_t             m_Next;
        int32_t             m_Tail;
        uint32_t            m_Align : 2;
        uint32_t            m_VAlign : 2;
        uint32_t            m_StencilTestParamsSet : 1;
    };

    struct TextContext
    {
        dmArray<dmRender::RenderObject>     m_RenderObjects;
        dmGraphics::HVertexBuffer           m_VertexBuffer;
        void*                               m_ClientBuffer;
        dmGraphics::HVertexDeclaration      m_VertexDecl;
        uint32_t                            m_RenderObjectIndex;
        uint32_t                            m_VertexIndex;
        uint32_t                            m_MaxVertexCount;
        dmArray<char>                       m_TextBuffer;
        // Map from batch id (hash of font-map etc) to index into m_TextEntries
        dmHashTable64<int32_t>              m_Batches;
        dmArray<TextEntry>                  m_TextEntries;
    };

    struct RenderTargetSetup
    {
        dmGraphics::HRenderTarget   m_RenderTarget;
        dmhash_t                    m_Hash;
    };

    struct RenderScriptContext
    {
        RenderScriptContext();

        lua_State*                  m_LuaState;
        uint32_t                    m_CommandBufferSize;
    };

    struct RenderContext
    {
        dmGraphics::HTexture        m_Textures[RenderObject::MAX_TEXTURE_COUNT];
        DebugRenderer               m_DebugRenderer;
        TextContext                 m_TextContext;
        dmScript::HContext          m_ScriptContext;
        RenderScriptContext         m_RenderScriptContext;
        dmArray<RenderTargetSetup>  m_RenderTargets;
        dmArray<RenderObject*>      m_RenderObjects;
        HFontMap                    m_SystemFontMap;

        Matrix4                     m_View;
        Matrix4                     m_Projection;
        Matrix4                     m_ViewProj;

        dmGraphics::HContext        m_GraphicsContext;

        HMaterial                   m_Material;

        dmMessage::HSocket          m_Socket;

        uint32_t                    m_OutOfResources : 1;
    };

    void RenderTypeTextBegin(HRenderContext rendercontext, void* user_context);
    void RenderTypeTextDraw(HRenderContext rendercontext, void* user_context, RenderObject* ro_, uint32_t count);

    void RenderTypeDebugBegin(HRenderContext rendercontext, void* user_context);
    void RenderTypeDebugDraw(HRenderContext rendercontext, void* user_context, RenderObject* ro, uint32_t count);

    Result GenerateKey(HRenderContext render_context, const Matrix4& view_matrix);

}

#endif

