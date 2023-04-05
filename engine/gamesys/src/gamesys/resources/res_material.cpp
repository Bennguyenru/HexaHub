// Copyright 2020-2023 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include "res_material.h"

#include <string.h>
#include <algorithm> // std::sort

#include <dlib/dstrings.h>
#include <dlib/hash.h>
#include <dlib/log.h>

#include <render/render.h>
#include <render/material_ddf.h>

#include "res_fragment_program.h"
#include "res_vertex_program.h"

namespace dmGameSystem
{
    static dmGraphics::TextureWrap wrap_lut[] = {dmGraphics::TEXTURE_WRAP_REPEAT,
                                                 dmGraphics::TEXTURE_WRAP_MIRRORED_REPEAT,
                                                 dmGraphics::TEXTURE_WRAP_CLAMP_TO_EDGE};

    static dmGraphics::TextureFilter filter_lut[] = {dmGraphics::TEXTURE_FILTER_NEAREST,
                                                     dmGraphics::TEXTURE_FILTER_LINEAR,
                                                     dmGraphics::TEXTURE_FILTER_NEAREST_MIPMAP_NEAREST,
                                                     dmGraphics::TEXTURE_FILTER_NEAREST_MIPMAP_LINEAR,
                                                     dmGraphics::TEXTURE_FILTER_LINEAR_MIPMAP_NEAREST,
                                                     dmGraphics::TEXTURE_FILTER_LINEAR_MIPMAP_LINEAR};

    static dmGraphics::TextureWrap WrapFromDDF(dmRenderDDF::MaterialDesc::WrapMode wrap_mode)
    {
        assert(wrap_mode <= dmRenderDDF::MaterialDesc::WRAP_MODE_CLAMP_TO_EDGE);
        return wrap_lut[wrap_mode];
    }

    static dmGraphics::TextureFilter FilterMinFromDDF(dmRenderDDF::MaterialDesc::FilterModeMin min_filter)
    {
        assert(min_filter <= dmRenderDDF::MaterialDesc::FILTER_MODE_MIN_LINEAR_MIPMAP_LINEAR);
        return filter_lut[min_filter];
    }

    static dmGraphics::TextureFilter FilterMagFromDDF(dmRenderDDF::MaterialDesc::FilterModeMag mag_filter)
    {
        assert(mag_filter <= dmRenderDDF::MaterialDesc::FILTER_MODE_MAG_LINEAR);
        return filter_lut[mag_filter];
    }

    static bool ValidateFormat(dmRenderDDF::MaterialDesc* material_desc)
    {
        if (strlen(material_desc->m_Name) == 0)
            return false;
        return true;
    }

    struct MaterialResources
    {
        MaterialResources() : m_FragmentProgram(0), m_VertexProgram(0) {}

        dmGraphics::HFragmentProgram m_FragmentProgram;
        dmGraphics::HVertexProgram m_VertexProgram;
    };

    static dmResource::Result AcquireResources(dmResource::HFactory factory, dmRenderDDF::MaterialDesc* ddf, MaterialResources* resources)
    {
        dmResource::Result factory_e;
        factory_e = dmResource::Get(factory, ddf->m_VertexProgram, (void**) &resources->m_VertexProgram);
        if ( factory_e != dmResource::RESULT_OK)
        {
            return factory_e;
        }

        factory_e = dmResource::Get(factory, ddf->m_FragmentProgram, (void**) &resources->m_FragmentProgram);
        if ( factory_e != dmResource::RESULT_OK)
        {
            dmResource::Release(factory, (void*)resources->m_VertexProgram);
            resources->m_VertexProgram = 0x0;
            return factory_e;
        }

        return dmResource::RESULT_OK;
    }

    static void ResourceReloadedCallback(const dmResource::ResourceReloadedParams& params)
    {
        dmRender::HMaterial material = (dmRender::HMaterial) params.m_UserData;

        uint64_t vertex_name_hash = dmRender::GetMaterialUserData1(material);
        uint64_t fragment_name_hash = dmRender::GetMaterialUserData2(material);

        if (params.m_Resource->m_NameHash == vertex_name_hash || params.m_Resource->m_NameHash == fragment_name_hash)
        {
            dmRender::HRenderContext render_context = dmRender::GetMaterialRenderContext(material);
            dmGraphics::HContext graphics_context = dmRender::GetGraphicsContext(render_context);
            dmGraphics::HProgram program = dmRender::GetMaterialProgram(material);
            dmGraphics::HVertexProgram vert_program = dmRender::GetMaterialVertexProgram(material);
            dmGraphics::HFragmentProgram frag_program = dmRender::GetMaterialFragmentProgram(material);

            if (!dmGraphics::ReloadProgram(graphics_context, program, vert_program, frag_program))
            {
                dmLogWarning("Reloading the material failed, some shaders might not have been correctly linked.");
            }
        }
    }

    static void GetAttributeValueInfo(const dmGraphics::VertexAttribute& attribute, uint8_t** data_ptr, uint32_t* data_size)
    {
        switch(attribute.m_DataType)
        {
            case dmGraphics::VertexAttribute::TYPE_BYTE:
                assert(0 && "Not supprted yet");
                break;
            case dmGraphics::VertexAttribute::TYPE_UNSIGNED_BYTE:
                assert(0 && "Not supprted yet");
                break;
            case dmGraphics::VertexAttribute::TYPE_SHORT:
                assert(0 && "Not supprted yet");
                break;
            case dmGraphics::VertexAttribute::TYPE_UNSIGNED_SHORT:
                assert(0 && "Not supprted yet");
                break;
            case dmGraphics::VertexAttribute::TYPE_INT:
                *data_ptr  = (uint8_t*) attribute.m_Values.m_IntValues.m_V.m_Data;
                *data_size = attribute.m_Values.m_IntValues.m_V.m_Count * sizeof(int32_t);
                break;
            case dmGraphics::VertexAttribute::TYPE_UNSIGNED_INT:
                *data_ptr  = (uint8_t*) attribute.m_Values.m_UintValues.m_V.m_Data;
                *data_size = attribute.m_Values.m_UintValues.m_V.m_Count * sizeof(uint32_t);
                break;
            case dmGraphics::VertexAttribute::TYPE_FLOAT:
                *data_ptr  = (uint8_t*) attribute.m_Values.m_FloatValues.m_V.m_Data;
                *data_size = attribute.m_Values.m_FloatValues.m_V.m_Count * sizeof(uint32_t);
                break;
            default:
                assert(0 && "Unknown data type");
                break;
        }
    }

    static void SetMaterial(const char* path, dmRender::HMaterial material, dmRenderDDF::MaterialDesc* ddf)
    {
        dmhash_t tags[dmRender::MAX_MATERIAL_TAG_COUNT];
        uint32_t tag_count = ddf->m_Tags.m_Count;

        if (tag_count > dmRender::MAX_MATERIAL_TAG_COUNT) {
            dmLogError("The maximum number of tags per material is %d. Skipping the last ones for %s", dmRender::MAX_MATERIAL_TAG_COUNT, path);
            tag_count = dmRender::MAX_MATERIAL_TAG_COUNT;
        }

        for (uint32_t i = 0; i < tag_count; ++i)
        {
            tags[i] = dmHashString64(ddf->m_Tags[i]);
        }
        std::sort(tags, tags + tag_count);
        dmRender::SetMaterialTags(material, tag_count, tags);

        dmRender::SetMaterialVertexSpace(material, ddf->m_VertexSpace);
        dmRenderDDF::MaterialDesc::Constant* fragment_constant = ddf->m_FragmentConstants.m_Data;
        dmRenderDDF::MaterialDesc::Constant* vertex_constant = ddf->m_VertexConstants.m_Data;

        uint32_t fragment_count = ddf->m_FragmentConstants.m_Count;
        uint32_t vertex_count = ddf->m_VertexConstants.m_Count;

        // save pre-set fragment constants
        for (uint32_t i = 0; i < fragment_count; i++)
        {
            const char* name = fragment_constant[i].m_Name;
            dmhash_t name_hash = dmHashString64(name);
            dmRender::SetMaterialProgramConstantType(material, name_hash, fragment_constant[i].m_Type);
            dmRender::SetMaterialProgramConstant(material, name_hash,
                (dmVMath::Vector4*) fragment_constant[i].m_Value.m_Data, fragment_constant[i].m_Value.m_Count);
        }
        // do the same for vertex constants
        for (uint32_t i = 0; i < vertex_count; i++)
        {
            const char* name = vertex_constant[i].m_Name;
            dmhash_t name_hash = dmHashString64(name);
            dmRender::SetMaterialProgramConstantType(material, name_hash, vertex_constant[i].m_Type);
            dmRender::SetMaterialProgramConstant(material, name_hash,
                (dmVMath::Vector4*) vertex_constant[i].m_Value.m_Data, vertex_constant[i].m_Value.m_Count);
        }

        // Set vertex attributes
        dmGraphics::VertexAttribute* attributes = ddf->m_Attributes.m_Data;
        for (int i = 0; i < ddf->m_Attributes.m_Count; ++i)
        {
            dmGraphics::VertexAttribute& attribute = ddf->m_Attributes[i];
            uint32_t byte_size;
            uint8_t* bytes;
            GetAttributeValueInfo(attribute, &bytes, &byte_size);
            dmRender::SetMaterialProgramAttribute(material, attribute.m_NameHash, bytes, byte_size);
        }

        const char** textures = ddf->m_Textures.m_Data;
        uint32_t texture_count = ddf->m_Textures.m_Count;
        if (texture_count > 0)
        {
            for (uint32_t i = 0; i < texture_count; i++)
            {
                dmhash_t name_hash = dmHashString64(textures[i]);
                dmRender::SetMaterialSampler(material, name_hash, i,
                    dmGraphics::TEXTURE_WRAP_CLAMP_TO_EDGE,
                    dmGraphics::TEXTURE_WRAP_CLAMP_TO_EDGE,
                    dmGraphics::TEXTURE_FILTER_DEFAULT,
                    dmGraphics::TEXTURE_FILTER_DEFAULT, 1.0f);
            }
        }

        dmRenderDDF::MaterialDesc::Sampler* sampler = ddf->m_Samplers.m_Data;

        uint32_t sampler_unit = 0;
        for (uint32_t i = 0; i < ddf->m_Samplers.m_Count; i++)
        {
            dmhash_t base_name_hash             = dmHashString64(sampler[i].m_Name);
            dmGraphics::TextureWrap uwrap       = WrapFromDDF(sampler[i].m_WrapU);
            dmGraphics::TextureWrap vwrap       = WrapFromDDF(sampler[i].m_WrapV);
            dmGraphics::TextureFilter minfilter = FilterMinFromDDF(sampler[i].m_FilterMin);
            dmGraphics::TextureFilter magfilter = FilterMagFromDDF(sampler[i].m_FilterMag);
            float anisotropy                    = sampler[i].m_MaxAnisotropy;

            if (dmRender::SetMaterialSampler(material, base_name_hash, sampler_unit, uwrap, vwrap, minfilter, magfilter, anisotropy))
            {
                sampler_unit++;
            }

            for (int j = 0; j < sampler[i].m_NameIndirections.m_Count; ++j)
            {
                if (dmRender::SetMaterialSampler(material, sampler[i].m_NameIndirections[j], sampler_unit, uwrap, vwrap, minfilter, magfilter, anisotropy))
                {
                    sampler_unit++;
                }
            }
        }
    }

    dmResource::Result ResMaterialCreate(const dmResource::ResourceCreateParams& params)
    {
        dmRender::HRenderContext render_context = (dmRender::HRenderContext) params.m_Context;
        dmRenderDDF::MaterialDesc* ddf = (dmRenderDDF::MaterialDesc*)params.m_PreloadData;
        MaterialResources resources;
        dmResource::Result r = AcquireResources(params.m_Factory, ddf, &resources);
        if (r == dmResource::RESULT_OK)
        {
            dmRender::HMaterial material = dmRender::NewMaterial(render_context, resources.m_VertexProgram, resources.m_FragmentProgram);

            dmResource::SResourceDescriptor desc;
            dmResource::Result factory_e;

            factory_e = dmResource::GetDescriptor(params.m_Factory, ddf->m_VertexProgram, &desc);
            assert(factory_e == dmResource::RESULT_OK); // Should not fail at this point
            dmRender::SetMaterialUserData1(material, desc.m_NameHash);

            factory_e = dmResource::GetDescriptor(params.m_Factory, ddf->m_FragmentProgram, &desc);
            assert(factory_e == dmResource::RESULT_OK); // Should not fail at this point
            dmRender::SetMaterialUserData2(material, desc.m_NameHash);

            dmResource::RegisterResourceReloadedCallback(params.m_Factory, ResourceReloadedCallback, material);

            SetMaterial(params.m_Filename, material, ddf);
            params.m_Resource->m_Resource = (void*) material;
        }
        dmDDF::FreeMessage(ddf);
        return r;
    }

    dmResource::Result ResMaterialDestroy(const dmResource::ResourceDestroyParams& params)
    {
        dmRender::HRenderContext render_context = (dmRender::HRenderContext) params.m_Context;
        dmRender::HMaterial material = (dmRender::HMaterial) params.m_Resource->m_Resource;
        dmResource::UnregisterResourceReloadedCallback(params.m_Factory, ResourceReloadedCallback, material);

        dmResource::Release(params.m_Factory, (void*)dmRender::GetMaterialFragmentProgram(material));
        dmResource::Release(params.m_Factory, (void*)dmRender::GetMaterialVertexProgram(material));
        dmRender::DeleteMaterial(render_context, material);

        return dmResource::RESULT_OK;
    }

    dmResource::Result ResMaterialRecreate(const dmResource::ResourceRecreateParams& params)
    {
        dmRenderDDF::MaterialDesc* ddf;
        dmDDF::Result e = dmDDF::LoadMessage<dmRenderDDF::MaterialDesc>(params.m_Buffer, params.m_BufferSize, &ddf);
        if (e != dmDDF::RESULT_OK)
        {
            return dmResource::RESULT_DDF_ERROR;
        }
        if (!ValidateFormat(ddf))
        {
            dmDDF::FreeMessage(ddf);
            return dmResource::RESULT_FORMAT_ERROR;
        }

        MaterialResources resources;
        dmResource::Result r = AcquireResources(params.m_Factory, ddf, &resources);
        if (r == dmResource::RESULT_OK)
        {
            dmRender::HMaterial material = (dmRender::HMaterial) params.m_Resource->m_Resource;
            dmResource::Release(params.m_Factory, (void*)dmRender::GetMaterialFragmentProgram(material));
            dmResource::Release(params.m_Factory, (void*)dmRender::GetMaterialVertexProgram(material));
            dmRender::ClearMaterialTags(material);
            SetMaterial(params.m_Filename, material, ddf);
        }
        dmDDF::FreeMessage(ddf);
        return r;
    }

    dmResource::Result ResMaterialPreload(const dmResource::ResourcePreloadParams& params)
    {
        dmRenderDDF::MaterialDesc* ddf;
        dmDDF::Result e = dmDDF::LoadMessage<dmRenderDDF::MaterialDesc>(params.m_Buffer, params.m_BufferSize, &ddf);
        if (e != dmDDF::RESULT_OK)
        {
            return dmResource::RESULT_DDF_ERROR;
        }
        if (!ValidateFormat(ddf))
        {
            dmDDF::FreeMessage(ddf);
            return dmResource::RESULT_FORMAT_ERROR;
        }

        dmResource::PreloadHint(params.m_HintInfo, ddf->m_VertexProgram);
        dmResource::PreloadHint(params.m_HintInfo, ddf->m_FragmentProgram);
        *params.m_PreloadData = ddf;
        return dmResource::RESULT_OK;
    }
}
