#include "comp_spine_model.h"

#include <string.h>
#include <float.h>
#include <algorithm>

#include <dlib/array.h>
#include <dlib/hash.h>
#include <dlib/log.h>
#include <dlib/message.h>
#include <dlib/profile.h>
#include <dlib/dstrings.h>
#include <dlib/object_pool.h>
#include <dlib/math.h>
#include <graphics/graphics.h>
#include <render/render.h>
#include <gameobject/gameobject_ddf.h>

#include "../gamesys.h"
#include "../gamesys_private.h"

#include "spine_ddf.h"
#include "sprite_ddf.h"
#include "tile_ddf.h"

using namespace Vectormath::Aos;

namespace dmGameSystem
{
    using namespace Vectormath::Aos;
    using namespace dmGameSystemDDF;

    static const dmhash_t NULL_ANIMATION = dmHashString64("");

    static const dmhash_t PROP_SKIN = dmHashString64("skin");
    static const dmhash_t PROP_ANIMATION = dmHashString64("animation");

    static void ResourceReloadedCallback(void* user_data, dmResource::SResourceDescriptor* descriptor, const char* name);
    static void DestroyComponent(SpineModelWorld* world, uint32_t index);

    dmGameObject::CreateResult CompSpineModelNewWorld(const dmGameObject::ComponentNewWorldParams& params)
    {
        SpineModelContext* context = (SpineModelContext*)params.m_Context;
        dmRender::HRenderContext render_context = context->m_RenderContext;
        SpineModelWorld* world = new SpineModelWorld();

        world->m_Components.SetCapacity(context->m_MaxSpineModelCount);
        world->m_RenderObjects.SetCapacity(context->m_MaxSpineModelCount);

        world->m_RenderSortBuffer.SetCapacity(context->m_MaxSpineModelCount);
        world->m_RenderSortBuffer.SetSize(context->m_MaxSpineModelCount);
        for (uint32_t i = 0; i < context->m_MaxSpineModelCount; ++i)
        {
            world->m_RenderSortBuffer[i] = i;
        }
        world->m_MinZ = 0;
        world->m_MaxZ = 0;

        dmGraphics::VertexElement ve[] =
        {
                {"position", 0, 3, dmGraphics::TYPE_FLOAT, false},
                {"texcoord0", 1, 2, dmGraphics::TYPE_UNSIGNED_SHORT, true},
                {"color", 2, 4, dmGraphics::TYPE_UNSIGNED_BYTE, true},
        };

        world->m_VertexDeclaration = dmGraphics::NewVertexDeclaration(dmRender::GetGraphicsContext(render_context), ve, sizeof(ve) / sizeof(dmGraphics::VertexElement));

        world->m_VertexBuffer = dmGraphics::NewVertexBuffer(dmRender::GetGraphicsContext(render_context), 0, 0x0, dmGraphics::BUFFER_USAGE_DYNAMIC_DRAW);
        // Assume 4 vertices per mesh
        world->m_VertexBufferData.SetCapacity(4 * world->m_Components.Capacity());

        *params.m_World = world;

        dmResource::RegisterResourceReloadedCallback(context->m_Factory, ResourceReloadedCallback, world);

        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompSpineModelDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params)
    {
        SpineModelWorld* world = (SpineModelWorld*)params.m_World;
        dmGraphics::DeleteVertexDeclaration(world->m_VertexDeclaration);
        dmGraphics::DeleteVertexBuffer(world->m_VertexBuffer);
        world->m_ScratchInstances.SetCapacity(0);

        dmResource::UnregisterResourceReloadedCallback(((SpineModelContext*)params.m_Context)->m_Factory, ResourceReloadedCallback, world);

        delete world;
        return dmGameObject::CREATE_RESULT_OK;
    }

    static bool GetSender(SpineModelComponent* component, dmMessage::URL* out_sender)
    {
        dmMessage::URL sender;
        sender.m_Socket = dmGameObject::GetMessageSocket(dmGameObject::GetCollection(component->m_Instance));
        if (dmMessage::IsSocketValid(sender.m_Socket))
        {
            dmGameObject::Result go_result = dmGameObject::GetComponentId(component->m_Instance, component->m_ComponentIndex, &sender.m_Fragment);
            if (go_result == dmGameObject::RESULT_OK)
            {
                sender.m_Path = dmGameObject::GetIdentifier(component->m_Instance);
                *out_sender = sender;
                return true;
            }
        }
        return false;
    }

    static dmGameSystemDDF::SpineAnimation* FindAnimation(dmGameSystemDDF::AnimationSet* anim_set, dmhash_t animation_id)
    {
        uint32_t anim_count = anim_set->m_Animations.m_Count;
        for (uint32_t i = 0; i < anim_count; ++i)
        {
            dmGameSystemDDF::SpineAnimation* anim = &anim_set->m_Animations[i];
            if (anim->m_Id == animation_id)
            {
                return anim;
            }
        }
        return 0x0;
    }

    static dmGameSystemDDF::MeshEntry* FindMeshEntry(dmGameSystemDDF::MeshSet* mesh_set, dmhash_t skin_id)
    {
        for (uint32_t i = 0; i < mesh_set->m_MeshEntries.m_Count; ++i)
        {
            dmGameSystemDDF::MeshEntry* mesh_entry = &mesh_set->m_MeshEntries[i];
            if (mesh_entry->m_Id == skin_id)
            {
                return mesh_entry;
            }
        }
        return 0x0;
    }

    static void AllocateMeshProperties(dmGameSystemDDF::MeshSet* mesh_set, dmArray<MeshProperties>& mesh_properties) {
        uint32_t max_mesh_count = 0;
        uint32_t count = mesh_set->m_MeshEntries.m_Count;
        for (uint32_t i = 0; i < count; ++i) {
            uint32_t mesh_count = mesh_set->m_MeshEntries[i].m_Meshes.m_Count;
            if (mesh_count > max_mesh_count) {
                max_mesh_count = mesh_count;
            }
        }
        mesh_properties.SetCapacity(max_mesh_count);
    }

    static SpinePlayer* GetPlayer(SpineModelComponent* component)
    {
        return &component->m_Players[component->m_CurrentPlayer];
    }

    static SpinePlayer* GetSecondaryPlayer(SpineModelComponent* component)
    {
        return &component->m_Players[(component->m_CurrentPlayer+1) % 2];
    }

    static SpinePlayer* SwitchPlayer(SpineModelComponent* component)
    {
        component->m_CurrentPlayer = (component->m_CurrentPlayer + 1) % 2;
        return &component->m_Players[component->m_CurrentPlayer];
    }

    static bool PlayAnimation(SpineModelComponent* component, dmhash_t animation_id, dmGameObject::Playback playback, float blend_duration)
    {
        dmGameSystemDDF::SpineAnimation* anim = FindAnimation(&component->m_Resource->m_Scene->m_SpineScene->m_AnimationSet, animation_id);
        if (anim != 0x0)
        {
            if (blend_duration > 0.0f)
            {
                component->m_BlendTimer = 0.0f;
                component->m_BlendDuration = blend_duration;
                component->m_Blending = 1;
            }
            else
            {
                SpinePlayer* player = GetPlayer(component);
                player->m_Playing = 0;
            }
            SpinePlayer* player = SwitchPlayer(component);
            player->m_AnimationId = animation_id;
            player->m_Animation = anim;
            player->m_Cursor = 0.0f;
            player->m_Playing = 1;
            player->m_Playback = playback;
            if (player->m_Playback == dmGameObject::PLAYBACK_ONCE_BACKWARD || player->m_Playback == dmGameObject::PLAYBACK_LOOP_BACKWARD)
                player->m_Backwards = 1;
            else
                player->m_Backwards = 0;
            return true;
        }
        return false;
    }

    static void CancelAnimation(SpineModelComponent* component)
    {
        SpinePlayer* player = GetPlayer(component);
        player->m_Playing = 0;
    }

    static void ReHash(SpineModelComponent* component)
    {
        // Hash resource-ptr, material-handle, blend mode and render constants
        HashState32 state;
        bool reverse = false;
        SpineModelResource* resource = component->m_Resource;
        dmGameSystemDDF::SpineModelDesc* ddf = resource->m_Model;
        dmHashInit32(&state, reverse);
        dmHashUpdateBuffer32(&state, &resource->m_Scene->m_TextureSet, sizeof(resource->m_Scene->m_TextureSet));
        dmHashUpdateBuffer32(&state, &resource->m_Material, sizeof(resource->m_Material));
        dmHashUpdateBuffer32(&state, &ddf->m_BlendMode, sizeof(ddf->m_BlendMode));
        dmArray<dmRender::Constant>& constants = component->m_RenderConstants;
        uint32_t size = constants.Size();
        // Padding in the SetConstant-struct forces us to copy the components by hand
        for (uint32_t i = 0; i < size; ++i)
        {
            dmRender::Constant& c = constants[i];
            dmHashUpdateBuffer32(&state, &c.m_NameHash, sizeof(uint64_t));
            dmHashUpdateBuffer32(&state, &c.m_Value, sizeof(Vector4));
            component->m_PrevRenderConstants[i] = c.m_Value;
        }
        component->m_MixedHash = dmHashFinal32(&state);
    }

    static dmGameObject::CreateResult CreatePose(SpineModelWorld* world, SpineModelComponent* component)
    {
        dmGameObject::HInstance instance = component->m_Instance;
        dmGameObject::HCollection collection = dmGameObject::GetCollection(instance);
        dmArray<SpineBone>& bind_pose = component->m_Resource->m_Scene->m_BindPose;
        dmGameSystemDDF::Skeleton* skeleton = &component->m_Resource->m_Scene->m_SpineScene->m_Skeleton;
        uint32_t bone_count = skeleton->m_Bones.m_Count;
        component->m_Pose.SetCapacity(bone_count);
        component->m_Pose.SetSize(bone_count);
        for (uint32_t i = 0; i < bone_count; ++i)
        {
            component->m_Pose[i].SetIdentity();
        }
        component->m_NodeIds.SetCapacity(bone_count);
        component->m_NodeIds.SetSize(bone_count);
        if (bone_count > world->m_ScratchInstances.Capacity()) {
            world->m_ScratchInstances.SetCapacity(bone_count);
        }
        world->m_ScratchInstances.SetSize(0);
        for (uint32_t i = 0; i < bone_count; ++i)
        {
            dmGameObject::HInstance inst = dmGameObject::New(collection, 0x0);
            if (inst == 0x0) {
                component->m_NodeIds.SetSize(i);
                return dmGameObject::CREATE_RESULT_UNKNOWN_ERROR;
            }

            dmhash_t id = dmGameObject::GenerateUniqueInstanceId(collection);
            dmGameObject::Result result = dmGameObject::SetIdentifier(collection, inst, id);
            if (dmGameObject::RESULT_OK != result) {
                dmGameObject::Delete(collection, inst);
                component->m_NodeIds.SetSize(i);
                return dmGameObject::CREATE_RESULT_UNKNOWN_ERROR;
            }

            dmGameObject::SetBone(inst, true);
            dmTransform::Transform transform = bind_pose[i].m_LocalToParent;
            if (i == 0)
            {
                transform = dmTransform::Mul(component->m_Transform, transform);
            }
            dmGameObject::SetPosition(inst, Point3(transform.GetTranslation()));
            dmGameObject::SetRotation(inst, transform.GetRotation());
            dmGameObject::SetScale(inst, transform.GetScale());
            component->m_NodeIds[i] = id;
            world->m_ScratchInstances.Push(inst);
        }
        // Set parents in reverse to account for child-prepending
        for (uint32_t i = 0; i < bone_count; ++i)
        {
            uint32_t index = bone_count - 1 - i;
            dmGameObject::HInstance inst = world->m_ScratchInstances[index];
            dmGameObject::HInstance parent = instance;
            if (index > 0)
            {
                parent = world->m_ScratchInstances[skeleton->m_Bones[index].m_Parent];
            }
            dmGameObject::SetParent(inst, parent);
        }
        return dmGameObject::CREATE_RESULT_OK;
    }

    static void DestroyPose(SpineModelComponent* component)
    {
        // Delete bone game objects
        dmGameObject::DeleteBones(component->m_Instance);
    }

    dmGameObject::CreateResult CompSpineModelCreate(const dmGameObject::ComponentCreateParams& params)
    {
        SpineModelWorld* world = (SpineModelWorld*)params.m_World;

        if (world->m_Components.Full())
        {
            dmLogError("Spine Model could not be created since the buffer is full (%d).", world->m_Components.Capacity());
            return dmGameObject::CREATE_RESULT_UNKNOWN_ERROR;
        }
        uint32_t index = world->m_Components.Alloc();
        SpineModelComponent* component = new SpineModelComponent;
        memset(component, 0, sizeof(SpineModelComponent));
        world->m_Components.Set(index, component);
        component->m_Instance = params.m_Instance;
        component->m_Transform = dmTransform::Transform(Vector3(params.m_Position), params.m_Rotation, 1.0f);
        component->m_Resource = (SpineModelResource*)params.m_Resource;
        dmMessage::ResetURL(component->m_Listener);
        component->m_ComponentIndex = params.m_ComponentIndex;
        component->m_Enabled = 1;
        component->m_Skin = dmHashString64(component->m_Resource->m_Model->m_Skin);
        dmGameSystemDDF::MeshSet* mesh_set = &component->m_Resource->m_Scene->m_SpineScene->m_MeshSet;
        AllocateMeshProperties(mesh_set, component->m_MeshProperties);
        component->m_MeshEntry = FindMeshEntry(&component->m_Resource->m_Scene->m_SpineScene->m_MeshSet, component->m_Skin);
        component->m_World = Matrix4::identity();

        dmGameObject::CreateResult result = CreatePose(world, component);
        if (result != dmGameObject::CREATE_RESULT_OK) {
            DestroyComponent(world, index);
            return result;
        }
        ReHash(component);
        dmhash_t default_animation_id = dmHashString64(component->m_Resource->m_Model->m_DefaultAnimation);
        if (default_animation_id != NULL_ANIMATION)
        {
            // Loop forward should be the most common for idle anims etc.
            PlayAnimation(component, default_animation_id, dmGameObject::PLAYBACK_LOOP_FORWARD, 0.0f);
        }

        *params.m_UserData = (uintptr_t)index;
        return dmGameObject::CREATE_RESULT_OK;
    }

    static void DestroyComponent(SpineModelWorld* world, uint32_t index)
    {
        SpineModelComponent* component = world->m_Components.Get(index);
        DestroyPose(component);
        // If we're going to use memset, then we should explicitly clear pose and instance arrays.
        component->m_Pose.SetCapacity(0);
        component->m_NodeIds.SetCapacity(0);
        component->m_MeshProperties.SetCapacity(0);
        delete component;
        world->m_Components.Free(index, true);
    }

    dmGameObject::CreateResult CompSpineModelDestroy(const dmGameObject::ComponentDestroyParams& params)
    {
        SpineModelWorld* world = (SpineModelWorld*)params.m_World;
        DestroyComponent(world, *params.m_UserData);
        return dmGameObject::CREATE_RESULT_OK;
    }

    struct SortPredSpine
    {
        SortPredSpine(SpineModelWorld* world) : m_Objects(world->m_Components.m_Objects) {}

        dmArray<SpineModelComponent*>& m_Objects;

        inline bool operator () (const uint32_t x, const uint32_t y)
        {
            SpineModelComponent* c1 = m_Objects[x];
            SpineModelComponent* c2 = m_Objects[y];
            return c1->m_SortKey.m_Key < c2->m_SortKey.m_Key;
        }

    };

    static void GenerateKeys(SpineModelWorld* world)
    {
        dmArray<SpineModelComponent*>& components = world->m_Components.m_Objects;
        uint32_t n = components.Size();

        float min_z = world->m_MinZ;
        float range = 1.0f / (world->m_MaxZ - world->m_MinZ);

        SpineModelComponent** first = world->m_Components.m_Objects.Begin();
        for (uint32_t i = 0; i < n; ++i)
        {
            SpineModelComponent* c = components[i];
            uint32_t index = &components[i] - first;
            if (c->m_Resource && c->m_Enabled && c->m_AddedToUpdate)
            {
                float z = (c->m_World.getElem(3, 2) - min_z) * range * 65535;
                z = dmMath::Clamp(z, 0.0f, 65535.0f);
                uint16_t zf = (uint16_t) z;
                c->m_SortKey.m_Z = zf;
                c->m_SortKey.m_Index = index;
                c->m_SortKey.m_MixedHash = c->m_MixedHash;
            }
            else
            {
                c->m_SortKey.m_Key = 0xffffffffffffffffULL;
            }
        }
    }

    static void Sort(SpineModelWorld* world)
    {
        DM_PROFILE(SpineModel, "Sort");
        dmArray<uint32_t>* buffer = &world->m_RenderSortBuffer;

        uint32_t n = world->m_Components.Size();
        world->m_RenderSortBuffer.SetSize(n);
        for (uint32_t i = 0; i < n; ++i)
        {
            world->m_RenderSortBuffer[i] = i;
        }

        SortPredSpine pred(world);
        std::sort(buffer->Begin(), buffer->Begin() + n, pred);
    }

#define TO_BYTE(val) (uint8_t)(val * 255.0f)
#define TO_SHORT(val) (uint16_t)((val) * 65535.0f)

    static void UpdateMeshDrawOrder(SpineModelWorld* world, const SpineModelComponent* component, uint32_t mesh_count) {
        // Spine's approach to update draw order is to:
        // * Initialize with default draw order (integer sequence)
        // * Add entries with changed draw order
        // * Fill untouched slots with the unchanged entries
        // E.g.:
        // Init: [0, 1, 2]
        // Changed: 1 => 0, results in [1, 1, 2]
        // Unchanged: 0 => 0, 2 => 2, results in [1, 0, 2] (indices 1 and 2 were untouched and filled)
        world->m_DrawOrderToMesh.SetSize(mesh_count);
        // Intialize
        for (uint32_t i = 0; i < mesh_count; ++i) {
            world->m_DrawOrderToMesh[i] = i;
        }
        // Update changed
        for (uint32_t i = 0; i < mesh_count; ++i) {
            uint32_t order = component->m_MeshProperties[i].m_Order;
            if (order != i) {
                world->m_DrawOrderToMesh[order] = i;
            }
        }
        // Fill with unchanged
        uint32_t draw_order = 0;
        for (uint32_t i = 0; i < mesh_count; ++i) {
            uint32_t order = component->m_MeshProperties[i].m_Order;
            if (order == i) {
                // Find free slot
                while (world->m_DrawOrderToMesh[draw_order] != draw_order) {
                    ++draw_order;
                }
                world->m_DrawOrderToMesh[draw_order] = i;
                ++draw_order;
            }
        }
    }

    static void CreateVertexData(SpineModelWorld* world, dmArray<SpineModelVertex>& vertex_buffer, TextureSetResource* texture_set, uint32_t start_index, uint32_t end_index)
    {
        DM_PROFILE(SpineModel, "CreateVertexData");

        const dmArray<SpineModelComponent*>& components = world->m_Components.m_Objects;
        const dmArray<uint32_t>& sort_buffer = world->m_RenderSortBuffer;

        for (uint32_t i = start_index; i < end_index; ++i)
        {
            const SpineModelComponent* component = components[sort_buffer[i]];

            dmGameSystemDDF::MeshEntry* mesh_entry = component->m_MeshEntry;
            if (mesh_entry == 0x0)
                continue;

            const Matrix4& w = component->m_World;

            dmArray<SpineBone>& bind_pose = component->m_Resource->m_Scene->m_BindPose;

            uint32_t mesh_count = mesh_entry->m_Meshes.m_Count;
            UpdateMeshDrawOrder(world, component, mesh_count);
            for (uint32_t draw_index = 0; draw_index < mesh_count; ++draw_index) {
                uint32_t mesh_index = world->m_DrawOrderToMesh[draw_index];
                const MeshProperties* properties = &component->m_MeshProperties[mesh_index];
                dmGameSystemDDF::Mesh* mesh = &mesh_entry->m_Meshes[mesh_index];
                if (!properties->m_Visible) {
                    continue;
                }
                uint32_t index_count = mesh->m_Indices.m_Count;
                uint32_t buffer_offset = vertex_buffer.Size();
                vertex_buffer.SetSize(buffer_offset + index_count);
                for (uint32_t ii = 0; ii < index_count; ++ii)
                {
                    SpineModelVertex& v = vertex_buffer[buffer_offset + ii];
                    uint32_t vi = mesh->m_Indices[ii];
                    uint32_t e = vi*3;
                    Point3 in_p(mesh->m_Positions[e+0], mesh->m_Positions[e+1], mesh->m_Positions[e+2]);
                    Point3 out_p(0.0f, 0.0f, 0.0f);
                    uint32_t bi_offset = vi * 4;
                    uint32_t* bone_indices = &mesh->m_BoneIndices[bi_offset];
                    float* bone_weights = &mesh->m_Weights[bi_offset];
                    for (uint32_t bi = 0; bi < 4; ++bi)
                    {
                        if (bone_weights[bi] > 0.0f)
                        {
                            uint32_t bone_index = bone_indices[bi];
                            out_p += Vector3(dmTransform::Apply(component->m_Pose[bone_index], dmTransform::Apply(bind_pose[bone_index].m_ModelToLocal, in_p))) * bone_weights[bi];
                        }
                    }
                    Vector4 posed_vertex = w * out_p;
                    v.x = posed_vertex[0];
                    v.y = posed_vertex[1];
                    v.z = posed_vertex[2];
                    e = vi*2;
                    v.u = TO_SHORT(mesh->m_Texcoord0[e+0]);
                    v.v = TO_SHORT(mesh->m_Texcoord0[e+1]);
                    v.r = TO_BYTE(properties->m_Color[0]);
                    v.g = TO_BYTE(properties->m_Color[1]);
                    v.b = TO_BYTE(properties->m_Color[2]);
                    v.a = TO_BYTE(properties->m_Color[3]);
                }
            }
        }
    }

#undef TO_BYTE
#undef TO_SHORT

    static uint32_t RenderBatch(SpineModelWorld* world, dmRender::HRenderContext render_context, dmArray<SpineModelVertex>& vertex_buffer, uint32_t start_index)
    {
        DM_PROFILE(SpineModel, "RenderBatch");
        uint32_t n = world->m_Components.Size();

        const dmArray<SpineModelComponent*>& components = world->m_Components.m_Objects;
        const dmArray<uint32_t>& sort_buffer = world->m_RenderSortBuffer;

        const SpineModelComponent* first = components[sort_buffer[start_index]];
        assert(first->m_Enabled);
        TextureSetResource* texture_set = first->m_Resource->m_Scene->m_TextureSet;
        uint32_t hash = first->m_MixedHash;

        uint32_t vertex_count = 0;
        uint32_t end_index = n;
        for (uint32_t i = start_index; i < n; ++i)
        {
            const SpineModelComponent* c = components[sort_buffer[i]];
            if (!c->m_Enabled || c->m_MixedHash != hash || !c->m_AddedToUpdate)
            {
                end_index = i;
                break;
            }
            if (c->m_MeshEntry != 0x0) {
                uint32_t mesh_count = c->m_MeshEntry->m_Meshes.m_Count;
                for (uint32_t mesh_index = 0; mesh_index < mesh_count; ++mesh_index) {
                    if (c->m_MeshProperties[mesh_index].m_Visible) {
                        vertex_count += c->m_MeshEntry->m_Meshes[mesh_index].m_Indices.m_Count;
                    }
                }
            }
        }

        if (vertex_buffer.Remaining() < vertex_count)
            vertex_buffer.OffsetCapacity(vertex_count - vertex_buffer.Remaining());

        // Render object
        dmRender::RenderObject ro;
        ro.m_VertexDeclaration = world->m_VertexDeclaration;
        ro.m_VertexBuffer = world->m_VertexBuffer;
        ro.m_PrimitiveType = dmGraphics::PRIMITIVE_TRIANGLES;
        ro.m_VertexStart = vertex_buffer.Size();
        ro.m_VertexCount = vertex_count;
        ro.m_Material = first->m_Resource->m_Material;
        ro.m_Textures[0] = texture_set->m_Texture;
        // The first transform is used for the batch. Mean-value might be better?
        // NOTE: The position is already transformed, see CreateVertexData, but set for sorting.
        // See also sprite.vp
        ro.m_WorldTransform = first->m_World;
        ro.m_CalculateDepthKey = 1;

        const dmArray<dmRender::Constant>& constants = first->m_RenderConstants;
        uint32_t size = constants.Size();
        for (uint32_t i = 0; i < size; ++i)
        {
            const dmRender::Constant& c = constants[i];
            dmRender::EnableRenderObjectConstant(&ro, c.m_NameHash, c.m_Value);
        }

        dmGameSystemDDF::SpineModelDesc::BlendMode blend_mode = first->m_Resource->m_Model->m_BlendMode;
        switch (blend_mode)
        {
            case dmGameSystemDDF::SpineModelDesc::BLEND_MODE_ALPHA:
                ro.m_SourceBlendFactor = dmGraphics::BLEND_FACTOR_ONE;
                ro.m_DestinationBlendFactor = dmGraphics::BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            break;

            case dmGameSystemDDF::SpineModelDesc::BLEND_MODE_ADD:
                ro.m_SourceBlendFactor = dmGraphics::BLEND_FACTOR_ONE;
                ro.m_DestinationBlendFactor = dmGraphics::BLEND_FACTOR_ONE;
            break;

            case dmGameSystemDDF::SpineModelDesc::BLEND_MODE_MULT:
                ro.m_SourceBlendFactor = dmGraphics::BLEND_FACTOR_DST_COLOR;
                ro.m_DestinationBlendFactor = dmGraphics::BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            break;

            default:
                dmLogError("Unknown blend mode: %d\n", blend_mode);
                assert(0);
            break;
        }
        ro.m_SetBlendFactors = 1;

        world->m_RenderObjects.Push(ro);
        dmRender::AddToRender(render_context, &world->m_RenderObjects[world->m_RenderObjects.Size() - 1]);

        CreateVertexData(world, vertex_buffer, texture_set, start_index, end_index);
        return end_index;
    }

    void UpdateTransforms(SpineModelWorld* world)
    {
        DM_PROFILE(SpineModel, "UpdateTransforms");

        dmArray<SpineModelComponent*>& components = world->m_Components.m_Objects;
        uint32_t n = components.Size();
        float min_z = FLT_MAX;
        float max_z = -FLT_MAX;
        for (uint32_t i = 0; i < n; ++i)
        {
            SpineModelComponent* c = components[i];

            // NOTE: texture_set = c->m_Resource might be NULL so it's essential to "continue" here
            if (!c->m_Enabled || !c->m_AddedToUpdate)
                continue;

            if (c->m_MeshEntry != 0x0)
            {
                dmTransform::Transform world = dmGameObject::GetWorldTransform(c->m_Instance);
                if (dmGameObject::ScaleAlongZ(c->m_Instance))
                {
                    world = dmTransform::Mul(world, c->m_Transform);
                }
                else
                {
                    world = dmTransform::MulNoScaleZ(world, c->m_Transform);
                }
                Matrix4 w = dmTransform::ToMatrix4(world);
                Vector4 position = w.getCol3();
                float z = position.getZ();
                min_z = dmMath::Min(min_z, z);
                max_z = dmMath::Max(max_z, z);
                w.setCol3(position);
                c->m_World = w;
            }
        }

        if (n == 0)
        {
            // NOTE: Avoid large numbers and risk of de-normalized etc.
            // if n == 0 the actual values of min/max-z doens't matter
            min_z = 0;
            max_z = 1;
        }

        world->m_MinZ = min_z;
        world->m_MaxZ = max_z;
    }

    static Vector3 SampleVec3(uint32_t sample, float frac, float* data)
    {
        uint32_t i0 = sample*3;
        uint32_t i1 = i0+3;
        return lerp(frac, Vector3(data[i0+0], data[i0+1], data[i0+2]), Vector3(data[i1+0], data[i1+1], data[i1+2]));
    }

    static Vector4 SampleVec4(uint32_t sample, float frac, float* data)
    {
        uint32_t i0 = sample*4;
        uint32_t i1 = i0+4;
        return lerp(frac, Vector4(data[i0+0], data[i0+1], data[i0+2], data[i0+3]), Vector4(data[i1+0], data[i1+1], data[i1+2], data[i1+3]));
    }

    static Quat SampleQuat(uint32_t sample, float frac, float* data)
    {
        uint32_t i = sample*4;
        return lerp(frac, Quat(data[i+0], data[i+1], data[i+2], data[i+3]), Quat(data[i+0+4], data[i+1+4], data[i+2+4], data[i+3+4]));
    }

    static float CursorToTime(float cursor, float duration, bool backwards, bool once_pingpong)
    {
        float t = cursor;
        if (backwards)
            t = duration - t;
        if (once_pingpong && t > duration * 0.5f)
        {
            t = duration - t;
        }
        return t;
    }

    static void PostEvent(dmMessage::URL* sender, dmMessage::URL* receiver, dmhash_t event_id, dmhash_t animation_id, float blend_weight, dmGameSystemDDF::EventKey* key)
    {
        dmGameSystemDDF::SpineEvent event;
        event.m_EventId = event_id;
        event.m_AnimationId = animation_id;
        event.m_BlendWeight = blend_weight;
        event.m_T = key->m_T;
        event.m_Integer = key->m_Integer;
        event.m_Float = key->m_Float;
        event.m_String = key->m_String;

        dmhash_t message_id = dmGameSystemDDF::SpineEvent::m_DDFDescriptor->m_NameHash;

        uintptr_t descriptor = (uintptr_t)dmGameSystemDDF::SpineEvent::m_DDFDescriptor;
        uint32_t data_size = sizeof(dmGameSystemDDF::SpineEvent);
        dmMessage::Result result = dmMessage::Post(sender, receiver, message_id, 0, descriptor, &event, data_size);
        if (result != dmMessage::RESULT_OK)
        {
            dmLogError("Could not send spine_event to listener.");
        }
    }

    static void PostEventsInterval(dmMessage::URL* sender, dmMessage::URL* receiver, dmGameSystemDDF::SpineAnimation* animation, float start_cursor, float end_cursor, float duration, bool backwards, float blend_weight)
    {
        const uint32_t track_count = animation->m_EventTracks.m_Count;
        for (uint32_t ti = 0; ti < track_count; ++ti)
        {
            dmGameSystemDDF::EventTrack* track = &animation->m_EventTracks[ti];
            const uint32_t key_count = track->m_Keys.m_Count;
            for (uint32_t ki = 0; ki < key_count; ++ki)
            {
                dmGameSystemDDF::EventKey* key = &track->m_Keys[ki];
                float cursor = key->m_T;
                if (backwards)
                    cursor = duration - cursor;
                if (start_cursor <= cursor && cursor < end_cursor)
                {
                    PostEvent(sender, receiver, track->m_EventId, animation->m_Id, blend_weight, key);
                }
            }
        }
    }

    static void PostEvents(SpinePlayer* player, dmMessage::URL* sender, dmMessage::URL* listener, dmGameSystemDDF::SpineAnimation* animation, float dt, float prev_cursor, float duration, bool completed, float blend_weight)
    {
        dmMessage::URL receiver = *listener;
        if (!dmMessage::IsSocketValid(receiver.m_Socket))
        {
            receiver = *sender;
            receiver.m_Fragment = 0; // broadcast to sibling components
        }
        float cursor = player->m_Cursor;
        // Since the intervals are defined as t0 <= t < t1, make sure we include the end of the animation, i.e. when t1 == duration
        if (completed)
            cursor += dt;
        // If the start cursor is greater than the end cursor, we have looped and handle that as two distinct intervals: [0,end_cursor) and [start_cursor,duration)
        // Note that for looping ping pong, one event can be triggered twice during the same frame by appearing in both intervals
        if (prev_cursor > cursor)
        {
            bool prev_backwards = player->m_Backwards;
            // Handle the flipping nature of ping pong
            if (player->m_Playback == dmGameObject::PLAYBACK_LOOP_PINGPONG)
            {
                prev_backwards = !player->m_Backwards;
            }
            PostEventsInterval(sender, &receiver, animation, prev_cursor, duration, duration, prev_backwards, blend_weight);
            PostEventsInterval(sender, &receiver, animation, 0.0f, cursor, duration, player->m_Backwards, blend_weight);
        }
        else
        {
            // Special handling when we reach the way back of once ping pong playback
            float half_duration = duration * 0.5f;
            if (player->m_Playback == dmGameObject::PLAYBACK_ONCE_PINGPONG && cursor > half_duration)
            {
                // If the previous cursor was still in the forward direction, treat it as two distinct intervals: [start_cursor,half_duration) and [half_duration, end_cursor)
                if (prev_cursor < half_duration)
                {
                    PostEventsInterval(sender, &receiver, animation, prev_cursor, half_duration, duration, false, blend_weight);
                    PostEventsInterval(sender, &receiver, animation, half_duration, cursor, duration, true, blend_weight);
                }
                else
                {
                    PostEventsInterval(sender, &receiver, animation, prev_cursor, cursor, duration, true, blend_weight);
                }
            }
            else
            {
                PostEventsInterval(sender, &receiver, animation, prev_cursor, cursor, duration, player->m_Backwards, blend_weight);
            }
        }
    }

    static float GetCursorDuration(SpinePlayer* player, dmGameSystemDDF::SpineAnimation* animation)
    {
        float duration = animation->m_Duration;
        if (player->m_Playback == dmGameObject::PLAYBACK_ONCE_PINGPONG)
        {
            duration *= 2.0f;
        }
        return duration;
    }

    static void UpdatePlayer(SpineModelComponent* component, SpinePlayer* player, float dt, dmMessage::URL* listener, float blend_weight)
    {
        dmGameSystemDDF::SpineAnimation* animation = player->m_Animation;
        if (animation == 0x0 || !player->m_Playing)
            return;

        // Advance cursor
        float prev_cursor = player->m_Cursor;
        if (player->m_Playback != dmGameObject::PLAYBACK_NONE)
        {
            player->m_Cursor += dt;
        }
        float duration = GetCursorDuration(player, animation);
        if (duration == 0.0f) {
            player->m_Cursor = 0;
        }

        // Adjust cursor
        bool completed = false;
        switch (player->m_Playback)
        {
        case dmGameObject::PLAYBACK_ONCE_FORWARD:
        case dmGameObject::PLAYBACK_ONCE_BACKWARD:
        case dmGameObject::PLAYBACK_ONCE_PINGPONG:
            if (player->m_Cursor >= duration)
            {
                player->m_Cursor = duration;
                completed = true;
            }
            break;
        case dmGameObject::PLAYBACK_LOOP_FORWARD:
        case dmGameObject::PLAYBACK_LOOP_BACKWARD:
            while (player->m_Cursor >= duration && duration > 0.0f)
            {
                player->m_Cursor -= duration;
            }
            break;
        case dmGameObject::PLAYBACK_LOOP_PINGPONG:
            while (player->m_Cursor >= duration && duration > 0.0f)
            {
                player->m_Cursor -= duration;
                player->m_Backwards = ~player->m_Backwards;
            }
            break;
        default:
            break;
        }

        dmMessage::URL sender;
        if (prev_cursor != player->m_Cursor && GetSender(component, &sender))
        {
            dmMessage::URL receiver = *listener;
            receiver.m_Function = 0;
            PostEvents(player, &sender, &receiver, animation, dt, prev_cursor, duration, completed, blend_weight);
        }

        if (completed)
        {
            player->m_Playing = 0;
            // Only report completeness for the primary player
            if (player == GetPlayer(component) && dmMessage::IsSocketValid(listener->m_Socket))
            {
                dmMessage::URL sender;
                if (GetSender(component, &sender))
                {
                    dmhash_t message_id = dmGameSystemDDF::SpineAnimationDone::m_DDFDescriptor->m_NameHash;
                    dmGameSystemDDF::SpineAnimationDone message;
                    message.m_AnimationId = player->m_AnimationId;
                    message.m_Playback = player->m_Playback;

                    dmMessage::URL receiver = *listener;
                    uintptr_t descriptor = (uintptr_t)dmGameSystemDDF::SpineAnimationDone::m_DDFDescriptor;
                    uint32_t data_size = sizeof(dmGameSystemDDF::SpineAnimationDone);
                    dmMessage::Result result = dmMessage::Post(&sender, &receiver, message_id, 0, descriptor, &message, data_size);
                    dmMessage::ResetURL(*listener);
                    if (result != dmMessage::RESULT_OK)
                    {
                        dmLogError("Could not send animation_done to listener.");
                    }
                }
                else
                {
                    dmLogError("Could not send animation_done to listener because of incomplete component.");
                }
            }
        }
    }

    static void ApplyAnimation(SpinePlayer* player, dmArray<dmTransform::Transform>& pose, dmArray<MeshProperties>& properties, float blend_weight, dmhash_t skin_id, bool draw_order)
    {
        dmGameSystemDDF::SpineAnimation* animation = player->m_Animation;
        if (animation == 0x0)
            return;
        float duration = GetCursorDuration(player, animation);
        float t = CursorToTime(player->m_Cursor, duration, player->m_Backwards, player->m_Playback == dmGameObject::PLAYBACK_ONCE_PINGPONG);

        float fraction = t * animation->m_SampleRate;
        uint32_t sample = (uint32_t)fraction;
        uint32_t rounded_sample = (uint32_t)(fraction + 0.5f);
        fraction -= sample;
        // Sample animation tracks
        uint32_t track_count = animation->m_Tracks.m_Count;
        for (uint32_t ti = 0; ti < track_count; ++ti)
        {
            dmGameSystemDDF::AnimationTrack* track = &animation->m_Tracks[ti];
            uint32_t bone_index = track->m_BoneIndex;
            dmTransform::Transform& transform = pose[bone_index];
            if (track->m_Positions.m_Count > 0)
            {
                transform.SetTranslation(lerp(blend_weight, transform.GetTranslation(), SampleVec3(sample, fraction, track->m_Positions.m_Data)));
            }
            if (track->m_Rotations.m_Count > 0)
            {
                transform.SetRotation(lerp(blend_weight, transform.GetRotation(), SampleQuat(sample, fraction, track->m_Rotations.m_Data)));
            }
            if (track->m_Scale.m_Count > 0)
            {
                transform.SetScale(lerp(blend_weight, transform.GetScale(), SampleVec3(sample, fraction, track->m_Scale.m_Data)));
            }
        }
        track_count = animation->m_MeshTracks.m_Count;
        for (uint32_t ti = 0; ti < track_count; ++ti) {
            dmGameSystemDDF::MeshAnimationTrack* track = &animation->m_MeshTracks[ti];
            if (skin_id == track->m_SkinId) {
                MeshProperties& props = properties[track->m_MeshIndex];
                if (track->m_Colors.m_Count > 0) {
                    Vector4 color(props.m_Color[0], props.m_Color[1], props.m_Color[2], props.m_Color[3]);
                    color = lerp(blend_weight, color, SampleVec4(sample, fraction, track->m_Colors.m_Data));
                    props.m_Color[0] = color[0];
                    props.m_Color[1] = color[1];
                    props.m_Color[2] = color[2];
                    props.m_Color[3] = color[3];
                }
                if (track->m_Visible.m_Count > 0) {
                    if (blend_weight >= 0.5f) {
                        props.m_Visible = track->m_Visible[rounded_sample];
                    }
                }
                if (track->m_OrderOffset.m_Count > 0 && draw_order) {
                    props.m_Order += track->m_OrderOffset[rounded_sample];
                }
            }
        }
    }

    static void UpdateBlend(SpineModelComponent* component, float dt)
    {
        if (component->m_Blending)
        {
            component->m_BlendTimer += dt;
            if (component->m_BlendTimer >= component->m_BlendDuration)
            {
                component->m_Blending = 0;
                SpinePlayer* secondary = GetSecondaryPlayer(component);
                secondary->m_Playing = 0;
            }
        }
    }

    static void Animate(SpineModelWorld* world, float dt)
    {
        DM_PROFILE(SpineModel, "Animate");

        dmArray<SpineModelComponent*>& components = world->m_Components.m_Objects;
        uint32_t n = components.Size();
        for (uint32_t i = 0; i < n; ++i)
        {
            SpineModelComponent* component = components[i];
            if (!component->m_Enabled || component->m_Pose.Empty() || !component->m_AddedToUpdate)
                continue;

            dmGameSystemDDF::Skeleton* skeleton = &component->m_Resource->m_Scene->m_SpineScene->m_Skeleton;
            dmArray<SpineBone>& bind_pose = component->m_Resource->m_Scene->m_BindPose;
            dmArray<dmTransform::Transform>& pose = component->m_Pose;
            // Reset pose
            uint32_t bone_count = pose.Size();
            for (uint32_t bi = 0; bi < bone_count; ++bi)
            {
                pose[bi].SetIdentity();
            }
            dmArray<MeshProperties>& properties = component->m_MeshProperties;

            UpdateBlend(component, dt);

            SpinePlayer* player = GetPlayer(component);
            if (component->m_Blending)
            {
                float fade_rate = component->m_BlendTimer / component->m_BlendDuration;
                // How much to blend the pose, 1 first time to overwrite the bind pose, either fade_rate or 1 - fade_rate second depending on which one is the current player
                float alpha = 1.0f;
                for (uint32_t pi = 0; pi < 2; ++pi)
                {
                    SpinePlayer* p = &component->m_Players[pi];
                    // How much relative blending between the two players
                    float blend_weight = fade_rate;
                    if (player != p) {
                        blend_weight = 1.0f - fade_rate;
                    }
                    UpdatePlayer(component, p, dt, &component->m_Listener, blend_weight);
                    bool draw_order = true;
                    if (player == p) {
                        draw_order = fade_rate >= 0.5f;
                    } else {
                        draw_order = fade_rate < 0.5f;
                    }
                    ApplyAnimation(p, pose, properties, alpha, component->m_Skin, draw_order);
                    if (player == p)
                    {
                        alpha = 1.0f - fade_rate;
                    }
                    else
                    {
                        alpha = fade_rate;
                    }
                }
            }
            else
            {
                UpdatePlayer(component, player, dt, &component->m_Listener, 1.0f);
                ApplyAnimation(player, pose, properties, 1.0f, component->m_Skin, true);
            }

            for (uint32_t bi = 0; bi < bone_count; ++bi)
            {
                dmTransform::Transform& t = pose[bi];
                // Normalize quaternions while we blend
                if (component->m_Blending)
                {
                    Quat rotation = t.GetRotation();
                    if (dot(rotation, rotation) > 0.001f)
                        rotation = normalize(rotation);
                    t.SetRotation(rotation);
                }
                const dmTransform::Transform& bind_t = bind_pose[bi].m_LocalToParent;
                t.SetTranslation(bind_t.GetTranslation() + t.GetTranslation());
                t.SetRotation(bind_t.GetRotation() * t.GetRotation());
                t.SetScale(mulPerElem(bind_t.GetScale(), t.GetScale()));
            }

            // Include component transform in the GO instance reflecting the root bone
            dmTransform::Transform root_t = pose[0];
            pose[0] = dmTransform::Mul(component->m_Transform, root_t);
            dmGameObject::SetBoneTransforms(component->m_Instance, pose.Begin(), pose.Size());
            pose[0] = root_t;
            for (uint32_t bi = 0; bi < bone_count; ++bi)
            {
                dmTransform::Transform& transform = pose[bi];
                // Convert every transform into model space
                if (bi > 0) {
                    dmGameSystemDDF::Bone* bone = &skeleton->m_Bones[bi];
                    if (bone->m_InheritScale)
                    {
                        transform = dmTransform::Mul(pose[bone->m_Parent], transform);
                    }
                    else
                    {
                        Vector3 scale = transform.GetScale();
                        transform = dmTransform::Mul(pose[bone->m_Parent], transform);
                        transform.SetScale(scale);
                    }
                }
            }
        }
    }

    dmGameObject::CreateResult CompSpineModelAddToUpdate(const dmGameObject::ComponentAddToUpdateParams& params) {
        SpineModelWorld* world = (SpineModelWorld*)params.m_World;
        uint32_t index = (uint32_t)*params.m_UserData;
        SpineModelComponent* component = world->m_Components.Get(index);
        component->m_AddedToUpdate = true;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::UpdateResult CompSpineModelUpdate(const dmGameObject::ComponentsUpdateParams& params)
    {
        /*
         * All spine models are sorted, using the m_RenderSortBuffer, with respect to the:
         *
         *     - hash value of m_Resource, i.e. equal iff the sprite is rendering with identical atlas
         *     - z-value
         *     - component index
         *  or
         *     - 0xffffffff (or corresponding 64-bit value) if not enabled
         * such that all non-enabled spine models ends up last in the array
         * and spine models with equal atlas and depth consecutively
         *
         * The z-sorting is considered a hack as we assume a camera pointing along the z-axis. We currently
         * have no access, by design as render-data currently should be invariant to camera parameters,
         * to the transformation matrices when generating render-data. The render-system and go-system should probably
         * be changed such that unique render-objects are created when necessary and on-demand instead of up-front as
         * currently. Another option could be a call-back when the actual rendering occur.
         *
         * The sorted array of indices are grouped into batches, using z and resource-hash as predicates, and every
         * batch is rendered using a single draw-call. Note that the world transform
         * is set to first sprite transform for correct batch sorting. The actual vertex transformation is performed in code
         * and standard world-transformation is removed from vertex-program.
         *
         * NOTES:
         * * When/if transparency the batching predicates must be updated in order to
         *   support per sprite correct sorting.
         */

        SpineModelContext* context = (SpineModelContext*)params.m_Context;
        dmRender::HRenderContext render_context = context->m_RenderContext;
        SpineModelWorld* world = (SpineModelWorld*)params.m_World;

        dmGraphics::SetVertexBufferData(world->m_VertexBuffer, 6 * sizeof(SpineModelVertex) * world->m_Components.Size(), 0x0, dmGraphics::BUFFER_USAGE_DYNAMIC_DRAW);
        dmArray<SpineModelVertex>& vertex_buffer = world->m_VertexBufferData;
        vertex_buffer.SetSize(0);

        dmArray<SpineModelComponent*>& components = world->m_Components.m_Objects;
        uint32_t sprite_count = components.Size();
        for (uint32_t i = 0; i < sprite_count; ++i)
        {
            SpineModelComponent& component = *components[i];
            if (!component.m_Enabled || !component.m_AddedToUpdate)
                continue;
            uint32_t const_count = component.m_RenderConstants.Size();
            for (uint32_t const_i = 0; const_i < const_count; ++const_i)
            {
                float diff_sq = lengthSqr(component.m_RenderConstants[const_i].m_Value - component.m_PrevRenderConstants[const_i]);
                if (diff_sq > 0)
                {
                    ReHash(&component);
                    break;
                }
            }
            if (component.m_MeshEntry != 0x0) {
                uint32_t mesh_count = component.m_MeshEntry->m_Meshes.m_Count;
                component.m_MeshProperties.SetSize(mesh_count);
                for (uint32_t mesh_index = 0; mesh_index < mesh_count; ++mesh_index) {
                    dmGameSystemDDF::Mesh* mesh = &component.m_MeshEntry->m_Meshes[mesh_index];
                    float* color = mesh->m_Color.m_Data;
                    MeshProperties* properties = &component.m_MeshProperties[mesh_index];
                    properties->m_Color[0] = color[0];
                    properties->m_Color[1] = color[1];
                    properties->m_Color[2] = color[2];
                    properties->m_Color[3] = color[3];
                    properties->m_Order = mesh->m_DrawOrder;
                    properties->m_Visible = mesh->m_Visible;
                }
                if (world->m_DrawOrderToMesh.Capacity() < mesh_count) {
                    world->m_DrawOrderToMesh.SetCapacity(mesh_count);
                }
            } else {
                component.m_MeshProperties.SetSize(0);
            }
        }
        UpdateTransforms(world);
        GenerateKeys(world);
        Sort(world);

        world->m_RenderObjects.SetSize(0);

        const dmArray<uint32_t>& sort_buffer = world->m_RenderSortBuffer;

        Animate(world, params.m_UpdateContext->m_DT);

        uint32_t n = components.Size();
        if (n > 0) {
            uint32_t start_index = 0;
            SpineModelComponent* component = components[sort_buffer[start_index]];
            while (start_index < n && component->m_Enabled && component->m_AddedToUpdate)
            {
                start_index = RenderBatch(world, render_context, vertex_buffer, start_index);
                if (start_index >= n) {
                    break;
                }
                component = components[sort_buffer[start_index]];
            }

            void* vertex_buffer_data = 0x0;
            if (!vertex_buffer.Empty())
                vertex_buffer_data = (void*)&(vertex_buffer[0]);
            dmGraphics::SetVertexBufferData(world->m_VertexBuffer, vertex_buffer.Size() * sizeof(SpineModelVertex), vertex_buffer_data, dmGraphics::BUFFER_USAGE_DYNAMIC_DRAW);
        }
        DM_COUNTER("SpineVertexBuffer", vertex_buffer.Size() * sizeof(SpineModelVertex));

        return dmGameObject::UPDATE_RESULT_OK;
    }

    static bool CompSpineModelGetConstantCallback(void* user_data, dmhash_t name_hash, dmRender::Constant** out_constant)
    {
        SpineModelComponent* component = (SpineModelComponent*)user_data;
        dmArray<dmRender::Constant>& constants = component->m_RenderConstants;
        uint32_t count = constants.Size();
        for (uint32_t i = 0; i < count; ++i)
        {
            dmRender::Constant& c = constants[i];
            if (c.m_NameHash == name_hash)
            {
                *out_constant = &c;
                return true;
            }
        }
        return false;
    }

    static void CompSpineModelSetConstantCallback(void* user_data, dmhash_t name_hash, uint32_t* element_index, const dmGameObject::PropertyVar& var)
    {
        SpineModelComponent* component = (SpineModelComponent*)user_data;
        dmArray<dmRender::Constant>& constants = component->m_RenderConstants;
        uint32_t count = constants.Size();
        Vector4* v = 0x0;
        for (uint32_t i = 0; i < count; ++i)
        {
            dmRender::Constant& c = constants[i];
            if (c.m_NameHash == name_hash)
            {
                v = &c.m_Value;
                break;
            }
        }
        if (v == 0x0)
        {
            if (constants.Full())
            {
                uint32_t capacity = constants.Capacity() + 4;
                constants.SetCapacity(capacity);
                component->m_PrevRenderConstants.SetCapacity(capacity);
            }
            dmRender::Constant c;
            dmRender::GetMaterialProgramConstant(component->m_Resource->m_Material, name_hash, c);
            constants.Push(c);
            component->m_PrevRenderConstants.Push(c.m_Value);
            v = &(constants[constants.Size()-1].m_Value);
        }
        if (element_index == 0x0)
            *v = Vector4(var.m_V4[0], var.m_V4[1], var.m_V4[2], var.m_V4[3]);
        else
            v->setElem(*element_index, (float)var.m_Number);
        ReHash(component);
    }

    dmGameObject::UpdateResult CompSpineModelOnMessage(const dmGameObject::ComponentOnMessageParams& params)
    {
        SpineModelWorld* world = (SpineModelWorld*)params.m_World;
        SpineModelComponent* component = world->m_Components.Get(*params.m_UserData);
        if (params.m_Message->m_Id == dmGameObjectDDF::Enable::m_DDFDescriptor->m_NameHash)
        {
            component->m_Enabled = 1;
        }
        else if (params.m_Message->m_Id == dmGameObjectDDF::Disable::m_DDFDescriptor->m_NameHash)
        {
            component->m_Enabled = 0;
        }
        else if (params.m_Message->m_Descriptor != 0x0)
        {
            if (params.m_Message->m_Id == dmGameSystemDDF::SpinePlayAnimation::m_DDFDescriptor->m_NameHash)
            {
                dmGameSystemDDF::SpinePlayAnimation* ddf = (dmGameSystemDDF::SpinePlayAnimation*)params.m_Message->m_Data;
                if (PlayAnimation(component, ddf->m_AnimationId, (dmGameObject::Playback)ddf->m_Playback, ddf->m_BlendDuration))
                {
                    component->m_Listener = params.m_Message->m_Sender;
                }
            }
            else if (params.m_Message->m_Id == dmGameSystemDDF::SpineCancelAnimation::m_DDFDescriptor->m_NameHash)
            {
                CancelAnimation(component);
            }
        }

        return dmGameObject::UPDATE_RESULT_OK;
    }

    static void OnResourceReloaded(SpineModelWorld* world, SpineModelComponent* component)
    {
        dmGameSystemDDF::SpineScene* scene = component->m_Resource->m_Scene->m_SpineScene;
        component->m_Skin = dmHashString64(component->m_Resource->m_Model->m_Skin);
        AllocateMeshProperties(&scene->m_MeshSet, component->m_MeshProperties);
        component->m_MeshEntry = FindMeshEntry(&scene->m_MeshSet, component->m_Skin);
        dmhash_t default_anim_id = dmHashString64(component->m_Resource->m_Model->m_DefaultAnimation);
        for (uint32_t i = 0; i < 2; ++i)
        {
            SpinePlayer* player = &component->m_Players[i];
            if (player->m_Playing)
            {
                player->m_Animation = FindAnimation(&scene->m_AnimationSet, player->m_AnimationId);
                if (player->m_Animation == 0x0)
                {
                    player->m_AnimationId = default_anim_id;
                    player->m_Animation = FindAnimation(&scene->m_AnimationSet, player->m_AnimationId);
                }
            }
        }
        DestroyPose(component);
        CreatePose(world, component);
    }

    void CompSpineModelOnReload(const dmGameObject::ComponentOnReloadParams& params)
    {
        SpineModelWorld* world = (SpineModelWorld*)params.m_World;
        SpineModelComponent* component = world->m_Components.Get(*params.m_UserData);
        component->m_Resource = (SpineModelResource*)params.m_Resource;
        OnResourceReloaded(world, component);
    }

    dmGameObject::PropertyResult CompSpineModelGetProperty(const dmGameObject::ComponentGetPropertyParams& params, dmGameObject::PropertyDesc& out_value)
    {
        SpineModelWorld* world = (SpineModelWorld*)params.m_World;
        SpineModelComponent* component = world->m_Components.Get(*params.m_UserData);
        if (params.m_PropertyId == PROP_SKIN)
        {
            out_value.m_Variant = dmGameObject::PropertyVar(component->m_Skin);
            return dmGameObject::PROPERTY_RESULT_OK;
        }
        else if (params.m_PropertyId == PROP_ANIMATION)
        {
            SpinePlayer* player = GetPlayer(component);
            out_value.m_Variant = dmGameObject::PropertyVar(player->m_AnimationId);
            return dmGameObject::PROPERTY_RESULT_OK;
        }
        return GetMaterialConstant(component->m_Resource->m_Material, params.m_PropertyId, out_value, CompSpineModelGetConstantCallback, component);
    }

    dmGameObject::PropertyResult CompSpineModelSetProperty(const dmGameObject::ComponentSetPropertyParams& params)
    {
        SpineModelWorld* world = (SpineModelWorld*)params.m_World;
        SpineModelComponent* component = world->m_Components.Get(*params.m_UserData);
        if (params.m_PropertyId == PROP_SKIN)
        {
            if (params.m_Value.m_Type != dmGameObject::PROPERTY_TYPE_HASH)
                return dmGameObject::PROPERTY_RESULT_TYPE_MISMATCH;
            dmGameSystemDDF::MeshEntry* mesh_entry = 0x0;
            dmGameSystemDDF::MeshSet* mesh_set = &component->m_Resource->m_Scene->m_SpineScene->m_MeshSet;
            dmhash_t skin = params.m_Value.m_Hash;
            for (uint32_t i = 0; i < mesh_set->m_MeshEntries.m_Count; ++i)
            {
                if (skin == mesh_set->m_MeshEntries[i].m_Id)
                {
                    mesh_entry = &mesh_set->m_MeshEntries[i];
                    break;
                }
            }
            if (mesh_entry == 0x0)
            {
                dmLogError("Could not find skin '%s' in the mesh set.", (const char*)dmHashReverse64(skin, 0x0));
                return dmGameObject::PROPERTY_RESULT_UNSUPPORTED_VALUE;
            }
            component->m_MeshEntry = mesh_entry;
            component->m_Skin = params.m_Value.m_Hash;
            return dmGameObject::PROPERTY_RESULT_OK;
        }
        return SetMaterialConstant(component->m_Resource->m_Material, params.m_PropertyId, params.m_Value, CompSpineModelSetConstantCallback, component);
    }

    static void ResourceReloadedCallback(void* user_data, dmResource::SResourceDescriptor* descriptor, const char* name)
    {
        SpineModelWorld* world = (SpineModelWorld*)user_data;
        dmArray<SpineModelComponent*>& components = world->m_Components.m_Objects;
        uint32_t n = components.Size();
        for (uint32_t i = 0; i < n; ++i)
        {
            SpineModelComponent* component = components[i];
            if (component->m_Resource != 0x0 && component->m_Resource->m_Scene == descriptor->m_Resource)
                OnResourceReloaded(world, component);
        }
    }

}
