#include "gui.h"

#include <string.h>
#include <new>
#include <algorithm>

#include <dlib/array.h>
#include <dlib/dstrings.h>
#include <dlib/index_pool.h>
#include <dlib/log.h>
#include <dlib/hash.h>
#include <dlib/hashtable.h>
#include <dlib/math.h>
#include <dlib/vmath.h>
#include <dlib/transform.h>
#include <dlib/message.h>
#include <dlib/profile.h>
#include <dlib/trig_lookup.h>

#include <script/script.h>
#include <script/lua_source_ddf.h>

#include <render/display_profiles.h>

#include "gui_private.h"
#include "gui_script.h"

namespace dmGui
{
    const uint16_t INVALID_INDEX = 0xffff;

    const uint32_t INITIAL_SCENE_COUNT = 32;

    const uint32_t LAYER_RANGE = 3;
    const uint32_t INDEX_RANGE = 9;
    const uint32_t CLIPPER_RANGE = 8;

    const uint32_t SUB_INDEX_SHIFT = 0;
    const uint32_t SUB_LAYER_SHIFT = INDEX_RANGE;
    const uint32_t CLIPPER_SHIFT = SUB_LAYER_SHIFT + LAYER_RANGE;
    const uint32_t INDEX_SHIFT = CLIPPER_SHIFT + CLIPPER_RANGE;
    const uint32_t LAYER_SHIFT = INDEX_SHIFT + INDEX_RANGE;

    inline void CalculateNodeTransformAndAlphaCached(HScene scene, InternalNode* n, const CalculateNodeTransformFlags flags, Matrix4& out_transform, float& out_opacity);
    static inline void UpdateTextureSetAnimData(HScene scene, InternalNode* n);

    static const char* SCRIPT_FUNCTION_NAMES[] =
    {
        "init",
        "final",
        "update",
        "on_message",
        "on_input",
        "on_reload"
    };

#define PROP(name, prop)\
    { dmHashString64(#name), prop, 0xff }, \
    { dmHashString64(#name ".x"), prop, 0 }, \
    { dmHashString64(#name ".y"), prop, 1 }, \
    { dmHashString64(#name ".z"), prop, 2 }, \
    { dmHashString64(#name ".w"), prop, 3 },

    struct PropDesc
    {
        dmhash_t m_Hash;
        Property m_Property;
        uint8_t  m_Component;
    };

    PropDesc g_Properties[] = {
            PROP(position, PROPERTY_POSITION )
            PROP(rotation, PROPERTY_ROTATION )
            PROP(scale, PROPERTY_SCALE )
            PROP(color, PROPERTY_COLOR )
            PROP(size, PROPERTY_SIZE )
            PROP(outline, PROPERTY_OUTLINE )
            PROP(shadow, PROPERTY_SHADOW )
            PROP(slice9, PROPERTY_SLICE9 )
            { dmHashString64("inner_radius"), PROPERTY_PIE_PARAMS, 0 },
            { dmHashString64("fill_angle"), PROPERTY_PIE_PARAMS, 1 },
    };
#undef PROP

    PropDesc g_PropTable[] = {
            { dmHashString64("position"), PROPERTY_POSITION, 0xff },
            { dmHashString64("rotation"), PROPERTY_ROTATION, 0xff },
            { dmHashString64("scale"), PROPERTY_SCALE, 0xff },
            { dmHashString64("color"), PROPERTY_COLOR, 0xff },
            { dmHashString64("size"), PROPERTY_SIZE, 0xff },
            { dmHashString64("outline"), PROPERTY_OUTLINE, 0xff },
            { dmHashString64("shadow"), PROPERTY_SHADOW, 0xff },
            { dmHashString64("slice"), PROPERTY_SLICE9, 0xff },
    };

    static PropDesc* GetPropertyDesc(dmhash_t property_hash)
    {
        int n_props = sizeof(g_Properties) / sizeof(g_Properties[0]);
        for (int i = 0; i < n_props; ++i) {
            PropDesc* pd = &g_Properties[i];
            if (pd->m_Hash == property_hash) {
                return pd;
            }
        }
        return 0;
    }

    TextMetrics::TextMetrics()
    {
        memset(this, 0, sizeof(TextMetrics));
    }

    InputAction::InputAction()
    {
        memset(this, 0, sizeof(InputAction));
    }

    InternalNode* GetNode(HScene scene, HNode node)
    {
        uint16_t version = (uint16_t) (node >> 16);
        uint16_t index = node & 0xffff;
        InternalNode* n = &scene->m_Nodes[index];
        assert(n->m_Version == version);
        assert(n->m_Index == index);
        return n;
    }

    HContext NewContext(const NewContextParams* params)
    {
        Context* context = new Context();
        context->m_LuaState = InitializeScript(params->m_ScriptContext);
        context->m_GetURLCallback = params->m_GetURLCallback;
        context->m_GetUserDataCallback = params->m_GetUserDataCallback;
        context->m_ResolvePathCallback = params->m_ResolvePathCallback;
        context->m_GetTextMetricsCallback = params->m_GetTextMetricsCallback;
        context->m_DefaultProjectWidth = params->m_DefaultProjectWidth;
        context->m_DefaultProjectHeight = params->m_DefaultProjectHeight;
        context->m_PhysicalWidth = params->m_PhysicalWidth;
        context->m_PhysicalHeight = params->m_PhysicalHeight;
        context->m_Dpi = params->m_Dpi;
        context->m_HidContext = params->m_HidContext;
        context->m_Scenes.SetCapacity(INITIAL_SCENE_COUNT);

        return context;
    }

    void DeleteContext(HContext context, dmScript::HContext script_context)
    {
        FinalizeScript(context->m_LuaState, script_context);
        delete context;
    }

    void GetPhysicalResolution(HContext context, uint32_t& width, uint32_t& height)
    {
        width = context->m_PhysicalWidth;
        height = context->m_PhysicalHeight;
    }

    uint32_t GetDisplayDpi(HContext context)
    {
        return context->m_Dpi;
    }

    void GetSceneResolution(HScene scene, uint32_t& width, uint32_t& height)
    {
        width = scene->m_Width;
        height = scene->m_Height;
    }

    void SetSceneResolution(HScene scene, uint32_t width, uint32_t height)
    {
        scene->m_Width = width;
        scene->m_Height = height;
        scene->m_ResChanged = 1;
    }

    void GetPhysicalResolution(HScene scene, uint32_t& width, uint32_t& height)
    {
        width = scene->m_Context->m_PhysicalWidth;
        height = scene->m_Context->m_PhysicalHeight;
    }

    uint32_t GetDisplayDpi(HScene scene)
    {
        return scene->m_Context->m_Dpi;
    }

    void SetPhysicalResolution(HContext context, uint32_t width, uint32_t height)
    {
        context->m_PhysicalWidth = width;
        context->m_PhysicalHeight = height;
        dmArray<HScene>& scenes = context->m_Scenes;
        uint32_t scene_count = scenes.Size();

        for (uint32_t i = 0; i < scene_count; ++i)
        {
            Scene* scene = scenes[i];
            scene->m_ResChanged = 1;
            if(scene->m_OnWindowResizeCallback)
            {
                scene->m_OnWindowResizeCallback(scene, width, height);
            }
        }
    }

    void GetDefaultResolution(HContext context, uint32_t& width, uint32_t& height)
    {
        width = context->m_DefaultProjectWidth;
        height = context->m_DefaultProjectHeight;
    }

    void SetDefaultResolution(HContext context, uint32_t width, uint32_t height)
    {
        context->m_DefaultProjectWidth = width;
        context->m_DefaultProjectHeight = height;
    }

    void* GetDisplayProfiles(HScene scene)
    {
        return scene->m_Context->m_DisplayProfiles;
    }

    AdjustReference GetSceneAdjustReference(HScene scene)
    {
        return scene->m_AdjustReference;
    }

    void SetDisplayProfiles(HContext context, void* display_profiles)
    {
        context->m_DisplayProfiles = display_profiles;
    }

    void SetDefaultFont(HContext context, void* font)
    {
        context->m_DefaultFont = font;
    }

    void SetSceneAdjustReference(HScene scene, AdjustReference adjust_reference)
    {
        scene->m_AdjustReference = adjust_reference;
    }

    void SetDefaultNewSceneParams(NewSceneParams* params)
    {
        memset(params, 0, sizeof(*params));
        // 512 is a hard cap since only 9 bits is available in the render key
        params->m_MaxNodes = 512;
        params->m_MaxAnimations = 128;
        params->m_MaxTextures = 32;
        params->m_MaxFonts = 4;
        // 8 is hard cap for the same reason as above
        params->m_MaxLayers = 8;
        params->m_AdjustReference = dmGui::ADJUST_REFERENCE_LEGACY;
    }

    static void ResetScene(HScene scene) {
        memset(scene, 0, sizeof(Scene));
        scene->m_InstanceReference = LUA_NOREF;
        scene->m_DataReference = LUA_NOREF;
    }

    HScene NewScene(HContext context, const NewSceneParams* params)
    {
        lua_State* L = context->m_LuaState;
        int top = lua_gettop(L);
        (void) top;

        Scene* scene = (Scene*)lua_newuserdata(L, sizeof(Scene));
        ResetScene(scene);

        dmArray<HScene>& scenes = context->m_Scenes;
        if (scenes.Full())
        {
            scenes.SetCapacity(scenes.Capacity() + INITIAL_SCENE_COUNT);
        }
        scenes.Push(scene);

        lua_pushvalue(L, -1);
        scene->m_InstanceReference = luaL_ref( L, LUA_REGISTRYINDEX );

        lua_newtable(L);
        scene->m_DataReference = luaL_ref(L, LUA_REGISTRYINDEX);

        scene->m_Context = context;
        scene->m_Script = 0x0;
        scene->m_Nodes.SetCapacity(params->m_MaxNodes);
        scene->m_Nodes.SetSize(params->m_MaxNodes);
        scene->m_NodePool.SetCapacity(params->m_MaxNodes);
        scene->m_Animations.SetCapacity(params->m_MaxAnimations);
        scene->m_Textures.SetCapacity(params->m_MaxTextures*2, params->m_MaxTextures);
        scene->m_DynamicTextures.SetCapacity(params->m_MaxTextures*2, params->m_MaxTextures);
        scene->m_Material = 0;
        scene->m_Fonts.SetCapacity(params->m_MaxFonts*2, params->m_MaxFonts);
        scene->m_Layers.SetCapacity(params->m_MaxLayers*2, params->m_MaxLayers);
        scene->m_Layouts.SetCapacity(1);
        scene->m_AdjustReference = params->m_AdjustReference;
        scene->m_DefaultFont = 0;
        scene->m_UserData = params->m_UserData;
        scene->m_RenderHead = INVALID_INDEX;
        scene->m_RenderTail = INVALID_INDEX;
        scene->m_NextVersionNumber = 0;
        scene->m_RenderOrder = 0;
        scene->m_Width = context->m_DefaultProjectWidth;
        scene->m_Height = context->m_DefaultProjectHeight;
        scene->m_FetchTextureSetAnimCallback = params->m_FetchTextureSetAnimCallback;
        scene->m_OnWindowResizeCallback = params->m_OnWindowResizeCallback;

        scene->m_Layers.Put(DEFAULT_LAYER, scene->m_NextLayerIndex++);

        ClearLayouts(scene);

        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            InternalNode* n = &scene->m_Nodes[i];
            memset(n, 0, sizeof(*n));
            n->m_Index = INVALID_INDEX;
        }

        luaL_getmetatable(L, GUI_SCRIPT_INSTANCE);
        lua_setmetatable(L, -2);

        lua_pop(L, 1);

        assert(top == lua_gettop(L));

        return scene;
    }

    void DeleteScene(HScene scene)
    {
        lua_State*L = scene->m_Context->m_LuaState;

        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            InternalNode* n = &scene->m_Nodes[i];
            if (n->m_Node.m_Text)
                free((void*) n->m_Node.m_Text);
        }

        luaL_unref(L, LUA_REGISTRYINDEX, scene->m_InstanceReference);
        luaL_unref(L, LUA_REGISTRYINDEX, scene->m_DataReference);

        dmArray<HScene>& scenes = scene->m_Context->m_Scenes;
        uint32_t scene_count = scenes.Size();
        for (uint32_t i = 0; i < scene_count; ++i)
        {
            if (scenes[i] == scene)
            {
                scenes.EraseSwap(i);
                break;
            }
        }

        scene->~Scene();

        ResetScene(scene);
    }

    void SetSceneUserData(HScene scene, void* user_data)
    {
        scene->m_UserData = user_data;
    }

    void* GetSceneUserData(HScene scene)
    {
        return scene->m_UserData;
    }

    Result AddTexture(HScene scene, const char* texture_name, void* texture, void* textureset)
    {
        if (scene->m_Textures.Full())
            return RESULT_OUT_OF_RESOURCES;

        uint64_t texture_hash = dmHashString64(texture_name);
        scene->m_Textures.Put(texture_hash, TextureInfo(texture, textureset));
        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            if (scene->m_Nodes[i].m_Node.m_TextureHash == texture_hash)
            {
                scene->m_Nodes[i].m_Node.m_Texture = texture;
                scene->m_Nodes[i].m_Node.m_TextureSet = textureset;
            }
        }
        return RESULT_OK;
    }

    void RemoveTexture(HScene scene, const char* texture_name)
    {
        uint64_t texture_name_hash = dmHashString64(texture_name);
        scene->m_Textures.Erase(texture_name_hash);
        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            Node& node = scene->m_Nodes[i].m_Node;
            if (node.m_TextureHash == texture_name_hash)
            {
                if(node.m_TextureSet)
                {
                    node.m_TextureSet = 0;
                    CancelNodeFlipbookAnim(scene, GetNodeHandle(&scene->m_Nodes[i]));
                }
                node.m_Texture = 0;
            }
        }
    }

    void ClearTextures(HScene scene)
    {
        scene->m_Textures.Clear();
        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            Node& node = scene->m_Nodes[i].m_Node;
            if(node.m_TextureSet)
            {
                node.m_TextureSet = 0;
                CancelNodeFlipbookAnim(scene, GetNodeHandle(&scene->m_Nodes[i]));
            }
            node.m_Texture = 0;
        }
    }

    Result NewDynamicTexture(HScene scene, const char* texture_name, uint32_t width, uint32_t height, dmImage::Type type, const void* buffer, uint32_t buffer_size)
    {
        dmhash_t texture_hash = dmHashString64(texture_name);
        uint32_t expected_buffer_size = width * height * dmImage::BytesPerPixel(type);
        if (buffer_size != expected_buffer_size) {
            dmLogError("Invalid image buffer size. Expected %d, got %d", expected_buffer_size, buffer_size);
            return RESULT_INVAL_ERROR;
        }

        if (DynamicTexture* t = scene->m_DynamicTextures.Get(texture_hash)) {
            if (t->m_Deleted) {
                t->m_Deleted = 0;
                return RESULT_OK;
            } else {
                return RESULT_TEXTURE_ALREADY_EXISTS;
            }
        }

        if (scene->m_DynamicTextures.Full()) {
            return RESULT_OUT_OF_RESOURCES;
        }

        DynamicTexture t(0);
        t.m_Buffer = malloc(buffer_size);
        memcpy(t.m_Buffer, buffer, buffer_size);
        t.m_Width = width;
        t.m_Height = height;
        t.m_Type = type;

        scene->m_DynamicTextures.Put(texture_hash, t);

        return RESULT_OK;
    }

    Result DeleteDynamicTexture(HScene scene, const char* texture_name)
    {
        dmhash_t texture_hash = dmHashString64(texture_name);
        DynamicTexture* t = scene->m_DynamicTextures.Get(texture_hash);

        if (!t) {
            return RESULT_RESOURCE_NOT_FOUND;
        }
        t->m_Deleted = 1U;

        if (t->m_Buffer) {
            free(t->m_Buffer);
            t->m_Buffer = 0;
        }

        return RESULT_OK;
    }

    Result SetDynamicTextureData(HScene scene, const char* texture_name, uint32_t width, uint32_t height, dmImage::Type type, const void* buffer, uint32_t buffer_size)
    {
        dmhash_t texture_hash = dmHashString64(texture_name);
        DynamicTexture*t = scene->m_DynamicTextures.Get(texture_hash);

        if (!t) {
            return RESULT_RESOURCE_NOT_FOUND;
        }

        if (t->m_Deleted) {
            dmLogError("Can't set texture data for deleted texture");
            return RESULT_INVAL_ERROR;
        }

        if (t->m_Buffer) {
            free(t->m_Buffer);
            t->m_Buffer = 0;
        }

        t->m_Buffer = malloc(buffer_size);
        memcpy(t->m_Buffer, buffer, buffer_size);
        t->m_Width = width;
        t->m_Height = height;
        t->m_Type = type;

        return RESULT_OK;
    }

    Result AddFont(HScene scene, const char* font_name, void* font)
    {
        if (scene->m_Fonts.Full())
            return RESULT_OUT_OF_RESOURCES;

        if (!scene->m_DefaultFont)
            scene->m_DefaultFont = font;

        uint64_t font_hash = dmHashString64(font_name);
        scene->m_Fonts.Put(font_hash, font);
        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            if (scene->m_Nodes[i].m_Node.m_FontHash == font_hash)
                scene->m_Nodes[i].m_Node.m_Font = font;
        }
        return RESULT_OK;
    }

    void RemoveFont(HScene scene, const char* font_name)
    {
        uint64_t font_hash = dmHashString64(font_name);
        scene->m_Fonts.Erase(font_hash);
        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            if (scene->m_Nodes[i].m_Node.m_FontHash == font_hash)
                scene->m_Nodes[i].m_Node.m_Font = 0;
        }
    }

    void ClearFonts(HScene scene)
    {
        scene->m_Fonts.Clear();
        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            scene->m_Nodes[i].m_Node.m_Font = 0;
        }
    }

    void SetMaterial(HScene scene, void* material)
    {
        scene->m_Material = material;
    }

    void* GetMaterial(HScene scene)
    {
        return scene->m_Material;
    }

    Result AddLayer(HScene scene, const char* layer_name)
    {
        if (scene->m_Layers.Full())
        {
            dmLogError("Max number of layers exhausted (max %d total)", scene->m_Layers.Capacity());
            return RESULT_OUT_OF_RESOURCES;
        }

        uint64_t layer_hash = dmHashString64(layer_name);
        uint16_t index = scene->m_NextLayerIndex++;
        scene->m_Layers.Put(layer_hash, index);
        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            if (scene->m_Nodes[i].m_Node.m_LayerHash == layer_hash)
                scene->m_Nodes[i].m_Node.m_LayerIndex = index;
        }
        return RESULT_OK;
    }

    void AllocateLayouts(HScene scene, size_t node_count, size_t layouts_count)
    {
        layouts_count++;
        size_t capacity = dmMath::Max((uint32_t) layouts_count, scene->m_Layouts.Capacity());
        scene->m_Layouts.SetCapacity(capacity);
        scene->m_LayoutsNodeDescs.SetCapacity(layouts_count*node_count);
        scene->m_LayoutsNodeDescs.SetSize(0);
    }

    void ClearLayouts(HScene scene)
    {
        scene->m_LayoutId = DEFAULT_LAYOUT;
        scene->m_Layouts.SetSize(0);
        scene->m_Layouts.Push(DEFAULT_LAYOUT);
        scene->m_LayoutsNodeDescs.SetCapacity(0);
    }

    Result AddLayout(HScene scene, const char* layout_id)
    {
        if (scene->m_Layouts.Full())
        {
            dmLogError("Could not add layout to scene since the buffer is full (%d).", scene->m_Layouts.Capacity());
            return RESULT_OUT_OF_RESOURCES;
        }
        uint64_t layout_hash = dmHashString64(layout_id);
        scene->m_Layouts.Push(layout_hash);
        return RESULT_OK;
    }

    dmhash_t GetLayout(const HScene scene)
    {
        return scene->m_LayoutId;
    }

    uint16_t GetLayoutCount(const HScene scene)
    {
        return (uint16_t)scene->m_Layouts.Size();
    }

    Result GetLayoutId(const HScene scene, uint16_t layout_index, dmhash_t& layout_id_out)
    {
        if(layout_index >= (uint16_t)scene->m_Layouts.Size())
        {
            return RESULT_RESOURCE_NOT_FOUND;
        }
        layout_id_out = scene->m_Layouts[layout_index];
        return RESULT_OK;
    }

    uint16_t GetLayoutIndex(const HScene scene, dmhash_t layout_id)
    {
        uint32_t i;
        for(i = 0; i < scene->m_Layouts.Size(); ++i) {
            if(layout_id == scene->m_Layouts[i])
                break;
        }
        if(i == scene->m_Layouts.Size())
        {
            const char *str = (const char*) dmHashReverse64(layout_id, 0x0);
            dmLogError("Could not get index for layout %s", (str == 0x0 ? "<unknown>" : str));
            return 0;
        }
        return i;
    }

    Result SetNodeLayoutDesc(const HScene scene, HNode node, const void *desc, uint16_t layout_index_start, uint16_t layout_index_end)
    {
        InternalNode* n = GetNode(scene, node);
        void **table = n->m_Node.m_NodeDescTable;
        if(table == 0)
        {
            if(scene->m_LayoutsNodeDescs.Full())
                return RESULT_OUT_OF_RESOURCES;
            size_t table_index = scene->m_LayoutsNodeDescs.Size();
            scene->m_LayoutsNodeDescs.SetSize(table_index + scene->m_Layouts.Size());
            n->m_Node.m_NodeDescTable = table = &scene->m_LayoutsNodeDescs[table_index];
        }
        assert(layout_index_end < scene->m_Layouts.Size());
        for(uint16_t i = layout_index_start; i <= layout_index_end; ++i)
            table[i] = (void*) desc;
        return RESULT_OK;
    }

    Result SetLayout(const HScene scene, dmhash_t layout_id, SetNodeCallback set_node_callback)
    {
        scene->m_LayoutId = layout_id;
        uint16_t index = GetLayoutIndex(scene, layout_id);
        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            InternalNode *n = &scene->m_Nodes[i];
            if(!n->m_Node.m_NodeDescTable)
                continue;
            set_node_callback(scene, GetNodeHandle(n), n->m_Node.m_NodeDescTable[index]);
            n->m_Node.m_DirtyLocal = 1;
        }
        return RESULT_OK;
    }

    HNode GetNodeHandle(InternalNode* node)
    {
        return ((uint32_t) node->m_Version) << 16 | node->m_Index;
    }

    Vector4 CalculateReferenceScale(HScene scene, InternalNode* node)
    {
        float scale_x = 1.0f;
        float scale_y = 1.0f;

        if (scene->m_AdjustReference == ADJUST_REFERENCE_LEGACY || node == 0x0 || node->m_ParentIndex == INVALID_INDEX) {
            scale_x = (float) scene->m_Context->m_PhysicalWidth / (float) scene->m_Width;
            scale_y = (float) scene->m_Context->m_PhysicalHeight / (float) scene->m_Height;
        } else {
            Vector4 adjust_scale = scene->m_Nodes[node->m_ParentIndex].m_Node.m_LocalAdjustScale;
            scale_x = adjust_scale.getX();
            scale_y = adjust_scale.getY();
        }
        return Vector4(scale_x, scale_y, 1.0f, 1.0f);
    }

    struct UpdateDynamicTexturesParams
    {
        UpdateDynamicTexturesParams()
        {
            memset(this, 0, sizeof(*this));
        }
        HScene m_Scene;
        void*  m_Context;
        const RenderSceneParams* m_Params;
        int    m_NewCount;
    };

    static void UpdateDynamicTextures(UpdateDynamicTexturesParams* params, const dmhash_t* key, DynamicTexture* texture)
    {
        dmGui::Scene* const scene = params->m_Scene;
        void* const context = params->m_Context;

        if (texture->m_Deleted) {
            if (texture->m_Handle) {
                // handle might be null if the texture is created/destroyed in the same frame
                params->m_Params->m_DeleteTexture(scene, texture->m_Handle, context);
            }
            if (scene->m_DeletedDynamicTextures.Full()) {
                scene->m_DeletedDynamicTextures.OffsetCapacity(16);
            }
            scene->m_DeletedDynamicTextures.Push(*key);
        } else {
            if (!texture->m_Handle && texture->m_Buffer) {
                texture->m_Handle = params->m_Params->m_NewTexture(scene, texture->m_Width, texture->m_Height, texture->m_Type, texture->m_Buffer, context);
                params->m_NewCount++;
                free(texture->m_Buffer);
                texture->m_Buffer = 0;
            } else if (texture->m_Handle && texture->m_Buffer) {
                params->m_Params->m_SetTextureData(scene, texture->m_Handle, texture->m_Width, texture->m_Height, texture->m_Type, texture->m_Buffer, context);
                free(texture->m_Buffer);
                texture->m_Buffer = 0;
            }
        }
    }

    static void UpdateDynamicTextures(HScene scene, const RenderSceneParams& params, void* context)
    {
        UpdateDynamicTexturesParams p;
        p.m_Scene = scene;
        p.m_Context = context;
        p.m_Params = &params;
        scene->m_DeletedDynamicTextures.SetSize(0);
        scene->m_DynamicTextures.Iterate(UpdateDynamicTextures, &p);

        if (p.m_NewCount > 0) {
            dmArray<InternalNode>& nodes = scene->m_Nodes;
            uint32_t n = nodes.Size();
            for (uint32_t j = 0; j < n; ++j) {
                Node& node = nodes[j].m_Node;
                if (DynamicTexture* texture = scene->m_DynamicTextures.Get(node.m_TextureHash)) {
                    node.m_Texture = texture->m_Handle;
                }
            }
        }
    }

    static void DeferredDeleteDynamicTextures(HScene scene, const RenderSceneParams& params, void* context)
    {
        for (uint32_t i = 0; i < scene->m_DeletedDynamicTextures.Size(); ++i) {
            dmhash_t texture_hash = scene->m_DeletedDynamicTextures[i];
            scene->m_DynamicTextures.Erase(texture_hash);

            dmArray<InternalNode>& nodes = scene->m_Nodes;
            uint32_t n = nodes.Size();
            for (uint32_t j = 0; j < n; ++j) {
                Node& node = nodes[j].m_Node;
                if (node.m_TextureHash == texture_hash) {
                    node.m_Texture = 0;
                    // Do not break here. Texture may be used multiple times.
                }
            }
        }
    }

    static uint16_t GetLayerIndex(HScene scene, InternalNode* node)
    {
        if (node->m_Node.m_LayerHash == DEFAULT_LAYER && node->m_ParentIndex != INVALID_INDEX)
        {
            return GetLayerIndex(scene, &scene->m_Nodes[node->m_ParentIndex & 0xffff]);
        } else {
            return node->m_Node.m_LayerIndex;
        }
    }

    struct RenderEntrySortPred
    {
        HScene m_Scene;
        RenderEntrySortPred(HScene scene) : m_Scene(scene) {}

        bool operator ()(const RenderEntry& a, const RenderEntry& b) const
        {
            return a.m_RenderKey < b.m_RenderKey;
        }
    };

    struct ScopeContext {
        ScopeContext() {
            memset(this, 0, sizeof(*this));
            m_NonInvClipperHead = INVALID_INDEX;
            m_NonInvClipperTail = INVALID_INDEX;
        }
        uint16_t m_NonInvClipperHead;
        uint16_t m_NonInvClipperTail;
        uint16_t m_BitFieldOffset;
        uint16_t m_ClipperCount;
        uint16_t m_InvClipperCount;
    };

    static uint16_t CalcBitRange(uint16_t val) {
        uint16_t bit_range = 0;
        while (val != 0) {
            bit_range++;
            val >>= 1;
        }
        return bit_range;
    }

    static uint16_t CalcMask(uint16_t bits) {
        return (1 << bits) - 1;
    }

    static uint32_t CalcRenderKey(uint16_t layer, uint16_t index, uint8_t inv_clipper_id, uint16_t sub_layer, uint16_t sub_index) {
        return (layer << LAYER_SHIFT)
                | (index << INDEX_SHIFT)
                | (inv_clipper_id << CLIPPER_SHIFT)
                | (sub_layer << SUB_LAYER_SHIFT)
                | (sub_index << SUB_INDEX_SHIFT);
    }

    static void UpdateScope(InternalNode* node, StencilScope& scope, StencilScope& child_scope, const StencilScope* parent_scope, uint16_t index, uint16_t non_inv_clipper_count, uint16_t inv_clipper_count, uint16_t bit_field_offset) {
        int bit_range = CalcBitRange(non_inv_clipper_count);
        // state used for drawing the clipper
        scope.m_WriteMask = 0xff;
        scope.m_TestMask = 0;
        if (parent_scope != 0x0) {
            scope.m_TestMask = parent_scope->m_TestMask;
        }
        bool inverted = node->m_Node.m_ClippingInverted;
        if (!inverted) {
            scope.m_RefVal = (index + 1) << bit_field_offset;
            if (parent_scope != 0x0) {
                scope.m_RefVal |= parent_scope->m_RefVal;
            }
        } else {
            scope.m_RefVal = 1 << (7 - index);
            if (parent_scope != 0x0) {
                scope.m_RefVal |= (CalcMask(bit_field_offset) & parent_scope->m_RefVal);
            }
        }
        if (inverted && node->m_Node.m_ClippingVisible) {
            scope.m_ColorMask = 0xf;
        } else {
            scope.m_ColorMask = 0;
        }
        // state used for drawing any sub non-clippers
        child_scope.m_WriteMask = 0;
        if (!inverted) {
            child_scope.m_RefVal = scope.m_RefVal;
            child_scope.m_TestMask = (CalcMask(bit_range) << bit_field_offset) | scope.m_TestMask;
        } else {
            child_scope.m_RefVal = 0;
            child_scope.m_TestMask = scope.m_RefVal;
            if (parent_scope != 0x0) {
                child_scope.m_RefVal |= parent_scope->m_RefVal;
                child_scope.m_TestMask |= parent_scope->m_TestMask;
            }
        }
        child_scope.m_ColorMask = 0xf;
        // Check for overflow
        int inverted_count = 0;
        if (inverted) {
            inverted_count = index + 1;
        } else {
            inverted_count = inv_clipper_count;
        }
        int bit_count = inverted_count + bit_field_offset + bit_range;
        if (bit_count > 8) {
            dmLogWarning("Stencil buffer exceeded, clipping will not work as expected.");
        }
    }

    static void CollectInvClippers(HScene scene, uint16_t start_index, dmArray<InternalClippingNode>& clippers, ScopeContext& scope_context, uint16_t parent_index) {
        uint32_t index = start_index;
        InternalClippingNode* parent = 0x0;
        if (parent_index != INVALID_INDEX) {
            parent = &clippers[parent_index];
        }
        while (index != INVALID_INDEX)
        {
            InternalNode* n = &scene->m_Nodes[index];
            if (n->m_Node.m_Enabled)
            {
                switch (n->m_Node.m_ClippingMode) {
                case CLIPPING_MODE_STENCIL:
                    {
                        uint32_t clipper_index = clippers.Size();
                        clippers.SetSize(clipper_index + 1);
                        InternalClippingNode& clipper = clippers.Back();
                        clipper.m_NodeIndex = index;
                        clipper.m_ParentIndex = parent_index;
                        clipper.m_NextNonInvIndex = INVALID_INDEX;
                        clipper.m_VisibleRenderKey = ~0;
                        n->m_ClipperIndex = clipper_index;
                        if (n->m_Node.m_ClippingInverted) {
                            StencilScope* parent_scope = 0x0;
                            if (parent != 0x0) {
                                parent_scope = &parent->m_ChildScope;
                            }
                            UpdateScope(n, clipper.m_Scope, clipper.m_ChildScope, parent_scope, scope_context.m_InvClipperCount, 0, 0, scope_context.m_BitFieldOffset);
                            ++scope_context.m_InvClipperCount;
                            CollectInvClippers(scene, n->m_ChildHead, clippers, scope_context, clipper_index);
                        } else {
                            // append to linked list
                            uint16_t* pointer = &scope_context.m_NonInvClipperHead;
                            if (*pointer != INVALID_INDEX) {
                                pointer = &clippers[scope_context.m_NonInvClipperTail].m_NextNonInvIndex;
                            }
                            *pointer = clipper_index;
                            scope_context.m_NonInvClipperTail = clipper_index;
                            ++scope_context.m_ClipperCount;
                        }
                    }
                    break;
                case CLIPPING_MODE_NONE:
                    n->m_ClipperIndex = parent_index;
                    CollectInvClippers(scene, n->m_ChildHead, clippers, scope_context, parent_index);
                    break;
                }
            }
            index = n->m_NextIndex;
        }
    }

    static void CollectClippers(HScene scene, uint16_t start_index, uint16_t bit_field_offset, uint16_t inv_clipper_count, dmArray<InternalClippingNode>& clippers, uint16_t parent_index)
    {
        ScopeContext context;
        context.m_BitFieldOffset = bit_field_offset;
        context.m_InvClipperCount = inv_clipper_count;
        CollectInvClippers(scene, start_index, clippers, context, parent_index);
        uint16_t non_inv_clipper_index = context.m_NonInvClipperHead;
        uint16_t index = 0;
        while (non_inv_clipper_index != INVALID_INDEX) {
            InternalClippingNode* non_inv_clipper = &clippers[non_inv_clipper_index];
            StencilScope* parent_scope = 0x0;
            if (non_inv_clipper->m_ParentIndex != INVALID_INDEX) {
                parent_scope = &clippers[non_inv_clipper->m_ParentIndex].m_ChildScope;
            }
            InternalNode* node = &scene->m_Nodes[non_inv_clipper->m_NodeIndex];
            UpdateScope(node, non_inv_clipper->m_Scope, non_inv_clipper->m_ChildScope, parent_scope, index, context.m_ClipperCount, context.m_InvClipperCount, bit_field_offset);
            uint16_t bit_range = CalcBitRange(context.m_ClipperCount);
            CollectClippers(scene, node->m_ChildHead, context.m_BitFieldOffset + bit_range, context.m_InvClipperCount, clippers, non_inv_clipper_index);
            non_inv_clipper_index = non_inv_clipper->m_NextNonInvIndex;
            ++index;
        }
    }

    struct Scope {
        Scope(int layer, int index) : m_Index(1), m_RootLayer(layer), m_RootIndex(index) {}

        uint16_t m_Index;
        uint16_t m_RootLayer;
        uint16_t m_RootIndex;
    };

    static void Increment(Scope* scope) {
        scope->m_Index = dmMath::Min(255, scope->m_Index + 1);
    }

    static uint32_t CalcRenderKey(Scope* scope, uint16_t layer, uint16_t index) {
        if (scope != 0x0) {
            return CalcRenderKey(scope->m_RootLayer, scope->m_RootIndex, scope->m_Index, layer, index);
        } else {
            return CalcRenderKey(layer, index, 0, 0, 0);
        }
    }

    static uint16_t CollectRenderEntries(HScene scene, uint16_t start_index, uint16_t order, Scope* scope, dmArray<InternalClippingNode>& clippers, dmArray<RenderEntry>& render_entries) {
        uint16_t index = start_index;
        while (index != INVALID_INDEX) {
            InternalNode* n = &scene->m_Nodes[index];
            if (n->m_Node.m_Enabled) {
                HNode node = GetNodeHandle(n);
                uint16_t layer = GetLayerIndex(scene, n);
                if (n->m_ClipperIndex != INVALID_INDEX) {
                    InternalClippingNode& clipper = clippers[n->m_ClipperIndex];
                    if (clipper.m_NodeIndex == index) {
                        bool root_clipper = scope == 0x0;
                        Scope tmp_scope(0, order);
                        Scope* current_scope = scope;
                        if (current_scope == 0x0) {
                            current_scope = &tmp_scope;
                            ++order;
                        } else {
                            Increment(current_scope);
                        }
                        uint32_t clipping_key = CalcRenderKey(current_scope, 0, 0);
                        uint32_t render_key = CalcRenderKey(current_scope, layer, 1);
                        CollectRenderEntries(scene, n->m_ChildHead, 2, current_scope, clippers, render_entries);
                        if (layer > 0) {
                            render_key = CalcRenderKey(current_scope, layer, 1);
                        }
                        clipper.m_VisibleRenderKey = render_key;
                        RenderEntry entry;
                        entry.m_Node = node;
                        entry.m_RenderKey = clipping_key;
                        render_entries.Push(entry);
                        if (n->m_Node.m_ClippingVisible) {
                            entry.m_RenderKey = render_key;
                            render_entries.Push(entry);
                        }
                        if (!root_clipper) {
                            Increment(current_scope);
                        }
                        index = n->m_NextIndex;
                        continue;
                    }
                }
                RenderEntry entry;
                entry.m_Node = node;
                entry.m_RenderKey = CalcRenderKey(scope, layer, order++);
                render_entries.Push(entry);
                order = CollectRenderEntries(scene, n->m_ChildHead, order, scope, clippers, render_entries);
            }
            index = n->m_NextIndex;
        }
        return order;
    }

    static void CollectNodes(HScene scene, dmArray<InternalClippingNode>& clippers, dmArray<RenderEntry>& render_entries)
    {
        CollectClippers(scene, scene->m_RenderHead, 0, 0, clippers, INVALID_INDEX);
        CollectRenderEntries(scene, scene->m_RenderHead, 0, 0x0, clippers, render_entries);
    }

    void RenderScene(HScene scene, const RenderSceneParams& params, void* context)
    {
        Context* c = scene->m_Context;

        UpdateDynamicTextures(scene, params, context);
        DeferredDeleteDynamicTextures(scene, params, context);

        c->m_RenderNodes.SetSize(0);
        c->m_RenderTransforms.SetSize(0);
        c->m_RenderOpacities.SetSize(0);
        c->m_StencilClippingNodes.SetSize(0);
        c->m_StencilScopes.SetSize(0);
        c->m_StencilScopeIndices.SetSize(0);
        uint32_t capacity = scene->m_NodePool.Size() * 2;
        if (capacity > c->m_RenderNodes.Capacity())
        {
            c->m_RenderNodes.SetCapacity(capacity);
            c->m_RenderTransforms.SetCapacity(capacity);
            c->m_RenderOpacities.SetCapacity(capacity);
            c->m_SceneTraversalCache.m_Data.SetCapacity(capacity);
            c->m_SceneTraversalCache.m_Data.SetSize(capacity);
            c->m_StencilClippingNodes.SetCapacity(capacity);
            c->m_StencilScopes.SetCapacity(capacity);
            c->m_StencilScopeIndices.SetCapacity(capacity);
        }

        c->m_SceneTraversalCache.m_NodeIndex = 0;
        if(++c->m_SceneTraversalCache.m_Version == INVALID_INDEX)
        {
            c->m_SceneTraversalCache.m_Version = 0;
        }

        Matrix4 node_transform;
        CollectNodes(scene, c->m_StencilClippingNodes, c->m_RenderNodes);
        uint32_t node_count = c->m_RenderNodes.Size();
        std::sort(c->m_RenderNodes.Begin(), c->m_RenderNodes.End(), RenderEntrySortPred(scene));
        Matrix4 transform;

        for (uint32_t i = 0; i < node_count; ++i)
        {
            const RenderEntry& entry = c->m_RenderNodes[i];
            uint16_t index = entry.m_Node & 0xffff;
            InternalNode* n = &scene->m_Nodes[index];
            float opacity = 1.0f;
            CalculateNodeTransformAndAlphaCached(scene, n, CalculateNodeTransformFlags(CALCULATE_NODE_INCLUDE_SIZE | CALCULATE_NODE_RESET_PIVOT), transform, opacity);
            c->m_RenderTransforms.Push(transform);
            c->m_RenderOpacities.Push(opacity);
            if (n->m_ClipperIndex != INVALID_INDEX) {
                InternalClippingNode* clipper = &c->m_StencilClippingNodes[n->m_ClipperIndex];
                if (clipper->m_NodeIndex == index) {
                    if (clipper->m_VisibleRenderKey == entry.m_RenderKey) {
                        StencilScope* scope = 0x0;
                        if (clipper->m_ParentIndex != INVALID_INDEX) {
                            scope = &c->m_StencilClippingNodes[clipper->m_ParentIndex].m_ChildScope;
                        }
                        c->m_StencilScopes.Push(scope);
                    } else {
                        c->m_StencilScopes.Push(&clipper->m_Scope);
                    }
                } else {
                    c->m_StencilScopes.Push(&clipper->m_ChildScope);
                }
            } else {
                c->m_StencilScopes.Push(0x0);
            }
            UpdateTextureSetAnimData(scene, n);
        }

        scene->m_ResChanged = 0;
        params.m_RenderNodes(scene, c->m_RenderNodes.Begin(), c->m_RenderTransforms.Begin(), c->m_RenderOpacities.Begin(), (const StencilScope**)c->m_StencilScopes.Begin(), c->m_RenderNodes.Size(), context);
    }

    void RenderScene(HScene scene, RenderNodes render_nodes, void* context)
    {
        RenderSceneParams p;
        p.m_RenderNodes = render_nodes;
        RenderScene(scene, p, context);
    }

    static bool IsNodeEnabledRecursive(HScene scene, uint16_t node_index)
    {
        InternalNode* node = &scene->m_Nodes[node_index];
        if (node->m_Node.m_Enabled && node->m_ParentIndex != INVALID_INDEX)
        {
            return IsNodeEnabledRecursive(scene, node->m_ParentIndex);
        }
        else
        {
            return node->m_Node.m_Enabled;
        }
    }

    void UpdateAnimations(HScene scene, float dt)
    {
        dmArray<Animation>* animations = &scene->m_Animations;
        uint32_t n = animations->Size();

        uint32_t active_animations = 0;

        for (uint32_t i = 0; i < n; ++i)
        {
            Animation* anim = &(*animations)[i];

            if (anim->m_Elapsed >= anim->m_Duration || anim->m_Cancelled)
            {
                continue;
            }
            if (!IsNodeEnabledRecursive(scene, anim->m_Node & 0xffff))
            {
                continue;
            }
            ++active_animations;

            if (anim->m_Delay < dt)
            {
                if (anim->m_FirstUpdate)
                {
                    anim->m_From = *anim->m_Value;
                    anim->m_FirstUpdate = 0;
                    // Compensate Elapsed with Delay underflow
                    anim->m_Elapsed = -anim->m_Delay;
                }

                // NOTE: We add dt to elapsed before we calculate t.
                // Example: 60 updates with dt=1/60.0 should result in a complete animation
                anim->m_Elapsed += dt;
                // Clamp elapsed to duration if we are closer than half a time step
                anim->m_Elapsed = dmMath::Select(anim->m_Elapsed + dt * 0.5f - anim->m_Duration, anim->m_Duration, anim->m_Elapsed);
                // Calculate normalized time if elapsed has not yet reached duration, otherwise it's set to 1 (animation complete)
                float t = dmMath::Select(anim->m_Duration - anim->m_Elapsed, anim->m_Elapsed / anim->m_Duration, 1.0f);
                float t2 = t;
                if (anim->m_Playback == PLAYBACK_ONCE_BACKWARD || anim->m_Playback == PLAYBACK_LOOP_BACKWARD || anim->m_Backwards) {
                    t2 = 1.0f - t;
                }
                if (anim->m_Playback == PLAYBACK_ONCE_PINGPONG || anim->m_Playback == PLAYBACK_LOOP_PINGPONG) {
                    t2 *= 2.0f;
                    if (t2 > 1.0f) {
                        t2 = 2.0f - t2;
                    }
                }

                float x = dmEasing::GetValue(anim->m_Easing, t2);

                *anim->m_Value = anim->m_From * (1-x) + anim->m_To * x;
                // Flag local transform as dirty for the node
                scene->m_Nodes[anim->m_Node & 0xffff].m_Node.m_DirtyLocal = 1;

                // Animation complete, see above
                if (t >= 1.0f)
                {
                    bool looping = anim->m_Playback == PLAYBACK_LOOP_FORWARD || anim->m_Playback == PLAYBACK_LOOP_BACKWARD || anim->m_Playback == PLAYBACK_LOOP_PINGPONG;
                    if (looping) {
                        anim->m_Elapsed = anim->m_Elapsed - anim->m_Duration;
                        if (anim->m_Playback == PLAYBACK_LOOP_PINGPONG) {
                            anim->m_Backwards ^= 1;
                        }
                    } else {
                        if (!anim->m_AnimationCompleteCalled && anim->m_AnimationComplete)
                        {
                            // NOTE: Very important to set m_AnimationCompleteCalled to 1
                            // before invoking the call-back. The call-back could potentially
                            // start a new animation that could reuse the same animation slot.
                            anim->m_AnimationCompleteCalled = 1;
                            anim->m_AnimationComplete(scene, anim->m_Node, anim->m_Userdata1, anim->m_Userdata2);

                            if (anim->m_Easing.release_callback != 0x0)
                            {
                                anim->m_Easing.release_callback(&anim->m_Easing);
                            }
                        }
                    }
                }
            }
            else
            {
                anim->m_Delay -= dt;
            }
        }

        n = animations->Size();
        for (uint32_t i = 0; i < n; ++i)
        {
            Animation* anim = &(*animations)[i];

            if (anim->m_Elapsed >= anim->m_Duration || anim->m_Cancelled)
            {
                animations->EraseSwap(i);
                i--;
                n--;
                continue;
            }
        }

        DM_COUNTER("Gui.Animations", n);
        DM_COUNTER("Gui.ActiveAnimations", active_animations);
    }

    struct InputArgs
    {
        const InputAction* m_Action;
        bool m_Consumed;
    };

    Result RunScript(HScene scene, ScriptFunction script_function, int custom_ref, void* args)
    {
        if (scene->m_Script == 0x0)
            return RESULT_OK;

        lua_State* L = scene->m_Context->m_LuaState;
        int top = lua_gettop(L);
        (void)top;

        int lua_ref = scene->m_Script->m_FunctionReferences[script_function];
        if (custom_ref != LUA_NOREF) {
            lua_ref = custom_ref;
        }

        if (lua_ref != LUA_NOREF)
        {
            lua_rawgeti(L, LUA_REGISTRYINDEX, scene->m_InstanceReference);
            dmScript::SetInstance(L);

            lua_rawgeti(L, LUA_REGISTRYINDEX, lua_ref);
            assert(lua_isfunction(L, -1));
            lua_rawgeti(L, LUA_REGISTRYINDEX, scene->m_InstanceReference);

            uint32_t arg_count = 1;
            uint32_t ret_count = 0;

            switch (script_function)
            {
            case SCRIPT_FUNCTION_UPDATE:
                {
                    float* dt = (float*)args;
                    lua_pushnumber(L, (lua_Number) *dt);
                    arg_count += 1;
                }
                break;
            case SCRIPT_FUNCTION_ONMESSAGE:
                {
                    dmMessage::Message* message = (dmMessage::Message*)args;
                    dmScript::PushHash(L, message->m_Id);

                    if (message->m_Descriptor)
                    {
                        dmScript::PushDDF(L, (dmDDF::Descriptor*)message->m_Descriptor, (const char*) message->m_Data, true);
                    }
                    else if (message->m_DataSize > 0)
                    {
                        dmScript::PushTable(L, (const char*) message->m_Data);
                    }
                    else
                    {
                        lua_newtable(L);
                    }

                    dmScript::PushURL(L, message->m_Sender);
                    arg_count += 3;
                }
                break;
            case SCRIPT_FUNCTION_ONINPUT:
                {
                    InputArgs* input_args = (InputArgs*)args;
                    const InputAction* ia = input_args->m_Action;
                    // 0 is reserved for mouse movement
                    if (ia->m_ActionId != 0)
                    {
                        dmScript::PushHash(L, ia->m_ActionId);
                    }
                    else
                    {
                        lua_pushnil(L);
                    }

                    lua_newtable(L);

                    if (ia->m_ActionId != 0)
                    {
                        lua_pushstring(L, "value");
                        lua_pushnumber(L, ia->m_Value);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "pressed");
                        lua_pushboolean(L, ia->m_Pressed);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "released");
                        lua_pushboolean(L, ia->m_Released);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "repeated");
                        lua_pushboolean(L, ia->m_Repeated);
                        lua_rawset(L, -3);
                    }

                    if (ia->m_PositionSet)
                    {
                        lua_pushstring(L, "x");
                        lua_pushnumber(L, ia->m_X);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "y");
                        lua_pushnumber(L, ia->m_Y);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "dx");
                        lua_pushnumber(L, ia->m_DX);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "dy");
                        lua_pushnumber(L, ia->m_DY);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "screen_x");
                        lua_pushnumber(L, ia->m_ScreenX);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "screen_y");
                        lua_pushnumber(L, ia->m_ScreenY);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "screen_dx");
                        lua_pushnumber(L, ia->m_ScreenDX);
                        lua_rawset(L, -3);

                        lua_pushstring(L, "screen_dy");
                        lua_pushnumber(L, ia->m_ScreenDY);
                        lua_rawset(L, -3);
                    }

                    if (ia->m_TouchCount > 0)
                    {
                        int tc = ia->m_TouchCount;
                        lua_pushliteral(L, "touch");
                        lua_createtable(L, tc, 0);
                        for (int i = 0; i < tc; ++i)
                        {
                            const dmHID::Touch& t = ia->m_Touch[i];

                            lua_pushinteger(L, (lua_Integer) (i+1));
                            lua_createtable(L, 0, 6);

                            lua_pushliteral(L, "tap_count");
                            lua_pushinteger(L, (lua_Integer) t.m_TapCount);
                            lua_settable(L, -3);

                            lua_pushliteral(L, "pressed");
                            lua_pushboolean(L, t.m_Phase == dmHID::PHASE_BEGAN);
                            lua_settable(L, -3);

                            lua_pushliteral(L, "released");
                            lua_pushboolean(L, t.m_Phase == dmHID::PHASE_ENDED || t.m_Phase == dmHID::PHASE_CANCELLED);
                            lua_settable(L, -3);

                            lua_pushliteral(L, "x");
                            lua_pushinteger(L, (lua_Integer) t.m_X);
                            lua_settable(L, -3);

                            lua_pushliteral(L, "y");
                            lua_pushinteger(L, (lua_Integer) t.m_Y);
                            lua_settable(L, -3);

                            lua_pushliteral(L, "dx");
                            lua_pushinteger(L, (lua_Integer) t.m_DX);
                            lua_settable(L, -3);

                            lua_pushliteral(L, "dy");
                            lua_pushinteger(L, (lua_Integer) t.m_DY);
                            lua_settable(L, -3);

                            lua_settable(L, -3);
                        }
                        lua_settable(L, -3);
                    }

                    if (ia->m_TextCount > 0)
                    {
                        lua_pushliteral(L, "text");
                        lua_pushlstring(L, ia->m_Text, ia->m_TextCount);
                        lua_settable(L, -3);
                    }

                    arg_count += 2;
                }
                break;
            default:
                break;
            }

            int ret = dmScript::PCall(L, arg_count, LUA_MULTRET);

            Result result = RESULT_OK;
            if (ret != 0)
            {
                assert(top == lua_gettop(L));
                result = RESULT_SCRIPT_ERROR;
            }
            else
            {
                switch (script_function)
                {
                case SCRIPT_FUNCTION_ONINPUT:
                    {
                        InputArgs* input_args = (InputArgs*)args;
                        int ret_count = lua_gettop(L) - top;
                        if (ret_count == 1 && lua_isboolean(L, -1))
                        {
                            input_args->m_Consumed = (bool) lua_toboolean(L, -1);
                            lua_pop(L, 1);
                        }
                        else if (ret_count != 0)
                        {
                            dmLogError("The function %s must either return true/false, or no value at all.", SCRIPT_FUNCTION_NAMES[script_function]);
                            result = RESULT_SCRIPT_ERROR;
                            lua_settop(L, top);
                        }
                    }
                    break;
                default:
                    if (lua_gettop(L) - top != (int32_t)ret_count)
                    {
                        dmLogError("The function %s must have exactly %d return values.", SCRIPT_FUNCTION_NAMES[script_function], ret_count);
                        result = RESULT_SCRIPT_ERROR;
                        lua_settop(L, top);
                    }
                    break;
                }
            }
            lua_pushnil(L);
            dmScript::SetInstance(L);
            assert(top == lua_gettop(L));
            return result;
        }
        assert(top == lua_gettop(L));
        return RESULT_OK;
    }

    Result InitScene(HScene scene)
    {
        return RunScript(scene, SCRIPT_FUNCTION_INIT, LUA_NOREF, 0x0);
    }

    Result FinalScene(HScene scene)
    {
        Result result = RunScript(scene, SCRIPT_FUNCTION_FINAL, LUA_NOREF, 0x0);

        // Deferred deletion of nodes
        uint32_t n = scene->m_Nodes.Size();
        for (uint32_t i = 0; i < n; ++i)
        {
            InternalNode* node = &scene->m_Nodes[i];
            if (node->m_Deleted)
            {
                HNode hnode = GetNodeHandle(node);
                DeleteNode(scene, hnode);
                node->m_Deleted = 0; // Make sure to clear deferred delete flag
            }
        }

        ClearLayouts(scene);
        return result;
    }

    Result UpdateScene(HScene scene, float dt)
    {
        Result result = RunScript(scene, SCRIPT_FUNCTION_UPDATE, LUA_NOREF, (void*)&dt);

        UpdateAnimations(scene, dt);

        uint32_t total_nodes = 0;
        uint32_t active_nodes = 0;
        // Deferred deletion of nodes
        uint32_t n = scene->m_Nodes.Size();
        for (uint32_t i = 0; i < n; ++i)
        {
            InternalNode* node = &scene->m_Nodes[i];
            if (node->m_Deleted)
            {
                uint16_t index = node->m_Index;
                uint16_t version = node->m_Version;
                HNode hnode = ((uint32_t) version) << 16 | index;
                DeleteNode(scene, hnode);
                node->m_Deleted = 0; // Make sure to clear deferred delete flag
            }
            else if (node->m_Index != INVALID_INDEX)
            {
                ++total_nodes;
                if (node->m_Node.m_Enabled)
                    ++active_nodes;
            }
        }

        DM_COUNTER("Gui.Nodes", total_nodes);
        DM_COUNTER("Gui.ActiveNodes", active_nodes);
        DM_COUNTER("Gui.StaticTextures", scene->m_Textures.Size());
        DM_COUNTER("Gui.DynamicTextures", scene->m_DynamicTextures.Size());
        DM_COUNTER("Gui.Textures", scene->m_Textures.Size() + scene->m_DynamicTextures.Size());

        return result;
    }

    Result DispatchMessage(HScene scene, dmMessage::Message* message)
    {
        int custom_ref = LUA_NOREF;
        bool is_callback = false;
        if (message->m_Receiver.m_Function) {
            // NOTE: By convention m_Function is the ref + 2, see message.h in dlib
            custom_ref = message->m_Receiver.m_Function - 2;
            is_callback = true;
        }

        Result r = RunScript(scene, SCRIPT_FUNCTION_ONMESSAGE, custom_ref, (void*)message);

        if (is_callback) {
            lua_State* L = scene->m_Context->m_LuaState;
            luaL_unref(L, LUA_REGISTRYINDEX, custom_ref);
        }
        return r;
    }

    Result DispatchInput(HScene scene, const InputAction* input_actions, uint32_t input_action_count, bool* input_consumed)
    {
        InputArgs args;
        args.m_Consumed = false;
        for (uint32_t i = 0; i < input_action_count; ++i)
        {
            args.m_Action = &input_actions[i];
            Result result = RunScript(scene, SCRIPT_FUNCTION_ONINPUT, LUA_NOREF, (void*)&args);
            if (result != RESULT_OK)
            {
                return result;
            }
            else
            {
                input_consumed[i] = args.m_Consumed;
            }
        }

        return RESULT_OK;
    }

    Result ReloadScene(HScene scene)
    {
        return RunScript(scene, SCRIPT_FUNCTION_ONRELOAD, LUA_NOREF, 0x0);
    }

    Result SetSceneScript(HScene scene, HScript script)
    {
        scene->m_Script = script;
        return RESULT_OK;
    }

    HScript GetSceneScript(HScene scene)
    {
        return scene->m_Script;
    }

    HNode NewNode(HScene scene, const Point3& position, const Vector3& size, NodeType node_type)
    {
        if (scene->m_NodePool.Remaining() == 0)
        {
            dmLogError("Could not create the node since the buffer is full (%d).", scene->m_NodePool.Capacity());
            return 0;
        }
        else
        {
            uint16_t index = scene->m_NodePool.Pop();
            uint16_t version = scene->m_NextVersionNumber;
            if (version == 0)
            {
                // We can't use zero in order to avoid a handle == 0
                ++version;
            }
            HNode hnode = ((uint32_t) version) << 16 | index;
            InternalNode* node = &scene->m_Nodes[index];
            node->m_Node.m_Properties[PROPERTY_POSITION] = Vector4(Vector3(position), 1);
            node->m_Node.m_Properties[PROPERTY_ROTATION] = Vector4(0);
            node->m_Node.m_Properties[PROPERTY_SCALE] = Vector4(1,1,1,0);
            node->m_Node.m_Properties[PROPERTY_COLOR] = Vector4(1,1,1,1);
            node->m_Node.m_Properties[PROPERTY_OUTLINE] = Vector4(0,0,0,1);
            node->m_Node.m_Properties[PROPERTY_SHADOW] = Vector4(0,0,0,1);
            node->m_Node.m_Properties[PROPERTY_SIZE] = Vector4(size, 0);
            node->m_Node.m_Properties[PROPERTY_SLICE9] = Vector4(0,0,0,0);
            node->m_Node.m_Properties[PROPERTY_PIE_PARAMS] = Vector4(0,360,0,0);
            node->m_Node.m_LocalTransform = Matrix4::identity();
            node->m_Node.m_PerimeterVertices = 32;
            node->m_Node.m_OuterBounds = PIEBOUNDS_ELLIPSE;
            node->m_Node.m_BlendMode = 0;
            node->m_Node.m_NodeType = (uint32_t) node_type;
            node->m_Node.m_XAnchor = 0;
            node->m_Node.m_YAnchor = 0;
            node->m_Node.m_Pivot = 0;
            node->m_Node.m_AdjustMode = 0;
            node->m_Node.m_LineBreak = 0;
            node->m_Node.m_Enabled = 1;
            node->m_Node.m_DirtyLocal = 1;
            node->m_Node.m_InheritAlpha = 0;
            node->m_Node.m_ClippingMode = CLIPPING_MODE_NONE;
            node->m_Node.m_ClippingVisible = true;
            node->m_Node.m_ClippingInverted = false;

            node->m_Node.m_HasResetPoint = false;
            node->m_Node.m_TextureHash = 0;
            node->m_Node.m_Texture = 0;
            node->m_Node.m_TextureSet = 0;
            node->m_Node.m_TextureSetAnimDesc.Init();
            node->m_Node.m_FlipbookAnimHash = 0;
            node->m_Node.m_FlipbookAnimPosition = 0.0f;
            node->m_Node.m_FontHash = 0;
            node->m_Node.m_Font = 0;
            node->m_Node.m_LayerHash = DEFAULT_LAYER;
            node->m_Node.m_LayerIndex = 0;
            node->m_Node.m_NodeDescTable = 0;
            node->m_Version = version;
            node->m_Index = index;
            node->m_PrevIndex = INVALID_INDEX;
            node->m_NextIndex = INVALID_INDEX;
            node->m_ParentIndex = INVALID_INDEX;
            node->m_ChildHead = INVALID_INDEX;
            node->m_ChildTail = INVALID_INDEX;
            node->m_SceneTraversalCacheVersion = INVALID_INDEX;
            node->m_ClipperIndex = INVALID_INDEX;
            scene->m_NextVersionNumber = (version + 1) % ((1 << 16) - 1);
            MoveNodeAbove(scene, hnode, INVALID_HANDLE);

            return hnode;
        }
    }

    void SetNodeId(HScene scene, HNode node, dmhash_t id)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_NameHash = id;
    }

    void SetNodeId(HScene scene, HNode node, const char* id)
    {
        SetNodeId(scene, node, dmHashString64(id));
    }

    HNode GetNodeById(HScene scene, const char* id)
    {
        dmhash_t name_hash = dmHashString64(id);
        return GetNodeById(scene, name_hash);
    }

    HNode GetNodeById(HScene scene, dmhash_t id)
    {
        uint32_t n = scene->m_Nodes.Size();
        for (uint32_t i = 0; i < n; ++i)
        {
            InternalNode* node = &scene->m_Nodes[i];
            if (node->m_NameHash == id)
            {
                return ((uint32_t) node->m_Version) << 16 | node->m_Index;
            }
        }
        return 0;
    }

    uint32_t GetNodeCount(HScene scene)
    {
        return scene->m_NodePool.Size();
    }

    static void GetNodeList(HScene scene, InternalNode* n, uint16_t** out_head, uint16_t** out_tail)
    {
        if (n->m_ParentIndex != INVALID_INDEX)
        {
            InternalNode* parent = &scene->m_Nodes[n->m_ParentIndex];
            *out_head = &parent->m_ChildHead;
            *out_tail = &parent->m_ChildTail;
        }
        else
        {
            *out_head = &scene->m_RenderHead;
            *out_tail = &scene->m_RenderTail;
        }
    }

    static void AddToNodeList(HScene scene, InternalNode* n, InternalNode* parent_n, InternalNode* prev_n)
    {
        uint16_t* head = &scene->m_RenderHead, * tail = &scene->m_RenderTail;
        uint16_t parent_index = INVALID_INDEX;
        if (parent_n != 0x0)
        {
            parent_index = parent_n->m_Index;
            head = &parent_n->m_ChildHead;
            tail = &parent_n->m_ChildTail;
        }
        n->m_ParentIndex = parent_index;
        if (prev_n != 0x0)
        {
            if (*tail == prev_n->m_Index)
            {
                *tail = n->m_Index;
                n->m_NextIndex = INVALID_INDEX;
            }
            else if (prev_n->m_NextIndex != INVALID_INDEX)
            {
                InternalNode* next_n = &scene->m_Nodes[prev_n->m_NextIndex];
                next_n->m_PrevIndex = n->m_Index;
                n->m_NextIndex = prev_n->m_NextIndex;
            }
            prev_n->m_NextIndex = n->m_Index;
            n->m_PrevIndex = prev_n->m_Index;
        }
        else
        {
            n->m_PrevIndex = INVALID_INDEX;
            n->m_NextIndex = *head;
            if (*head != INVALID_INDEX)
            {
                InternalNode* next_n = &scene->m_Nodes[*head];
                next_n->m_PrevIndex = n->m_Index;
            }
            *head = n->m_Index;
            if (*tail == INVALID_INDEX)
            {
                *tail = n->m_Index;
            }
        }
    }

    static void RemoveFromNodeList(HScene scene, InternalNode* n)
    {
        // Remove from list
        if (n->m_PrevIndex != INVALID_INDEX)
            scene->m_Nodes[n->m_PrevIndex].m_NextIndex = n->m_NextIndex;
        if (n->m_NextIndex != INVALID_INDEX)
            scene->m_Nodes[n->m_NextIndex].m_PrevIndex = n->m_PrevIndex;
        uint16_t* head_ptr = 0x0, * tail_ptr = 0x0;
        GetNodeList(scene, n, &head_ptr, &tail_ptr);

        if (*head_ptr == n->m_Index)
            *head_ptr = n->m_NextIndex;
        if (*tail_ptr == n->m_Index)
            *tail_ptr = n->m_PrevIndex;
    }

    void DeleteNode(HScene scene, HNode node)
    {
        InternalNode*n = GetNode(scene, node);

        // Delete children first
        uint16_t child_index = n->m_ChildHead;
        while (child_index != INVALID_INDEX)
        {
            InternalNode* child = &scene->m_Nodes[child_index & 0xffff];
            child_index = child->m_NextIndex;
            DeleteNode(scene, GetNodeHandle(child));
        }

        dmArray<Animation> *animations = &scene->m_Animations;
        uint32_t n_anims = animations->Size();
        for (uint32_t i = 0; i < n_anims; ++i)
        {
            Animation* anim = &(*animations)[i];

            if (anim->m_Node == node)
            {
                animations->EraseSwap(i);
                i--;
                n_anims--;
                continue;
            }
        }
        RemoveFromNodeList(scene, n);
        scene->m_NodePool.Push(n->m_Index);
        if (n->m_Node.m_Text)
            free((void*)n->m_Node.m_Text);
        memset(n, 0, sizeof(InternalNode));
        n->m_Index = INVALID_INDEX;
    }

    void ClearNodes(HScene scene)
    {
        for (uint32_t i = 0; i < scene->m_Nodes.Size(); ++i)
        {
            InternalNode* n = &scene->m_Nodes[i];
            memset(n, 0, sizeof(*n));
            n->m_Index = INVALID_INDEX;
        }
        scene->m_RenderHead = INVALID_INDEX;
        scene->m_RenderTail = INVALID_INDEX;
        scene->m_NodePool.Clear();
        scene->m_Animations.SetSize(0);
    }

    static Vector4 CalcPivotDelta(uint32_t pivot, Vector4 size)
    {
        float width = size.getX();
        float height = size.getY();

        Vector4 delta_pivot = Vector4(0.0f, 0.0f, 0.0f, 1.0f);

        switch (pivot)
        {
            case dmGui::PIVOT_CENTER:
            case dmGui::PIVOT_S:
            case dmGui::PIVOT_N:
                delta_pivot.setX(-width * 0.5f);
                break;

            case dmGui::PIVOT_NE:
            case dmGui::PIVOT_E:
            case dmGui::PIVOT_SE:
                delta_pivot.setX(-width);
                break;

            case dmGui::PIVOT_SW:
            case dmGui::PIVOT_W:
            case dmGui::PIVOT_NW:
                break;
        }
        switch (pivot) {
            case dmGui::PIVOT_CENTER:
            case dmGui::PIVOT_E:
            case dmGui::PIVOT_W:
                delta_pivot.setY(-height * 0.5f);
                break;

            case dmGui::PIVOT_N:
            case dmGui::PIVOT_NE:
            case dmGui::PIVOT_NW:
                delta_pivot.setY(-height);
                break;

            case dmGui::PIVOT_S:
            case dmGui::PIVOT_SW:
            case dmGui::PIVOT_SE:
                break;
        }
        return delta_pivot;
    }

    static void AdjustPosScale(HScene scene, InternalNode* n, const Vector4& reference_scale, Vector4& position, Vector4& scale)
    {
        if (scene->m_AdjustReference == ADJUST_REFERENCE_LEGACY && n->m_ParentIndex != INVALID_INDEX)
        {
            return;
        }

        Node& node = n->m_Node;
        // Apply ref-scaling to scale uniformly, select the smallest scale component to make sure everything fits
        Vector4 adjust_scale = reference_scale;
        if (node.m_AdjustMode == dmGui::ADJUST_MODE_FIT)
        {
            float uniform = dmMath::Min(reference_scale.getX(), reference_scale.getY());
            adjust_scale.setX(uniform);
            adjust_scale.setY(uniform);
        }
        else if (node.m_AdjustMode == dmGui::ADJUST_MODE_ZOOM)
        {
            float uniform = dmMath::Max(reference_scale.getX(), reference_scale.getY());
            adjust_scale.setX(uniform);
            adjust_scale.setY(uniform);
        }

        Context* context = scene->m_Context;
        Vector4 parent_dims;

        if (scene->m_AdjustReference == ADJUST_REFERENCE_LEGACY || n->m_ParentIndex == INVALID_INDEX) {
            parent_dims = Vector4((float) scene->m_Width, (float) scene->m_Height, 0.0f, 1.0f);
        } else {
            InternalNode* parent = &scene->m_Nodes[n->m_ParentIndex];
            parent_dims = Vector4(parent->m_Node.m_Properties[dmGui::PROPERTY_SIZE].getX(), parent->m_Node.m_Properties[dmGui::PROPERTY_SIZE].getY(), 0.0f, 1.0f);
        }

        Vector4 offset = Vector4(0.0f, 0.0f, 0.0f, 0.0f);
        Vector4 adjusted_dims = mulPerElem(parent_dims, adjust_scale);
        Vector4 ref_size;
        if (scene->m_AdjustReference == ADJUST_REFERENCE_LEGACY || n->m_ParentIndex == INVALID_INDEX) {
            ref_size = Vector4((float) context->m_PhysicalWidth, (float) context->m_PhysicalHeight, 0.0f, 1.0f);

            // need to calculate offset for root nodes, since (0,0) is in middle of scene
            offset = (ref_size - adjusted_dims) * 0.5f;
        } else {
            InternalNode* parent = &scene->m_Nodes[n->m_ParentIndex];
            ref_size = Vector4(parent->m_Node.m_Properties[dmGui::PROPERTY_SIZE].getX() * reference_scale.getX(), parent->m_Node.m_Properties[dmGui::PROPERTY_SIZE].getY() * reference_scale.getY(), 0.0f, 1.0f);
        }

        // Apply anchoring
        Vector4 scaled_position = mulPerElem(position, adjust_scale);
        if (node.m_XAnchor == XANCHOR_LEFT)
        {
            offset.setX(0.0f);
            scaled_position.setX(position.getX() * reference_scale.getX());
        }
        else if (node.m_XAnchor == XANCHOR_RIGHT)
        {
            offset.setX(0.0f);
            float distance = (parent_dims.getX() - position.getX()) * reference_scale.getX();
            scaled_position.setX(ref_size.getX() - distance);
        }
        if (node.m_YAnchor == YANCHOR_TOP)
        {
            offset.setY(0.0f);
            float distance = (parent_dims.getY() - position.getY()) * reference_scale.getY();
            scaled_position.setY(ref_size.getY() - distance);
        }
        else if (node.m_YAnchor == YANCHOR_BOTTOM)
        {
            offset.setY(0.0f);
            scaled_position.setY(position.getY() * reference_scale.getY());
        }

        position = scaled_position + offset;
        scale = mulPerElem(adjust_scale, scale);
    }

    static void UpdateLocalTransform(HScene scene, InternalNode* n)
    {
        Node& node = n->m_Node;

        Vector4 position = node.m_Properties[dmGui::PROPERTY_POSITION];
        Vector4 prop_scale = node.m_Properties[dmGui::PROPERTY_SCALE];
        node.m_LocalAdjustScale = Vector4(1.0, 1.0, 1.0, 1.0);
        Vector4 reference_scale = CalculateReferenceScale(scene, n);
        AdjustPosScale(scene, n, reference_scale, position, node.m_LocalAdjustScale);
        const Vector3& rotation = node.m_Properties[dmGui::PROPERTY_ROTATION].getXYZ();
        Quat r = dmVMath::EulerToQuat(rotation);
        r = normalize(r);

        node.m_LocalTransform.setUpper3x3(Matrix3::rotation(r) * Matrix3::scale( mulPerElem(node.m_LocalAdjustScale, prop_scale).getXYZ() ));
        node.m_LocalTransform.setTranslation(position.getXYZ());

        if (scene->m_AdjustReference == ADJUST_REFERENCE_PARENT && n->m_ParentIndex != INVALID_INDEX)
        {
            // undo parent scale (if node has parent)
            Vector3 inv_ref_scale = Vector3(1.0f / reference_scale.getX(), 1.0f / reference_scale.getY(), 1.0f / reference_scale.getZ());
            node.m_LocalTransform = Matrix4::scale( inv_ref_scale ) * node.m_LocalTransform;
        }

        node.m_DirtyLocal = 0;
    }

    void ResetNodes(HScene scene)
    {
        uint32_t n_nodes = scene->m_Nodes.Size();
        for (uint32_t i = 0; i < n_nodes; ++i) {
            InternalNode* node = &scene->m_Nodes[i];
            Node* n = &node->m_Node;
            if (n->m_HasResetPoint) {
                memcpy(n->m_Properties, n->m_ResetPointProperties, sizeof(n->m_Properties));
                n->m_DirtyLocal = 1;
                n->m_State = n->m_ResetPointState;
            }
        }
        scene->m_Animations.SetSize(0);
    }

    uint16_t GetRenderOrder(HScene scene)
    {
        return scene->m_RenderOrder;
    }

    NodeType GetNodeType(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return (NodeType)n->m_Node.m_NodeType;
    }

    Point3 GetNodePosition(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return Point3(n->m_Node.m_Properties[PROPERTY_POSITION].getXYZ());
    }

    Point3 GetNodeSize(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return Point3(n->m_Node.m_Properties[PROPERTY_SIZE].getXYZ());
    }

    Vector4 GetNodeSlice9(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_Properties[PROPERTY_SLICE9];
    }

    void SetNodePosition(HScene scene, HNode node, const Point3& position)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_Properties[PROPERTY_POSITION] = Vector4(position);
        n->m_Node.m_DirtyLocal = 1;
    }

    bool HasPropertyHash(HScene scene, HNode node, dmhash_t property)
    {
        PropDesc* pd = GetPropertyDesc(property);
        return pd != 0;
    }

    Vector4 GetNodeProperty(HScene scene, HNode node, Property property)
    {
        assert(property < PROPERTY_COUNT);
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_Properties[property];
    }

    Vector4 GetNodePropertyHash(HScene scene, HNode node, dmhash_t property)
    {
        InternalNode* n = GetNode(scene, node);
        PropDesc* pd = GetPropertyDesc(property);
        if (pd) {
            Vector4* base_value = &n->m_Node.m_Properties[pd->m_Property];

            if (pd->m_Component == 0xff) {
                return *base_value;
            } else {
                return Vector4(base_value->getElem(pd->m_Component));
            }
        }
        dmLogError("Property %s not found", (const char*) dmHashReverse64(property, 0));
        return Vector4(0, 0, 0, 0);
    }

    void SetNodeProperty(HScene scene, HNode node, Property property, const Vector4& value)
    {
        assert(property < PROPERTY_COUNT);
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_Properties[property] = value;
        n->m_Node.m_DirtyLocal = 1;
    }

    void SetNodeResetPoint(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        memcpy(n->m_Node.m_ResetPointProperties, n->m_Node.m_Properties, sizeof(n->m_Node.m_Properties));
        n->m_Node.m_ResetPointState = n->m_Node.m_State;
        n->m_Node.m_HasResetPoint = true;
    }

    const char* GetNodeText(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_Text;
    }

    void SetNodeText(HScene scene, HNode node, const char* text)
    {
        InternalNode* n = GetNode(scene, node);
        if (n->m_Node.m_Text)
            free((void*) n->m_Node.m_Text);

        if (text)
            n->m_Node.m_Text = strdup(text);
        else
            n->m_Node.m_Text = 0;
    }

    void SetNodeLineBreak(HScene scene, HNode node, bool line_break)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_LineBreak = line_break;
    }

    bool GetNodeLineBreak(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_LineBreak;
    }

    void* GetNodeTexture(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_Texture;
    }

    void* GetNodeTextureSet(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_TextureSet;
    }

    dmhash_t GetNodeTextureId(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_TextureHash;
    }

    dmhash_t GetNodeFlipbookAnimId(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_TextureSet == 0x0 ? 0x0 : n->m_Node.m_FlipbookAnimHash;
    }

    Result SetNodeTexture(HScene scene, HNode node, dmhash_t texture_id)
    {
        InternalNode* n = GetNode(scene, node);
        if(n->m_Node.m_TextureSet)
            CancelNodeFlipbookAnim(scene, node);
        if (TextureInfo* texture_info = scene->m_Textures.Get(texture_id)) {
            n->m_Node.m_TextureHash = texture_id;
            n->m_Node.m_Texture = texture_info->m_Texture;
            n->m_Node.m_TextureSet = texture_info->m_TextureSet;
            return RESULT_OK;
        } else if (DynamicTexture* texture = scene->m_DynamicTextures.Get(texture_id)) {
            n->m_Node.m_TextureHash = texture_id;
            n->m_Node.m_Texture = texture->m_Handle;
            n->m_Node.m_TextureSet = 0x0;
            return RESULT_OK;
        }
        n->m_Node.m_Texture = 0;
        n->m_Node.m_TextureSet = 0;
        return RESULT_RESOURCE_NOT_FOUND;
    }

    Result SetNodeTexture(HScene scene, HNode node, const char* texture_id)
    {
        return SetNodeTexture(scene, node, dmHashString64(texture_id));
    }

    void* GetNodeFont(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_Font;
    }

    dmhash_t GetNodeFontId(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_FontHash;
    }

    Result SetNodeFont(HScene scene, HNode node, dmhash_t font_id)
    {
        void** font = scene->m_Fonts.Get(font_id);
        if (font)
        {
            InternalNode* n = GetNode(scene, node);
            n->m_Node.m_FontHash = font_id;
            n->m_Node.m_Font = *font;
            return RESULT_OK;
        }
        else
        {
            return RESULT_RESOURCE_NOT_FOUND;
        }
    }

    Result SetNodeFont(HScene scene, HNode node, const char* font_id)
    {
        return SetNodeFont(scene, node, dmHashString64(font_id));
    }

    dmhash_t GetNodeLayerId(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_LayerHash;
    }

    Result SetNodeLayer(HScene scene, HNode node, dmhash_t layer_id)
    {
        uint16_t* layer_index = scene->m_Layers.Get(layer_id);
        if (layer_index)
        {
            InternalNode* n = GetNode(scene, node);
            n->m_Node.m_LayerHash = layer_id;
            n->m_Node.m_LayerIndex = *layer_index;
            return RESULT_OK;
        }
        else
        {
            return RESULT_RESOURCE_NOT_FOUND;
        }
    }

    Result SetNodeLayer(HScene scene, HNode node, const char* layer_id)
    {
        return SetNodeLayer(scene, node, dmHashString64(layer_id));
    }

    void SetNodeInheritAlpha(HScene scene, HNode node, bool inherit_alpha)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_InheritAlpha = inherit_alpha;
    }

    void SetNodeClippingMode(HScene scene, HNode node, ClippingMode mode)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_ClippingMode = mode;
    }

    ClippingMode GetNodeClippingMode(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return (ClippingMode) n->m_Node.m_ClippingMode;
    }

    void SetNodeClippingVisible(HScene scene, HNode node, bool visible)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_ClippingVisible = (uint32_t) visible;
    }

    bool GetNodeClippingVisible(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_ClippingVisible;
    }

    void SetNodeClippingInverted(HScene scene, HNode node, bool inverted)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_ClippingInverted = (uint32_t) inverted;
    }

    bool GetNodeClippingInverted(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_ClippingInverted;
    }

    Result GetTextMetrics(HScene scene, const char* text, const char* font_id, float width, bool line_break, TextMetrics* metrics)
    {
        return GetTextMetrics(scene, text, dmHashString64(font_id), width, line_break, metrics);
    }

    Result GetTextMetrics(HScene scene, const char* text, dmhash_t font_id, float width, bool line_break, TextMetrics* metrics)
    {
        memset(metrics, 0, sizeof(*metrics));
        void** font = scene->m_Fonts.Get(font_id);
        if (!font) {
            return RESULT_RESOURCE_NOT_FOUND;
        }

        scene->m_Context->m_GetTextMetricsCallback(*font, text, width, line_break, metrics);
        return RESULT_OK;
    }

    BlendMode GetNodeBlendMode(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return (BlendMode)n->m_Node.m_BlendMode;
    }

    void SetNodeBlendMode(HScene scene, HNode node, BlendMode blend_mode)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_BlendMode = (uint32_t) blend_mode;
    }

    XAnchor GetNodeXAnchor(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return (XAnchor)n->m_Node.m_XAnchor;
    }

    void SetNodeXAnchor(HScene scene, HNode node, XAnchor x_anchor)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_XAnchor = (uint32_t) x_anchor;
    }

    YAnchor GetNodeYAnchor(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return (YAnchor)n->m_Node.m_YAnchor;
    }

    void SetNodeYAnchor(HScene scene, HNode node, YAnchor y_anchor)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_YAnchor = (uint32_t) y_anchor;
    }


    void SetNodeOuterBounds(HScene scene, HNode node, PieBounds bounds)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_OuterBounds = bounds;
    }

    void SetNodePerimeterVertices(HScene scene, HNode node, uint32_t vertices)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_PerimeterVertices = vertices;
    }

    void SetNodeInnerRadius(HScene scene, HNode node, float radius)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_Properties[PROPERTY_PIE_PARAMS].setX(radius);
    }

    void SetNodePieFillAngle(HScene scene, HNode node, float fill_angle)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_Properties[PROPERTY_PIE_PARAMS].setY(fill_angle);
    }

    PieBounds GetNodeOuterBounds(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_OuterBounds;
    }

    uint32_t GetNodePerimeterVertices(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_PerimeterVertices;
    }

    float GetNodeInnerRadius(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_Properties[PROPERTY_PIE_PARAMS].getX();
    }

    float GetNodePieFillAngle(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_Properties[PROPERTY_PIE_PARAMS].getY();
    }

    Pivot GetNodePivot(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return (Pivot)n->m_Node.m_Pivot;
    }

    void SetNodePivot(HScene scene, HNode node, Pivot pivot)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_Pivot = (uint32_t) pivot;
    }

    void SetNodeAdjustMode(HScene scene, HNode node, AdjustMode adjust_mode)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_AdjustMode = (uint32_t) adjust_mode;
    }

    static void AnimateComponent(HScene scene,
                                 HNode node,
                                 float* value,
                                 float to,
                                 dmEasing::Curve easing,
                                 Playback playback,
                                 float duration,
                                 float delay,
                                 AnimationComplete animation_complete,
                                 void* userdata1,
                                 void* userdata2)
    {
        uint16_t version = (uint16_t) (node >> 16);
        uint16_t index = node & 0xffff;
        InternalNode* n = &scene->m_Nodes[index];
        assert(n->m_Version == version);

        Animation animation;
        uint32_t animation_index = 0xffffffff;

        // Remove old animation for the same property
        for (uint32_t i = 0; i < scene->m_Animations.Size(); ++i)
        {
            const Animation* anim = &scene->m_Animations[i];
            if (value == anim->m_Value)
            {
                //scene->m_Animations.EraseSwap(i);
                animation_index = i;
                break;
            }
        }

        if (animation_index == 0xffffffff)
        {
            if (scene->m_Animations.Full())
            {
                dmLogWarning("Out of animation resources (%d)", scene->m_Animations.Size());
                return;
            }
            animation_index = scene->m_Animations.Size();
            scene->m_Animations.SetSize(animation_index+1);
        }

        animation.m_Node = node;
        animation.m_Value = value;
        animation.m_To = to;
        animation.m_Delay = delay;
        animation.m_Elapsed = 0.0f;
        animation.m_Duration = duration;
        animation.m_Easing = easing;
        animation.m_Playback = playback;
        animation.m_AnimationComplete = animation_complete;
        animation.m_Userdata1 = userdata1;
        animation.m_Userdata2 = userdata2;
        animation.m_FirstUpdate = 1;
        animation.m_AnimationCompleteCalled = 0;
        animation.m_Cancelled = 0;
        animation.m_Backwards = 0;

        scene->m_Animations[animation_index] = animation;
    }

    void AnimateNodeHash(HScene scene,
                         HNode node,
                         dmhash_t property,
                         const Vector4& to,
                         dmEasing::Curve easing,
                         Playback playback,
                         float duration,
                         float delay,
                         AnimationComplete animation_complete,
                         void* userdata1,
                         void* userdata2)
    {
        uint16_t version = (uint16_t) (node >> 16);
        uint16_t index = node & 0xffff;
        InternalNode* n = &scene->m_Nodes[index];
        assert(n->m_Version == version);

        PropDesc* pd = GetPropertyDesc(property);
        if (pd) {
            Vector4* base_value = &n->m_Node.m_Properties[pd->m_Property];

            if (pd->m_Component == 0xff) {
                for (int j = 0; j < 4; ++j) {
                    AnimateComponent(scene, node, ((float*) base_value) + j, to.getElem(j), easing, playback, duration, delay, animation_complete, userdata1, userdata2);
                    // Only run callback for the first component
                    animation_complete = 0;
                    userdata1 = 0;
                    userdata2 = 0;
                }
            } else {
                AnimateComponent(scene, node, ((float*) base_value) + pd->m_Component, to.getElem(pd->m_Component), easing, playback, duration, delay, animation_complete, userdata1, userdata2);
            }
        } else {
            dmLogError("property '%s' not found", (const char*) dmHashReverse64(property, 0));
        }
    }

    void AnimateNode(HScene scene,
                     HNode node,
                     Property property,
                     const Vector4& to,
                     dmEasing::Curve easing,
                     Playback playback,
                     float duration,
                     float delay,
                     AnimationComplete animation_complete,
                     void* userdata1,
                     void* userdata2)
    {
        dmhash_t prop_hash = g_PropTable[property].m_Hash;
        AnimateNodeHash(scene, node, prop_hash, to, easing, playback, duration, delay, animation_complete, userdata1, userdata2);
    }

    dmhash_t GetPropertyHash(Property property)
    {
        dmhash_t hash = 0;
        if (PROPERTY_SHADOW >= property) {
            hash = g_PropTable[property].m_Hash;
        }
        return hash;
    }

    void CancelAnimationHash(HScene scene, HNode node, dmhash_t property_hash)
    {
        uint16_t version = (uint16_t) (node >> 16);
        uint16_t index = node & 0xffff;
        InternalNode* n = &scene->m_Nodes[index];
        assert(n->m_Version == version);

        dmArray<Animation>* animations = &scene->m_Animations;
        uint32_t n_animations = animations->Size();

        PropDesc* pd = GetPropertyDesc(property_hash);
        if (pd) {
            for (uint32_t i = 0; i < n_animations; ++i)
            {
                Animation* anim = &(*animations)[i];

                int from = 0;
                int to = 4; // NOTE: Exclusive range
                int expect = 4;
                if (pd->m_Component != 0xff) {
                    from = pd->m_Component;
                    to = pd->m_Component + 1;
                    expect = 1;
                }

                float* value = (float*) &n->m_Node.m_Properties[pd->m_Property];
                int count = 0;
                for (int j = from; j < to; ++j) {
                    if (anim->m_Node == node && anim->m_Value == (value + j))
                    {
                        anim->m_Cancelled = 1;
                        ++count;
                        if (count == expect) {
                            return;
                        }
                    }
                }
            }
        } else {
            dmLogError("property '%s' not found", (const char*) dmHashReverse64(property_hash, 0));
        }
    }

    inline Animation* GetComponentAnimation(HScene scene, HNode node, float* value)
    {
        uint16_t version = (uint16_t) (node >> 16);
        uint16_t index = node & 0xffff;
        InternalNode* n = &scene->m_Nodes[index];
        assert(n->m_Version == version);

        dmArray<Animation>* animations = &scene->m_Animations;
        uint32_t n_animations = animations->Size();
        for (uint32_t i = 0; i < n_animations; ++i)
        {
            Animation* anim = &(*animations)[i];
            if (anim->m_Node == node && anim->m_Value == value)
                return anim;
        }
        return 0;
    }

    static void CancelAnimationComponent(HScene scene, HNode node, float* value)
    {
        Animation* anim = GetComponentAnimation(scene, node, value);
        if(anim == 0x0)
            return;
        anim->m_Cancelled = 1;
    }

    static inline void AnimateTextureSetAnim(HScene scene, HNode node, AnimationComplete anim_complete_callback, void* callback_userdata1, void* callback_userdata2)
    {
        InternalNode* n = GetNode(scene, node);
        TextureSetAnimDesc& anim_desc = n->m_Node.m_TextureSetAnimDesc;
        float anim_frames = (float) (anim_desc.m_End - anim_desc.m_Start);
        AnimateComponent(
                scene,
                node,
                &n->m_Node.m_FlipbookAnimPosition,
                1.0f,
                dmEasing::Curve(dmEasing::TYPE_LINEAR),
                (Playback) anim_desc.m_Playback,
                anim_frames / (float) anim_desc.m_FPS,
                0.0f,
                anim_complete_callback,
                callback_userdata1,
                callback_userdata2);
    }

    static inline FetchTextureSetAnimResult FetchTextureSetAnim(HScene scene, InternalNode* n, dmhash_t anim)
    {
        FetchTextureSetAnimCallback fetch_anim_callback = scene->m_FetchTextureSetAnimCallback;
        if(fetch_anim_callback == 0x0)
        {
            dmLogError("PlayNodeFlipbookAnim called with node in scene with no FetchTextureSetAnimCallback set.");
            return FETCH_ANIMATION_CALLBACK_ERROR;
        }
        return fetch_anim_callback(n->m_Node.m_TextureSet, anim, &n->m_Node.m_TextureSetAnimDesc);
    }

    static inline void UpdateTextureSetAnimData(HScene scene, InternalNode* n)
    {
        // if we got a textureset (i.e. texture animation), we want to update the animation in case it is reloaded
        uint64_t anim_hash = n->m_Node.m_FlipbookAnimHash;
        if(n->m_Node.m_TextureSet == 0 || anim_hash == 0)
            return;

        // update animationdata, compare state to current and early bail if equal
        TextureSetAnimDesc& anim_desc = n->m_Node.m_TextureSetAnimDesc;
        uint64_t current_state = anim_desc.m_State;
        if(FetchTextureSetAnim(scene, n, anim_hash)!=FETCH_ANIMATION_OK)
        {
            // general error in retreiving animation. This could be it being deleted or otherwise changed erraneously
            anim_desc.Init();
            CancelAnimationComponent(scene, GetNodeHandle(n), &n->m_Node.m_FlipbookAnimPosition);
            const char* anim_str = (const char*)dmHashReverse64(anim_hash, 0x0);
            dmLogWarning("Failed to update animation '%s'.", anim_str == 0 ? "<unknown>" : anim_str);
            return;
        }

        if(current_state == anim_desc.m_State)
            return;

        n->m_Node.m_FlipbookAnimPosition = 0.0f;
        HNode node = GetNodeHandle(n);
        if(anim_desc.m_Playback == PLAYBACK_NONE)
        {
            CancelAnimationComponent(scene, node, &n->m_Node.m_FlipbookAnimPosition);
            return;
        }

        Animation* anim = GetComponentAnimation(scene, node, &n->m_Node.m_FlipbookAnimPosition);
        if(anim && (anim->m_Cancelled == 0))
            AnimateTextureSetAnim(scene, node, anim->m_AnimationComplete, anim->m_Userdata1, anim->m_Userdata2);
        else
            AnimateTextureSetAnim(scene, node, 0, 0, 0);
    }

    Result PlayNodeFlipbookAnim(HScene scene, HNode node, dmhash_t anim, AnimationComplete anim_complete_callback, void* callback_userdata1, void* callback_userdata2)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_FlipbookAnimPosition = 0.0f;
        n->m_Node.m_FlipbookAnimHash = 0x0;

        if(anim == 0x0)
        {
            dmLogError("PlayNodeFlipbookAnim called with invalid anim name.");
            return RESULT_INVAL_ERROR;
        }
        if(n->m_Node.m_TextureSet == 0x0)
        {
            dmLogError("PlayNodeFlipbookAnim called with node not containing animation.");
            return RESULT_INVAL_ERROR;
        }

        n->m_Node.m_FlipbookAnimHash = anim;
        FetchTextureSetAnimResult result = FetchTextureSetAnim(scene, n, anim);
        if(result != FETCH_ANIMATION_OK)
        {
            CancelAnimationComponent(scene, node, &n->m_Node.m_FlipbookAnimPosition);
            n->m_Node.m_FlipbookAnimHash = 0;
            n->m_Node.m_TextureSetAnimDesc.Init();
            const char* anim_str = (const char*)dmHashReverse64(anim, 0x0);
            if(result == FETCH_ANIMATION_NOT_FOUND)
            {
                dmLogWarning("The animation '%s' could not be found.", anim_str == 0 ? "<unknown>" : anim_str);
            }
            else
            {
                dmLogWarning("Error playing animation '%s' (result %d).", anim_str == 0 ? "<unknown>" : anim_str, (int32_t) result);
            }
            return RESULT_RESOURCE_NOT_FOUND;
        }

        if(n->m_Node.m_TextureSetAnimDesc.m_Playback == PLAYBACK_NONE)
            CancelAnimationComponent(scene, node, &n->m_Node.m_FlipbookAnimPosition);
        else
            AnimateTextureSetAnim(scene, node, anim_complete_callback, callback_userdata1, callback_userdata2);
        return RESULT_OK;
    }

    Result PlayNodeFlipbookAnim(HScene scene, HNode node, const char* anim, AnimationComplete anim_complete_callback, void* callback_userdata1, void* callback_userdata2)
    {
        return PlayNodeFlipbookAnim(scene, node, dmHashString64(anim), anim_complete_callback, callback_userdata1, callback_userdata2);
    }

    void CancelNodeFlipbookAnim(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        CancelAnimationComponent(scene, node, &n->m_Node.m_FlipbookAnimPosition);
        n->m_Node.m_FlipbookAnimHash = 0;
    }

    const float* GetNodeFlipbookAnimUV(HScene scene, HNode node)
    {
        InternalNode* in = GetNode(scene, node);
        Node& n = in->m_Node;
        if(n.m_TextureSet == 0x0 || n.m_TextureSetAnimDesc.m_TexCoords == 0x0)
            return 0;
        TextureSetAnimDesc* anim_desc = &n.m_TextureSetAnimDesc;
        int32_t anim_frames = anim_desc->m_End - anim_desc->m_Start;
        int32_t anim_frame = (int32_t) (n.m_FlipbookAnimPosition * (float)anim_frames);
        anim_frame = dmMath::Clamp(anim_frame, 0, anim_frames-1);
        const float* frame_uv = n.m_TextureSetAnimDesc.m_TexCoords + ((anim_desc->m_Start + anim_frame)<<3);
        return frame_uv;
    }

    void GetNodeFlipbookAnimUVFlip(HScene scene, HNode node, bool& flip_horizontal, bool& flip_vertical)
    {
        InternalNode* n = GetNode(scene, node);
        flip_horizontal = n->m_Node.m_TextureSetAnimDesc.m_FlipHorizontal;
        flip_vertical = n->m_Node.m_TextureSetAnimDesc.m_FlipVertical;
    }

    bool PickNode(HScene scene, HNode node, float x, float y)
    {
        Vector4 scale((float) scene->m_Context->m_PhysicalWidth / (float) scene->m_Context->m_DefaultProjectWidth,
                (float) scene->m_Context->m_PhysicalHeight / (float) scene->m_Context->m_DefaultProjectHeight, 1, 1);
        Matrix4 transform;
        InternalNode* n = GetNode(scene, node);
        CalculateNodeTransform(scene, n, CalculateNodeTransformFlags(CALCULATE_NODE_BOUNDARY | CALCULATE_NODE_INCLUDE_SIZE | CALCULATE_NODE_RESET_PIVOT), transform);
        transform = inverse(transform);
        Vector4 screen_pos(x * scale.getX(), y * scale.getY(), 0.0f, 1.0f);
        Vector4 node_pos = transform * screen_pos;
        const float EPSILON = 0.0001f;
        // check if we need to project the local position to the node plane
        if (dmMath::Abs(node_pos.getZ()) > EPSILON)
        {
            Vector4 ray_dir = transform.getCol2();
            // falsify if node is almost orthogonal to the screen plane, impossible to pick
            if (dmMath::Abs(ray_dir.getZ()) < 0.0001f)
            {
                return false;
            }
            node_pos -= ray_dir * (node_pos.getZ() / ray_dir.getZ());
        }
        return node_pos.getX() >= 0.0f
                && node_pos.getX() <= 1.0f
                && node_pos.getY() >= 0.0f
                && node_pos.getY() <= 1.0f;
    }

    bool IsNodeEnabled(HScene scene, HNode node)
    {
        InternalNode* n = GetNode(scene, node);
        return n->m_Node.m_Enabled;
    }

    void SetNodeEnabled(HScene scene, HNode node, bool enabled)
    {
        InternalNode* n = GetNode(scene, node);
        n->m_Node.m_Enabled = enabled;
    }

    void MoveNodeBelow(HScene scene, HNode node, HNode reference)
    {
        if (node != INVALID_HANDLE && node != reference)
        {
            InternalNode* n = GetNode(scene, node);
            RemoveFromNodeList(scene, n);
            InternalNode* parent = 0x0;
            InternalNode* prev = 0x0;
            if (reference != INVALID_HANDLE)
            {
                uint16_t ref_index = reference & 0xffff;
                InternalNode* ref = &scene->m_Nodes[ref_index];
                // The reference is actually the next node, find the previous
                if (ref->m_PrevIndex != INVALID_INDEX)
                {
                    prev = &scene->m_Nodes[ref->m_PrevIndex];
                }
                if (ref->m_ParentIndex != INVALID_INDEX)
                {
                    parent = &scene->m_Nodes[ref->m_ParentIndex];
                }
            }
            AddToNodeList(scene, n, parent, prev);
        }
    }

    void MoveNodeAbove(HScene scene, HNode node, HNode reference)
    {
        if (node != INVALID_HANDLE && node != reference)
        {
            InternalNode* n = GetNode(scene, node);
            RemoveFromNodeList(scene, n);
            InternalNode* parent = 0x0;
            InternalNode* prev = 0x0;
            if (reference != INVALID_HANDLE)
            {
                uint16_t ref_index = reference & 0xffff;
                prev = &scene->m_Nodes[ref_index];
                if (prev->m_ParentIndex != INVALID_INDEX)
                {
                    parent = &scene->m_Nodes[prev->m_ParentIndex];
                }
            }
            else
            {
                // Find the previous node of the root list
                uint16_t prev_index = scene->m_RenderTail;
                if (prev_index != INVALID_INDEX)
                {
                    prev = &scene->m_Nodes[prev_index];
                }
            }
            AddToNodeList(scene, n, parent, prev);
        }
    }

    Result SetNodeParent(HScene scene, HNode node, HNode parent)
    {
        if (node == parent)
            return RESULT_INF_RECURSION;
        InternalNode* n = GetNode(scene, node);
        uint16_t parent_index = INVALID_INDEX;
        InternalNode* parent_node = 0x0;
        if (parent != INVALID_HANDLE)
        {
            parent_node = GetNode(scene, parent);
            // Check for infinite recursion
            uint16_t ancestor_index = parent_node->m_ParentIndex;
            while (ancestor_index != INVALID_INDEX)
            {
                if (n->m_Index == ancestor_index)
                    return RESULT_INF_RECURSION;
                ancestor_index = scene->m_Nodes[ancestor_index].m_ParentIndex;
            }
            parent_index = parent_node->m_Index;
        }
        if (parent_index != n->m_ParentIndex)
        {
            RemoveFromNodeList(scene, n);
            InternalNode* prev = 0x0;
            uint16_t prev_index = scene->m_RenderTail;
            if (parent_index != INVALID_INDEX)
            {
                prev_index = parent_node->m_ChildTail;
            }
            if (prev_index != INVALID_INDEX)
            {
                prev = &scene->m_Nodes[prev_index];
            }
            AddToNodeList(scene, n, parent_node, prev);
        }
        return RESULT_OK;
    }

    Result CloneNode(HScene scene, HNode node, HNode* out_node)
    {
        if (scene->m_NodePool.Remaining() == 0)
        {
            dmLogError("Could not create the node since the buffer is full (%d).", scene->m_NodePool.Capacity());
            return RESULT_OUT_OF_RESOURCES;
        }
        else
        {
            uint16_t index = scene->m_NodePool.Pop();
            uint16_t version = scene->m_NextVersionNumber;
            if (version == 0)
            {
                // We can't use zero in order to avoid a handle == 0
                ++version;
            }
            *out_node = ((uint32_t) version) << 16 | index;
            InternalNode* out_n = &scene->m_Nodes[index];
            memset(out_n, 0, sizeof(InternalNode));

            InternalNode* n = GetNode(scene, node);
            out_n->m_Node = n->m_Node;
            if (n->m_Node.m_Text != 0x0)
                out_n->m_Node.m_Text = strdup(n->m_Node.m_Text);
            out_n->m_Version = version;
            out_n->m_Index = index;
            out_n->m_SceneTraversalCacheVersion = INVALID_INDEX;
            out_n->m_PrevIndex = INVALID_INDEX;
            out_n->m_NextIndex = INVALID_INDEX;
            out_n->m_ParentIndex = INVALID_INDEX;
            out_n->m_ChildHead = INVALID_INDEX;
            out_n->m_ChildTail = INVALID_INDEX;
            scene->m_NextVersionNumber = (version + 1) % ((1 << 16) - 1);
            // Add to the top of the scene
            MoveNodeAbove(scene, *out_node, INVALID_HANDLE);

            return RESULT_OK;
        }
    }

    inline void CalculateNodeExtents(const Node& node, const CalculateNodeTransformFlags flags, Matrix4& transform)
    {
        Vector4 size(1.0f, 1.0f, 0.0f, 0.0f);
        if (flags & CALCULATE_NODE_INCLUDE_SIZE)
        {
            size = node.m_Properties[dmGui::PROPERTY_SIZE];
        }
        // Reset the pivot of the node, so that the resulting transform has the origin in the lower left, which is used for quad rendering etc.
        if (flags & CALCULATE_NODE_RESET_PIVOT)
        {
            Vector4 pivot_delta = transform * CalcPivotDelta(node.m_Pivot, size);
            transform.setCol3(pivot_delta);
        }

        bool render_text = node.m_NodeType == NODE_TYPE_TEXT && !(flags & CALCULATE_NODE_BOUNDARY);
        if ((flags & CALCULATE_NODE_INCLUDE_SIZE) && !render_text)
        {
            transform.setUpper3x3(transform.getUpper3x3() * Matrix3::scale(Vector3(size.getX(), size.getY(), 1)));
        }
    }

    inline void CalculateParentNodeTransformAndAlphaCached(HScene scene, InternalNode* n, Matrix4& out_transform, float& out_opacity, SceneTraversalCache& traversal_cache)
    {
        const Node& node = n->m_Node;
        uint16_t cache_index;
        bool cached;
        uint16_t cache_version = n->m_SceneTraversalCacheVersion;
        if(cache_version != traversal_cache.m_Version)
        {
            n->m_SceneTraversalCacheVersion = traversal_cache.m_Version;
            cache_index = n->m_SceneTraversalCacheIndex = traversal_cache.m_NodeIndex++;
            cached = false;
        }
        else
        {
            cache_index = n->m_SceneTraversalCacheIndex;
            cached = true;
        }
        SceneTraversalCache::Data& cache_data = traversal_cache.m_Data[cache_index];

        if (node.m_DirtyLocal || scene->m_ResChanged)
        {
            UpdateLocalTransform(scene, n);
        }
        else if(cached)
        {
            out_transform = cache_data.m_Transform;
            out_opacity = cache_data.m_Opacity;
            return;
        }
        out_transform = node.m_LocalTransform;

        out_opacity = n->m_Node.m_Properties[dmGui::PROPERTY_COLOR].getW();

        if (n->m_ParentIndex != INVALID_INDEX)
        {
            Matrix4 parent_trans;
            float parent_opacity;
            InternalNode* parent = &scene->m_Nodes[n->m_ParentIndex];
            CalculateParentNodeTransformAndAlphaCached(scene, parent, parent_trans, parent_opacity, traversal_cache);
            out_transform = parent_trans * out_transform;
            if (node.m_InheritAlpha)
            {
                out_opacity *= parent_opacity;
            }
        }

        cache_data.m_Transform = out_transform;
        cache_data.m_Opacity = out_opacity;
    }

    inline void CalculateNodeTransformAndAlphaCached(HScene scene, InternalNode* n, const CalculateNodeTransformFlags flags, Matrix4& out_transform, float& out_opacity)
    {
        const Node& node = n->m_Node;
        if (node.m_DirtyLocal || scene->m_ResChanged)
        {
            UpdateLocalTransform(scene, n);
        }
        out_transform = node.m_LocalTransform;
        CalculateNodeExtents(node, flags, out_transform);

        out_opacity = node.m_Properties[dmGui::PROPERTY_COLOR].getW();
        if (n->m_ParentIndex != INVALID_INDEX)
        {
            Matrix4 parent_trans;
            float parent_opacity;
            InternalNode* parent = &scene->m_Nodes[n->m_ParentIndex];
            CalculateParentNodeTransformAndAlphaCached(scene, parent, parent_trans, parent_opacity, scene->m_Context->m_SceneTraversalCache);
            out_transform = parent_trans * out_transform;
            if (node.m_InheritAlpha)
            {
                out_opacity *= parent_opacity;
            }
        }
    }

    inline void CalculateParentNodeTransform(HScene scene, InternalNode* n, Matrix4& out_transform)
    {
        const Node& node = n->m_Node;
        if (node.m_DirtyLocal || scene->m_ResChanged)
        {
            UpdateLocalTransform(scene, n);
        }
        out_transform = node.m_LocalTransform;

        if (n->m_ParentIndex != INVALID_INDEX)
        {
            Matrix4 parent_trans;
            InternalNode* parent = &scene->m_Nodes[n->m_ParentIndex];
            CalculateParentNodeTransform(scene, parent, parent_trans);
            out_transform = parent_trans * out_transform;
        }
    }

    void CalculateNodeTransform(HScene scene, InternalNode* n, const CalculateNodeTransformFlags flags, Matrix4& out_transform)
    {
        const Node& node = n->m_Node;
        if (node.m_DirtyLocal || scene->m_ResChanged)
        {
            UpdateLocalTransform(scene, n);
        }
        out_transform = node.m_LocalTransform;
        CalculateNodeExtents(node, flags, out_transform);

        if (n->m_ParentIndex != INVALID_INDEX)
        {
            Matrix4 parent_trans;
            InternalNode* parent = &scene->m_Nodes[n->m_ParentIndex];
            CalculateParentNodeTransform(scene, parent, parent_trans);
            out_transform = parent_trans * out_transform;
        }
    }

    static void ResetScript(HScript script) {
        memset(script, 0, sizeof(Script));
        for (int i = 0; i < MAX_SCRIPT_FUNCTION_COUNT; ++i) {
            script->m_FunctionReferences[i] = LUA_NOREF;
        }
        script->m_InstanceReference = LUA_NOREF;
    }

    HScript NewScript(HContext context)
    {
        lua_State* L = context->m_LuaState;
        Script* script = (Script*)lua_newuserdata(L, sizeof(Script));
        ResetScript(script);
        script->m_Context = context;

        luaL_getmetatable(L, GUI_SCRIPT);
        lua_setmetatable(L, -2);

        script->m_InstanceReference = luaL_ref(L, LUA_REGISTRYINDEX);

        return script;
    }

    void DeleteScript(HScript script)
    {
        lua_State* L = script->m_Context->m_LuaState;
        for (int i = 0; i < MAX_SCRIPT_FUNCTION_COUNT; ++i) {
            if (script->m_FunctionReferences[i] != LUA_NOREF) {
                luaL_unref(L, LUA_REGISTRYINDEX, script->m_FunctionReferences[i]);
            }
        }
        luaL_unref(L, LUA_REGISTRYINDEX, script->m_InstanceReference);
        script->~Script();
        ResetScript(script);
    }

    Result SetScript(HScript script, dmLuaDDF::LuaSource *source)
    {
        lua_State* L = script->m_Context->m_LuaState;
        int top = lua_gettop(L);
        (void) top;

        Result res = RESULT_OK;

        int ret = dmScript::LuaLoad(L, source);
        if (ret != 0)
        {
            dmLogError("Error compiling script: %s", lua_tostring(L,-1));
            lua_pop(L, 1);
            res = RESULT_SYNTAX_ERROR;
            goto bail;
        }

        lua_rawgeti(L, LUA_REGISTRYINDEX, script->m_InstanceReference);
        dmScript::SetInstance(L);

        ret = dmScript::PCall(L, 0, LUA_MULTRET);

        lua_pushnil(L);
        dmScript::SetInstance(L);

        if (ret != 0)
        {
            res = RESULT_SCRIPT_ERROR;
            goto bail;
        }

        for (uint32_t i = 0; i < MAX_SCRIPT_FUNCTION_COUNT; ++i)
        {
            if (script->m_FunctionReferences[i] != LUA_NOREF)
            {
                luaL_unref(L, LUA_REGISTRYINDEX, script->m_FunctionReferences[i]);
                script->m_FunctionReferences[i] = LUA_NOREF;
            }

            lua_getglobal(L, SCRIPT_FUNCTION_NAMES[i]);
            if (lua_type(L, -1) == LUA_TFUNCTION)
            {
                script->m_FunctionReferences[i] = luaL_ref(L, LUA_REGISTRYINDEX);
            }
            else
            {
                if (lua_isnil(L, -1) == 0)
                    dmLogWarning("'%s' is not a function (%s)", SCRIPT_FUNCTION_NAMES[i], source->m_Filename);
                lua_pop(L, 1);
            }

            lua_pushnil(L);
            lua_setglobal(L, SCRIPT_FUNCTION_NAMES[i]);
        }

bail:
        assert(top == lua_gettop(L));
        return res;
    }

    lua_State* GetLuaState(HContext context)
    {
        return context->m_LuaState;
    }

}  // namespace dmGui
