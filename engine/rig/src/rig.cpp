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

#include "rig.h"
#include "rig_private.h"

#include <dlib/log.h>
#include <dlib/math.h>
#include <dlib/vmath.h>
#include <dlib/profile.h>
#include <dmsdk/dlib/object_pool.h>
#include <graphics/graphics.h>

#include <stdio.h>

static bool CheckSetting(const char* var, bool default_value)
{
    const char* value = getenv(var);
    if (!value)
        return default_value;
    bool disabled = strcmp(value, "0") == 0;
    dmLogInfo("Using %s = %s", var, value);
    return !disabled;
}

static bool IS_COLLADA = CheckSetting("COLLADA", false);
static bool IS_GLTF = !IS_COLLADA;

static bool USE_BIND_POSE = CheckSetting("USE_BIND_POSE", false);
static bool IS_PLAYING = CheckSetting("PLAYING", true);

static bool g_Debug = false;

namespace dmRig
{
    using namespace dmVMath;

    static void printVector4(const Vector4& v)
    {
        printf("%f, %f, %f, %f\n", v.getX(), v.getY(), v.getZ(), v.getW());
    }

    // static void printTransform(uint32_t index, const dmRigDDF::Bone* bone, const Matrix4& pose, const Matrix4& transform)
    // {
    //     printf("Bone: %u: %s\n", index, bone->m_Name);
    //     printf("    pose:\n");
    //     printf("        "); printVector4(pose.getRow(0));
    //     printf("        "); printVector4(pose.getRow(1));
    //     printf("        "); printVector4(pose.getRow(2));
    //     printf("        "); printVector4(pose.getRow(3));
    //     printf("    out:\n");
    //     printf("        "); printVector4(transform.getRow(0));
    //     printf("        "); printVector4(transform.getRow(1));
    //     printf("        "); printVector4(transform.getRow(2));
    //     printf("        "); printVector4(transform.getRow(3));
    // }

    static void printMatrix(const Matrix4& transform)
    {
        printf("    "); printVector4(transform.getRow(0));
        printf("    "); printVector4(transform.getRow(1));
        printf("    "); printVector4(transform.getRow(2));
        printf("    "); printVector4(transform.getRow(3));
    }
    static void printTransformAsMatrix(const dmTransform::Transform& transform)
    {
        printMatrix(dmTransform::ToMatrix4(transform));
    }
    static void printTransform(const dmTransform::Transform& transform)
    {
        printf("    pos: %f, %f, %f\n",transform.GetTranslation().getX(),transform.GetTranslation().getY(),transform.GetTranslation().getZ());
        printf("    rot: %f, %f, %f, %f\n",transform.GetRotation().getX(),transform.GetRotation().getY(),transform.GetRotation().getZ(),transform.GetRotation().getW());
        printf("    scl: %f, %f, %f\n",transform.GetScale().getX(),transform.GetScale().getY(),transform.GetScale().getZ());
        printf("\n");
    }

    static const dmhash_t NULL_ANIMATION = dmHashString64("");
    static const float CURSOR_EPSILON = 0.0001f;
    //static const int SIGNAL_DELTA_UNCHANGED = 0x10cced; // Used to indicate if a draw order was unchanged for a certain slot
    //static const uint32_t INVALID_ATTACHMENT_INDEX = 0xffffffffu;

    //static const float white[] = {1.0f, 1.0f, 1.0, 1.0f};

    static void DoAnimate(HRigContext context, RigInstance* instance, float dt);
    static bool DoPostUpdate(RigInstance* instance);

    struct RigContext
    {
        dmObjectPool<HRigInstance>      m_Instances;
        // Temporary scratch buffers used for store pose as transform and matrices
        // (avoids modifying the real pose transform data during rendering).
        dmArray<dmTransform::Transform> m_ScratchPoseTransformBuffer;
        dmArray<dmVMath::Matrix4>       m_ScratchInfluenceMatrixBuffer;
        dmArray<dmVMath::Matrix4>       m_ScratchPoseMatrixBuffer;
        // Temporary scratch buffers used when transforming the vertex buffer,
        // used to creating primitives from indices.
        dmArray<dmVMath::Vector3>       m_ScratchPositionBuffer;
        dmArray<dmVMath::Vector3>       m_ScratchNormalBuffer;
    };


    Result NewContext(const NewContextParams& params, HRigContext* out)
    {
        RigContext* context = new RigContext;
        if (!context) {
            return dmRig::RESULT_ERROR;
        }

        context->m_Instances.SetCapacity(params.m_MaxRigInstanceCount);
        context->m_ScratchPoseTransformBuffer.SetCapacity(0);
        context->m_ScratchPoseMatrixBuffer.SetCapacity(0);
        *out = context;
        return dmRig::RESULT_OK;
    }

    void DeleteContext(HRigContext context)
    {
        delete context;
    }

    static const dmRigDDF::RigAnimation* FindAnimation(const dmRigDDF::AnimationSet* anim_set, dmhash_t animation_id)
    {
        if(anim_set == 0x0)
            return 0x0;
        uint32_t anim_count = anim_set->m_Animations.m_Count;
        for (uint32_t i = 0; i < anim_count; ++i)
        {
            const dmRigDDF::RigAnimation* anim = &anim_set->m_Animations[i];
            if (anim->m_Id == animation_id)
            {
                return anim;
            }
        }
        return 0x0;
    }

    static RigPlayer* GetPlayer(HRigInstance instance)
    {
        return &instance->m_Players[instance->m_CurrentPlayer];
    }

    static RigPlayer* GetSecondaryPlayer(HRigInstance instance)
    {
        return &instance->m_Players[(instance->m_CurrentPlayer+1) % 2];
    }

    static RigPlayer* SwitchPlayer(HRigInstance instance)
    {
        instance->m_CurrentPlayer = (instance->m_CurrentPlayer + 1) % 2;
        return &instance->m_Players[instance->m_CurrentPlayer];
    }

    Result PlayAnimation(HRigInstance instance, dmhash_t animation_id, dmRig::RigPlayback playback, float blend_duration, float offset, float playback_rate)
    {
        const dmRigDDF::RigAnimation* anim = FindAnimation(instance->m_AnimationSet, animation_id);
        if (anim == 0x0)
        {
            return dmRig::RESULT_ANIM_NOT_FOUND;
        }

        if (blend_duration > 0.0f)
        {
            instance->m_BlendTimer = 0.0f;
            instance->m_BlendDuration = blend_duration;
            instance->m_Blending = 1;
        }
        else
        {
            RigPlayer* player = GetPlayer(instance);
            player->m_Playing = 0;
        }

        RigPlayer* player = SwitchPlayer(instance);
        player->m_Initial = 1;// DEPRECATED SPINE STUFF
        player->m_BlendFinished = blend_duration > 0.0f ? 0 : 1;// DEPRECATED SPINE STUFF
        player->m_AnimationId = animation_id;
        player->m_Animation = anim;
        player->m_Playing = 1;
        player->m_Playback = playback;

        if (player->m_Playback == dmRig::PLAYBACK_ONCE_BACKWARD || player->m_Playback == dmRig::PLAYBACK_LOOP_BACKWARD) {
            player->m_Backwards = 1;
            offset = 1.0f - dmMath::Clamp(offset, 0.0f, 1.0f);
        } else {
            player->m_Backwards = 0;
        }
offset = 0.0f;
        SetCursor(instance, offset, true);
        SetPlaybackRate(instance, playback_rate);
player->m_Playing = IS_PLAYING;
        return dmRig::RESULT_OK;
    }

    Result CancelAnimation(HRigInstance instance)
    {
        RigPlayer* player = GetPlayer(instance);
        player->m_Playing = 0;

        return dmRig::RESULT_OK;
    }

    dmhash_t GetAnimation(HRigInstance instance)
    {
        RigPlayer* player = GetPlayer(instance);
        return player->m_AnimationId;
    }

    dmhash_t GetModel(HRigInstance instance)
    {
        return instance->m_ModelId;
    }

    Result SetModel(HRigInstance instance, dmhash_t model_id)
    {
        for (uint32_t i = 0; i < instance->m_MeshSet->m_Models.m_Count; ++i)
        {
            const dmRigDDF::Model* model = &instance->m_MeshSet->m_Models[i];
            if (model->m_Id == model_id)
            {
                instance->m_Model = model;
                instance->m_ModelId = model_id;
                instance->m_DoRender = 1;
                return dmRig::RESULT_OK;
            }
        }
        instance->m_Model = 0;
        instance->m_ModelId = 0;
        instance->m_DoRender = 0;
        return dmRig::RESULT_ERROR;
    }

    static void UpdateBlend(RigInstance* instance, float dt)
    {
        if (instance->m_Blending)
        {
            instance->m_BlendTimer += dt;
            if (instance->m_BlendTimer >= instance->m_BlendDuration)
            {
                instance->m_Blending = 0;
                RigPlayer* secondary = GetSecondaryPlayer(instance);
                secondary->m_Playing = 0;
            }
        }
    }

    static float GetCursorDuration(RigPlayer* player, const dmRigDDF::RigAnimation* animation)
    {
        if (!animation)
        {
            return 0.0f;
        }

        float duration = animation->m_Duration;
        if (player->m_Playback == dmRig::PLAYBACK_ONCE_PINGPONG)
        {
            duration *= 2.0f;
        }
        return duration;
    }

    static void PostEventsInterval(HRigInstance instance, const dmRigDDF::RigAnimation* animation, float start_cursor, float end_cursor, float duration, bool backwards, float blend_weight)
    {
        const uint32_t track_count = animation->m_EventTracks.m_Count;
        for (uint32_t ti = 0; ti < track_count; ++ti)
        {
            const dmRigDDF::EventTrack* track = &animation->m_EventTracks[ti];
            const uint32_t key_count = track->m_Keys.m_Count;
            for (uint32_t ki = 0; ki < key_count; ++ki)
            {
                const dmRigDDF::EventKey* key = &track->m_Keys[ki];
                float cursor = key->m_T;
                if (backwards)
                    cursor = duration - cursor;
                if (start_cursor <= cursor && cursor < end_cursor)
                {
                    RigKeyframeEventData event_data;
                    event_data.m_EventId = track->m_EventId;
                    event_data.m_AnimationId = animation->m_Id;
                    event_data.m_BlendWeight = blend_weight;
                    event_data.m_T = key->m_T;
                    event_data.m_Integer = key->m_Integer;
                    event_data.m_Float = key->m_Float;
                    event_data.m_String = key->m_String;

                    instance->m_EventCallback(RIG_EVENT_TYPE_KEYFRAME, (void*)&event_data, instance->m_EventCBUserData1, instance->m_EventCBUserData2);
                }
            }
        }
    }

    static void PostEvents(HRigInstance instance, RigPlayer* player, const dmRigDDF::RigAnimation* animation, float dt, float prev_cursor, float duration, bool completed, float blend_weight)
    {
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
            if (player->m_Playback == dmRig::PLAYBACK_LOOP_PINGPONG)
            {
                prev_backwards = !player->m_Backwards;
            }
            PostEventsInterval(instance, animation, prev_cursor, duration, duration, prev_backwards, blend_weight);
            PostEventsInterval(instance, animation, 0.0f, cursor, duration, player->m_Backwards, blend_weight);
        }
        else
        {
            // Special handling when we reach the way back of once ping pong playback
            float half_duration = duration * 0.5f;
            if (player->m_Playback == dmRig::PLAYBACK_ONCE_PINGPONG && cursor > half_duration)
            {
                // If the previous cursor was still in the forward direction, treat it as two distinct intervals: [start_cursor,half_duration) and [half_duration, end_cursor)
                if (prev_cursor < half_duration)
                {
                    PostEventsInterval(instance, animation, prev_cursor, half_duration, duration, false, blend_weight);
                    PostEventsInterval(instance, animation, half_duration, cursor, duration, true, blend_weight);
                }
                else
                {
                    PostEventsInterval(instance, animation, prev_cursor, cursor, duration, true, blend_weight);
                }
            }
            else
            {
                PostEventsInterval(instance, animation, prev_cursor, cursor, duration, player->m_Backwards, blend_weight);
            }
        }
    }

    static void UpdatePlayer(RigInstance* instance, RigPlayer* player, float dt, float blend_weight)
    {
        const dmRigDDF::RigAnimation* animation = player->m_Animation;
        if (animation == 0x0 || !player->m_Playing)
        {
            return;
        }

        // Advance cursor
        float prev_cursor = player->m_Cursor;
        if (player->m_Playback != dmRig::PLAYBACK_NONE)
        {
            player->m_Cursor += dt * player->m_PlaybackRate;
        }
        float duration = GetCursorDuration(player, animation);
        if (duration == 0.0f)
        {
            player->m_Cursor = 0.0f;
        }

        // Adjust cursor
        bool completed = false;
        switch (player->m_Playback)
        {
        case dmRig::PLAYBACK_ONCE_FORWARD:
        case dmRig::PLAYBACK_ONCE_BACKWARD:
        case dmRig::PLAYBACK_ONCE_PINGPONG:
            if (player->m_Cursor >= duration)
            {
                player->m_Cursor = duration;
                completed = true;
            }
            break;
        case dmRig::PLAYBACK_LOOP_FORWARD:
        case dmRig::PLAYBACK_LOOP_BACKWARD:
            while (player->m_Cursor >= duration && duration > 0.0f)
            {
                player->m_Cursor -= duration;
            }
            break;
        case dmRig::PLAYBACK_LOOP_PINGPONG:
            while (player->m_Cursor >= duration && duration > 0.0f)
            {
                player->m_Cursor -= duration;
                player->m_Backwards = ~player->m_Backwards;
            }
            break;
        default:
            break;
        }

        if (prev_cursor != player->m_Cursor && instance->m_EventCallback)
        {
            PostEvents(instance, player, animation, dt, prev_cursor, duration, completed, blend_weight);
        }

        if (completed)
        {
            player->m_Playing = 0;
            // Only report completeness for the primary player
            if (player == GetPlayer(instance) && instance->m_EventCallback)
            {
                RigCompletedEventData event_data;
                event_data.m_AnimationId = player->m_AnimationId;
                event_data.m_Playback = player->m_Playback;

                instance->m_EventCallback(RIG_EVENT_TYPE_COMPLETED, (void*)&event_data, instance->m_EventCBUserData1, instance->m_EventCBUserData2);
            }
        }

    }

    static Vector3 SampleVec3(uint32_t sample, float frac, float* data)
    {
        uint32_t i0 = sample*3;
        uint32_t i1 = i0+3;
        return lerp(frac, Vector3(data[i0+0], data[i0+1], data[i0+2]), Vector3(data[i1+0], data[i1+1], data[i1+2]));
    }

    // static Vector4 SampleVec4(uint32_t sample, float frac, float* data)
    // {
    //     uint32_t i0 = sample*4;
    //     uint32_t i1 = i0+4;
    //     return lerp(frac, Vector4(data[i0+0], data[i0+1], data[i0+2], data[i0+3]), Vector4(data[i1+0], data[i1+1], data[i1+2], data[i1+3]));
    // }

    static Quat SampleQuat(uint32_t sample, float frac, float* data)
    {
        uint32_t i = sample*4;
        return slerp(frac, Quat(data[i+0], data[i+1], data[i+2], data[i+3]), Quat(data[i+0+4], data[i+1+4], data[i+2+4], data[i+3+4]));
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

    static inline dmTransform::Transform GetPoseTransform(const dmArray<RigBone>& bind_pose, const dmArray<dmTransform::Transform>& pose, dmTransform::Transform transform, const uint32_t index) {
        if(bind_pose[index].m_ParentIndex == INVALID_BONE_INDEX)
            return transform;
        transform = dmTransform::Mul(pose[bind_pose[index].m_ParentIndex], transform);
        return GetPoseTransform(bind_pose, pose, transform, bind_pose[index].m_ParentIndex);
    }

    static inline float ToEulerZ(const dmTransform::Transform& t)
    {
        Quat q(t.GetRotation());
        return dmVMath::QuatToEuler(q.getZ(), q.getY(), q.getX(), q.getW()).getZ() * (M_PI/180.0f);
    }

    static void ApplyOneBoneIKConstraint(const dmRigDDF::IK* ik, const dmArray<RigBone>& bind_pose, dmArray<dmTransform::Transform>& pose, const Vector3 target_wp, const Vector3 parent_wp, const float mix)
    {
        if (mix == 0.0f)
            return;
        const dmTransform::Transform& parent_bt = bind_pose[ik->m_Parent].m_LocalToParent;
        dmTransform::Transform& parent_t = pose[ik->m_Parent];
        float parentRotation = ToEulerZ(parent_bt);
        // Based on code by Ryan Juckett with permission: Copyright (c) 2008-2009 Ryan Juckett, http://www.ryanjuckett.com/
        float rotationIK = atan2(target_wp.getY() - parent_wp.getY(), target_wp.getX() - parent_wp.getX());
        parentRotation = parentRotation + (rotationIK - parentRotation) * mix;
        parent_t.SetRotation( dmVMath::QuatFromAngle(2, parentRotation) );
    }

    // Based on http://www.ryanjuckett.com/programming/analytic-two-bone-ik-in-2d/
    static void ApplyTwoBoneIKConstraint(const dmRigDDF::IK* ik, const dmArray<RigBone>& bind_pose, dmArray<dmTransform::Transform>& pose, const Vector3 target_wp, const Vector3 parent_wp, const bool bend_positive, const float mix)
    {
        if (mix == 0.0f)
            return;
        const dmTransform::Transform& parent_bt = bind_pose[ik->m_Parent].m_LocalToParent;
        const dmTransform::Transform& child_bt = bind_pose[ik->m_Child].m_LocalToParent;
        dmTransform::Transform& parent_t = pose[ik->m_Parent];
        dmTransform::Transform& child_t = pose[ik->m_Child];
        float childRotation = ToEulerZ(child_bt), parentRotation = ToEulerZ(parent_bt);

        // recalc target position to local (relative parent)
        const Vector3 target(target_wp.getX() - parent_wp.getX(), target_wp.getY() - parent_wp.getY(), 0.0f);
        const Vector3 child_p = child_bt.GetTranslation();
        const float childX = child_p.getX(), childY = child_p.getY();
        const float offset = atan2(childY, childX);
        const float len1 = (float)sqrt(childX * childX + childY * childY);
        const float len2 = bind_pose[ik->m_Child].m_Length;

        // Based on code by Ryan Juckett with permission: Copyright (c) 2008-2009 Ryan Juckett, http://www.ryanjuckett.com/
        const float cosDenom = 2.0f * len1 * len2;
        if (cosDenom < 0.0001f) {
            childRotation = childRotation + ((float)atan2(target.getY(), target.getX()) - parentRotation - childRotation) * mix;
            child_t.SetRotation( dmVMath::QuatFromAngle(2, childRotation) );
            return;
        }
        float cosValue = (target.getX() * target.getX() + target.getY() * target.getY() - len1 * len1 - len2 * len2) / cosDenom;
        cosValue = dmMath::Max(-1.0f, dmMath::Min(1.0f, cosValue));
        const float childAngle = (float)acos(cosValue) * (bend_positive ? 1.0f : -1.0f);
        const float adjacent = len1 + len2 * cosValue;
        const float opposite = len2 * sin(childAngle);
        const float parentAngle = (float)atan2(target.getY() * adjacent - target.getX() * opposite, target.getX() * adjacent + target.getY() * opposite);
        parentRotation = ((parentAngle - offset) - parentRotation) * mix;
        childRotation = ((childAngle + offset) - childRotation) * mix;
        parent_t.SetRotation( dmVMath::QuatFromAngle(2, parentRotation) );
        child_t.SetRotation( dmVMath::QuatFromAngle(2, childRotation) );
    }

    static void ApplyAnimation(RigPlayer* player, dmArray<dmTransform::Transform>& pose, const dmArray<uint32_t>& track_idx_to_pose, dmArray<IKAnimation>& ik_animation, float blend_weight)
    {
        const dmRigDDF::RigAnimation* animation = player->m_Animation;
        if (animation == 0x0)
            return;
        float duration = GetCursorDuration(player, animation);
        float t = CursorToTime(player->m_Cursor, duration, player->m_Backwards, player->m_Playback == dmRig::PLAYBACK_ONCE_PINGPONG);

        float fraction = t * animation->m_SampleRate;
        uint32_t sample = (uint32_t)fraction;
        fraction -= sample;
        // Sample animation tracks
        uint32_t track_count = animation->m_Tracks.m_Count;
        for (uint32_t ti = 0; ti < track_count; ++ti)
        {
            const dmRigDDF::AnimationTrack* track = &animation->m_Tracks[ti];
            uint32_t bone_index = track->m_BoneIndex;
            if (bone_index >= track_idx_to_pose.Size()) {
                continue;
            }
            uint32_t pose_index = track_idx_to_pose[bone_index];
            dmTransform::Transform& transform = pose[pose_index];
            if (track->m_Positions.m_Count > 0)
            {
                transform.SetTranslation(lerp(blend_weight, transform.GetTranslation(), SampleVec3(sample, fraction, track->m_Positions.m_Data)));
            }
            if (track->m_Rotations.m_Count > 0)
            {
                transform.SetRotation(slerp(blend_weight, transform.GetRotation(), SampleQuat(sample, fraction, track->m_Rotations.m_Data)));
            }
            if (track->m_Scale.m_Count > 0)
            {
                transform.SetScale(lerp(blend_weight, transform.GetScale(), SampleVec3(sample, fraction, track->m_Scale.m_Data)));
            }
        }
    }

    static void Animate(HRigContext context, float dt)
    {
        DM_PROFILE("RigAnimate");

        const dmArray<RigInstance*>& instances = context->m_Instances.m_Objects;
        uint32_t n = instances.Size();
        for (uint32_t i = 0; i < n; ++i)
        {
            RigInstance* instance = instances[i];
            DoAnimate(context, instance, dt);
        }
    }

    static bool IsBoneAnimated(RigPlayer* player, uint32_t bone_index, bool* translation, bool* rotation, bool* scale)
    {
        const dmRigDDF::RigAnimation* animation = player->m_Animation;
        if (animation == 0x0)
            return false;

        *translation = false;
        *rotation = false;
        *scale = false;

        uint32_t track_count = animation->m_Tracks.m_Count;
        for (uint32_t i = 0; i < track_count; ++i)
        {
            const dmRigDDF::AnimationTrack* track = &animation->m_Tracks[i];
            uint32_t bone_index = track->m_BoneIndex;
            if (track->m_BoneIndex != bone_index)
                continue;

            *translation = *translation || track->m_Positions.m_Count > 0;
            *rotation = *rotation || track->m_Rotations.m_Count > 0;
            *scale = *scale || track->m_Scale.m_Count > 0;
        }

        return *translation || *rotation || *scale;
    }

    static void DoAnimate(HRigContext context, RigInstance* instance, float dt)
    {
            // NOTE we previously checked for (!instance->m_Enabled || !instance->m_AddedToUpdate) here also
            if (instance->m_Pose.Empty() || !instance->m_Enabled)
                return;

            const dmRigDDF::Skeleton* skeleton = instance->m_Skeleton;
            const dmArray<RigBone>& bind_pose = *instance->m_BindPose;
            const dmArray<uint32_t>& track_idx_to_pose = *instance->m_TrackIdxToPose;
            dmArray<dmTransform::Transform>& pose = instance->m_Pose;
            // Reset pose
            uint32_t bone_count = pose.Size();
            for (uint32_t bi = 0; bi < bone_count; ++bi)
            {
                pose[bi].SetIdentity();
            }
            // Reset IK animation
            dmArray<IKAnimation>& ik_animation = instance->m_IKAnimation;
            uint32_t ik_animation_count = ik_animation.Size();
            for (uint32_t ii = 0; ii < ik_animation_count; ++ii)
            {
                const dmRigDDF::IK* ik = &skeleton->m_Iks[ii];
                ik_animation[ii].m_Mix = ik->m_Mix;
                ik_animation[ii].m_Positive = ik->m_Positive;
            }

            UpdateBlend(instance, dt);

            RigPlayer* player = GetPlayer(instance);

            if (player->m_Initial) {
                player->m_Initial = 0;
                // DEPRECATED
            }

            if (instance->m_Blending)
            {
                float fade_rate = instance->m_BlendTimer / instance->m_BlendDuration;
                // How much to blend the pose, 1 first time to overwrite the bind pose, either fade_rate or 1 - fade_rate second depending on which one is the current player
                float alpha = 1.0f;
                for (uint32_t pi = 0; pi < 2; ++pi)
                {
                    RigPlayer* p = &instance->m_Players[pi];
                    // How much relative blending between the two players
                    float blend_weight = fade_rate;
                    if (player != p) {
                        blend_weight = 1.0f - fade_rate;
                    }

                    // DEPRECATED SPINE STUFF
                    if (p->m_BlendFinished == 0 && blend_weight > 0.5) {
                        p->m_BlendFinished = 1;
                    }

                    UpdatePlayer(instance, p, dt, blend_weight);
                    ApplyAnimation(p, pose, track_idx_to_pose, ik_animation, alpha);
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
                UpdatePlayer(instance, player, dt, 1.0f);
                ApplyAnimation(player, pose, track_idx_to_pose, ik_animation, 1.0f);
            }

static int first = 0;
bool debug = false;
if (first)
{
    first = 0;
    debug = true;
}
g_Debug = debug;

            for (uint32_t bi = 0; bi < bone_count; ++bi)
            {
                dmTransform::Transform& t = pose[bi];
                // Normalize quaternions while we blend
                if (instance->m_Blending)
                {
                    Quat rotation = t.GetRotation();
                    if (dot(rotation, rotation) > 0.001f)
                        rotation = normalize(rotation);
                    t.SetRotation(rotation);
                }

                if (bi > 0)
                {
                    assert(skeleton->m_Bones[bi].m_Parent < bi);
                }

                if (debug)
                {
                    printf("Bone index: %u %s   parent: %u\n", bi, skeleton->m_Bones[bi].m_Name, skeleton->m_Bones[bi].m_Parent);
                    printf("  local\n");
                    printTransformAsMatrix(bind_pose[bi].m_LocalToParent);
                }

                const dmTransform::Transform& bind_t = bind_pose[bi].m_LocalToParent;
                t.SetTranslation(bind_t.GetTranslation() + t.GetTranslation());
                t.SetRotation(bind_t.GetRotation() * t.GetRotation());
                t.SetScale(mulPerElem(bind_t.GetScale(), t.GetScale()));

                if (debug)
                {
                    printf("  pose + local\n");
                    printTransformAsMatrix(t);
                    printf("\n");
                }
            }

            if (skeleton->m_Iks.m_Count > 0) {
                DM_PROFILE("RigIK");
                const uint32_t count = skeleton->m_Iks.m_Count;
                dmArray<IKTarget>& ik_targets = instance->m_IKTargets;


                for (uint32_t i = 0; i < count; ++i) {
                    const dmRigDDF::IK* ik = &skeleton->m_Iks[i];

                    // transform local space hiearchy for pose
                    dmTransform::Transform parent_t = GetPoseTransform(bind_pose, pose, pose[ik->m_Parent], ik->m_Parent);
                    dmTransform::Transform target_t = GetPoseTransform(bind_pose, pose, pose[ik->m_Target], ik->m_Target);
                    const uint32_t parent_parent_index = skeleton->m_Bones[ik->m_Parent].m_Parent;
                    dmTransform::Transform parent_parent_t;
                    if(parent_parent_index != INVALID_BONE_INDEX)
                    {
                        parent_parent_t = dmTransform::Inv(GetPoseTransform(bind_pose, pose, pose[skeleton->m_Bones[ik->m_Parent].m_Parent], skeleton->m_Bones[ik->m_Parent].m_Parent));
                        parent_t = dmTransform::Mul(parent_parent_t, parent_t);
                        target_t = dmTransform::Mul(parent_parent_t, target_t);
                    }
                    Vector3 parent_position = parent_t.GetTranslation();
                    Vector3 target_position = target_t.GetTranslation();

                    if(ik_targets[i].m_Mix != 0.0f)
                    {
                        // get custom target position either from go or vector position
                        Vector3 user_target_position = target_position;
                        if(ik_targets[i].m_Callback != 0)
                        {
                            user_target_position = ik_targets[i].m_Callback(&ik_targets[i]);
                        } else {
                            // instance have been removed, disable animation
                            ik_targets[i].m_UserHash = 0;
                            ik_targets[i].m_Mix = 0.0f;
                        }

                        const float target_mix = ik_targets[i].m_Mix;

                        if (parent_parent_index != INVALID_BONE_INDEX) {
                            user_target_position = dmTransform::Apply(parent_parent_t, user_target_position);
                        }

                        // blend default target pose and target pose
                        target_position = target_mix == 1.0f ? user_target_position : lerp(target_mix, target_position, user_target_position);
                    }

                    if(ik->m_Child == ik->m_Parent)
                        ApplyOneBoneIKConstraint(ik, bind_pose, pose, target_position, parent_position, ik_animation[i].m_Mix);
                    else
                        ApplyTwoBoneIKConstraint(ik, bind_pose, pose, target_position, parent_position, ik_animation[i].m_Positive, ik_animation[i].m_Mix);
                }
            }
    }

    static Result PostUpdate(HRigContext context)
    {
        const dmArray<RigInstance*>& instances = context->m_Instances.m_Objects;
        uint32_t count = instances.Size();
        bool updated_pose = false;
        for (uint32_t i = 0; i < count; ++i)
        {
            RigInstance* instance = instances[i];
            if (DoPostUpdate(instance)) {
                updated_pose = true;
            }
        }

        return updated_pose ? dmRig::RESULT_UPDATED_POSE : dmRig::RESULT_OK;
    }

    static bool DoPostUpdate(RigInstance* instance)
    {
            // If pose is empty, there are no bones to update
            dmArray<dmTransform::Transform>& pose = instance->m_Pose;
            if (pose.Empty())
                return false;

            // Notify any listener that the pose has been recalculated
            if (instance->m_PoseCallback) {
                instance->m_PoseCallback(instance->m_PoseCBUserData1, instance->m_PoseCBUserData2);
                return true;
            }

        return false;
    }

    Result Update(HRigContext context, float dt)
    {
        DM_PROFILE("RigUpdate");

        Animate(context, dt);

        return PostUpdate(context);
    }

    static dmRig::Result CreatePose(HRigContext context, HRigInstance instance)
    {
        if(!instance->m_Skeleton)
            return dmRig::RESULT_OK;

        const dmRigDDF::Skeleton* skeleton = instance->m_Skeleton;
        uint32_t bone_count = skeleton->m_Bones.m_Count;
        instance->m_Pose.SetCapacity(bone_count);
        instance->m_Pose.SetSize(bone_count);
        for (uint32_t i = 0; i < bone_count; ++i)
        {
            instance->m_Pose[i].SetIdentity();
        }

        instance->m_IKTargets.SetCapacity(skeleton->m_Iks.m_Count);
        instance->m_IKTargets.SetSize(skeleton->m_Iks.m_Count);
        memset(instance->m_IKTargets.Begin(), 0x0, instance->m_IKTargets.Size()*sizeof(IKTarget));

        instance->m_IKAnimation.SetCapacity(skeleton->m_Iks.m_Count);
        instance->m_IKAnimation.SetSize(skeleton->m_Iks.m_Count);

        return dmRig::RESULT_OK;
    }

    dmArray<dmTransform::Transform>* GetPose(HRigInstance instance)
    {
        return &instance->m_Pose;
    }


    float GetCursor(HRigInstance instance, bool normalized)
    {
        RigPlayer* player = GetPlayer(instance);

        if (!player || !player->m_Animation)
        {
            return 0.0f;
        }

        float duration = player->m_Animation->m_Duration;
        if (duration == 0.0f)
        {
            return 0.0f;
        }

        float t = player->m_Cursor;
        if (player->m_Playback == dmRig::PLAYBACK_ONCE_PINGPONG && t > duration)
        {
            // In once-pingpong the cursor will be greater than duration during the "pong" part, compensate for that
            t = (2.f * duration) - t;
        }

        if (player->m_Backwards)
        {
            t = duration - t;
        }

        if (normalized)
        {
            t = t / duration;
        }
        return t;
    }

    Result SetCursor(HRigInstance instance, float cursor, bool normalized)
    {
        float t = cursor;
        RigPlayer* player = GetPlayer(instance);

        if (!player)
        {
            return dmRig::RESULT_ERROR;
        }

        if (!player->m_Animation)
        {
            return RESULT_OK;
        }

        float duration = player->m_Animation->m_Duration;
        if (normalized)
        {
            t = t * duration;
        }

        if (player->m_Playback == dmRig::PLAYBACK_LOOP_PINGPONG && player->m_Backwards)
        {
            // NEVER set cursor on the "looped" part of a pingpong animation
            player->m_Backwards = 0;
        }

        if (fabs(t) > duration)
        {
            t = fmod(t, duration);
            if (fabs(t) < CURSOR_EPSILON)
            {
                t = duration;
            }
        }

        if (t < 0.0f)
        {
            t = duration - fmod(fabs(t), duration);
        }

        if (player->m_Backwards)
        {
            t = duration - t;
        }

        player->m_Cursor = t;

        return dmRig::RESULT_OK;
    }

    float GetPlaybackRate(HRigInstance instance)
    {
        RigPlayer* player = GetPlayer(instance);

        if (!player || !player->m_Animation)
        {
            return 1.0f;
        }

        return player->m_PlaybackRate;
    }

    Result SetPlaybackRate(HRigInstance instance, float playback_rate)
    {
        RigPlayer* player = GetPlayer(instance);

        if (!player)
        {
            return dmRig::RESULT_ERROR;
        }

        player->m_PlaybackRate = dmMath::Max(playback_rate, 0.0f);

        return dmRig::RESULT_OK;
    }

    uint32_t GetVertexCount(HRigInstance instance)
    {
        if (!instance->m_Model || !instance->m_DoRender) {
            return 0;
        }

        uint32_t vertex_count = 0;
        for (uint32_t i = 0; i < instance->m_Model->m_Meshes.m_Count; ++i)
        {
            const dmRigDDF::Mesh& mesh = instance->m_Model->m_Meshes[i];

            vertex_count += mesh.m_PositionIndices.m_Count;
        }

        return vertex_count;
    }

    static float* GenerateNormalData(const dmRigDDF::Mesh* mesh, const Matrix4& normal_matrix, const dmArray<Matrix4>& pose_matrices, float* out_buffer)
    {
        const float* normals_in = mesh->m_Normals.m_Data;
        const uint32_t* normal_indices = mesh->m_NormalsIndices.m_Data;
        uint32_t index_count = mesh->m_PositionIndices.m_Count;
        Vector4 v;

        if (!mesh->m_BoneIndices.m_Count || pose_matrices.Size() == 0)
        {
            for (uint32_t ii = 0; ii < index_count; ++ii)
            {
                uint32_t ni = normal_indices[ii];
                Vector3 normal_in(normals_in[ni*3+0], normals_in[ni*3+1], normals_in[ni*3+2]);
                v = normal_matrix * normal_in;
                if (lengthSqr(v) > 0.0f) {
                    normalize(v);
                }
                *out_buffer++ = v[0];
                *out_buffer++ = v[1];
                *out_buffer++ = v[2];
            }
            return out_buffer;
        }

        const uint32_t* indices = mesh->m_BoneIndices.m_Data;
        const float* weights = mesh->m_Weights.m_Data;
        const uint32_t* vertex_indices = mesh->m_PositionIndices.m_Data;
        for (uint32_t ii = 0; ii < index_count; ++ii)
        {
            const uint32_t ni = normal_indices[ii]*3;
            const Vector3 normal_in(normals_in[ni+0], normals_in[ni+1], normals_in[ni+2]);
            Vector4 normal_out(0.0f, 0.0f, 0.0f, 0.0f);

            const uint32_t bi_offset = vertex_indices[ii] << 2;
            const uint32_t* bone_indices = &indices[bi_offset];
            const float* bone_weights = &weights[bi_offset];

            if (bone_weights[0])
            {
                normal_out += (pose_matrices[bone_indices[0]] * normal_in) * bone_weights[0];
                if (bone_weights[1])
                {
                    normal_out += (pose_matrices[bone_indices[1]] * normal_in) * bone_weights[1];
                    if (bone_weights[2])
                    {
                        normal_out += (pose_matrices[bone_indices[2]] * normal_in) * bone_weights[2];
                        if (bone_weights[3])
                        {
                            normal_out += (pose_matrices[bone_indices[3]] * normal_in) * bone_weights[3];
                        }
                    }
                }
            }

            v = normal_matrix * Vector3(normal_out.getX(), normal_out.getY(), normal_out.getZ());
            if (lengthSqr(v) > 0.0f) {
                normalize(v);
            }
            *out_buffer++ = v[0];
            *out_buffer++ = v[1];
            *out_buffer++ = v[2];
        }

        return out_buffer;
    }

    static float* GeneratePositionData(const dmRigDDF::Mesh* mesh, const Matrix4& model_matrix, const dmArray<Matrix4>& pose_matrices, float* out_buffer)
    {
        const float *positions = mesh->m_Positions.m_Data;
        const size_t vertex_count = mesh->m_Positions.m_Count / 3;
        Point3 in_p;
        Vector4 v;
        if(!mesh->m_BoneIndices.m_Count || pose_matrices.Size() == 0)
        {
            for (uint32_t i = 0; i < vertex_count; ++i)
            {
                in_p[0] = *positions++;
                in_p[1] = *positions++;
                in_p[2] = *positions++;
                v = model_matrix * in_p;
                *out_buffer++ = v[0];
                *out_buffer++ = v[1];
                *out_buffer++ = v[2];
            }
            return out_buffer;
        }

        const uint32_t* indices = mesh->m_BoneIndices.m_Data;
        const float* weights = mesh->m_Weights.m_Data;
        for (uint32_t i = 0; i < vertex_count; ++i)
        {
            Vector4 in_v;
            in_v.setX(*positions++);
            in_v.setY(*positions++);
            in_v.setZ(*positions++);
            in_v.setW(1.0f);

            Vector4 out_p(0.0f, 0.0f, 0.0f, 0.0f);
            const uint32_t bi_offset = i * 4;
            const uint32_t* bone_indices = &indices[bi_offset];
            const float* bone_weights = &weights[bi_offset];

            if(bone_weights[0])
            {
                out_p += pose_matrices[bone_indices[0]] * in_v * bone_weights[0];
                if(bone_weights[1])
                {
                    out_p += pose_matrices[bone_indices[1]] * in_v * bone_weights[1];
                    if(bone_weights[2])
                    {
                        out_p += pose_matrices[bone_indices[2]] * in_v * bone_weights[2];
                        if(bone_weights[3])
                        {
                            out_p += pose_matrices[bone_indices[3]] * in_v * bone_weights[3];
                        }
                    }
                }
            }

            v = model_matrix * Point3(out_p.getX(), out_p.getY(), out_p.getZ());
            *out_buffer++ = v[0];
            *out_buffer++ = v[1];
            *out_buffer++ = v[2];
        }
        return out_buffer;
    }

    static void PoseToMatrix(const dmArray<dmTransform::Transform>& pose, dmArray<Matrix4>& out_matrices)
    {
        uint32_t bone_count = pose.Size();
        for (uint32_t bi = 0; bi < bone_count; ++bi)
        {
            out_matrices[bi] = dmTransform::ToMatrix4(pose[bi]);
        }
    }

    static void PoseToModelSpace(const dmRigDDF::Skeleton* skeleton, const dmArray<dmTransform::Transform>& pose, dmArray<dmTransform::Transform>& out_pose)
    {
        const dmRigDDF::Bone* bones = skeleton->m_Bones.m_Data;
        uint32_t bone_count = skeleton->m_Bones.m_Count;
        for (uint32_t bi = 0; bi < bone_count; ++bi)
        {
            const dmTransform::Transform& transform = pose[bi];
            dmTransform::Transform& out_transform = out_pose[bi];
            out_transform = transform;
            if (bi > 0) {
                const dmRigDDF::Bone* bone = &bones[bi];
                if (bone->m_InheritScale)
                {
                    out_transform = dmTransform::Mul(out_pose[bone->m_Parent], transform);
                }
                else
                {
                    Vector3 scale = transform.GetScale();
                    out_transform = dmTransform::Mul(out_pose[bone->m_Parent], transform);
                    out_transform.SetScale(scale);
                }
            }
        }
    }

    static void PoseToModelSpace(const dmRigDDF::Skeleton* skeleton, const dmArray<Matrix4>& pose, dmArray<Matrix4>& out_pose)
    {
if (g_Debug)
{
    printf("%s\n", __FUNCTION__);
}
        const dmRigDDF::Bone* bones = skeleton->m_Bones.m_Data;
        uint32_t bone_count = skeleton->m_Bones.m_Count;
        for (uint32_t bi = 0; bi < bone_count; ++bi)
        {

            const Matrix4& transform = pose[bi];
            Matrix4& out_transform = out_pose[bi];
            out_transform = transform;

if (g_Debug)
{
    printf("Bone index: %u %s   parent: %u  %s\n", bi, bones[bi].m_Name, bones[bi].m_Parent, bones[bi].m_Parent == INVALID_BONE_INDEX ? "" : bones[bones[bi].m_Parent].m_Name);
    printf("  pose\n");
    printMatrix(transform);
}
            if (bi > 0) {
                const dmRigDDF::Bone* bone = &bones[bi];
                assert(bone->m_Parent < bi);

                if (bone->m_InheritScale)
                {
if (g_Debug)
{
    printf("  parent:\n");
    printMatrix(out_pose[bone->m_Parent]);
}

                    out_transform = out_pose[bone->m_Parent] * transform;

                }
                else
                {
                    Vector3 scale = dmTransform::ExtractScale(out_pose[bone->m_Parent]);
                    out_transform.setUpper3x3(Matrix3::scale(Vector3(1.0f/scale.getX(), 1.0f/scale.getY(), 1.0f/scale.getZ())) * transform.getUpper3x3());
                    out_transform = out_pose[bone->m_Parent] * transform;
                }
            }
if (g_Debug)
{
    printf("  world_xform\n");
    printMatrix(out_transform);
}
        }
    }

    static void PoseToInfluence(const dmArray<uint32_t>& pose_idx_to_influence, const dmArray<Matrix4>& in_pose, dmArray<Matrix4>& out_pose)
    {
        for (uint32_t i = 0; i < pose_idx_to_influence.Size(); ++i)
        {
            uint32_t j = pose_idx_to_influence[i];
            out_pose[j] = in_pose[i];
        }
    }

    static RigModelVertex* WriteVertexData(const dmRigDDF::Mesh* mesh, const float* positions, const float* normals, RigModelVertex* out_write_ptr)
    {
        uint32_t indices_count = mesh->m_PositionIndices.m_Count;
        const uint32_t* indices = mesh->m_PositionIndices.m_Data;
        const uint32_t* uv0_indices = mesh->m_Texcoord0Indices.m_Count ? mesh->m_Texcoord0Indices.m_Data : mesh->m_PositionIndices.m_Data;
        const float* uv0 = mesh->m_Texcoord0.m_Data;

        // TODO: I'm not sure what the use case is for having the index data available.
        // Preferably, I'd have the vertex buffer already packed after the build pipelibne /MAWE

        if (mesh->m_NormalsIndices.m_Count)
        {
            for (uint32_t i = 0; i < indices_count; ++i)
            {
                uint32_t vi = indices[i];
                uint32_t e = vi * 3;
                out_write_ptr->x = positions[e];
                out_write_ptr->y = positions[++e];
                out_write_ptr->z = positions[++e];
                vi = uv0_indices[i];
                e = vi << 1;
                out_write_ptr->u = uv0[e+0];
                out_write_ptr->v = uv0[e+1];
                e = i * 3;
                out_write_ptr->nx = normals[e];
                out_write_ptr->ny = normals[++e];
                out_write_ptr->nz = normals[++e];
                out_write_ptr++;
            }
        }
        else
        {
            for (uint32_t i = 0; i < indices_count; ++i)
            {
                uint32_t vi = indices[i];
                uint32_t e = vi * 3;
                out_write_ptr->x = positions[e];
                out_write_ptr->y = positions[++e];
                out_write_ptr->z = positions[++e];
                vi = uv0_indices[i];
                e = vi << 1;
                out_write_ptr->u = uv0[e+0];
                out_write_ptr->v = uv0[e+1];
                out_write_ptr->nx = 0.0f;
                out_write_ptr->ny = 0.0f;
                out_write_ptr->nz = 1.0f;
                out_write_ptr++;
            }
        }

        return out_write_ptr;
    }

    RigModelVertex* GenerateVertexData(dmRig::HRigContext context, dmRig::HRigInstance instance, const Matrix4& instance_matrix, const Vector4 color, RigModelVertex* vertex_data_out)
    {
        const dmRigDDF::Model* model = instance->m_Model;

        if (!model || !instance->m_DoRender) {
            return vertex_data_out;
        }

        dmArray<Matrix4>& pose_matrices      = context->m_ScratchPoseMatrixBuffer;
        dmArray<Matrix4>& influence_matrices = context->m_ScratchInfluenceMatrixBuffer;
        dmArray<Vector3>& positions          = context->m_ScratchPositionBuffer;
        dmArray<Vector3>& normals            = context->m_ScratchNormalBuffer;

        // If the rig has bones, update the pose to be local-to-model
        uint32_t bone_count = GetBoneCount(instance);
        influence_matrices.SetSize(0);
        if (!USE_BIND_POSE && bone_count && instance->m_PoseIdxToInfluence->Size() > 0) {

            // Make sure pose scratch buffers have enough space
            if (pose_matrices.Capacity() < bone_count) {
                uint32_t size_offset = bone_count - pose_matrices.Capacity();
                pose_matrices.OffsetCapacity(size_offset);
            }
            pose_matrices.SetSize(bone_count);

            // Make sure influence scratch buffers have enough space sufficient for max bones to be indexed
            uint32_t max_bone_count = instance->m_MaxBoneCount;
            if (influence_matrices.Capacity() < max_bone_count) {
                uint32_t capacity = influence_matrices.Capacity();
                uint32_t size_offset = max_bone_count - capacity;
                influence_matrices.OffsetCapacity(size_offset);
                influence_matrices.SetSize(max_bone_count);
                for(uint32_t i = capacity; i < capacity+size_offset; ++i)
                    influence_matrices[i] = Matrix4::identity();
            }
            influence_matrices.SetSize(max_bone_count);

            const dmArray<dmTransform::Transform>& pose = instance->m_Pose;
            const dmRigDDF::Skeleton* skeleton = instance->m_Skeleton;
            if (skeleton->m_LocalBoneScaling) {

                dmArray<dmTransform::Transform>& pose_transforms = context->m_ScratchPoseTransformBuffer;
                if (pose_transforms.Capacity() < bone_count) {
                    pose_transforms.OffsetCapacity(bone_count - pose_transforms.Capacity());
                }
                pose_transforms.SetSize(bone_count);

                PoseToModelSpace(skeleton, pose, pose_transforms);
                PoseToMatrix(pose_transforms, pose_matrices);
            } else {
                PoseToMatrix(pose, pose_matrices);
                PoseToModelSpace(skeleton, pose_matrices, pose_matrices);
            }

            if (g_Debug)
            {
                printf("%s\n", __FUNCTION__);
            }

            // Premultiply pose matrices with the bind pose inverse so they
            // can be directly be used to transform each vertex.
            const dmArray<RigBone>& bind_pose = *instance->m_BindPose;
            for (uint32_t bi = 0; bi < pose_matrices.Size(); ++bi)
            {
                Matrix4& pose_matrix = pose_matrices[bi];

                if (g_Debug)
                {
                    printf("Bone index: %u %s   parent: %u\n", bi, skeleton->m_Bones[bi].m_Name, skeleton->m_Bones[bi].m_Parent);
                }

                pose_matrix = pose_matrix * bind_pose[bi].m_ModelToLocal;

                if (g_Debug)
                {
                    printf("  inv_bind_pose\n");
                    printMatrix(bind_pose[bi].m_ModelToLocal);
                    printf("  final\n");
                    printMatrix(pose_matrix);
                }
            }

            // Rearrange pose matrices to indices that the mesh vertices understand.
            PoseToInfluence(*instance->m_PoseIdxToInfluence, pose_matrices, influence_matrices);
        }

        dmVMath::Matrix4 mesh_matrix = dmTransform::ToMatrix4(model->m_Local);
        dmVMath::Matrix4 world_matrix = instance_matrix * mesh_matrix;

        Matrix4 normal_matrix = Vectormath::Aos::inverse(world_matrix);
        normal_matrix = Vectormath::Aos::transpose(normal_matrix);

        for (uint32_t i = 0; i < model->m_Meshes.m_Count; ++i)
        {
            const dmRigDDF::Mesh* mesh = &model->m_Meshes[i];

            // TODO: Currently, we only have support for a single material
            // so we bake all meshes into one

            uint32_t index_count = mesh->m_PositionIndices.m_Count;

            // Bump scratch buffer capacity to handle current vertex count
            if (positions.Capacity() < index_count) {
                positions.OffsetCapacity(index_count - positions.Capacity());
            }
            positions.SetSize(index_count);

            if (normals.Capacity() < index_count) {
                normals.OffsetCapacity(index_count - normals.Capacity());
            }
            normals.SetSize(index_count);

            float* positions_buffer = (float*)positions.Begin();
            float* normals_buffer = (float*)normals.Begin();

            // Transform the mesh data into world space

            dmRig::GeneratePositionData(mesh, world_matrix, influence_matrices, positions_buffer);
            if (mesh->m_NormalsIndices.m_Count) {
                dmRig::GenerateNormalData(mesh, normal_matrix, influence_matrices, normals_buffer);
            }

            vertex_data_out = WriteVertexData(mesh, positions_buffer, normals_buffer, vertex_data_out);
        }

        // DEF-3610
        // Using Wasm on Microsoft Edge this function returns NULL after a couple of runs.
        // There is no code path that could result in NULL, leading us to suspect it has
        // to do with some runtime optimization.
        // If we add some logic that touches vertex_data_out the function keeps returning
        // valid values, so we add an assert to verify vertex_data_out never is NULL.
        // However this means we need assert on Emscripten, so for now we output a
        // compile error if the engine is built with NDEBUG.
#ifdef __EMSCRIPTEN__
#ifdef NDEBUG
        #error "DEF-3610 - Can't compile with NDEBUG since dmRig::GenerateVertexData currently depends on assert() to work on Emscripten builds."
#else
        assert(vertex_data_out != 0x0);
#endif
#endif

        return vertex_data_out;
    }

    static uint32_t FindIKIndex(HRigInstance instance, dmhash_t ik_constraint_id)
    {
        const dmRigDDF::Skeleton* skeleton = instance->m_Skeleton;
        uint32_t ik_count = skeleton->m_Iks.m_Count;
        uint32_t ik_index = ~0u;
        for (uint32_t i = 0; i < ik_count; ++i)
        {
            if (skeleton->m_Iks[i].m_Id == ik_constraint_id)
            {
                ik_index = i;
                break;
            }
        }
        return ik_index;
    }

    void SetEnabled(HRigInstance instance, bool enabled)
    {
        instance->m_Enabled = enabled;
    }

    bool GetEnabled(HRigInstance instance)
    {
        return instance->m_Enabled;
    }

    bool IsValid(HRigInstance instance)
    {
        return instance->m_Model != 0;
    }

    uint32_t GetBoneCount(HRigInstance instance)
    {
        if (instance == 0x0 || instance->m_Skeleton == 0x0) {
            return 0;
        }

        return instance->m_Skeleton->m_Bones.m_Count;
    }

    uint32_t GetMaxBoneCount(HRigInstance instance)
    {
        if (instance == 0x0) {
            return 0;
        }
        return instance->m_MaxBoneCount;
    }

    void SetEventCallback(HRigInstance instance, RigEventCallback event_callback, void* user_data1, void* user_data2)
    {
        if (!instance) {
            return;
        }

        instance->m_EventCallback = event_callback;
        instance->m_EventCBUserData1 = user_data1;
        instance->m_EventCBUserData2 = user_data2;
    }

    IKTarget* GetIKTarget(HRigInstance instance, dmhash_t constraint_id)
    {
        if (!instance) {
            return 0x0;
        }
        uint32_t ik_index = FindIKIndex(instance, constraint_id);
        if (ik_index == ~0u) {
            dmLogError("Could not find IK constraint (%llu)", (unsigned long long)constraint_id);
            return 0x0;
        }

        return &instance->m_IKTargets[ik_index];
    }

    bool ResetIKTarget(HRigInstance instance, dmhash_t constraint_id)
    {
        if (!instance) {
            return false;
        }

        uint32_t ik_index = FindIKIndex(instance, constraint_id);
        if (ik_index == ~0u) {
            dmLogError("Could not find IK constraint (%llu)", (unsigned long long)constraint_id);
            return false;
        }

        // Clear target fields, see DoAnimate function of the fields usage.
        // If callback is NULL it is considered not active, clear rest of fields
        // to avoid confusion.
        IKTarget* target = &instance->m_IKTargets[ik_index];
        target->m_Callback = 0x0;
        target->m_Mix = 0.0f;
        target->m_UserPtr = 0x0;
        target->m_UserHash = 0x0;

        return true;
    }

    static void DestroyInstance(HRigContext context, uint32_t index)
    {
        RigInstance* instance = context->m_Instances.Get(index);
        // If we're going to use memset, then we should explicitly clear pose and instance arrays.
        instance->m_Pose.SetCapacity(0);
        instance->m_IKTargets.SetCapacity(0);
        delete instance;
        context->m_Instances.Free(index, true);
    }

    Result InstanceCreate(const InstanceCreateParams& params)
    {
        RigContext* context = (RigContext*)params.m_Context;

        if (context->m_Instances.Full())
        {
            dmLogError("Rig instance could not be created since the buffer is full (%d).", context->m_Instances.Capacity());
            return dmRig::RESULT_ERROR_BUFFER_FULL;
        }

        *params.m_Instance = new RigInstance;
        RigInstance* instance = *params.m_Instance;

        uint32_t index = context->m_Instances.Alloc();
        memset(instance, 0, sizeof(RigInstance));
        instance->m_Index = index;
        context->m_Instances.Set(index, instance);
        instance->m_ModelId = params.m_ModelId;

        instance->m_PoseCallback     = params.m_PoseCallback;
        instance->m_PoseCBUserData1  = params.m_PoseCBUserData1;
        instance->m_PoseCBUserData2  = params.m_PoseCBUserData2;
        instance->m_EventCallback    = params.m_EventCallback;
        instance->m_EventCBUserData1 = params.m_EventCBUserData1;
        instance->m_EventCBUserData2 = params.m_EventCBUserData2;

        instance->m_BindPose           = params.m_BindPose;
        instance->m_Skeleton           = params.m_Skeleton;
        instance->m_MeshSet            = params.m_MeshSet;
        instance->m_AnimationSet       = params.m_AnimationSet;
        instance->m_PoseIdxToInfluence = params.m_PoseIdxToInfluence;
        instance->m_TrackIdxToPose     = params.m_TrackIdxToPose;

        instance->m_Enabled = 1;

        SetModel(instance, instance->m_ModelId);

        instance->m_MaxBoneCount = dmMath::Max(instance->m_MeshSet->m_MaxBoneCount, instance->m_Skeleton == 0x0 ? 0 : instance->m_Skeleton->m_Bones.m_Count);
        Result result = CreatePose(context, instance);
        if (result != dmRig::RESULT_OK) {
            DestroyInstance(context, index);
            return result;
        }

        if (params.m_DefaultAnimation != NULL_ANIMATION)
        {
            // Loop forward should be the most common for idle anims etc.
            (void)PlayAnimation(instance, params.m_DefaultAnimation, dmRig::PLAYBACK_LOOP_FORWARD, 0.0f, 0.0f, 1.0f);
        }

        // m_ForceAnimatePose should be set if the animation step needs to run once (with dt 0)
        // to setup the pose to the current cursor.
        // Useful if pose needs to be calculated before draw but dmRig::Update will not be called
        // before that happens, for example cloning a GUI spine node happens in script update,
        // which comes after the regular dmRig::Update.
        if (params.m_ForceAnimatePose) {
            DoAnimate(context, instance, 0.0f);
        }

        return dmRig::RESULT_OK;
    }

    Result InstanceDestroy(const InstanceDestroyParams& params)
    {
        if (!params.m_Context || !params.m_Instance) {
            return dmRig::RESULT_ERROR;
        }

        DestroyInstance((RigContext*)params.m_Context, params.m_Instance->m_Index);
        return dmRig::RESULT_OK;
    }

    void CopyBindPose(dmRigDDF::Skeleton& skeleton, dmArray<RigBone>& bind_pose)
    {
        uint32_t bone_count = skeleton.m_Bones.m_Count;
        bind_pose.SetCapacity(bone_count);
        bind_pose.SetSize(bone_count);
        for (uint32_t i = 0; i < bone_count; ++i)
        {
            dmRig::RigBone* bind_bone = &bind_pose[i];
            dmRigDDF::Bone* bone = &skeleton.m_Bones[i];
            bind_bone->m_LocalToParent = bone->m_Local;
            bind_bone->m_LocalToModel = bone->m_World;
            bind_bone->m_ModelToLocal = dmTransform::ToMatrix4(bone->m_InverseBindPose);

            bind_bone->m_ParentIndex = bone->m_Parent;
            bind_bone->m_Length = bone->m_Length;
        }
    }

    static const uint32_t INVALID_BONE_IDX = 0xffffffff;
    static uint32_t FindBoneInList(uint64_t* list, uint32_t count, uint64_t bone_id)
    {
        for (uint32_t i = 0; i < count; ++i)
        {
            uint64_t entry = list[i];
            if (bone_id == entry) {
                return i;
            }
        }

        return INVALID_BONE_IDX;
    }

    void FillBoneListArrays(const dmRigDDF::MeshSet& meshset, const dmRigDDF::AnimationSet& animationset, const dmRigDDF::Skeleton& skeleton, dmArray<uint32_t>& track_idx_to_pose, dmArray<uint32_t>& pose_idx_to_influence)
    {
        // Create lookup arrays
        // - track-to-pose, used to convert animation track bonde index into correct pose transform index
        // - pose-to-influence, used during vertex generation to convert pose transform index into influence index
        uint32_t bone_count = skeleton.m_Bones.m_Count;

        track_idx_to_pose.SetCapacity(bone_count);
        track_idx_to_pose.SetSize(bone_count);
        memset((void*)track_idx_to_pose.Begin(), 0x0, track_idx_to_pose.Size()*sizeof(uint32_t));
        pose_idx_to_influence.SetCapacity(bone_count);
        pose_idx_to_influence.SetSize(bone_count);

        uint32_t anim_bone_list_count = animationset.m_BoneList.m_Count;
        uint32_t mesh_bone_list_count = meshset.m_BoneList.m_Count;

        for (uint32_t bi = 0; bi < bone_count; ++bi)
        {
            uint64_t bone_id = skeleton.m_Bones[bi].m_Id;

            if (anim_bone_list_count) {
                uint32_t track_idx = FindBoneInList(animationset.m_BoneList.m_Data, animationset.m_BoneList.m_Count, bone_id);
                if (track_idx != INVALID_BONE_IDX) {
                    track_idx_to_pose[track_idx] = bi;
                }
            } else {
                track_idx_to_pose[bi] = bi;
            }

            if (mesh_bone_list_count) {
                uint32_t influence_idx = FindBoneInList(meshset.m_BoneList.m_Data, meshset.m_BoneList.m_Count, bone_id);
                if (influence_idx != INVALID_BONE_IDX) {
                    pose_idx_to_influence[bi] = influence_idx;
                } else {
                    // If there is no influence index for the current bone
                    // we still need to put the pose matrix somewhere during
                    // pose-to-influence rearrangement so just put it last.
                    pose_idx_to_influence[bi] = bone_count - 1;
                }
            } else {
                pose_idx_to_influence[bi] = bi;
            }
        }
    }

}
