#include "res_particlefx.h"

#include <dlib/log.h>

#include <particle/particle_ddf.h>

namespace dmGameSystem
{
    dmResource::Result AcquireResources(dmResource::HFactory factory, const void* buffer, uint32_t buffer_size, dmParticle::HPrototype prototype, const char* filename)
    {
        if (prototype == dmParticle::INVALID_PROTOTYPE)
        {
            dmLogWarning("Particle fx could not be loaded: %s.", filename);
            return dmResource::RESULT_FORMAT_ERROR;
        }
        dmResource::Result result;
        uint32_t emitter_count = dmParticle::GetEmitterCount(prototype);
        for (uint32_t i = 0; i < emitter_count; ++i)
        {
            void* tile_source;
            const char* tile_source_path = dmParticle::GetTileSourcePath(prototype, i);
            result = dmResource::Get(factory, tile_source_path, (void**) &tile_source);
            if (result != dmResource::RESULT_OK)
            {
                dmLogError("Could not load texture \"%s\" for particle fx \"%s\".", tile_source_path, filename);
                return result;
            }
            dmParticle::SetTileSource(prototype, i, tile_source);
            void* material;
            const char* material_path = dmParticle::GetMaterialPath(prototype, i);
            result = dmResource::Get(factory, material_path, (void**) &material);
            if (result != dmResource::RESULT_OK)
            {
                dmLogError("Could not load material \"%s\" for particle fx \"%s\".", material_path, filename);
                return result;
            }
            dmParticle::SetMaterial(prototype, i, material);
        }
        return dmResource::RESULT_OK;
    }

    static void ReleasePrototypeResources(dmResource::HFactory factory, dmParticle::HPrototype prototype)
    {
        if (prototype != dmParticle::INVALID_PROTOTYPE)
        {
            uint32_t emitter_count = dmParticle::GetEmitterCount(prototype);
            for (uint32_t i = 0; i < emitter_count; ++i)
            {
                void* material = dmParticle::GetMaterial(prototype, i);
                if (material != 0)
                {
                    dmResource::Release(factory, material);
                    dmParticle::SetMaterial(prototype, i, 0x0);
                }
                void* tile_source = dmParticle::GetTileSource(prototype, i);
                if (tile_source != 0)
                {
                    dmResource::Release(factory, tile_source);
                    dmParticle::SetTileSource(prototype, i, 0x0);
                }
            }
        }
    }

    dmResource::Result ResParticleFXCreate(const dmResource::ResourceCreateParams& params)
    {
        dmParticle::HPrototype prototype = dmParticle::NewPrototype(params.m_Buffer, params.m_BufferSize);
        dmResource::Result r = AcquireResources(params.m_Factory, params.m_Buffer, params.m_BufferSize, prototype, params.m_Filename);
        if (r == dmResource::RESULT_OK)
        {
            params.m_Resource->m_Resource = (void*) prototype;
        }
        else
        {
            ReleasePrototypeResources(params.m_Factory, prototype);
            dmParticle::DeletePrototype(prototype);
        }
        return r;
    }

    dmResource::Result ResParticleFXDestroy(const dmResource::ResourceDestroyParams& params)
    {
        dmParticle::HPrototype prototype = (dmParticle::HPrototype)params.m_Resource->m_Resource;
        assert(prototype != dmParticle::INVALID_PROTOTYPE);
        ReleasePrototypeResources(params.m_Factory, prototype);
        dmParticle::DeletePrototype(prototype);
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResParticleFXRecreate(const dmResource::ResourceRecreateParams& params)
    {
        dmParticle::HPrototype prototype = (dmParticle::HPrototype)params.m_Resource->m_Resource;
        ReleasePrototypeResources(params.m_Factory, prototype);
        if (!dmParticle::ReloadPrototype(prototype, params.m_Buffer, params.m_BufferSize))
        {
            return dmResource::RESULT_INVALID_DATA;
        }
        return AcquireResources(params.m_Factory, params.m_Buffer, params.m_BufferSize, prototype, params.m_Filename);
    }
}
