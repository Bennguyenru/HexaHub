#include "res_model.h"

#include <dlib/log.h>

namespace dmGameSystem
{
    dmResource::Result AcquireResources(dmResource::HFactory factory, ModelResource* resource, const char* filename)
    {
        dmResource::Result result = dmResource::Get(factory, resource->m_Model->m_Mesh, (void**) &resource->m_RigScene);
        if (result != dmResource::RESULT_OK)
            return result;
        result = dmResource::Get(factory, resource->m_Model->m_Material, (void**) &resource->m_Material);
        return result;
    }

    static void ReleaseResources(dmResource::HFactory factory, ModelResource* resource)
    {
        if (resource->m_Model != 0x0)
            dmDDF::FreeMessage(resource->m_Model);
        if (resource->m_RigScene != 0x0)
            dmResource::Release(factory, resource->m_RigScene);
        if (resource->m_Material != 0x0)
            dmResource::Release(factory, resource->m_Material);
    }

    dmResource::Result ResModelPreload(const dmResource::ResourcePreloadParams& params)
    {
        dmModelDDF::ModelDesc* ddf;
        dmDDF::Result e = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &dmModelDDF_ModelDesc_DESCRIPTOR, (void**) &ddf);
        if (e != dmDDF::RESULT_OK)
        {
            return dmResource::RESULT_DDF_ERROR;
        }

        dmResource::PreloadHint(params.m_HintInfo, ddf->m_Mesh);
        dmResource::PreloadHint(params.m_HintInfo, ddf->m_Material);

        *params.m_PreloadData = ddf;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResModelCreate(const dmResource::ResourceCreateParams& params)
    {
        ModelResource* model_resource = new ModelResource();
        model_resource->m_Model = (dmModelDDF::ModelDesc*) params.m_PreloadData;
        dmResource::Result r = AcquireResources(params.m_Factory, model_resource, params.m_Filename);
        if (r == dmResource::RESULT_OK)
        {
            params.m_Resource->m_Resource = (void*) model_resource;
        }
        else
        {
            ReleaseResources(params.m_Factory, model_resource);
            delete model_resource;
        }
        return r;
    }

    dmResource::Result ResModelDestroy(const dmResource::ResourceDestroyParams& params)
    {
        ModelResource* model_resource = (ModelResource*)params.m_Resource->m_Resource;
        ReleaseResources(params.m_Factory, model_resource);
        delete model_resource;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResModelRecreate(const dmResource::ResourceRecreateParams& params)
    {
        dmModelDDF::ModelDesc* ddf;
        dmDDF::Result e = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &dmModelDDF_ModelDesc_DESCRIPTOR, (void**) &ddf);
        if (e != dmDDF::RESULT_OK)
        {
            return dmResource::RESULT_DDF_ERROR;
        }
        ModelResource* model_resource = (ModelResource*)params.m_Resource->m_Resource;
        ReleaseResources(params.m_Factory, model_resource);
        model_resource->m_Model = ddf;
        return AcquireResources(params.m_Factory, model_resource, params.m_Filename);
    }
}
