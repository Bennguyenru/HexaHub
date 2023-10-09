// generated, do not edit

#include <jni.h>
#include "modelimporter.h"

#define JAVA_PACKAGE_NAME "com/dynamo/bob/pipeline"
#define CLASS_NAME "com/dynamo/bob/pipeline/ModelImporter"

namespace dmModelImporter {
namespace jni {
struct Vec3fJNI {
    jclass cls;
    jfieldID x;
    jfieldID y;
    jfieldID z;
};
struct Vec4fJNI {
    jclass cls;
    jfieldID x;
    jfieldID y;
    jfieldID z;
    jfieldID w;
};
struct AabbJNI {
    jclass cls;
    jfieldID min;
    jfieldID max;
};
struct TransformJNI {
    jclass cls;
    jfieldID translation;
    jfieldID scale;
    jfieldID rotation;
};
struct MaterialJNI {
    jclass cls;
    jfieldID name;
    jfieldID index;
    jfieldID isSkinned;
};
struct MeshJNI {
    jclass cls;
    jfieldID name;
    jfieldID material;
    jfieldID aabb;
    jfieldID vertexCount;
    jfieldID positions;
    jfieldID normals;
    jfieldID tangents;
    jfieldID colors;
    jfieldID weights;
    jfieldID texCoords0;
    jfieldID texCoords1;
    jfieldID texCoords0NumComponents;
    jfieldID texCoords1NumComponents;
    jfieldID bones;
    jfieldID indices;
};
struct ModelJNI {
    jclass cls;
    jfieldID name;
    jfieldID meshes;
    jfieldID index;
    jfieldID parentBone;
};
struct BoneJNI {
    jclass cls;
    jfieldID invBindPose;
    jfieldID name;
    jfieldID node;
    jfieldID parent;
    jfieldID index;
    jfieldID children;
};
struct SkinJNI {
    jclass cls;
    jfieldID name;
    jfieldID index;
    jfieldID bones;
    jfieldID boneRemap;
};
struct NodeJNI {
    jclass cls;
    jfieldID local;
    jfieldID world;
    jfieldID name;
    jfieldID model;
    jfieldID skin;
    jfieldID parent;
    jfieldID children;
    jfieldID index;
    jfieldID nameHash;
};
struct KeyFrameJNI {
    jclass cls;
    jfieldID value;
    jfieldID time;
};
struct NodeAnimationJNI {
    jclass cls;
    jfieldID node;
    jfieldID startTime;
    jfieldID endTime;
    jfieldID translationKeys;
    jfieldID rotationKeys;
    jfieldID scaleKeys;
};
struct AnimationJNI {
    jclass cls;
    jfieldID name;
    jfieldID duration;
    jfieldID nodeAnimations;
};
struct BufferJNI {
    jclass cls;
    jfieldID uri;
    jfieldID buffer;
    jfieldID bufferSize;
};
struct SceneJNI {
    jclass cls;
    jfieldID opaqueSceneData;
    jfieldID loadFinalizeFn;
    jfieldID validateFn;
    jfieldID destroyFn;
    jfieldID nodes;
    jfieldID models;
    jfieldID skins;
    jfieldID rootNodes;
    jfieldID animations;
    jfieldID materials;
    jfieldID dynamicMaterials;
    jfieldID buffers;
};
struct OptionsJNI {
    jclass cls;
    jfieldID dummy;
};
struct TypeInfos {
    Vec3fJNI m_Vec3fJNI;
    Vec4fJNI m_Vec4fJNI;
    AabbJNI m_AabbJNI;
    TransformJNI m_TransformJNI;
    MaterialJNI m_MaterialJNI;
    MeshJNI m_MeshJNI;
    ModelJNI m_ModelJNI;
    BoneJNI m_BoneJNI;
    SkinJNI m_SkinJNI;
    NodeJNI m_NodeJNI;
    KeyFrameJNI m_KeyFrameJNI;
    NodeAnimationJNI m_NodeAnimationJNI;
    AnimationJNI m_AnimationJNI;
    BufferJNI m_BufferJNI;
    SceneJNI m_SceneJNI;
    OptionsJNI m_OptionsJNI;
};
void InitializeJNITypes(JNIEnv* env, TypeInfos* infos);
void FinalizeJNITypes(JNIEnv* env, TypeInfos* infos);

struct ScopedContext {
    JNIEnv*   m_Env;
    TypeInfos m_TypeInfos;
    ScopedContext(JNIEnv* env) : m_Env(env) {
        InitializeJNITypes(m_Env, &m_TypeInfos);
    }
    ~ScopedContext() {
        FinalizeJNITypes(m_Env, &m_TypeInfos);
    }
};


//----------------------------------------
// From C to Jni
//----------------------------------------
jobject C2J_CreateVec3f(JNIEnv* env, TypeInfos* types, const Vec3f* src);
jobject C2J_CreateVec4f(JNIEnv* env, TypeInfos* types, const Vec4f* src);
jobject C2J_CreateAabb(JNIEnv* env, TypeInfos* types, const Aabb* src);
jobject C2J_CreateTransform(JNIEnv* env, TypeInfos* types, const Transform* src);
jobject C2J_CreateMaterial(JNIEnv* env, TypeInfos* types, const Material* src);
jobject C2J_CreateMesh(JNIEnv* env, TypeInfos* types, const Mesh* src);
jobject C2J_CreateModel(JNIEnv* env, TypeInfos* types, const Model* src);
jobject C2J_CreateBone(JNIEnv* env, TypeInfos* types, const Bone* src);
jobject C2J_CreateSkin(JNIEnv* env, TypeInfos* types, const Skin* src);
jobject C2J_CreateNode(JNIEnv* env, TypeInfos* types, const Node* src);
jobject C2J_CreateKeyFrame(JNIEnv* env, TypeInfos* types, const KeyFrame* src);
jobject C2J_CreateNodeAnimation(JNIEnv* env, TypeInfos* types, const NodeAnimation* src);
jobject C2J_CreateAnimation(JNIEnv* env, TypeInfos* types, const Animation* src);
jobject C2J_CreateBuffer(JNIEnv* env, TypeInfos* types, const Buffer* src);
jobject C2J_CreateScene(JNIEnv* env, TypeInfos* types, const Scene* src);
jobject C2J_CreateOptions(JNIEnv* env, TypeInfos* types, const Options* src);
jobjectArray C2J_CreateVec3fArray(JNIEnv* env, TypeInfos* types, const Vec3f* src, uint32_t src_count);
jobjectArray C2J_CreateVec3fPtrArray(JNIEnv* env, TypeInfos* types, const Vec3f* const* src, uint32_t src_count);
jobjectArray C2J_CreateVec4fArray(JNIEnv* env, TypeInfos* types, const Vec4f* src, uint32_t src_count);
jobjectArray C2J_CreateVec4fPtrArray(JNIEnv* env, TypeInfos* types, const Vec4f* const* src, uint32_t src_count);
jobjectArray C2J_CreateAabbArray(JNIEnv* env, TypeInfos* types, const Aabb* src, uint32_t src_count);
jobjectArray C2J_CreateAabbPtrArray(JNIEnv* env, TypeInfos* types, const Aabb* const* src, uint32_t src_count);
jobjectArray C2J_CreateTransformArray(JNIEnv* env, TypeInfos* types, const Transform* src, uint32_t src_count);
jobjectArray C2J_CreateTransformPtrArray(JNIEnv* env, TypeInfos* types, const Transform* const* src, uint32_t src_count);
jobjectArray C2J_CreateMaterialArray(JNIEnv* env, TypeInfos* types, const Material* src, uint32_t src_count);
jobjectArray C2J_CreateMaterialPtrArray(JNIEnv* env, TypeInfos* types, const Material* const* src, uint32_t src_count);
jobjectArray C2J_CreateMeshArray(JNIEnv* env, TypeInfos* types, const Mesh* src, uint32_t src_count);
jobjectArray C2J_CreateMeshPtrArray(JNIEnv* env, TypeInfos* types, const Mesh* const* src, uint32_t src_count);
jobjectArray C2J_CreateModelArray(JNIEnv* env, TypeInfos* types, const Model* src, uint32_t src_count);
jobjectArray C2J_CreateModelPtrArray(JNIEnv* env, TypeInfos* types, const Model* const* src, uint32_t src_count);
jobjectArray C2J_CreateBoneArray(JNIEnv* env, TypeInfos* types, const Bone* src, uint32_t src_count);
jobjectArray C2J_CreateBonePtrArray(JNIEnv* env, TypeInfos* types, const Bone* const* src, uint32_t src_count);
jobjectArray C2J_CreateSkinArray(JNIEnv* env, TypeInfos* types, const Skin* src, uint32_t src_count);
jobjectArray C2J_CreateSkinPtrArray(JNIEnv* env, TypeInfos* types, const Skin* const* src, uint32_t src_count);
jobjectArray C2J_CreateNodeArray(JNIEnv* env, TypeInfos* types, const Node* src, uint32_t src_count);
jobjectArray C2J_CreateNodePtrArray(JNIEnv* env, TypeInfos* types, const Node* const* src, uint32_t src_count);
jobjectArray C2J_CreateKeyFrameArray(JNIEnv* env, TypeInfos* types, const KeyFrame* src, uint32_t src_count);
jobjectArray C2J_CreateKeyFramePtrArray(JNIEnv* env, TypeInfos* types, const KeyFrame* const* src, uint32_t src_count);
jobjectArray C2J_CreateNodeAnimationArray(JNIEnv* env, TypeInfos* types, const NodeAnimation* src, uint32_t src_count);
jobjectArray C2J_CreateNodeAnimationPtrArray(JNIEnv* env, TypeInfos* types, const NodeAnimation* const* src, uint32_t src_count);
jobjectArray C2J_CreateAnimationArray(JNIEnv* env, TypeInfos* types, const Animation* src, uint32_t src_count);
jobjectArray C2J_CreateAnimationPtrArray(JNIEnv* env, TypeInfos* types, const Animation* const* src, uint32_t src_count);
jobjectArray C2J_CreateBufferArray(JNIEnv* env, TypeInfos* types, const Buffer* src, uint32_t src_count);
jobjectArray C2J_CreateBufferPtrArray(JNIEnv* env, TypeInfos* types, const Buffer* const* src, uint32_t src_count);
jobjectArray C2J_CreateSceneArray(JNIEnv* env, TypeInfos* types, const Scene* src, uint32_t src_count);
jobjectArray C2J_CreateScenePtrArray(JNIEnv* env, TypeInfos* types, const Scene* const* src, uint32_t src_count);
jobjectArray C2J_CreateOptionsArray(JNIEnv* env, TypeInfos* types, const Options* src, uint32_t src_count);
jobjectArray C2J_CreateOptionsPtrArray(JNIEnv* env, TypeInfos* types, const Options* const* src, uint32_t src_count);
//----------------------------------------
// From Jni to C
//----------------------------------------
bool J2C_CreateVec3f(JNIEnv* env, TypeInfos* types, jobject obj, Vec3f* out);
bool J2C_CreateVec4f(JNIEnv* env, TypeInfos* types, jobject obj, Vec4f* out);
bool J2C_CreateAabb(JNIEnv* env, TypeInfos* types, jobject obj, Aabb* out);
bool J2C_CreateTransform(JNIEnv* env, TypeInfos* types, jobject obj, Transform* out);
bool J2C_CreateMaterial(JNIEnv* env, TypeInfos* types, jobject obj, Material* out);
bool J2C_CreateMesh(JNIEnv* env, TypeInfos* types, jobject obj, Mesh* out);
bool J2C_CreateModel(JNIEnv* env, TypeInfos* types, jobject obj, Model* out);
bool J2C_CreateBone(JNIEnv* env, TypeInfos* types, jobject obj, Bone* out);
bool J2C_CreateSkin(JNIEnv* env, TypeInfos* types, jobject obj, Skin* out);
bool J2C_CreateNode(JNIEnv* env, TypeInfos* types, jobject obj, Node* out);
bool J2C_CreateKeyFrame(JNIEnv* env, TypeInfos* types, jobject obj, KeyFrame* out);
bool J2C_CreateNodeAnimation(JNIEnv* env, TypeInfos* types, jobject obj, NodeAnimation* out);
bool J2C_CreateAnimation(JNIEnv* env, TypeInfos* types, jobject obj, Animation* out);
bool J2C_CreateBuffer(JNIEnv* env, TypeInfos* types, jobject obj, Buffer* out);
bool J2C_CreateScene(JNIEnv* env, TypeInfos* types, jobject obj, Scene* out);
bool J2C_CreateOptions(JNIEnv* env, TypeInfos* types, jobject obj, Options* out);
Vec3f* J2C_CreateVec3fArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateVec3fArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Vec3f* dst, uint32_t dst_count);
Vec3f** J2C_CreateVec3fPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateVec3fPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Vec3f** dst, uint32_t dst_count);
Vec4f* J2C_CreateVec4fArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateVec4fArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Vec4f* dst, uint32_t dst_count);
Vec4f** J2C_CreateVec4fPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateVec4fPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Vec4f** dst, uint32_t dst_count);
Aabb* J2C_CreateAabbArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateAabbArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Aabb* dst, uint32_t dst_count);
Aabb** J2C_CreateAabbPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateAabbPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Aabb** dst, uint32_t dst_count);
Transform* J2C_CreateTransformArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateTransformArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Transform* dst, uint32_t dst_count);
Transform** J2C_CreateTransformPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateTransformPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Transform** dst, uint32_t dst_count);
Material* J2C_CreateMaterialArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateMaterialArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Material* dst, uint32_t dst_count);
Material** J2C_CreateMaterialPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateMaterialPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Material** dst, uint32_t dst_count);
Mesh* J2C_CreateMeshArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateMeshArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Mesh* dst, uint32_t dst_count);
Mesh** J2C_CreateMeshPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateMeshPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Mesh** dst, uint32_t dst_count);
Model* J2C_CreateModelArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateModelArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Model* dst, uint32_t dst_count);
Model** J2C_CreateModelPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateModelPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Model** dst, uint32_t dst_count);
Bone* J2C_CreateBoneArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateBoneArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Bone* dst, uint32_t dst_count);
Bone** J2C_CreateBonePtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateBonePtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Bone** dst, uint32_t dst_count);
Skin* J2C_CreateSkinArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateSkinArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Skin* dst, uint32_t dst_count);
Skin** J2C_CreateSkinPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateSkinPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Skin** dst, uint32_t dst_count);
Node* J2C_CreateNodeArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateNodeArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Node* dst, uint32_t dst_count);
Node** J2C_CreateNodePtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateNodePtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Node** dst, uint32_t dst_count);
KeyFrame* J2C_CreateKeyFrameArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateKeyFrameArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, KeyFrame* dst, uint32_t dst_count);
KeyFrame** J2C_CreateKeyFramePtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateKeyFramePtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, KeyFrame** dst, uint32_t dst_count);
NodeAnimation* J2C_CreateNodeAnimationArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateNodeAnimationArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, NodeAnimation* dst, uint32_t dst_count);
NodeAnimation** J2C_CreateNodeAnimationPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateNodeAnimationPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, NodeAnimation** dst, uint32_t dst_count);
Animation* J2C_CreateAnimationArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateAnimationArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Animation* dst, uint32_t dst_count);
Animation** J2C_CreateAnimationPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateAnimationPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Animation** dst, uint32_t dst_count);
Buffer* J2C_CreateBufferArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateBufferArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Buffer* dst, uint32_t dst_count);
Buffer** J2C_CreateBufferPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateBufferPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Buffer** dst, uint32_t dst_count);
Scene* J2C_CreateSceneArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateSceneArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Scene* dst, uint32_t dst_count);
Scene** J2C_CreateScenePtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateScenePtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Scene** dst, uint32_t dst_count);
Options* J2C_CreateOptionsArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateOptionsArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Options* dst, uint32_t dst_count);
Options** J2C_CreateOptionsPtrArray(JNIEnv* env, TypeInfos* types, jobjectArray arr, uint32_t* out_count);
void J2C_CreateOptionsPtrArrayInPlace(JNIEnv* env, TypeInfos* types, jobjectArray arr, Options** dst, uint32_t dst_count);
} // jni
} // dmModelImporter
