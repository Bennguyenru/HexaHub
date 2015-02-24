#ifndef DM_GRAPHICS_DEVICE_FLASH
#define DM_GRAPHICS_DEVICE_FLASH

namespace dmGraphics
{
    const uint32_t DM_GRAPHICS_FLASH_MAX_INDICES = 65536;

    struct Texture
    {
        flash::display3D::textures::Texture m_Texture;
        TextureFormat m_Format;
        TextureFormat m_InternalFormat;
        uint32_t m_Width;
        uint32_t m_Height;
        uint32_t m_OriginalWidth;
        uint32_t m_OriginalHeight;
    };

    struct VertexStream
    {
        const void* m_Source;
        void* m_Buffer;
        uint16_t m_Size;
        uint16_t m_Stride;
    };

    const static uint32_t MAX_VERTEX_STREAM_COUNT = 8;
    const static uint32_t MAX_REGISTER_COUNT = 16;
    const static uint32_t MAX_TEXTURE_COUNT = 32;

    struct FrameBuffer
    {
        void*       m_ColorBuffer;
        void*       m_DepthBuffer;
        void*       m_StencilBuffer;
        uint32_t    m_ColorBufferSize;
        uint32_t    m_DepthBufferSize;
        uint32_t    m_StencilBufferSize;
    };

    struct VertexDeclaration
    {
        VertexElement m_Elements[MAX_VERTEX_STREAM_COUNT];
    };

    struct VertexBuffer
    {
        flash::display3D::VertexBuffer3D m_VB;
        char*                            m_Buffer;
        uint32_t                         m_Size;
    };

    struct IndexBuffer
    {
        flash::display3D::IndexBuffer3D m_IB;
        uint32_t m_Size;
    };

    struct RenderTarget
    {
        TextureParams   m_BufferTextureParams[MAX_BUFFER_TYPE_COUNT];
        HTexture        m_BufferTextures[MAX_BUFFER_TYPE_COUNT];
        FrameBuffer     m_FrameBuffer;
    };

    struct VertexProgram
    {
        char* m_Data;
    };

    struct FragmentProgram
    {
        char* m_Data;
    };

    struct Program
    {
        Program(VertexProgram* vp, FragmentProgram* fp)
        {
            m_VP = vp;
            m_FP = fp;
        }
        VertexProgram* m_VP;
        FragmentProgram* m_FP;
    };

    struct Context
    {
        // TODO: Review context and cleanup
        Context(const ContextParams& params);
        flash::display3D::Context3D m_Ctx3d;
        flash::display3D::IndexBuffer3D m_IndexBuffer;

        BlendFactor                 m_SourceFactor;
        BlendFactor                 m_DestinationFactor;
        FaceType                    m_CullFace;

        VertexStream                m_VertexStreams[MAX_VERTEX_STREAM_COUNT];
        Vectormath::Aos::Vector4    m_ProgramRegisters[MAX_REGISTER_COUNT];
        HTexture                    m_Textures[MAX_TEXTURE_COUNT];
        FrameBuffer                 m_MainFrameBuffer;
        FrameBuffer*                m_CurrentFrameBuffer;
        void*                       m_Program;
        WindowResizeCallback        m_WindowResizeCallback;
        void*                       m_WindowResizeCallbackUserData;
        WindowCloseCallback         m_WindowCloseCallback;
        void*                       m_WindowCloseCallbackUserData;
        TextureFilter               m_DefaultTextureMinFilter;
        TextureFilter               m_DefaultTextureMagFilter;
        CompareFunc                 m_DepthFunc;
        CompareFunc                 m_StencilFunc;
        StencilOp                   m_StencilOpSFail;
        StencilOp                   m_StencilOpDPFail;
        StencilOp                   m_StencilOpDPPass;
        uint32_t                    m_Width;
        uint32_t                    m_Height;
        uint32_t                    m_WindowWidth;
        uint32_t                    m_WindowHeight;
        uint32_t                    m_StencilMask;
        uint32_t                    m_StencilFuncRef;
        uint32_t                    m_StencilFuncMask;
        uint32_t                    m_TextureFormatSupport;
        uint32_t                    m_WindowOpened : 1;
        uint32_t                    m_RedMask : 1;
        uint32_t                    m_GreenMask : 1;
        uint32_t                    m_BlueMask : 1;
        uint32_t                    m_AlphaMask : 1;
        uint32_t                    m_DepthMask : 1;
        // Only use for testing
        uint32_t                    m_RequestWindowClose : 1;
    };
}

#endif // DM_GRAPHICS_DEVICE_FLASH
