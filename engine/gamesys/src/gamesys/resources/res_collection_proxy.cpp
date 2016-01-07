#include "res_collection_proxy.h"

namespace dmGameSystem
{
    dmResource::Result AcquireResource(dmResource::HFactory factory, const void* buffer, uint32_t buffer_size, CollectionProxyResource* resource)
    {
        dmDDF::Result e  = dmDDF::LoadMessage(buffer, buffer_size, &resource->m_DDF);
        if ( e != dmDDF::RESULT_OK )
            return dmResource::RESULT_FORMAT_ERROR;

        return dmResource::RESULT_OK;
    }

    void ReleaseResources(dmResource::HFactory factory, CollectionProxyResource* resource)
    {
        if (resource->m_DDF != 0x0)
            dmDDF::FreeMessage(resource->m_DDF);
    }

    dmResource::Result ResCollectionProxyCreate(const dmResource::ResourceCreateParams& params)
    {
        CollectionProxyResource* cspr = new CollectionProxyResource();
        dmResource::Result r = AcquireResource(params.m_Factory, params.m_Buffer, params.m_BufferSize, cspr);
        if (r == dmResource::RESULT_OK)
        {
            params.m_Resource->m_Resource = (void*) cspr;
        }
        else
        {
            ReleaseResources(params.m_Factory, cspr);
        }
        return r;
    }

    dmResource::Result ResCollectionProxyDestroy(const dmResource::ResourceDestroyParams& params)
    {
        CollectionProxyResource* cspr = (CollectionProxyResource*) params.m_Resource->m_Resource;
        ReleaseResources(params.m_Factory, cspr);
        delete cspr;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResCollectionProxyRecreate(const dmResource::ResourceRecreateParams& params)
    {
        CollectionProxyResource tmp_cspr;
        dmResource::Result r = AcquireResource(params.m_Factory, params.m_Buffer, params.m_BufferSize, &tmp_cspr);
        if (r == dmResource::RESULT_OK)
        {
            CollectionProxyResource* cspr = (CollectionProxyResource*) params.m_Resource->m_Resource;
            ReleaseResources(params.m_Factory, cspr);
            *cspr = tmp_cspr;
        }
        else
        {
            ReleaseResources(params.m_Factory, &tmp_cspr);
        }
        return r;
    }
}
