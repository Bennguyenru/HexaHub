
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

#include "modelimporter.h"
#include <dmsdk/dlib/log.h>
#include <dmsdk/dlib/dstrings.h>
#include <dmsdk/dlib/math.h>
#include <dmsdk/dlib/vmath.h>
#include <float.h> // FLT_MAX
#include <stdio.h>
#include <stdlib.h> // getenv
#include <string.h>


static void SetLogLevel()
{
    const char* env_debug_level = getenv("DM_LOG_LEVEL");
    if (!env_debug_level)
        return;

    LogSeverity severity = LOG_SEVERITY_WARNING;
#define STRMATCH(LEVEL) if (strcmp(env_debug_level, #LEVEL) == 0) \
        severity = LOG_SEVERITY_ ## LEVEL;

    STRMATCH(DEBUG);
    STRMATCH(USER_DEBUG);
    STRMATCH(INFO);
    STRMATCH(WARNING);
    STRMATCH(ERROR);
    STRMATCH(FATAL);
#undef STRMATCH

    dmLogSetLevel(severity);
}

struct ModelImporterInitializer
{
    ModelImporterInitializer() {
        SetLogLevel();
    }
} g_ModelImporterInitializer;


namespace dmModelImporter
{

Options::Options()
{
}

inline dmVMath::Vector3 ToVector3(const Vec3f& v)
{
    return dmVMath::Vector3(v.x, v.y, v.z);
}

inline Vec3f FromVector3(const dmVMath::Vector3& v)
{
    return Vec3f(v.getX(), v.getY(), v.getZ());
}
// inline dmVMath::Vector4 ToVector4(const Vec4f& v)
// {
//     return dmVMath::Vector4(v.x, v.y, v.z, v.w);
// }

inline dmVMath::Quat ToQuat(const Vec4f& v)
{
    return dmVMath::Quat(v.x, v.y, v.z, v.w);
}

inline Vec4f FromQuat(const dmVMath::Quat& v)
{
    return Vec4f(v.getX(), v.getY(), v.getZ(), v.getW());
}

Transform ToTransform(const dmTransform::Transform& t)
{
    return Transform(   FromVector3(t.GetTranslation()),
                        FromQuat(t.GetRotation()),
                        FromVector3(t.GetScale()));
}

Transform ToTransform(const float* m)
{
    dmVMath::Matrix4 mat = dmVMath::Matrix4(dmVMath::Vector4(m[0], m[1], m[2], m[3]),
                                            dmVMath::Vector4(m[4], m[5], m[6], m[7]),
                                            dmVMath::Vector4(m[8], m[9], m[10], m[11]),
                                            dmVMath::Vector4(m[12], m[13], m[14], m[15]));
    dmTransform::Transform t = dmTransform::ToTransform(mat);
    return ToTransform(t);
}

Transform Mul(const Transform& a, const Transform& b)
{
    dmTransform::Transform ta(  ToVector3(a.m_Translation),
                                ToQuat(a.m_Rotation),
                                ToVector3(a.m_Scale));

    dmTransform::Transform tb(  ToVector3(b.m_Translation),
                                ToQuat(b.m_Rotation),
                                ToVector3(b.m_Scale));

    dmTransform::Transform t = dmTransform::Mul(ta, tb);
    return ToTransform(t);
}

Aabb::Aabb()
: m_Min(FLT_MAX, FLT_MAX, FLT_MAX)
, m_Max(-FLT_MAX, -FLT_MAX, -FLT_MAX)
{
}

void Aabb::Union(const Vec3f& p)
{
    m_Min.x = dmMath::Min(m_Min.x, p.x);
    m_Min.y = dmMath::Min(m_Min.y, p.y);
    m_Min.z = dmMath::Min(m_Min.z, p.z);
    m_Max.x = dmMath::Max(m_Max.x, p.x);
    m_Max.y = dmMath::Max(m_Max.y, p.y);
    m_Max.z = dmMath::Max(m_Max.z, p.z);
}

static void DestroyMesh(Mesh* mesh)
{
    free((void*)mesh->m_Name);
    mesh->m_Positions.SetCapacity(0);
    mesh->m_Normals.SetCapacity(0);
    mesh->m_Tangents.SetCapacity(0);
    mesh->m_Colors.SetCapacity(0);
    mesh->m_Weights.SetCapacity(0);
    mesh->m_Bones.SetCapacity(0);
    mesh->m_TexCoords0.SetCapacity(0);
    mesh->m_TexCoords1.SetCapacity(0);
}

static void DestroyModel(Model* model)
{
    free((void*)model->m_Name);
    uint32_t size = model->m_Meshes.Size();
    for (uint32_t i = 0; i < size; ++i)
        DestroyMesh(&model->m_Meshes[i]);
    model->m_Meshes.SetCapacity(0);
}

static void DestroyNode(Node* node)
{
    free((void*)node->m_Name);
}

static void DestroyBone(Bone* bone)
{
    bone->m_Children.SetCapacity(0);
    free((void*)bone->m_Name);
}

static void DestroySkin(Skin* skin)
{
    free((void*)skin->m_Name);
    uint32_t size = skin->m_Bones.Size();
    for (uint32_t i = 0; i < size; ++i)
        DestroyBone(skin->m_Bones[i]);
    skin->m_Bones.SetCapacity(0);
    skin->m_BoneRemap.SetCapacity(0);
}

static void DestroyNodeAnimation(NodeAnimation* node_animation)
{
    node_animation->m_TranslationKeys.SetCapacity(0);
    node_animation->m_RotationKeys.SetCapacity(0);
    node_animation->m_ScaleKeys.SetCapacity(0);
}

static void DestroyAnimation(Animation* animation)
{
    free((void*)animation->m_Name);
    uint32_t size = animation->m_NodeAnimations.Size();
    for (uint32_t i = 0; i < size; ++i)
        DestroyNodeAnimation(&animation->m_NodeAnimations[i]);
    animation->m_NodeAnimations.SetCapacity(0);
}

static void DestroyMaterial(Material* material)
{
    free((void*)material->m_Name);
}

bool Validate(Scene* scene)
{
    if (scene->m_ValidateFn)
        return scene->m_ValidateFn(scene);
    return true;
}

bool LoadFinalize(Scene* scene)
{
    if (scene->m_LoadFinalizeFn)
        return scene->m_LoadFinalizeFn(scene);
    return true;
}

void DestroyScene(Scene* scene)
{
    if (!scene)
    {
        return;
    }

    if (!scene->m_OpaqueSceneData)
    {
        printf("Already deleted!\n");
        return;
    }

    scene->m_DestroyFn(scene);
    scene->m_OpaqueSceneData = 0;

    uint32_t size;

    size = scene->m_Nodes.Size();
    for (uint32_t i = 0; i < size; ++i)
        DestroyNode(&scene->m_Nodes[i]);
    scene->m_Nodes.SetCapacity(0);

    scene->m_RootNodes.SetCapacity(0);

    size = scene->m_Models.Size();
    for (uint32_t i = 0; i < size; ++i)
        DestroyModel(&scene->m_Models[i]);
    scene->m_Models.SetCapacity(0);

    size = scene->m_Skins.Size();
    for (uint32_t i = 0; i < size; ++i)
        DestroySkin(&scene->m_Skins[i]);
    scene->m_Skins.SetCapacity(0);

    size = scene->m_Animations.Size();
    for (uint32_t i = 0; i < size; ++i)
        DestroyAnimation(&scene->m_Animations[i]);
    scene->m_Animations.SetCapacity(0);

    size = scene->m_Materials.Size();
    for (uint32_t i = 0; i < size; ++i)
        DestroyMaterial(&scene->m_Materials[i]);
    scene->m_Materials.SetCapacity(0);

    size = scene->m_DynamicMaterials.Size();
    for (uint32_t i = 0; i < size; ++i)
        DestroyMaterial(scene->m_DynamicMaterials[i]);
    scene->m_DynamicMaterials.SetSize(0);

    scene->m_Buffers.SetCapacity(0);

    delete scene;
}

Scene* LoadFromBuffer(Options* options, const char* suffix, void* data, uint32_t file_size)
{
    if (suffix == 0)
    {
        printf("ModelImporter: No suffix specified!\n");
        return 0;
    }

    if (dmStrCaseCmp(suffix, "gltf") == 0 || dmStrCaseCmp(suffix, "glb") == 0)
        return LoadGltfFromBuffer(options, data, file_size);

    printf("ModelImporter: File type not supported: %s\n", suffix);
    return 0;
}

static void* BufferResolveUri(const char* dirname, const char* uri, uint32_t* file_size)
{
    char path[512];
    dmStrlCpy(path, dirname, sizeof(path));
    dmStrlCat(path, "/", sizeof(path));
    dmStrlCat(path, uri, sizeof(path));

    return dmModelImporter::ReadFile(path, file_size);
}

Scene* LoadFromPath(Options* options, const char* path)
{
    const char* suffix = strrchr(path, '.') + 1;

    uint32_t file_size = 0;
    void* data = ReadFile(path, &file_size);
    if (!data)
    {
        printf("Failed to load '%s'\n", path);
        return 0;
    }

    Scene* scene = LoadFromBuffer(options, suffix, data, file_size);
    if (!scene)
    {
        dmLogError("Failed to create scene from path '%s'", path);
        return 0;
    }

    char dirname[512];
    dmStrlCpy(dirname, path, sizeof(dirname));
    char* c = strrchr(dirname, '/');
    if (!c)
        c = strrchr(dirname, '\\');
    if (c)
        *c = 0;

    if (dmModelImporter::NeedsResolve(scene))
    {
        for (uint32_t i = 0; i < scene->m_Buffers.Size(); ++i)
        {
            if (scene->m_Buffers[i].m_Buffer)
                continue;

            uint32_t mem_size = 0;
            void* mem = BufferResolveUri(dirname, scene->m_Buffers[i].m_Uri, &mem_size);
            dmModelImporter::ResolveBuffer(scene, scene->m_Buffers[i].m_Uri, mem, mem_size);
        }
    }

    if (!dmModelImporter::LoadFinalize(scene))
    {
        DestroyScene(scene);
        printf("Failed to load '%s'\n", path);
        return 0;
    }

    free(data);

    return scene;
}

bool NeedsResolve(Scene* scene)
{
    for (uint32_t i = 0; i < scene->m_Buffers.Size(); ++i)
    {
        if (!scene->m_Buffers[i].m_Buffer)
            return true;;
    }
    return false;
}

void EnableDebugLogging(bool enable)
{
    dmLogSetLevel( enable ? LOG_SEVERITY_DEBUG : LOG_SEVERITY_WARNING);
}

}
