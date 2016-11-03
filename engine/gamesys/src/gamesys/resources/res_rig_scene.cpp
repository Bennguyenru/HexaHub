#include "res_rig_scene.h"

#include <dlib/log.h>

namespace dmGameSystem
{
    using namespace Vectormath::Aos;

    dmResource::Result AcquireResources(dmResource::HFactory factory, RigSceneResource* resource, const char* filename, bool reload)
    {
        dmResource::Result result;
        if (strlen(resource->m_RigScene->m_TextureSet) != 0)
        {
            result = dmResource::Get(factory, resource->m_RigScene->m_TextureSet, (void**) &resource->m_TextureSet);
            if (result != dmResource::RESULT_OK)
                return result;
        }
        else
        {
            resource->m_TextureSet = 0x0;
        }
        if(resource->m_RigScene->m_Skeleton[0])
        {
            result = dmResource::RESULT_INVAL;
            if(reload)
            {
                result = dmResource::ReloadResource(factory, resource->m_RigScene->m_Skeleton, 0);
            }
            if (result != dmResource::RESULT_OK)
            {
                result = dmResource::Get(factory, resource->m_RigScene->m_Skeleton, (void**) &resource->m_SkeletonRes);
                if (result != dmResource::RESULT_OK)
                    return result;
            }
        }
        else
        {
            resource->m_SkeletonRes = 0x0;
        }
        if(resource->m_RigScene->m_AnimationSet[0])
        {
            result = dmResource::RESULT_INVAL;
            if(reload)
            {
                result = dmResource::ReloadResource(factory, resource->m_RigScene->m_AnimationSet, 0);
            }
            if (result != dmResource::RESULT_OK)
            {
                result = dmResource::Get(factory, resource->m_RigScene->m_AnimationSet, (void**) &resource->m_AnimationSetRes);
                if (result != dmResource::RESULT_OK)
                    return result;
            }
        }
        else
        {
            resource->m_AnimationSetRes = 0x0;
        }
        result = dmResource::RESULT_INVAL;
        if(reload)
        {
            result = dmResource::ReloadResource(factory, resource->m_RigScene->m_MeshSet, 0);
        }
        if (result != dmResource::RESULT_OK)
        {
            result = dmResource::Get(factory, resource->m_RigScene->m_MeshSet, (void**) &resource->m_MeshSetRes);
            if (result != dmResource::RESULT_OK)
                return result;
        }

        if (result == dmResource::RESULT_OK && resource->m_SkeletonRes)
        {
            dmRig::CreateBindPose(*resource->m_SkeletonRes->m_Skeleton, resource->m_BindPose);
            dmRig::CreateLookUpArrays(*resource->m_MeshSetRes->m_MeshSet, *resource->m_AnimationSetRes->m_AnimationSet, *resource->m_SkeletonRes->m_Skeleton, resource->m_TrackIdxToPose, resource->m_PoseIdxToInfluence);
        }
        return result;
    }

    static void ReleaseResources(dmResource::HFactory factory, RigSceneResource* resource)
    {
        if (resource->m_RigScene != 0x0)
            dmDDF::FreeMessage(resource->m_RigScene);
        if (resource->m_TextureSet != 0x0)
            dmResource::Release(factory, resource->m_TextureSet);
        if (resource->m_SkeletonRes != 0x0)
            dmResource::Release(factory, resource->m_SkeletonRes);
        if (resource->m_AnimationSetRes != 0x0)
            dmResource::Release(factory, resource->m_AnimationSetRes);
        if (resource->m_MeshSetRes != 0x0)
            dmResource::Release(factory, resource->m_MeshSetRes);

    }

    dmResource::Result ResRigScenePreload(const dmResource::ResourcePreloadParams& params)
    {
        dmRigDDF::RigScene* rig_scene;
        dmDDF::Result e = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &dmRigDDF_RigScene_DESCRIPTOR, (void**) &rig_scene);
        if (e != dmDDF::RESULT_OK)
        {
            return dmResource::RESULT_DDF_ERROR;
        }

        dmResource::PreloadHint(params.m_HintInfo, rig_scene->m_TextureSet);
        dmResource::PreloadHint(params.m_HintInfo, rig_scene->m_Skeleton);
        dmResource::PreloadHint(params.m_HintInfo, rig_scene->m_AnimationSet);
        dmResource::PreloadHint(params.m_HintInfo, rig_scene->m_MeshSet);

        *params.m_PreloadData = rig_scene;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResRigSceneCreate(const dmResource::ResourceCreateParams& params)
    {
        RigSceneResource* ss_resource = new RigSceneResource();
        ss_resource->m_RigScene = (dmRigDDF::RigScene*) params.m_PreloadData;
        dmResource::Result r = AcquireResources(params.m_Factory, ss_resource, params.m_Filename, false);
        if (r == dmResource::RESULT_OK)
        {
            params.m_Resource->m_Resource = (void*) ss_resource;
        }
        else
        {
            ReleaseResources(params.m_Factory, ss_resource);
            delete ss_resource;
        }
        return r;
    }

    dmResource::Result ResRigSceneDestroy(const dmResource::ResourceDestroyParams& params)
    {
        RigSceneResource* ss_resource = (RigSceneResource*)params.m_Resource->m_Resource;
        ReleaseResources(params.m_Factory, ss_resource);
        delete ss_resource;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResRigSceneRecreate(const dmResource::ResourceRecreateParams& params)
    {
        dmRigDDF::RigScene* rig_scene;
        dmDDF::Result e = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &dmRigDDF_RigScene_DESCRIPTOR, (void**) &rig_scene);
        if (e != dmDDF::RESULT_OK)
        {
            return dmResource::RESULT_DDF_ERROR;
        }

        RigSceneResource* ss_resource = (RigSceneResource*)params.m_Resource->m_Resource;
        ReleaseResources(params.m_Factory, ss_resource);
        ss_resource->m_RigScene = rig_scene;
        return AcquireResources(params.m_Factory, ss_resource, params.m_Filename, true);
    }
}
