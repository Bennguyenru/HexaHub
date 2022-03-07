
// Copyright 2020-2022 The Defold Foundation
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

#define CGLTF_IMPLEMENTATION
#include <cgltf/cgltf.h>

#include <assert.h>
#include <stdio.h>

namespace dmModelImporter
{

static void DestroyGltf(void* opaque_scene_data);


static uint32_t FindNodeIndex(cgltf_node* node, uint32_t nodes_count, cgltf_node* nodes)
{
    for (uint32_t i = 0; i < nodes_count; ++i)
    {
        if (&nodes[i] == node)
            return i;
    }
    assert(false && "Failed to find node in list of nodes");
    return 0xFFFFFFFF;
}

static Node* TranslateNode(cgltf_node* node, cgltf_data* gltf_data, Scene* scene)
{
    uint32_t index = FindNodeIndex(node, gltf_data->nodes_count, gltf_data->nodes);
    return &scene->m_Nodes[index];
}

static void LoadNodes(Scene* scene, cgltf_data* gltf_data)
{
    scene->m_NodesCount = gltf_data->nodes_count;
    scene->m_Nodes = new Node[scene->m_NodesCount];

    for (size_t i = 0; i < gltf_data->nodes_count; ++i)
    {
        cgltf_node* gltf_node = &gltf_data->nodes[i];

        Node* node = &scene->m_Nodes[i];
        node->m_Name = strdup(gltf_node->name);

        //printf("    Node: %20s  mesh: %s  skin: %s\n", gltf_node->name, gltf_node->mesh?gltf_node->mesh->name:"-", gltf_node->skin?gltf_node->skin->name:"-");
    }

    // find all the parents and all the children

    for (size_t i = 0; i < gltf_data->nodes_count; ++i)
    {
        cgltf_node* gltf_node = &gltf_data->nodes[i];
        Node* node = &scene->m_Nodes[i];

        node->m_Parent = gltf_node->parent ? TranslateNode(gltf_node->parent, gltf_data, scene) : 0;

        node->m_ChildrenCount = gltf_node->children_count;
        node->m_Children = new Node*[node->m_ChildrenCount];

        for (uint32_t c = 0; c < gltf_node->children_count; ++c)
            node->m_Children[c] = TranslateNode(gltf_node->children[c], gltf_data, scene);
    }

    // Find root nodes
    scene->m_RootNodesCount = 0;
    for (size_t i = 0; i < scene->m_NodesCount; ++i)
    {
        Node* node = &scene->m_Nodes[i];
        if (node->m_Parent == 0)
            scene->m_RootNodesCount++;
    }

    scene->m_RootNodes = new Node*[scene->m_RootNodesCount];
    scene->m_RootNodesCount = 0;
    for (size_t i = 0; i < scene->m_NodesCount; ++i)
    {
        Node* node = &scene->m_Nodes[i];
        if (node->m_Parent == 0)
        {
            scene->m_RootNodes[scene->m_RootNodesCount++] = &scene->m_Nodes[i];
        }
    }
}


static const char* getPrimitiveTypeStr(cgltf_primitive_type type)
{
    switch(type)
    {
    case cgltf_primitive_type_points: return "cgltf_primitive_type_points";
    case cgltf_primitive_type_lines: return "cgltf_primitive_type_lines";
    case cgltf_primitive_type_line_loop: return "cgltf_primitive_type_line_loop";
    case cgltf_primitive_type_line_strip: return "cgltf_primitive_type_line_strip";
    case cgltf_primitive_type_triangles: return "cgltf_primitive_type_triangles";
    case cgltf_primitive_type_triangle_strip: return "cgltf_primitive_type_triangle_strip";
    case cgltf_primitive_type_triangle_fan: return "cgltf_primitive_type_triangle_fan";
    default: return "unknown";
    }
}

static const char* GetAttributeTypeStr(cgltf_attribute_type type)
{
    switch(type)
    {
    case cgltf_attribute_type_invalid: return "cgltf_attribute_type_invalid";
    case cgltf_attribute_type_position: return "cgltf_attribute_type_position";
    case cgltf_attribute_type_normal: return "cgltf_attribute_type_normal";
    case cgltf_attribute_type_tangent: return "cgltf_attribute_type_tangent";
    case cgltf_attribute_type_texcoord: return "cgltf_attribute_type_texcoord";
    case cgltf_attribute_type_color: return "cgltf_attribute_type_color";
    case cgltf_attribute_type_joints: return "cgltf_attribute_type_joints";
    case cgltf_attribute_type_weights: return "cgltf_attribute_type_weights";
    default: return "unknown";
    }
}



// static void outputPrimitive(cgltf_primitive* prim)
// {
//     const char* type_str = getPrimitiveTypeStr(prim->type);
//     printf("      %s ", type_str);

//     printf("mat: %s", prim->material?prim->material->name:"-");
//     for (size_t i = 0; i < prim->mappings_count; ++i)
//     {
//         printf("'%s'", prim->mappings[i].material->name);
//     }
//     printf("\n");
// }

// static void outputMesh(cgltf_mesh* mesh)
// {
//     printf("  %s\n", mesh->name);

//     for (size_t i = 0; i < mesh->primitives_count; ++i)
//     {
//         outputPrimitive(&mesh->primitives[i]);
//     }

//     for (size_t i = 0; i < mesh->target_names_count; ++i)
//     {
//         printf("      %s\n", mesh->target_names[i]);
//     }
// }

// typedef enum cgltf_attribute_type
// {
//     cgltf_attribute_type_invalid,
//     cgltf_attribute_type_position,
//     cgltf_attribute_type_normal,
//     cgltf_attribute_type_tangent,
//     cgltf_attribute_type_texcoord,
//     cgltf_attribute_type_color,
//     cgltf_attribute_type_joints,
//     cgltf_attribute_type_weights,
// } cgltf_attribute_type;

// typedef struct cgltf_attribute
// {
//     char* name;
//     cgltf_attribute_type type;
//     cgltf_int index;
//     cgltf_accessor* data;
// } cgltf_attribute;

static float* ReadAccessorFloat(cgltf_accessor* accessor, uint32_t desired_num_components)
{
    uint32_t num_components = (uint32_t)cgltf_num_components(accessor->type);

    if (desired_num_components == 0)
        desired_num_components = num_components;

    float* out = new float[accessor->count * desired_num_components];
    float* writeptr = out;

    for (uint32_t i = 0; i < accessor->count; ++i)
    {
        bool result = cgltf_accessor_read_float(accessor, i, writeptr, num_components);

        if (!result)
        {
            printf("couldnt read floats!\n");
            delete[] out;
            return 0;;
        }

        writeptr += desired_num_components;
    }

    return out;
}

static uint32_t* ReadAccessorUint32(cgltf_accessor* accessor, uint32_t desired_num_components)
{
    uint32_t num_components = (uint32_t)cgltf_num_components(accessor->type);

    if (desired_num_components == 0)
        desired_num_components = num_components;

    uint32_t* out = new uint32_t[accessor->count * desired_num_components];
    uint32_t* writeptr = out;

    for (uint32_t i = 0; i < accessor->count; ++i)
    {
        bool result = cgltf_accessor_read_uint(accessor, i, writeptr, num_components);

        if (!result)
        {
            printf("couldnt read floats!\n");
            delete[] out;
            return 0;;
        }

        writeptr += desired_num_components;
    }

    return out;
}


static void LoadPrimitives(Model* model, cgltf_mesh* gltf_mesh)
{
    model->m_MeshesCount = gltf_mesh->primitives_count;
    model->m_Meshes = new Mesh[gltf_mesh->primitives_count];

    for (size_t i = 0; i < gltf_mesh->primitives_count; ++i)
    {
        cgltf_primitive* prim = &gltf_mesh->primitives[i];
        Mesh* mesh = &model->m_Meshes[i];
        memset(mesh, 0, sizeof(Mesh));

        mesh->m_Material = strdup(prim->material->name);
        mesh->m_VertexCount = 0;

        //printf("primitive_type: %s\n", getPrimitiveTypeStr(prim->type));

        for (uint32_t a = 0; a < prim->attributes_count; ++a)
        {
            cgltf_attribute* attribute = &prim->attributes[a];
            cgltf_accessor* accessor = attribute->data;
            //printf("  attributes: %s   index: %u   type: %s  count: %u\n", attribute->name, attribute->index, GetAttributeTypeStr(attribute->type), (uint32_t)accessor->count);

            mesh->m_VertexCount = accessor->count;

            uint32_t num_components = (uint32_t)cgltf_num_components(accessor->type);
            uint32_t desired_num_components = num_components;

            if (attribute->type == cgltf_attribute_type_tangent)
            {
                desired_num_components = 3; // for some reason it give 4 elements
            }

            float* fdata = 0;
            uint32_t* udata = 0;

            if (attribute->type == cgltf_attribute_type_joints)
            {
                udata = ReadAccessorUint32(accessor, desired_num_components);
            }
            else
            {
                fdata = ReadAccessorFloat(accessor, desired_num_components);
            }

            if (fdata || udata)
            {
                if (attribute->type == cgltf_attribute_type_position)
                    mesh->m_Positions = fdata;

                else if (attribute->type == cgltf_attribute_type_normal)
                    mesh->m_Normals = fdata;

                else if (attribute->type == cgltf_attribute_type_tangent)
                    mesh->m_Tangents = fdata;

                else if (attribute->type == cgltf_attribute_type_texcoord)
                {
                    if (attribute->index == 0)
                    {
                        mesh->m_TexCoord0 = fdata;
                        mesh->m_TexCoord0NumComponents = num_components;
                    }
                    else if (attribute->index == 1)
                    {
                        mesh->m_TexCoord1 = fdata;
                        mesh->m_TexCoord1NumComponents = num_components;
                    }
                }

                else if (attribute->type == cgltf_attribute_type_color)
                    mesh->m_Color = fdata;

                else if (attribute->type == cgltf_attribute_type_joints)
                    mesh->m_Bones = udata;

                else if (attribute->type == cgltf_attribute_type_weights)
                    mesh->m_Weights = fdata;
            }

            // int maxcount = 10;
            // // if (attribute->type == cgltf_attribute_type_joints)
            // //     maxcount = accessor->count;

            // for (uint32_t c = 0; c < maxcount; ++c)
            // {
            //     printf("    %u:  ", c);

            //     uint32_t num_components = (uint32_t)cgltf_num_components(accessor->type);
            //     for (uint32_t ac = 0; ac < num_components; ++ac)
            //     {
            //         if (fdata)
            //         {
            //             printf("%f,", fdata[c * num_components + ac]);
            //         } else {
            //             printf("%u,", udata[c * num_components + ac]);
            //         }
            //     }
            //     printf("\n");
            // }
        }
    }
}

static void LoadMeshes(Scene* scene, cgltf_data* gltf_data)
{
    scene->m_ModelsCount = gltf_data->meshes_count;
    scene->m_Models = new Model[scene->m_ModelsCount];

    for (uint32_t i = 0; i < gltf_data->meshes_count; ++i)
    {
        cgltf_mesh* gltf_mesh = &gltf_data->meshes[i]; // our "Model"
        Model* model = &scene->m_Models[i];
        model->m_Name = strdup(gltf_mesh->name);

        LoadPrimitives(model, gltf_mesh);
    }
}

static const char* GetResultStr(cgltf_result result)
{
    switch(result)
    {
    case cgltf_result_success: return "cgltf_result_success";
    case cgltf_result_data_too_short: return "cgltf_result_data_too_short";
    case cgltf_result_unknown_format: return "cgltf_result_unknown_format";
    case cgltf_result_invalid_json: return "cgltf_result_invalid_json";
    case cgltf_result_invalid_gltf: return "cgltf_result_invalid_gltf";
    case cgltf_result_invalid_options: return "cgltf_result_invalid_options";
    case cgltf_result_file_not_found: return "cgltf_result_file_not_found";
    case cgltf_result_io_error: return "cgltf_result_io_error";
    case cgltf_result_out_of_memory: return "cgltf_result_out_of_memory";
    case cgltf_result_legacy_gltf: return "cgltf_result_legacy_gltf";
    default: return "unknown";
    }
}

Scene* LoadGltf(Options* importeroptions, void* mem, uint32_t file_size)
{
    cgltf_options options;
    memset(&options, 0, sizeof(cgltf_options));

    cgltf_data* data = NULL;
    cgltf_result result = cgltf_parse(&options, (uint8_t*)mem, file_size, &data);

    if (result == cgltf_result_success)
        result = cgltf_load_buffers(&options, data, 0);

    if (result == cgltf_result_success)
        result = cgltf_validate(data);

    if (result != cgltf_result_success)
    {
        printf("Failed to load gltf file: %s (%d)\n", GetResultStr(result), result);
        return 0;
    }

    Scene* scene = new Scene;
    scene->m_OpaqueSceneData = (void*)data;
    scene->m_DestroyFn = DestroyGltf;

    LoadNodes(scene, data);
    LoadMeshes(scene, data);

    return scene;
}

static void DestroyGltf(void* opaque_scene_data)
{
    cgltf_free((cgltf_data*)opaque_scene_data);
}

}
