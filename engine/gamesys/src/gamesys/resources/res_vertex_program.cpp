#include "res_vertex_program.h"
#include <graphics/graphics.h>
#include <graphics/graphics_ddf.h>

namespace dmGameSystem
{
    static dmResource::Result AcquireResources(dmGraphics::HContext context, dmResource::HFactory factory, dmGraphics::ShaderDesc* ddf, dmGraphics::HVertexProgram* program, const char* filename)
    {
        uint32_t shader_data_len;
        void* shader_data =  dmGraphics::GetShaderProgramData(context, ddf, shader_data_len);
        if (shader_data == 0x0)
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }
        dmGraphics::HVertexProgram prog = dmGraphics::NewVertexProgram(context, shader_data, shader_data_len);
        if (prog == 0)
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }
        *program = prog;

        return dmResource::RESULT_OK;
    }

    dmResource::Result ResVertexProgramPreload(const dmResource::ResourcePreloadParams& params)
    {
        dmGraphics::ShaderDesc* ddf;
        dmDDF::Result e = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &ddf);
        if (e != dmDDF::RESULT_OK)
        {
            return dmResource::RESULT_DDF_ERROR;
        }
        *params.m_PreloadData = ddf;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResVertexProgramCreate(const dmResource::ResourceCreateParams& params)
    {
        dmGraphics::ShaderDesc* ddf = (dmGraphics::ShaderDesc*)params.m_PreloadData;
        dmGraphics::HVertexProgram resource = 0x0;
        dmResource::Result r = AcquireResources((dmGraphics::HContext) params.m_Context, params.m_Factory, ddf, &resource, params.m_Filename);
        dmDDF::FreeMessage(ddf);
        if (r == dmResource::RESULT_OK)
        {
            params.m_Resource->m_Resource = (void*) resource;
        }
        return r;
    }

    dmResource::Result ResVertexProgramDestroy(const dmResource::ResourceDestroyParams& params)
    {
        dmGraphics::HVertexProgram resource = (dmGraphics::HVertexProgram)params.m_Resource->m_Resource;
        dmGraphics::DeleteVertexProgram(resource);
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResVertexProgramRecreate(const dmResource::ResourceRecreateParams& params)
    {
        dmGraphics::HVertexProgram resource = (dmGraphics::HVertexProgram)params.m_Resource->m_Resource;
        if (resource == 0)
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }

        dmGraphics::ShaderDesc* ddf;
        dmDDF::Result e = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &ddf);
        if (e != dmDDF::RESULT_OK)
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }

        dmResource::Result res = dmResource::RESULT_OK;
        uint32_t shader_data_len;
        void* shader_data =  dmGraphics::GetShaderProgramData((dmGraphics::HContext)params.m_Context, ddf, shader_data_len);
        if (shader_data == 0x0)
        {
            res = dmResource::RESULT_FORMAT_ERROR;
        }
        else if(!dmGraphics::ReloadVertexProgram(resource, shader_data, shader_data_len))
        {
            res = dmResource::RESULT_FORMAT_ERROR;
        }

        dmDDF::FreeMessage(ddf);
        return res;
    }

}
