#include <map>
#include <stdlib.h>
#include <gtest/gtest.h>
#include <dlib/hash.h>
#include <dlib/math.h>
#include <dlib/message.h>
#include <script/script.h>
#include <script/lua_source_ddf.h>
#include "../gui.h"
#include "../gui_private.h"
#include "test_gui_ddf.h"

extern "C"
{
#include "lua/lua.h"
#include "lua/lauxlib.h"
}

extern unsigned char BUG352_LUA[];
extern uint32_t BUG352_LUA_SIZE;


/*
 * Basic
 *  - Create scene
 *  - Create nodes
 *  - Stress tests
 *
 * self table
 *
 * reload script
 *
 * lua script basics
 *  - New/Delete node
 *
 * "Namespaces"
 *
 * Animation
 *
 * Render
 *
 */

#define MAX_NODES 64U
#define MAX_ANIMATIONS 32U

void GetURLCallback(dmGui::HScene scene, dmMessage::URL* url);

uintptr_t GetUserDataCallback(dmGui::HScene scene);

dmhash_t ResolvePathCallback(dmGui::HScene scene, const char* path, uint32_t path_size);

void GetTextMetricsCallback(const void* font, const char* text, float width, bool line_break, dmGui::TextMetrics* out_metrics);

static const float EPSILON = 0.000001f;
static const float TEXT_GLYPH_WIDTH = 1.0f;
static const float TEXT_MAX_ASCENT = 0.75f;
static const float TEXT_MAX_DESCENT = 0.25f;

static dmLuaDDF::LuaSource* LuaSourceFromStr(const char *str, int length = -1)
{
    static dmLuaDDF::LuaSource src;
    memset(&src, 0x00, sizeof(dmLuaDDF::LuaSource));
    src.m_Script.m_Data = (uint8_t*) str;
    src.m_Script.m_Count = (length != -1) ? length : strlen(str);
    src.m_Filename = "dummy";
    return &src;
}

class dmGuiTest : public ::testing::Test
{
public:
    dmScript::HContext m_ScriptContext;
    dmGui::HContext m_Context;
    dmGui::HScene m_Scene;
    dmMessage::HSocket m_Socket;
    dmGui::HScript m_Script;
    std::map<std::string, dmGui::HNode> m_NodeTextToNode;
    std::map<std::string, Point3> m_NodeTextToRenderedPosition;
    std::map<std::string, Vector3> m_NodeTextToRenderedSize;

    virtual void SetUp()
    {
        m_ScriptContext = dmScript::NewContext(0, 0);
        dmScript::Initialize(m_ScriptContext);

        dmMessage::NewSocket("test_m_Socket", &m_Socket);
        dmGui::NewContextParams context_params;
        context_params.m_ScriptContext = m_ScriptContext;
        context_params.m_GetURLCallback = GetURLCallback;
        context_params.m_GetUserDataCallback = GetUserDataCallback;
        context_params.m_ResolvePathCallback = ResolvePathCallback;
        context_params.m_GetTextMetricsCallback = GetTextMetricsCallback;

        m_Context = dmGui::NewContext(&context_params);
        // Bogus font for the metric callback to be run (not actually using the default font)
        dmGui::SetDefaultFont(m_Context, (void*)0x1);
        dmGui::NewSceneParams params;
        params.m_MaxNodes = MAX_NODES;
        params.m_MaxAnimations = MAX_ANIMATIONS;
        params.m_UserData = this;
        m_Scene = dmGui::NewScene(m_Context, &params);
        m_Script = dmGui::NewScript(m_Context);
        dmGui::SetSceneScript(m_Scene, m_Script);
    }

    static void RenderNodes(dmGui::HScene scene, const dmGui::RenderEntry* nodes, const Vectormath::Aos::Matrix4* node_transforms, const Vectormath::Aos::Vector4* node_colors,
            const dmGui::StencilScope** stencil_scopes, uint32_t node_count, void* context)
    {
        dmGuiTest* self = (dmGuiTest*) context;
        // The node is defined to completely cover the local space (0,1),(0,1)
        Vector4 origin(0.f, 0.f, 0.0f, 1.0f);
        Vector4 unit(1.0f, 1.0f, 0.0f, 1.0f);
        for (uint32_t i = 0; i < node_count; ++i)
        {
            Vector4 o = node_transforms[i] * origin;
            Vector4 u = node_transforms[i] * unit;
            const char* text = dmGui::GetNodeText(scene, nodes[i].m_Node);
            if (text) {
                self->m_NodeTextToRenderedPosition[text] = Point3(o.getXYZ());
                self->m_NodeTextToRenderedSize[text] = Vector3((u - o).getXYZ());
            }
        }
    }

    virtual void TearDown()
    {
        dmGui::DeleteScript(m_Script);
        dmGui::DeleteScene(m_Scene);
        dmGui::DeleteContext(m_Context, m_ScriptContext);
        dmMessage::DeleteSocket(m_Socket);
        dmScript::Finalize(m_ScriptContext);
        dmScript::DeleteContext(m_ScriptContext);
    }
};

void GetURLCallback(dmGui::HScene scene, dmMessage::URL* url)
{
    dmGuiTest* test = (dmGuiTest*)dmGui::GetSceneUserData(scene);
    url->m_Socket = test->m_Socket;
}

uintptr_t GetUserDataCallback(dmGui::HScene scene)
{
    return (uintptr_t)dmGui::GetSceneUserData(scene);
}

dmhash_t ResolvePathCallback(dmGui::HScene scene, const char* path, uint32_t path_size)
{
    return dmHashBuffer64(path, path_size);
}

void GetTextMetricsCallback(const void* font, const char* text, float width, bool line_break, dmGui::TextMetrics* out_metrics)
{
    out_metrics->m_Width = strlen(text) * TEXT_GLYPH_WIDTH;
    out_metrics->m_MaxAscent = TEXT_MAX_ASCENT;
    out_metrics->m_MaxDescent = TEXT_MAX_DESCENT;
}

static bool SetScript(dmGui::HScript script, const char* source)
{
    dmGui::Result r;
    r = dmGui::SetScript(script, LuaSourceFromStr(source));
    return dmGui::RESULT_OK == r;
}

TEST_F(dmGuiTest, Basic)
{
    for (uint32_t i = 0; i < MAX_NODES; ++i)
    {
        dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
        ASSERT_NE((dmGui::HNode) 0, node);
    }

    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    ASSERT_EQ((dmGui::HNode) 0, node);
    ASSERT_EQ(m_Script, dmGui::GetSceneScript(m_Scene));
}

// Test that a newly re-created node has default values
TEST_F(dmGuiTest, RecreateNodes)
{
    uint32_t n = MAX_NODES + 1;
    for (uint32_t i = 0; i < n; ++i)
    {
        dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
        ASSERT_NE((dmGui::HNode) 0, node);
        ASSERT_EQ(dmGui::PIVOT_CENTER, dmGui::GetNodePivot(m_Scene, node));
        dmGui::SetNodePivot(m_Scene, node, dmGui::PIVOT_E);
        ASSERT_EQ(dmGui::PIVOT_E, dmGui::GetNodePivot(m_Scene, node));

        dmGui::DeleteNode(m_Scene, node);
    }
}

TEST_F(dmGuiTest, Name)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    ASSERT_NE((dmGui::HNode) 0, node);

    dmGui::HNode get_node = dmGui::GetNodeById(m_Scene, "my_node");
    ASSERT_EQ((dmGui::HNode) 0, get_node);

    dmGui::SetNodeId(m_Scene, node, "my_node");
    get_node = dmGui::GetNodeById(m_Scene, "my_node");
    ASSERT_EQ(node, get_node);

    const char* s = "function init(self)\n"
                    "    local n = gui.get_node(\"my_node\")\n"
                    "    local id = gui.get_id(n)\n"
                    "    local n2 = gui.get_node(id)\n"
                    "    assert(n == n2)\n"
                    "end\n";

    ASSERT_TRUE(SetScript(m_Script, s));
    ASSERT_EQ(dmGui::RESULT_OK, dmGui::InitScene(m_Scene));
}

TEST_F(dmGuiTest, TextureFontLayer)
{
    int t1, t2;
    int f1, f2;

    dmGui::AddTexture(m_Scene, "t1", (void*) &t1);
    dmGui::AddTexture(m_Scene, "t2", (void*) &t2);
    dmGui::AddFont(m_Scene, "f1", &f1);
    dmGui::AddFont(m_Scene, "f2", &f2);
    dmGui::AddLayer(m_Scene, "l1");
    dmGui::AddLayer(m_Scene, "l2");

    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    ASSERT_NE((dmGui::HNode) 0, node);

    dmGui::Result r;

    // Texture
    r = dmGui::SetNodeTexture(m_Scene, node, "foo");
    ASSERT_EQ(r, dmGui::RESULT_RESOURCE_NOT_FOUND);

    r = dmGui::SetNodeTexture(m_Scene, node, "f1");
    ASSERT_EQ(r, dmGui::RESULT_RESOURCE_NOT_FOUND);

    r = dmGui::SetNodeTexture(m_Scene, node, "t1");
    ASSERT_EQ(r, dmGui::RESULT_OK);

    r = dmGui::SetNodeTexture(m_Scene, node, "t2");
    ASSERT_EQ(r, dmGui::RESULT_OK);

    dmGui::AddTexture(m_Scene, "t2", &t1);
    ASSERT_EQ(&t1, m_Scene->m_Nodes[node & 0xffff].m_Node.m_Texture);

    dmGui::RemoveTexture(m_Scene, "t2");
    ASSERT_EQ((void*)0, m_Scene->m_Nodes[node & 0xffff].m_Node.m_Texture);

    r = dmGui::SetNodeTexture(m_Scene, node, "t2");
    ASSERT_EQ(r, dmGui::RESULT_RESOURCE_NOT_FOUND);

    dmGui::ClearTextures(m_Scene);
    r = dmGui::SetNodeTexture(m_Scene, node, "t1");
    ASSERT_EQ(r, dmGui::RESULT_RESOURCE_NOT_FOUND);

    // Font
    r = dmGui::SetNodeFont(m_Scene, node, "foo");
    ASSERT_EQ(r, dmGui::RESULT_RESOURCE_NOT_FOUND);

    r = dmGui::SetNodeFont(m_Scene, node, "t1");
    ASSERT_EQ(r, dmGui::RESULT_RESOURCE_NOT_FOUND);

    r = dmGui::SetNodeFont(m_Scene, node, "f1");
    ASSERT_EQ(r, dmGui::RESULT_OK);

    r = dmGui::SetNodeFont(m_Scene, node, "f2");
    ASSERT_EQ(r, dmGui::RESULT_OK);

    dmGui::AddFont(m_Scene, "f2", &f1);
    ASSERT_EQ(&f1, m_Scene->m_Nodes[node & 0xffff].m_Node.m_Font);

    dmGui::RemoveFont(m_Scene, "f2");
    ASSERT_EQ((void*)0, m_Scene->m_Nodes[node & 0xffff].m_Node.m_Font);

    dmGui::ClearFonts(m_Scene);
    r = dmGui::SetNodeFont(m_Scene, node, "f1");
    ASSERT_EQ(r, dmGui::RESULT_RESOURCE_NOT_FOUND);

    // Layer
    r = dmGui::SetNodeLayer(m_Scene, node, "foo");
    ASSERT_EQ(r, dmGui::RESULT_RESOURCE_NOT_FOUND);

    r = dmGui::SetNodeLayer(m_Scene, node, "l1");
    ASSERT_EQ(r, dmGui::RESULT_OK);

    r = dmGui::SetNodeLayer(m_Scene, node, "l2");
    ASSERT_EQ(r, dmGui::RESULT_OK);

    dmGui::DeleteNode(m_Scene, node);
}

static void* DynamicNewTexture(dmGui::HScene scene, uint32_t width, uint32_t height, dmImage::Type type, const void* buffer, void* context)
{
    return malloc(16);
}

static void DynamicDeleteTexture(dmGui::HScene scene, void* texture, void* context)
{
    assert(texture);
    free(texture);
}

static void DynamicSetTextureData(dmGui::HScene scene, void* texture, uint32_t width, uint32_t height, dmImage::Type type, const void* buffer, void* context)
{
}

static void DynamicRenderNodes(dmGui::HScene scene, const dmGui::RenderEntry* nodes, const Vectormath::Aos::Matrix4* node_transforms, const Vectormath::Aos::Vector4* node_colors,
        const dmGui::StencilScope** stencil_scopes, uint32_t node_count, void* context)
{
    uint32_t* count = (uint32_t*) context;
    for (uint32_t i = 0; i < node_count; ++i) {
        dmGui::HNode node = nodes[i].m_Node;
        dmhash_t id = dmGui::GetNodeTextureId(scene, node);
        if ((id == dmHashString64("t1") || id == dmHashString64("t2")) && dmGui::GetNodeTexture(scene, node)) {
            *count = *count + 1;
        }
    }
}

TEST_F(dmGuiTest, DynamicTexture)
{
    uint32_t count = 0;
    dmGui::RenderSceneParams rp;
    rp.m_RenderNodes = DynamicRenderNodes;
    rp.m_NewTexture = DynamicNewTexture;
    rp.m_DeleteTexture = DynamicDeleteTexture;
    rp.m_SetTextureData = DynamicSetTextureData;

    const int width = 2;
    const int height = 2;
    char data[width * height * 3] = { 0 };

    // Test creation/deletion in the same frame (case 2355)
    dmGui::Result r;
    r = dmGui::NewDynamicTexture(m_Scene, "t1", width, height, dmImage::TYPE_RGB, data, sizeof(data));
    ASSERT_EQ(r, dmGui::RESULT_OK);
    r = dmGui::DeleteDynamicTexture(m_Scene, "t1");
    ASSERT_EQ(r, dmGui::RESULT_OK);
    dmGui::RenderScene(m_Scene, rp, &count);

    r = dmGui::NewDynamicTexture(m_Scene, "t1", width, height, dmImage::TYPE_RGB, data, sizeof(data));
    ASSERT_EQ(r, dmGui::RESULT_OK);

    r = dmGui::SetDynamicTextureData(m_Scene, "t1", width, height, dmImage::TYPE_RGB, data, sizeof(data));
    ASSERT_EQ(r, dmGui::RESULT_OK);

    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    ASSERT_NE((dmGui::HNode) 0, node);

    r = dmGui::SetNodeTexture(m_Scene, node, "foo");
    ASSERT_EQ(r, dmGui::RESULT_RESOURCE_NOT_FOUND);

    r = dmGui::SetNodeTexture(m_Scene, node, "t1");
    ASSERT_EQ(r, dmGui::RESULT_OK);

    dmGui::RenderScene(m_Scene, rp, &count);
    ASSERT_EQ(1U, count);

    r = dmGui::DeleteDynamicTexture(m_Scene, "t1");
    ASSERT_EQ(r, dmGui::RESULT_OK);

    // Recreate the texture again (without RenderScene)
    r = dmGui::NewDynamicTexture(m_Scene, "t1", width, height, dmImage::TYPE_RGB, data, sizeof(data));
    ASSERT_EQ(r, dmGui::RESULT_OK);

    r = dmGui::DeleteDynamicTexture(m_Scene, "t1");
    ASSERT_EQ(r, dmGui::RESULT_OK);

    // Set data on deleted texture
    r = dmGui::SetDynamicTextureData(m_Scene, "t1", width, height, dmImage::TYPE_RGB, data, sizeof(data));
    ASSERT_EQ(r, dmGui::RESULT_INVAL_ERROR);

    dmGui::DeleteNode(m_Scene, node);

    dmGui::RenderScene(m_Scene, rp, &count);
}

TEST_F(dmGuiTest, ScriptTextureFontLayer)
{
    int t;
    int f;

    dmGui::AddTexture(m_Scene, "t", (void*) &t);
    dmGui::AddFont(m_Scene, "f", &f);
    dmGui::AddLayer(m_Scene, "l");

    const char* id = "n";
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    ASSERT_NE((dmGui::HNode) 0, node);
    dmGui::SetNodeId(m_Scene, node, id);

    const char* s = "function init(self)\n"
                    "    local n = gui.get_node(\"n\")\n"
                    "    gui.set_texture(n, \"t\")\n"
                    "    local t = gui.get_texture(n)\n"
                    "    gui.set_texture(n, t)\n"
                    "    local t2 = gui.get_texture(n)\n"
                    "    assert(t == t2)\n"
                    "    gui.set_font(n, \"f\")\n"
                    "    local f = gui.get_font(n)\n"
                    "    gui.set_font(n, f)\n"
                    "    local f2 = gui.get_font(n)\n"
                    "    assert(f == f2)\n"
                    "    gui.set_layer(n, \"l\")\n"
                    "    local l = gui.get_layer(n)\n"
                    "    gui.set_layer(n, l)\n"
                    "    local l2 = gui.get_layer(n)\n"
                    "    assert(l == l2)\n"
                    "end\n";

    ASSERT_TRUE(SetScript(m_Script, s));
    ASSERT_EQ(dmGui::RESULT_OK, dmGui::InitScene(m_Scene));
}

TEST_F(dmGuiTest, ScriptDynamicTexture)
{
    const char* id = "n";
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    ASSERT_NE((dmGui::HNode) 0, node);
    dmGui::SetNodeId(m_Scene, node, id);

    const char* s = "function init(self)\n"
                    "    local r = gui.new_texture('t', 2, 2, 'rgb', string.rep('\\0', 2 * 2 * 3))\n"
                    "    assert(r == true)\n"
                    "    local r = gui.set_texture_data('t', 2, 2, 'rgb', string.rep('\\0', 2 * 2 * 3))\n"
                    "    assert(r == true)\n"
                    "    local n = gui.get_node('n')\n"
                    "    gui.set_texture(n, 't')\n"
                    "    gui.delete_texture('t')\n"
                    "end\n";

    ASSERT_TRUE(SetScript(m_Script, s));
    ASSERT_EQ(dmGui::RESULT_OK, dmGui::InitScene(m_Scene));
    ASSERT_EQ(dmGui::RESULT_OK, dmGui::UpdateScene(m_Scene, 1.0f / 60.0f));

    dmGui::RenderSceneParams rp;
    rp.m_RenderNodes = DynamicRenderNodes;
    rp.m_NewTexture = DynamicNewTexture;
    rp.m_DeleteTexture = DynamicDeleteTexture;
    rp.m_SetTextureData = DynamicSetTextureData;
    dmGui::RenderScene(m_Scene, rp, this);
}

TEST_F(dmGuiTest, ScriptIndex)
{
    const char* id = "n";
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    ASSERT_NE((dmGui::HNode) 0, node);
    dmGui::SetNodeId(m_Scene, node, id);

    const char* id2 = "n2";
    node = dmGui::NewNode(m_Scene, Point3(5,5,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    ASSERT_NE((dmGui::HNode) 0, node);
    dmGui::SetNodeId(m_Scene, node, id2);

    const char* s = "function init(self)\n"
                    "    local n = gui.get_node(\"n\")\n"
                    "    assert(gui.get_index(n) == 0)\n"
                    "    local n2 = gui.get_node(\"n2\")\n"
                    "    assert(gui.get_index(n2) == 1)\n"
                    "end\n";

    ASSERT_TRUE(SetScript(m_Script, s));
    ASSERT_EQ(dmGui::RESULT_OK, dmGui::InitScene(m_Scene));
}

TEST_F(dmGuiTest, NewDeleteNode)
{
    std::map<dmGui::HNode, float> node_to_pos;

    for (uint32_t i = 0; i < MAX_NODES; ++i)
    {
        dmGui::HNode node = dmGui::NewNode(m_Scene, Point3((float) i, 0, 0), Vector3(0, 0 ,0), dmGui::NODE_TYPE_BOX);
        ASSERT_NE((dmGui::HNode) 0, node);
        node_to_pos[node] = (float) i;
    }

    for (uint32_t i = 0; i < 1000; ++i)
    {
        ASSERT_EQ(MAX_NODES, node_to_pos.size());

        std::map<dmGui::HNode, float>::iterator iter;
        for (iter = node_to_pos.begin(); iter != node_to_pos.end(); ++iter)
        {
            dmGui::HNode node = iter->first;
            ASSERT_EQ(iter->second, dmGui::GetNodePosition(m_Scene, node).getX());
        }
        int index = rand() % MAX_NODES;
        iter = node_to_pos.begin();
        for (int j = 0; j < index; ++j)
            ++iter;
        dmGui::HNode node_to_remove = iter->first;
        node_to_pos.erase(iter);
        dmGui::DeleteNode(m_Scene, node_to_remove);

        dmGui::HNode new_node = dmGui::NewNode(m_Scene, Point3((float) i, 0, 0), Vector3(0, 0 ,0), dmGui::NODE_TYPE_BOX);
        ASSERT_NE((dmGui::HNode) 0, new_node);
        node_to_pos[new_node] = (float) i;
    }
}

TEST_F(dmGuiTest, ClearNodes)
{
    for (uint32_t i = 0; i < MAX_NODES; ++i)
    {
        dmGui::HNode node = dmGui::NewNode(m_Scene, Point3((float) i, 0, 0), Vector3(0, 0 ,0), dmGui::NODE_TYPE_BOX);
        ASSERT_NE((dmGui::HNode) 0, node);
    }

    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0, 0, 0), Vector3(0, 0 ,0), dmGui::NODE_TYPE_BOX);
    ASSERT_EQ((dmGui::HNode) 0, node);

    dmGui::ClearNodes(m_Scene);
    for (uint32_t i = 0; i < MAX_NODES; ++i)
    {
        dmGui::HNode node = dmGui::NewNode(m_Scene, Point3((float) i, 0, 0), Vector3(0, 0 ,0), dmGui::NODE_TYPE_BOX);
        ASSERT_NE((dmGui::HNode) 0, node);
    }
}

TEST_F(dmGuiTest, AnimateNode)
{
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    for (uint32_t i = 0; i < MAX_ANIMATIONS + 1; ++i)
    {
        dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
        dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0.5f, 0, 0, 0);

        ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

        // Delay
        for (int i = 0; i < 30; ++i)
            dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);

        ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

        // Animation
        for (int i = 0; i < 60; ++i)
        {
            dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        }

        ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f, EPSILON);
        dmGui::DeleteNode(m_Scene, node);
    }
}

TEST_F(dmGuiTest, Playback)
{
    const float duration = 4 / 60.0f;
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);

    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(0,0,0), dmGui::NODE_TYPE_BOX);

    dmGui::SetNodePosition(m_Scene, node, Point3(0,0,0));
    dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_BACKWARD, duration, 0, 0, 0, 0);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 3.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 2.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f / 4.0f, EPSILON);

    dmGui::SetNodePosition(m_Scene, node, Point3(0,0,0));
    dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_LOOP_FORWARD, duration, 0, 0, 0, 0);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 2.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 3.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 4.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f / 4.0f, EPSILON);

    dmGui::SetNodePosition(m_Scene, node, Point3(0,0,0));
    dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_LOOP_BACKWARD, duration, 0, 0, 0, 0);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 3.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 2.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 3.0f / 4.0f, EPSILON);

    dmGui::SetNodePosition(m_Scene, node, Point3(0,0,0));
    dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_LOOP_PINGPONG, duration, 0, 0, 0, 0);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 2.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 3.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 4.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 3.0f / 4.0f, EPSILON);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 2.0f / 4.0f, EPSILON);


    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, AnimateNode2)
{
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.1f, 0, 0, 0, 0);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    // Animation
    for (int i = 0; i < 200; ++i)
    {
        dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    }

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f, EPSILON);
    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, AnimateNodeDelayUnderFlow)
{
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 2.0f / 60.0f, 1.0f / 60.0f, 0, 0, 0);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    dmGui::UpdateScene(m_Scene, 0.5f * (1.0f / 60.0f));
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    dmGui::UpdateScene(m_Scene, 1.0f * (1.0f / 60.0f));
    // With underflow compensation: -(0.5 / 60.) + dt = 0.5 / 60
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.25f, EPSILON);

    // Animation done
    dmGui::UpdateScene(m_Scene, 1.5f * (1.0f / 60.0f));
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f, EPSILON);

    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, AnimateNodeDelete)
{
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.1f, 0, 0, 0, 0);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);
    dmGui::HNode node2 = 0;

    // Animation
    for (int i = 0; i < 60; ++i)
    {
        if (i == 30)
        {
            dmGui::DeleteNode(m_Scene, node);
            node2 = dmGui::NewNode(m_Scene, Point3(2,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
        }

        dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    }

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node2).getX(), 2.0f, EPSILON);
    dmGui::DeleteNode(m_Scene, node2);
}

uint32_t MyAnimationCompleteCount = 0;
void MyAnimationComplete(dmGui::HScene scene,
                         dmGui::HNode node,
                         void* userdata1,
                         void* userdata2)
{
    MyAnimationCompleteCount++;
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::AnimateNodeHash(scene, node, property, Vector4(2,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0, 0, 0, 0);
    // Check that we reached target position
    *(Point3*)userdata2 = dmGui::GetNodePosition(scene, node);
}

TEST_F(dmGuiTest, AnimateComplete)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    Point3 completed_position;
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0, &MyAnimationComplete, (void*) node, (void*)&completed_position);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    // Animation
    float dt = 1.0f / 60.0f;
    for (int i = 0; i < 60; ++i)
    {
        dmGui::UpdateScene(m_Scene, dt);
    }
    Point3 position = dmGui::GetNodePosition(m_Scene, node);
    ASSERT_NEAR(position.getX(), 1.0f, EPSILON);
    ASSERT_EQ(1.0f, completed_position.getX());

    // Animation
    for (int i = 0; i < 60; ++i)
    {
        dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    }
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 2.0f, EPSILON);

    dmGui::DeleteNode(m_Scene, node);
}

void MyPingPongComplete2(dmGui::HScene scene,
                         dmGui::HNode node,
                         void* userdata1,
                         void* userdata2);

uint32_t PingPongCount = 0;
void MyPingPongComplete1(dmGui::HScene scene,
                        dmGui::HNode node,
                        void* userdata1,
                        void* userdata2)
{
    ++PingPongCount;
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::AnimateNodeHash(scene, node, property, Vector4(0,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0, &MyPingPongComplete2, (void*) node, 0);
}

void MyPingPongComplete2(dmGui::HScene scene,
                         dmGui::HNode node,
                         void* userdata1,
                         void* userdata2)
{
    ++PingPongCount;
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::AnimateNodeHash(scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0, &MyPingPongComplete1, (void*) node, 0);
}

TEST_F(dmGuiTest, PingPong)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::AnimateNodeHash(m_Scene, node, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0, &MyPingPongComplete1, (void*) node, 0);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    for (int j = 0; j < 10; ++j)
    {
        // Animation
        for (int i = 0; i < 60; ++i)
        {
            dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        }
    }

    ASSERT_EQ(10U, PingPongCount);
    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, AnimateNodeOfDisabledParent)
{
    dmGui::HNode parent = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::HNode child = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeParent(m_Scene, child, parent);
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::AnimateNodeHash(m_Scene, child, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0.0f, 0, 0, 0);

    dmGui::SetNodeEnabled(m_Scene, parent, false);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, child).getX(), 0.0f, EPSILON);

    // Delay
    for (int i = 0; i < 30; ++i)
        dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, child).getX(), 0.0f, EPSILON);

    dmGui::DeleteNode(m_Scene, child);
    dmGui::DeleteNode(m_Scene, parent);
}

TEST_F(dmGuiTest, Reset)
{
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, Point3(10, 20, 30), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::HNode n2 = dmGui::NewNode(m_Scene, Point3(100, 200, 300), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    // Set reset point only for the first node
    dmGui::SetNodeResetPoint(m_Scene, n1);
    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::AnimateNodeHash(m_Scene, n1, property, Vector4(1, 0, 0, 0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0.0f, 0, 0, 0);
    dmGui::AnimateNodeHash(m_Scene, n2, property, Vector4(101, 0, 0, 0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0.0f, 0, 0, 0);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);

    dmGui::ResetNodes(m_Scene);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, n1).getX(), 10.0f, EPSILON);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, n2).getX(), 100.0f + 1.0f / 60.0f, EPSILON);

    dmGui::DeleteNode(m_Scene, n1);
    dmGui::DeleteNode(m_Scene, n2);
}

TEST_F(dmGuiTest, ScriptAnimate)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self)\n"
                    "    self.node = gui.get_node(\"n\")\n"
                    "    gui.animate(self.node, gui.PROP_POSITION, vmath.vector4(1,0,0,0), gui.EASING_NONE, 1, 0.5)\n"
                    "end\n"
                    "function final(self)\n"
                    "    gui.delete_node(self.node)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    // Delay
    for (int i = 0; i < 30; ++i)
    {
        r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        ASSERT_EQ(dmGui::RESULT_OK, r);
    }

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    // Animation
    for (int i = 0; i < 60; ++i)
    {
        r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        ASSERT_EQ(dmGui::RESULT_OK, r);
    }

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f, EPSILON);

    r = dmGui::FinalScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    ASSERT_EQ(m_Scene->m_NodePool.Capacity(), m_Scene->m_NodePool.Remaining());
}

TEST_F(dmGuiTest, ScriptPlayback)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self)\n"
                    "    self.node = gui.get_node(\"n\")\n"
                    "    gui.animate(self.node, gui.PROP_POSITION, vmath.vector4(1,0,0,0), gui.EASING_NONE, 1, 0, nil, gui.PLAYBACK_ONCE_BACKWARD)\n"
                    "end\n"
                    "function final(self)\n"
                    "    gui.delete_node(self.node)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    // Animation
    for (int i = 0; i < 60; ++i)
    {
        r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        ASSERT_EQ(dmGui::RESULT_OK, r);
    }

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    r = dmGui::FinalScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    ASSERT_EQ(m_Scene->m_NodePool.Capacity(), m_Scene->m_NodePool.Remaining());
}

TEST_F(dmGuiTest, ScriptAnimatePreserveAlpha)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self)\n"
                    "    self.node = gui.get_node(\"n\")\n"
                    "    gui.set_color(self.node, vmath.vector4(0,0,0,0.5))\n"
                    "    gui.animate(self.node, gui.PROP_COLOR, vmath.vector3(1,0,0), gui.EASING_NONE, 0.01)\n"
                    "end\n"
                    "function final(self)\n"
                    "    gui.delete_node(self.node)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    Vector4 color = dmGui::GetNodeProperty(m_Scene, node, dmGui::PROPERTY_COLOR);
    ASSERT_NEAR(color.getX(), 1.0f, EPSILON);
    ASSERT_NEAR(color.getW(), 0.5f, EPSILON);

    r = dmGui::FinalScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, ScriptAnimateComponent)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self)\n"
                    "    self.node = gui.get_node(\"n\")\n"
                    "    gui.set_color(self.node, vmath.vector4(0.1,0.2,0.3,0.4))\n"
                    "    gui.animate(self.node, \"color.z\", 0.9, gui.EASING_NONE, 0.01)\n"
                    "end\n"
                    "function final(self)\n"
                    "    gui.delete_node(self.node)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    Vector4 color = dmGui::GetNodeProperty(m_Scene, node, dmGui::PROPERTY_COLOR);
    ASSERT_NEAR(color.getX(), 0.1f, EPSILON);
    ASSERT_NEAR(color.getY(), 0.2f, EPSILON);
    ASSERT_NEAR(color.getZ(), 0.9f, EPSILON);
    ASSERT_NEAR(color.getW(), 0.4f, EPSILON);

    r = dmGui::FinalScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, ScriptAnimateComplete)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function cb(self, node)\n"
                    "    assert(self.foobar == 123)\n"
                    "    gui.animate(node, gui.PROP_POSITION, vmath.vector4(2,0,0,0), gui.EASING_NONE, 0.5, 0)\n"
                    "end\n;"
                    "function init(self)\n"
                    "    self.foobar = 123\n"
                    "    gui.animate(gui.get_node(\"n\"), gui.PROP_POSITION, vmath.vector4(1,0,0,0), gui.EASING_NONE, 1, 0, cb)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    // Animation
    for (int i = 0; i < 60; ++i)
    {
        r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        ASSERT_EQ(dmGui::RESULT_OK, r);
    }
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 1.0f, EPSILON);

    // Animation
    for (int i = 0; i < 30; ++i)
    {
        r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        ASSERT_EQ(dmGui::RESULT_OK, r);
    }
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 2.0f, EPSILON);

    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, ScriptAnimateCompleteDelete)
{
    dmGui::HNode node1 = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::HNode node2 = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node1, "n1");
    dmGui::SetNodeId(m_Scene, node2, "n2");
    const char* s = "function cb(self, node)\n"
                    "    gui.delete_node(node)\n"
                    "end\n;"
                    "function init(self)\n"
                    "    gui.animate(gui.get_node(\"n1\"), gui.PROP_POSITION, vmath.vector4(1,0,0,0), gui.EASING_NONE, 1, 0, cb)\n"
                    "    gui.animate(gui.get_node(\"n2\"), gui.PROP_POSITION, vmath.vector4(1,0,0,0), gui.EASING_NONE, 1, 0, cb)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    uint32_t node_count = dmGui::GetNodeCount(m_Scene);
    ASSERT_EQ(2U, node_count);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node1).getX(), 0.0f, EPSILON);
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node2).getX(), 0.0f, EPSILON);
    // Animation
    for (int i = 0; i < 60; ++i)
    {
        r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        ASSERT_EQ(dmGui::RESULT_OK, r);
    }

    node_count = dmGui::GetNodeCount(m_Scene);
    ASSERT_EQ(0U, node_count);
}

TEST_F(dmGuiTest, ScriptAnimateCancel1)
{
    // Immediate cancel
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self)\n"
                    "    self.node = gui.get_node(\"n\")\n"
                    "    gui.animate(self.node, gui.PROP_COLOR, vmath.vector4(1,0,0,0), gui.EASING_NONE, 0.2)\n"
                    "    gui.cancel_animation(self.node, gui.PROP_COLOR)\n"
                    "end\n"
                    "function update(self, dt)\n"
                    "end\n"
                    "function final(self)\n"
                    "    gui.delete_node(gui.get_node(\"n\"))\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    // Animation
    for (int i = 0; i < 60; ++i)
    {
        r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        ASSERT_EQ(dmGui::RESULT_OK, r);
    }

    ASSERT_NEAR(dmGui::GetNodeProperty(m_Scene, node, dmGui::PROPERTY_COLOR).getX(), 1.0f, EPSILON);

    r = dmGui::FinalScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}


TEST_F(dmGuiTest, ScriptAnimateCancel2)
{
    // Cancel after 50% has elapsed
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self)\n"
                    "    self.node = gui.get_node(\"n\")\n"
                    "    gui.animate(self.node, gui.PROP_POSITION, vmath.vector4(10,0,0,0), gui.EASING_NONE, 1)\n"
                    "    self.nframes = 0\n"
                    "end\n"
                    "function update(self, dt)\n"
                    "    self.nframes = self.nframes + 1\n"
                    "    if self.nframes > 30 then\n"
                    "        gui.cancel_animation(self.node, gui.PROP_POSITION)\n"
                    "    end\n"
                    "end\n"
                    "function final(self)\n"
                    "    gui.delete_node(gui.get_node(\"n\"))\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 0.0f, EPSILON);

    // Animation
    for (int i = 0; i < 60; ++i)
    {
        r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        ASSERT_EQ(dmGui::RESULT_OK, r);
    }

    // We can't use epsilon here because of precision errors when the animation is canceled, so half precision (= twice the error)
    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node).getX(), 5.0f, 2*EPSILON);

    r = dmGui::FinalScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, ScriptOutOfNodes)
{
    const char* s = "function init(self)\n"
                    "    for i=1,10000 do\n"
                    "        gui.new_box_node(vmath.vector3(0,0,0), vmath.vector3(1,1,1))\n"
                    "    end\n"
                    "end\n"
                    "function update(self)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_SCRIPT_ERROR, r);
}

TEST_F(dmGuiTest, ScriptGetNode)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function update(self) local n = gui.get_node(\"n\")\n print(n)\n end";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, ScriptGetMissingNode)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function update(self) local n = gui.get_node(\"x\")\n print(n)\n end";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_SCRIPT_ERROR, r);

    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, ScriptGetDeletedNode)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function update(self) local n = gui.get_node(\"n\")\n print(n)\n end";
    dmGui::DeleteNode(m_Scene, node);

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_SCRIPT_ERROR, r);
}

TEST_F(dmGuiTest, ScriptEqNode)
{
    dmGui::HNode node1 = dmGui::NewNode(m_Scene, Point3(1,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::HNode node2 = dmGui::NewNode(m_Scene, Point3(2,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node1, "n");
    dmGui::SetNodeId(m_Scene, node2, "m");

    const char* s = "function update(self)\n"
                    "local n1 = gui.get_node(\"n\")\n "
                    "local n2 = gui.get_node(\"n\")\n "
                    "local m = gui.get_node(\"m\")\n "
                    "assert(n1 == n2)\n"
                    "assert(m ~= n1)\n"
                    "assert(m ~= n2)\n"
                    "assert(m ~= 1)\n"
                    "assert(1 ~= m)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::DeleteNode(m_Scene, node1);
    dmGui::DeleteNode(m_Scene, node2);
}

TEST_F(dmGuiTest, ScriptNewNode)
{
    const char* s = "function init(self)\n"
                    "    self.n1 = gui.new_box_node(vmath.vector3(0,0,0), vmath.vector3(1,1,1))"
                    "    self.n2 = gui.new_text_node(vmath.vector3(0,0,0), \"My Node\")"
                    "end\n"
                    "function update(self)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, ScriptNewNodeVec4)
{
    const char* s = "function init(self)\n"
                    "    self.n1 = gui.new_box_node(vmath.vector4(0,0,0,0), vmath.vector3(1,1,1))"
                    "    self.n2 = gui.new_text_node(vmath.vector4(0,0,0,0), \"My Node\")"
                    "end\n"
                    "function update(self)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, ScriptGetSet)
{
    const char* s = "function init(self)\n"
                    "    self.n1 = gui.new_box_node(vmath.vector4(0,0,0,0), vmath.vector3(1,1,1))\n"
                    "    local p = gui.get_position(self.n1)\n"
                    "    assert(string.find(tostring(p), \"vector3\") ~= nil)\n"
                    "    gui.set_position(self.n1, p)\n"
                    "    local s = gui.get_scale(self.n1)\n"
                    "    assert(string.find(tostring(s), \"vector3\") ~= nil)\n"
                    "    gui.set_scale(self.n1, s)\n"
                    "    local r = gui.get_rotation(self.n1)\n"
                    "    assert(string.find(tostring(r), \"vector3\") ~= nil)\n"
                    "    gui.set_rotation(self.n1, r)\n"
                    "    local c = gui.get_color(self.n1)\n"
                    "    assert(string.find(tostring(c), \"vector4\") ~= nil)\n"
                    "    gui.set_color(self.n1, c)\n"
                    "    gui.set_color(self.n1, vmath.vector4(0, 0, 0, 1))\n"
                    "    gui.set_color(self.n1, vmath.vector3(0, 0, 0))\n"
                    "    c = gui.get_color(self.n1)\n"
                    "    assert(c.w == 1)\n"
                   "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, ScriptInput)
{
    const char* s = "function update(self)\n"
                    "   assert(g_value == 123)\n"
                    "end\n"
                    "function on_input(self, action_id, action)\n"
                    "   if(action_id == hash(\"SPACE\")) then\n"
                    "       g_value = 123\n"
                    "   end\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::InputAction input_action;
    input_action.m_ActionId = dmHashString64("SPACE");
    bool consumed;
    r = dmGui::DispatchInput(m_Scene, &input_action, 1, &consumed);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    ASSERT_FALSE(consumed);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, ScriptInputConsume)
{
    const char* s = "function update(self)\n"
                    "   assert(g_value == 123)\n"
                    "end\n"
                    "function on_input(self, action_id, action)\n"
                    "   if(action_id == hash(\"SPACE\")) then\n"
                    "       g_value = 123\n"
                    "   end\n"
                    "   return true\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::InputAction input_action;
    input_action.m_ActionId = dmHashString64("SPACE");
    bool consumed;
    r = dmGui::DispatchInput(m_Scene, &input_action, 1, &consumed);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    ASSERT_TRUE(consumed);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, ScriptInputMouseMovement)
{
    // No mouse
    const char* s = "function on_input(self, action_id, action)\n"
                    "   assert(action.x == nil)\n"
                    "   assert(action.y == nil)\n"
                    "   assert(action.dx == nil)\n"
                    "   assert(action.dy == nil)\n"
                    "end\n";
    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::InputAction input_action;
    input_action.m_ActionId = dmHashString64("SPACE");
    bool consumed;
    r = dmGui::DispatchInput(m_Scene, &input_action, 1, &consumed);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    // Mouse movement
    s = "function on_input(self, action_id, action)\n"
        "   assert(action_id == nil)\n"
        "   assert(action.value == nil)\n"
        "   assert(action.pressed == nil)\n"
        "   assert(action.released == nil)\n"
        "   assert(action.repeated == nil)\n"
        "   assert(action.x == 1.0)\n"
        "   assert(action.y == 2.0)\n"
        "   assert(action.dx == 3.0)\n"
        "   assert(action.dy == 4.0)\n"
        "end\n";
    input_action.m_ActionId = 0;
    input_action.m_PositionSet = true;
    input_action.m_X = 1.0f;
    input_action.m_Y = 2.0f;
    input_action.m_DX = 3.0f;
    input_action.m_DY = 4.0f;

    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::DispatchInput(m_Scene, &input_action, 1, &consumed);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

struct TestMessage
{
    dmhash_t m_ComponentId;
    dmhash_t m_MessageId;
};

static void Dispatch1(dmMessage::Message* message, void* user_ptr)
{
    TestMessage* test_message = (TestMessage*) user_ptr;
    test_message->m_ComponentId = message->m_Receiver.m_Fragment;
    test_message->m_MessageId = message->m_Id;
}

TEST_F(dmGuiTest, PostMessage1)
{
    const char* s = "function init(self)\n"
                    "   msg.post(\"#component\", \"my_named_message\")\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    TestMessage test_message;
    dmMessage::Dispatch(m_Socket, &Dispatch1, &test_message);

    ASSERT_EQ(dmHashString64("component"), test_message.m_ComponentId);
    ASSERT_EQ(dmHashString64("my_named_message"), test_message.m_MessageId);
}

TEST_F(dmGuiTest, MissingSetSceneInDispatchInputBug)
{
    const char* s = "function update(self)\n"
                    "end\n"
                    "function on_input(self, action_id, action)\n"
                    "   msg.post(\"#component\", \"my_named_message\")\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::InputAction input_action;
    input_action.m_ActionId = dmHashString64("SPACE");
    bool consumed;
    r = dmGui::DispatchInput(m_Scene, &input_action, 1, &consumed);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

static void Dispatch2(dmMessage::Message* message, void* user_ptr)
{
    assert(message->m_Receiver.m_Fragment == dmHashString64("component"));
    assert((dmDDF::Descriptor*)message->m_Descriptor == dmTestGuiDDF::AMessage::m_DDFDescriptor);

    dmTestGuiDDF::AMessage* amessage = (dmTestGuiDDF::AMessage*) message->m_Data;
    dmTestGuiDDF::AMessage* amessage_out = (dmTestGuiDDF::AMessage*) user_ptr;

    *amessage_out = *amessage;
}

TEST_F(dmGuiTest, PostMessage2)
{
    const char* s = "function init(self)\n"
                    "   msg.post(\"#component\", \"a_message\", { a = 123, b = 456 })\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmTestGuiDDF::AMessage amessage;
    dmMessage::Dispatch(m_Socket, &Dispatch2, &amessage);

    ASSERT_EQ(123, amessage.m_A);
    ASSERT_EQ(456, amessage.m_B);
}

static void Dispatch3(dmMessage::Message* message, void* user_ptr)
{
    dmGui::Result r = dmGui::DispatchMessage((dmGui::HScene)user_ptr, message);
    assert(r == dmGui::RESULT_OK);
}

TEST_F(dmGuiTest, PostMessage3)
{
    const char* s1 = "function init(self)\n"
                     "    msg.post(\"#component\", \"test_message\", { a = 123 })\n"
                     "end\n";

    const char* s2 = "function update(self, dt)\n"
                     "    assert(self.a == 123)\n"
                     "end\n"
                     "\n"
                     "function on_message(self, message_id, message, sender)\n"
                     "    if message_id == hash(\"test_message\") then\n"
                     "        self.a = message.a\n"
                     "    end\n"
                     "    local test_url = msg.url(\"\")\n"
                     "    assert(sender.socket == test_url.socket, \"invalid socket\")\n"
                     "    assert(sender.path == test_url.path, \"invalid path\")\n"
                     "    assert(sender.fragment == test_url.fragment, \"invalid fragment\")\n"
                     "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s1));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::NewSceneParams params;
    params.m_UserData = (void*)this;
    dmGui::HScene scene2 = dmGui::NewScene(m_Context, &params);
    ASSERT_NE((void*)scene2, (void*)0x0);
    dmGui::HScript script2 = dmGui::NewScript(m_Context);
    r = dmGui::SetSceneScript(scene2, script2);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::SetScript(script2, LuaSourceFromStr(s2));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    uint32_t message_count = dmMessage::Dispatch(m_Socket, &Dispatch3, scene2);
    ASSERT_EQ(1u, message_count);

    r = dmGui::UpdateScene(scene2, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::DeleteScript(script2);
    dmGui::DeleteScene(scene2);
}

TEST_F(dmGuiTest, PostMessageMissingField)
{
    const char* s = "function init(self)\n"
                    "   msg.post(\"a_message\", { a = 123 })\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_SCRIPT_ERROR, r);
}

TEST_F(dmGuiTest, PostMessageToGuiDDF)
{
    const char* s = "local a = 0\n"
                    "function update(self)\n"
                    "   assert(a == 123)\n"
                    "end\n"
                    "function on_message(self, message_id, message)\n"
                    "   assert(message_id == hash(\"amessage\"))\n"
                    "   a = message.a\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    char buf[sizeof(dmMessage::Message) + sizeof(dmTestGuiDDF::AMessage)];
    dmMessage::Message* message = (dmMessage::Message*)buf;
    message->m_Sender = dmMessage::URL();
    message->m_Receiver = dmMessage::URL();
    message->m_Id = dmHashString64("amessage");
    message->m_Descriptor = (uintptr_t)dmTestGuiDDF::AMessage::m_DDFDescriptor;
    message->m_DataSize = sizeof(dmTestGuiDDF::AMessage);
    dmTestGuiDDF::AMessage* amessage = (dmTestGuiDDF::AMessage*)message->m_Data;
    amessage->m_A = 123;
    r = dmGui::DispatchMessage(m_Scene, message);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, PostMessageToGuiEmptyLuaTable)
{
    const char* s = "local a = 0\n"
                    "function update(self)\n"
                    "   assert(a == 1)\n"
                    "end\n"
                    "function on_message(self, message_id, message)\n"
                    "   assert(message_id == hash(\"amessage\"))\n"
                    "   a = 1\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    char buffer[256 + sizeof(dmMessage::Message)];
    dmMessage::Message* message = (dmMessage::Message*)buffer;
    message->m_Sender = dmMessage::URL();
    message->m_Receiver = dmMessage::URL();
    message->m_Id = dmHashString64("amessage");
    message->m_Descriptor = 0;

    message->m_DataSize = 0;

    r = dmGui::DispatchMessage(m_Scene, message);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, PostMessageToGuiLuaTable)
{
    const char* s = "local a = 0\n"
                    "function update(self)\n"
                    "   assert(a == 456)\n"
                    "end\n"
                    "function on_message(self, message_id, message)\n"
                    "   assert(message_id == hash(\"amessage\"))\n"
                    "   a = message.a\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    char buffer[256 + sizeof(dmMessage::Message)];
    dmMessage::Message* message = (dmMessage::Message*)buffer;
    message->m_Sender = dmMessage::URL();
    message->m_Receiver = dmMessage::URL();
    message->m_Id = dmHashString64("amessage");
    message->m_Descriptor = 0;

    lua_State* L = lua_open();
    lua_newtable(L);
    lua_pushstring(L, "a");
    lua_pushinteger(L, 456);
    lua_settable(L, -3);
    message->m_DataSize = dmScript::CheckTable(L, (char*)message->m_Data, 256, -1);
    ASSERT_GT(message->m_DataSize, 0U);
    ASSERT_LE(message->m_DataSize, 256u);

    r = dmGui::DispatchMessage(m_Scene, message);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    lua_close(L);
}

TEST_F(dmGuiTest, SaveNode)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self)\n"
                    "    self.n = gui.get_node(\"n\")\n"
                    "end\n"
                    "function update(self)\n"
                    "    assert(self.n, \"Node could not be saved!\")\n"
                    "end";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, UseDeletedNode)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self) self.n = gui.get_node(\"n\")\n end function update(self) print(self.n)\n end";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::DeleteNode(m_Scene, node);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_SCRIPT_ERROR, r);
}

TEST_F(dmGuiTest, NodeProperties)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self)\n"
                    "    self.n = gui.get_node(\"n\")\n"
                    "    gui.set_position(self.n, vmath.vector4(1,2,3,0))\n"
                    "    gui.set_text(self.n, \"test\")\n"
                    "    gui.set_text(self.n, \"flipper\")\n"
                    "end\n"
                    "function update(self) "
                    "    local pos = gui.get_position(self.n)\n"
                    "    assert(pos.x == 1)\n"
                    "    assert(pos.y == 2)\n"
                    "    assert(pos.z == 3)\n"
                    "    assert(gui.get_text(self.n) == \"flipper\")\n"
                    "end";
    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, ReplaceAnimation)
{
    /*
     * NOTE: We create a node2 which animation duration is set to 0.5f
     * Internally the animation will removed an "erased-swapped". Used to test that the last animation
     * for node1 really invalidates the first animation of node1
     */
    dmGui::HNode node1 = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::HNode node2 = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);

    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_POSITION);
    dmGui::AnimateNodeHash(m_Scene, node2, property, Vector4(123,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 0.5f, 0, 0, 0, 0);
    dmGui::AnimateNodeHash(m_Scene, node1, property, Vector4(1,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0, 0, 0, 0);
    dmGui::AnimateNodeHash(m_Scene, node1, property, Vector4(10,0,0,0), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0, 0, 0, 0);

    for (int i = 0; i < 60; ++i)
    {
        dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    }

    ASSERT_NEAR(dmGui::GetNodePosition(m_Scene, node1).getX(), 10.0f, EPSILON);

    dmGui::DeleteNode(m_Scene, node1);
    dmGui::DeleteNode(m_Scene, node2);
}

TEST_F(dmGuiTest, SyntaxError)
{
    const char* s = "function_ foo(self)";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_SYNTAX_ERROR, r);
}

TEST_F(dmGuiTest, MissingUpdate)
{
    const char* s = "function init(self) end";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, MissingInit)
{
    const char* s = "function update(self) end";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, NoScript)
{
    dmGui::Result r;
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, Self)
{
    const char* s = "function init(self) self.x = 1122 end\n function update(self) assert(self.x==1122) end";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, Reload)
{
    const char* s1 = "function init(self)\n"
                     "    self.x = 1122\n"
                     "end\n"
                     "function update(self)\n"
                     "    assert(self.x==1122)\n"
                     "    self.x = self.x + 1\n"
                     "end";
    const char* s2 = "function update(self)\n"
                     "    assert(self.x==1124)\n"
                     "end\n"
                     "function on_reload(self)\n"
                     "    self.x = self.x + 1\n"
                     "end";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s1));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    // assert should fail due to + 1
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_SCRIPT_ERROR, r);

    // Reload
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s2));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    // Should fail since on_reload has not been called
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_SCRIPT_ERROR, r);

    r = dmGui::ReloadScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, ScriptNamespace)
{
    // Test that "local" per file works, default lua behavior
    // The test demonstrates how to create file local variables by using the local keyword at top scope
    const char* s1 = "local x = 123\n local function f() return x end\n function update(self) assert(f()==123)\n end\n";
    const char* s2 = "local x = 456\n local function f() return x end\n function update(self) assert(f()==456)\n end\n";

    dmGui::NewSceneParams params;
    dmGui::HScene scene2 = dmGui::NewScene(m_Context, &params);

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s1));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s2));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(scene2, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::DeleteScene(scene2);
}

TEST_F(dmGuiTest, DeltaTime)
{
    const char* s = "function update(self, dt)\n"
                    "assert (dt == 1122)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1122);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

TEST_F(dmGuiTest, Bug352)
{
    dmGui::AddFont(m_Scene, "big_score", 0);
    dmGui::AddFont(m_Scene, "score", 0);
    dmGui::AddTexture(m_Scene, "left_hud", 0);
    dmGui::AddTexture(m_Scene, "right_hud", 0);

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr((const char*)BUG352_LUA, BUG352_LUA_SIZE));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::SetScript(m_Script, LuaSourceFromStr((const char*)BUG352_LUA, BUG352_LUA_SIZE));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    char buffer[256 + sizeof(dmMessage::Message)];
    dmMessage::Message* message = (dmMessage::Message*)buffer;
    message->m_Sender = dmMessage::URL();
    message->m_Receiver = dmMessage::URL();
    message->m_Id = dmHashString64("inc_score");
    message->m_Descriptor = 0;

    lua_State* L = lua_open();
    lua_newtable(L);
    lua_pushstring(L, "score");
    lua_pushinteger(L, 123);
    lua_settable(L, -3);

    message->m_DataSize = dmScript::CheckTable(L, (char*)message->m_Data, 256, -1);
    ASSERT_GT(message->m_DataSize, 0U);
    ASSERT_LE(message->m_DataSize, 256u);

    for (int i = 0; i < 100; ++i)
    {
        dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
        dmGui::DispatchMessage(m_Scene, message);
    }

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);
    lua_close(L);
}

TEST_F(dmGuiTest, Scaling)
{
    uint32_t width = 1024;
    uint32_t height = 768;

    uint32_t physical_width = 640;
    uint32_t physical_height = 480;

    dmGui::SetResolution(m_Context, width, height);
    dmGui::SetPhysicalResolution(m_Context, physical_width, physical_height);

    const char* n1_name = "n1";
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, Point3(width/2.0f, height/2.0f, 0), Vector3(10, 10, 0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeText(m_Scene, n1, n1_name);

    dmGui::RenderScene(m_Scene, &RenderNodes, this);

    Point3 center = m_NodeTextToRenderedPosition[n1_name] + m_NodeTextToRenderedSize[n1_name] * 0.5f;
    ASSERT_EQ(physical_width/2, center.getX());
    ASSERT_EQ(physical_height/2, center.getY());
}

TEST_F(dmGuiTest, Anchoring)
{
    uint32_t width = 1024;
    uint32_t height = 768;

    uint32_t physical_width = 640;
    uint32_t physical_height = 320;

    dmGui::SetResolution(m_Context, width, height);
    dmGui::SetPhysicalResolution(m_Context, physical_width, physical_height);

    Vector4 ref_scale = dmGui::CalculateReferenceScale(m_Context);

    const char* n1_name = "n1";
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, Point3(10, 10, 0), Vector3(10, 10, 0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeText(m_Scene, n1, n1_name);
    dmGui::SetNodeXAnchor(m_Scene, n1, dmGui::XANCHOR_LEFT);
    dmGui::SetNodeYAnchor(m_Scene, n1, dmGui::YANCHOR_BOTTOM);

    const char* n2_name = "n2";
    dmGui::HNode n2 = dmGui::NewNode(m_Scene, Point3(width - 10.0f, height - 10.0f, 0), Vector3(10, 10, 0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeText(m_Scene, n2, n2_name);
    dmGui::SetNodeXAnchor(m_Scene, n2, dmGui::XANCHOR_RIGHT);
    dmGui::SetNodeYAnchor(m_Scene, n2, dmGui::YANCHOR_TOP);

    dmGui::RenderScene(m_Scene, &RenderNodes, this);

    Point3 pos1 = m_NodeTextToRenderedPosition[n1_name] + m_NodeTextToRenderedSize[n1_name] * 0.5f;
    const float EPSILON = 0.0001f;
    ASSERT_NEAR(10 * ref_scale.getX(), pos1.getX(), EPSILON);
    ASSERT_NEAR(10 * ref_scale.getY(), pos1.getY(), EPSILON);

    Point3 pos2 = m_NodeTextToRenderedPosition[n2_name] + m_NodeTextToRenderedSize[n2_name] * 0.5f;
    ASSERT_NEAR(physical_width - 10 * ref_scale.getX(), pos2.getX(), EPSILON);
    ASSERT_NEAR(physical_height - 10 * ref_scale.getY(), pos2.getY(), EPSILON);
}

TEST_F(dmGuiTest, ScriptAnchoring)
{
    uint32_t width = 1024;
    uint32_t height = 768;

    uint32_t physical_width = 640;
    uint32_t physical_height = 320;

    dmGui::SetResolution(m_Context, width, height);
    dmGui::SetPhysicalResolution(m_Context, physical_width, physical_height);

    Vector4 ref_scale = dmGui::CalculateReferenceScale(m_Context);

    const char* s = "function init(self)\n"
                    "    assert (1024 == gui.get_width())\n"
                    "    assert (768 == gui.get_height())\n"
                    "    self.n1 = gui.new_text_node(vmath.vector3(10, 10, 0), \"n1\")"
                    "    gui.set_xanchor(self.n1, gui.ANCHOR_LEFT)\n"
                    "    assert(gui.get_xanchor(self.n1) == gui.ANCHOR_LEFT)\n"
                    "    gui.set_yanchor(self.n1, gui.ANCHOR_BOTTOM)\n"
                    "    assert(gui.get_yanchor(self.n1) == gui.ANCHOR_BOTTOM)\n"
                    "    self.n2 = gui.new_text_node(vmath.vector3(gui.get_width() - 10, gui.get_height()-10, 0), \"n2\")"
                    "    gui.set_xanchor(self.n2, gui.ANCHOR_RIGHT)\n"
                    "    assert(gui.get_xanchor(self.n2) == gui.ANCHOR_RIGHT)\n"
                    "    gui.set_yanchor(self.n2, gui.ANCHOR_TOP)\n"
                    "    assert(gui.get_yanchor(self.n2) == gui.ANCHOR_TOP)\n"
                    "end\n"
                    "function update(self)\n"
                    "end\n";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    dmGui::RenderScene(m_Scene, &RenderNodes, this);

    // These tests the actual position of the cursor when rendering text so we need to adjust with the ref-scaled text metrics
    float ref_factor = dmMath::Min(ref_scale.getX(), ref_scale.getY());
    Point3 pos1 = m_NodeTextToRenderedPosition["n1"];
    ASSERT_EQ(10 * ref_scale.getX(), pos1.getX() + ref_factor * TEXT_GLYPH_WIDTH);
    ASSERT_EQ(10 * ref_scale.getY(), pos1.getY() + ref_factor * 0.5f * (TEXT_MAX_DESCENT + TEXT_MAX_ASCENT));

    Point3 pos2 = m_NodeTextToRenderedPosition["n2"];
    ASSERT_EQ(physical_width - 10 * ref_scale.getX(), pos2.getX() + ref_factor * TEXT_GLYPH_WIDTH);
    ASSERT_EQ(physical_height - 10 * ref_scale.getY(), pos2.getY() + ref_factor * 0.5f * (TEXT_MAX_DESCENT + TEXT_MAX_ASCENT));
}

TEST_F(dmGuiTest, ScriptPivot)
{
    const char* s = "function init(self)\n"
                    "    local n1 = gui.new_text_node(vmath.vector3(10, 10, 0), \"n1\")"
                    "    assert(gui.get_pivot(n1) == gui.PIVOT_CENTER)\n"
                    "    gui.set_pivot(n1, gui.PIVOT_N)\n"
                    "    assert(gui.get_pivot(n1) == gui.PIVOT_N)\n"
                    "end\n";

    ASSERT_TRUE(SetScript(m_Script, s));

    ASSERT_EQ(dmGui::RESULT_OK, dmGui::InitScene(m_Scene));
}

TEST_F(dmGuiTest, AdjustMode)
{
    uint32_t width = 640;
    uint32_t height = 320;

    uint32_t physical_width = 1280;
    uint32_t physical_height = 320;

    dmGui::SetResolution(m_Context, width, height);
    dmGui::SetPhysicalResolution(m_Context, physical_width, physical_height);

    Vector4 ref_scale = dmGui::CalculateReferenceScale(m_Context);
    float min_ref_scale = dmMath::Min(ref_scale.getX(), ref_scale.getY());
    float max_ref_scale = dmMath::Max(ref_scale.getX(), ref_scale.getY());

    dmGui::AdjustMode modes[] = {dmGui::ADJUST_MODE_FIT, dmGui::ADJUST_MODE_ZOOM, dmGui::ADJUST_MODE_STRETCH};
    Vector3 adjust_scales[] = {
            Vector3(min_ref_scale, min_ref_scale, 1.0f),
            Vector3(max_ref_scale, max_ref_scale, 1.0f),
            ref_scale.getXYZ()
    };

    for (uint32_t i = 0; i < 3; ++i)
    {
        dmGui::AdjustMode mode = modes[i];
        Vector3 adjust_scale = adjust_scales[i];

        const char* center_name = "center";
        dmGui::HNode center_node = dmGui::NewNode(m_Scene, Point3(10, 10, 0), Vector3(10, 10, 0), dmGui::NODE_TYPE_BOX);
        dmGui::SetNodeText(m_Scene, center_node, center_name);
        dmGui::SetNodePivot(m_Scene, center_node, dmGui::PIVOT_CENTER);
        dmGui::SetNodeAdjustMode(m_Scene, center_node, mode);

        const char* bl_name = "bottom_left";
        dmGui::HNode bl_node = dmGui::NewNode(m_Scene, Point3(10, 10, 0), Vector3(10, 10, 0), dmGui::NODE_TYPE_BOX);
        dmGui::SetNodeText(m_Scene, bl_node, bl_name);
        dmGui::SetNodePivot(m_Scene, bl_node, dmGui::PIVOT_SW);
        dmGui::SetNodeAdjustMode(m_Scene, bl_node, mode);

        const char* tr_name = "top_right";
        dmGui::HNode tr_node = dmGui::NewNode(m_Scene, Point3(10, 10, 0), Vector3(10, 10, 0), dmGui::NODE_TYPE_BOX);
        dmGui::SetNodeText(m_Scene, tr_node, tr_name);
        dmGui::SetNodePivot(m_Scene, tr_node, dmGui::PIVOT_NE);
        dmGui::SetNodeAdjustMode(m_Scene, tr_node, mode);

        dmGui::RenderScene(m_Scene, &RenderNodes, this);

        Vector3 offset((physical_width - width * adjust_scale.getX()) * 0.5f, (physical_height - height * adjust_scale.getY()) * 0.5f, 0.0f);

        Point3 center_p = m_NodeTextToRenderedPosition[center_name] + m_NodeTextToRenderedSize[center_name] * 0.5f;
        ASSERT_EQ(offset.getX() + 10 * adjust_scale.getX(), center_p.getX());
        ASSERT_EQ(offset.getY() + 10 * adjust_scale.getY(), center_p.getY());

        Point3 bl_p = m_NodeTextToRenderedPosition[bl_name];
        ASSERT_EQ(offset.getX() + 10 * adjust_scale.getX(), bl_p.getX());
        ASSERT_EQ(offset.getY() + 10 * adjust_scale.getY(), bl_p.getY());

        Point3 tr_p = m_NodeTextToRenderedPosition[tr_name] + m_NodeTextToRenderedSize[center_name];
        ASSERT_EQ(offset.getX() + 10 * adjust_scale.getX(), tr_p.getX());
        ASSERT_EQ(offset.getY() + 10 * adjust_scale.getY(), tr_p.getY());
    }
}

TEST_F(dmGuiTest, ScriptErroneousReturnValues)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(10,10,0), dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, node, "n");
    const char* s = "function init(self)\n"
                    "    return true\n"
                    "end\n"
                    "function final(self)\n"
                    "    return true\n"
                    "end\n"
                    "function update(self, dt)\n"
                    "    return true\n"
                    "end\n"
                    "function on_message(self, message_id, message, sender)\n"
                    "    return true\n"
                    "end\n"
                    "function on_input(self, action_id, action)\n"
                    "    return 1\n"
                    "end\n"
                    "function on_reload(self)\n"
                    "    return true\n"
                    "end";

    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(s));
    ASSERT_EQ(dmGui::RESULT_OK, r);
    r = dmGui::InitScene(m_Scene);
    ASSERT_NE(dmGui::RESULT_OK, r);
    r = dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_NE(dmGui::RESULT_OK, r);
    char buffer[sizeof(dmMessage::Message) + sizeof(dmTestGuiDDF::AMessage)];
    dmMessage::Message* message = (dmMessage::Message*)buffer;
    message->m_Sender = dmMessage::URL();
    message->m_Receiver = dmMessage::URL();
    message->m_Id = 1;
    message->m_DataSize = 0;
    message->m_Descriptor = (uintptr_t)dmTestGuiDDF::AMessage::m_DDFDescriptor;
    message->m_Next = 0;
    dmTestGuiDDF::AMessage* data = (dmTestGuiDDF::AMessage*)message->m_Data;
    data->m_A = 0;
    data->m_B = 0;
    r = dmGui::DispatchMessage(m_Scene, message);
    ASSERT_NE(dmGui::RESULT_OK, r);
    dmGui::InputAction action;
    action.m_ActionId = 1;
    action.m_Value = 1.0f;
    bool consumed;
    r = dmGui::DispatchInput(m_Scene, &action, 1, &consumed);
    ASSERT_NE(dmGui::RESULT_OK, r);
    r = dmGui::FinalScene(m_Scene);
    ASSERT_NE(dmGui::RESULT_OK, r);
    dmGui::DeleteNode(m_Scene, node);
}

TEST_F(dmGuiTest, Picking)
{
    uint32_t physical_width = 640;
    uint32_t physical_height = 320;
    float ref_scale = 0.5f;
    dmGui::SetResolution(m_Context, (uint32_t) (physical_width * ref_scale), (uint32_t) (physical_height * ref_scale));
    dmGui::SetPhysicalResolution(m_Context, physical_width, physical_height);

    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);

    // Account for some loss in precision
    Vector3 min(EPSILON, EPSILON, 0);
    Vector3 max = size - min;
    ASSERT_TRUE(dmGui::PickNode(m_Scene, n1, min.getX(), min.getY()));
    ASSERT_TRUE(dmGui::PickNode(m_Scene, n1, min.getX(), max.getY()));
    ASSERT_TRUE(dmGui::PickNode(m_Scene, n1, max.getX(), max.getY()));
    ASSERT_TRUE(dmGui::PickNode(m_Scene, n1, max.getX(), min.getY()));
    ASSERT_FALSE(dmGui::PickNode(m_Scene, n1, ceil(size.getX() + 0.5f), size.getY()));

    dmGui::SetNodeProperty(m_Scene, n1, dmGui::PROPERTY_ROTATION, Vector4(0, 45, 0, 0));
    Vector3 ext(pos);
    ext.setX(ext.getX() * cosf((float) (M_PI * 0.25)));
    ASSERT_TRUE(dmGui::PickNode(m_Scene, n1, pos.getX() + floor(ext.getX()), pos.getY()));
    ASSERT_FALSE(dmGui::PickNode(m_Scene, n1, pos.getX() + ceil(ext.getX()), pos.getY()));

    dmGui::SetNodeProperty(m_Scene, n1, dmGui::PROPERTY_ROTATION, Vector4(0, 90, 0, 0));
    ASSERT_TRUE(dmGui::PickNode(m_Scene, n1, pos.getX(), pos.getY()));
    ASSERT_FALSE(dmGui::PickNode(m_Scene, n1, pos.getX() + 1.0f, pos.getY()));
}

TEST_F(dmGuiTest, ScriptPicking)
{
    uint32_t physical_width = 640;
    uint32_t physical_height = 320;
    dmGui::SetPhysicalResolution(m_Context, physical_width, physical_height);
    dmGui::SetResolution(m_Context, physical_width, physical_height);

    char buffer[1024];

    const char* s = "function init(self)\n"
                    "    local id = \"node_1\"\n"
                    "    local size = vmath.vector3(string.len(id) * %.2f, %.2f + %.2f, 0)\n"
                    "    local epsilon = %.6f\n"
                    "    local min = vmath.vector3(epsilon, epsilon, 0)\n"
                    "    local max = size - min\n"
                    "    local position = size * 0.5\n"
                    "    local n1 = gui.new_text_node(position, id)\n"
                    "    assert(gui.pick_node(n1, min.x, min.y))\n"
                    "    assert(gui.pick_node(n1, min.x, max.y))\n"
                    "    assert(gui.pick_node(n1, max.x, min.y))\n"
                    "    assert(gui.pick_node(n1, max.x, max.y))\n"
                    "    assert(not gui.pick_node(n1, size.x + 1, size.y))\n"
                    "end\n";

    sprintf(buffer, s, TEXT_GLYPH_WIDTH, TEXT_MAX_ASCENT, TEXT_MAX_DESCENT, EPSILON);
    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(buffer));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);
}

// This render function simply flags a provided boolean when called
static void RenderEnabledNodes(dmGui::HScene scene, const dmGui::RenderEntry* nodes, const Vectormath::Aos::Matrix4* node_transforms, const Vectormath::Aos::Vector4* node_colors,
        const dmGui::StencilScope** stencil_scopes, uint32_t node_count, void* context)
{
    if (node_count > 0)
    {
        bool* rendered = (bool*)context;
        *rendered = true;
    }
}

TEST_F(dmGuiTest, EnableDisable)
{
    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);

    // Initially enabled
    dmGui::InternalNode* node = dmGui::GetNode(m_Scene, n1);
    ASSERT_TRUE(node->m_Node.m_Enabled);

    // Test rendering
    bool rendered = false;
    dmGui::RenderScene(m_Scene, RenderEnabledNodes, &rendered);
    ASSERT_TRUE(rendered);

    // Test no rendering when disabled
    dmGui::SetNodeEnabled(m_Scene, n1, false);
    rendered = false;
    dmGui::RenderScene(m_Scene, RenderEnabledNodes, &rendered);
    ASSERT_FALSE(rendered);

    dmhash_t property = dmGui::GetPropertyHash(dmGui::PROPERTY_COLOR);
    dmGui::AnimateNodeHash(m_Scene, n1, property, Vector4(0.0f, 0.0f, 0.0f, 0.0f), dmEasing::TYPE_LINEAR, dmGui::PLAYBACK_ONCE_FORWARD, 1.0f, 0.0f, 0x0, 0x0, 0x0);
    ASSERT_EQ(4U, m_Scene->m_Animations.Size());

    // Test no animation evaluation
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_EQ(0.0f, m_Scene->m_Animations[0].m_Elapsed);

    // Test animation evaluation when enabled
    dmGui::SetNodeEnabled(m_Scene, n1, true);
    dmGui::UpdateScene(m_Scene, 1.0f / 60.0f);
    ASSERT_LT(0.0f, m_Scene->m_Animations[0].m_Elapsed);
}

TEST_F(dmGuiTest, ScriptEnableDisable)
{
    char buffer[512];
    const char* s = "function init(self)\n"
                    "    local id = \"node_1\"\n"
                    "    local size = vmath.vector3(string.len(id) * %.2f, %.2f + %.2f, 0)\n"
                    "    local position = size * 0.5\n"
                    "    self.n1 = gui.new_text_node(position, id)\n"
                    "    assert(gui.is_enabled(self.n1))\n"
                    "    gui.set_enabled(self.n1, false)\n"
                    "    assert(not gui.is_enabled(self.n1))\n"
                    "end\n";
    sprintf(buffer, s, TEXT_GLYPH_WIDTH, TEXT_MAX_ASCENT, TEXT_MAX_DESCENT);
    dmGui::Result r;
    r = dmGui::SetScript(m_Script, LuaSourceFromStr(buffer));
    ASSERT_EQ(dmGui::RESULT_OK, r);

    // Run init function
    r = dmGui::InitScene(m_Scene);
    ASSERT_EQ(dmGui::RESULT_OK, r);

    // Retrieve node
    dmGui::InternalNode* node = &m_Scene->m_Nodes[0];
    ASSERT_STREQ("node_1", node->m_Node.m_Text); // make sure we found the right one
    ASSERT_FALSE(node->m_Node.m_Enabled);
}

static void RenderNodesOrder(dmGui::HScene scene, const dmGui::RenderEntry* nodes, const Vectormath::Aos::Matrix4* node_transforms, const Vectormath::Aos::Vector4* node_colors,
        const dmGui::StencilScope** stencil_scopes, uint32_t node_count, void* context)
{
    std::map<dmGui::HNode, uint16_t>* order = (std::map<dmGui::HNode, uint16_t>*)context;
    order->clear();
    for (uint32_t i = 0; i < node_count; ++i)
    {
        (*order)[nodes[i].m_Node] = (uint16_t)i;
    }
}

/**
 * Verify specific use cases of moving around nodes:
 * - single node (nop)
 *   - move to top
 *   - move to self (up)
 *   - move to bottom
 *   - move to self (down)
 * - two nodes
 *   - initial order
 *   - move to top
 *   - move explicit to top
 *   - move to bottom
 *   - move explicit to bottom
 * - three nodes
 *   - move to top
 *   - move from head to middle
 *   - move from middle to tail
 *   - move to bottom
 *   - move from tail to middle
 *   - move from middle to head
 */
TEST_F(dmGuiTest, MoveNodes)
{
    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);

    std::map<dmGui::HNode, uint16_t> order;

    // Edge case: single node
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    // Move to top
    dmGui::MoveNodeAbove(m_Scene, n1, dmGui::INVALID_HANDLE);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    // Move to self
    dmGui::MoveNodeAbove(m_Scene, n1, n1);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    // Move to bottom
    dmGui::MoveNodeBelow(m_Scene, n1, dmGui::INVALID_HANDLE);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    // Move to self
    dmGui::MoveNodeBelow(m_Scene, n1, n1);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);

    // Two nodes
    dmGui::HNode n2 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);
    // Move to top
    dmGui::MoveNodeAbove(m_Scene, n1, dmGui::INVALID_HANDLE);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(1u, order[n1]);
    ASSERT_EQ(0u, order[n2]);
    // Move explicit
    dmGui::MoveNodeAbove(m_Scene, n2, n1);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);
    // Move to bottom
    dmGui::MoveNodeBelow(m_Scene, n2, dmGui::INVALID_HANDLE);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(1u, order[n1]);
    ASSERT_EQ(0u, order[n2]);
    // Move explicit
    dmGui::MoveNodeBelow(m_Scene, n1, n2);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);

    // Three nodes
    dmGui::HNode n3 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);
    ASSERT_EQ(2u, order[n3]);
    // Move to top
    dmGui::MoveNodeAbove(m_Scene, n1, dmGui::INVALID_HANDLE);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(2u, order[n1]);
    ASSERT_EQ(0u, order[n2]);
    ASSERT_EQ(1u, order[n3]);
    // Move explicit from head to middle
    dmGui::MoveNodeAbove(m_Scene, n2, n3);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(2u, order[n1]);
    ASSERT_EQ(1u, order[n2]);
    ASSERT_EQ(0u, order[n3]);
    // Move explicit from middle to tail
    dmGui::MoveNodeAbove(m_Scene, n2, n1);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(1u, order[n1]);
    ASSERT_EQ(2u, order[n2]);
    ASSERT_EQ(0u, order[n3]);
    // Move to bottom
    dmGui::MoveNodeBelow(m_Scene, n2, dmGui::INVALID_HANDLE);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(2u, order[n1]);
    ASSERT_EQ(0u, order[n2]);
    ASSERT_EQ(1u, order[n3]);
    // Move explicit from tail to middle
    dmGui::MoveNodeBelow(m_Scene, n1, n3);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(1u, order[n1]);
    ASSERT_EQ(0u, order[n2]);
    ASSERT_EQ(2u, order[n3]);
    // Move explicit from middle to head
    dmGui::MoveNodeBelow(m_Scene, n1, n2);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);
    ASSERT_EQ(2u, order[n3]);
}

TEST_F(dmGuiTest, MoveNodesScript)
{
    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);

    const char* id1 = "n1";
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, n1, id1);
    const char* id2 = "n2";
    dmGui::HNode n2 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeId(m_Scene, n2, id2);
    const char* s = "function init(self)\n"
                    "    local n1 = gui.get_node(\"n1\")\n"
                    "    local n2 = gui.get_node(\"n2\")\n"
                    "    assert(gui.get_index(n1) == 0)\n"
                    "    assert(gui.get_index(n2) == 1)\n"
                    "    gui.move_above(n1, n2)\n"
                    "    assert(gui.get_index(n1) == 1)\n"
                    "    assert(gui.get_index(n2) == 0)\n"
                    "    gui.move_below(n1, n2)\n"
                    "    assert(gui.get_index(n1) == 0)\n"
                    "    assert(gui.get_index(n2) == 1)\n"
                    "end\n";
    ASSERT_TRUE(SetScript(m_Script, s));
    ASSERT_EQ(dmGui::RESULT_OK, dmGui::InitScene(m_Scene));
}

static void RenderNodesCount(dmGui::HScene scene, const dmGui::RenderEntry* nodes, const Vectormath::Aos::Matrix4* node_transforms, const Vectormath::Aos::Vector4* node_colors,
        const dmGui::StencilScope** stencil_scopes, uint32_t node_count, void* context)
{
    uint32_t* count = (uint32_t*)context;
    *count = node_count;
}

static dmGui::HNode PickNode(dmGui::HScene scene, uint32_t* seed)
{
    const uint32_t max_it = 10;
    for (uint32_t i = 0; i < max_it; ++i)
    {
        uint32_t index = dmMath::Rand(seed) % scene->m_Nodes.Size();
        if (scene->m_Nodes[index].m_Index != dmGui::INVALID_INDEX)
        {
            return dmGui::GetNodeHandle(&scene->m_Nodes[index]);
        }
    }
    return dmGui::INVALID_HANDLE;
}

/**
 * Verify that the render count holds under random inserts, deletes and moves
 */
TEST_F(dmGuiTest, MoveNodesLoad)
{
    const uint32_t node_count = 100;
    const uint32_t iterations = 500;

    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);

    dmGui::NewSceneParams params;
    params.m_MaxNodes = node_count * 2;
    params.m_MaxAnimations = MAX_ANIMATIONS;
    params.m_UserData = this;
    dmGui::HScene scene = dmGui::NewScene(m_Context, &params);

    for (uint32_t i = 0; i < node_count; ++i)
    {
        dmGui::NewNode(scene, pos, size, dmGui::NODE_TYPE_BOX);
    }
    uint32_t current_count = node_count;
    uint32_t render_count = 0;

    enum OpType {OP_ADD, OP_DELETE, OP_MOVE_ABOVE, OP_MOVE_BELOW, OP_TYPE_COUNT};

    uint32_t seed = 0;

    uint32_t min_node_count = node_count;
    uint32_t max_node_count = 0;
    uint32_t relative_move_count = 0;
    uint32_t absolute_move_count = 0;
    OpType op_type = OP_ADD;
    uint32_t op_count = 0;
    for (uint32_t i = 0; i < iterations; ++i)
    {
        if (op_count == 0)
        {
            op_type = (OpType)(dmMath::Rand(&seed) % OP_TYPE_COUNT);
            op_count = dmMath::Rand(&seed) % 10 + 1;
            if (op_type == OP_ADD || op_type == OP_DELETE)
            {
                int32_t diff = (int32_t)current_count - (int32_t)node_count;
                float t = dmMath::Min(1.0f, dmMath::Max(-1.0f, diff / (0.5f * node_count)));
                if (dmMath::Rand11(&seed) > t*t*t)
                {
                    op_type = OP_ADD;
                }
                else
                {
                    op_type = OP_DELETE;
                }
            }
        }
        --op_count;
        switch (op_type)
        {
        case OP_ADD:
            dmGui::NewNode(scene, pos, size, dmGui::NODE_TYPE_BOX);
            ++current_count;
            break;
        case OP_DELETE:
            {
                dmGui::HNode node = PickNode(scene, &seed);
                if (node != dmGui::INVALID_HANDLE)
                {
                    dmGui::DeleteNode(scene, node);
                    --current_count;
                }
            }
            break;
        case OP_MOVE_ABOVE:
        case OP_MOVE_BELOW:
            {
                dmGui::HNode source = PickNode(scene, &seed);
                if (source != dmGui::INVALID_HANDLE)
                {
                    dmGui::HNode target = dmGui::INVALID_HANDLE;
                    if (dmMath::Rand01(&seed) < 0.8f)
                        target = PickNode(scene, &seed);
                    if (op_type == OP_MOVE_ABOVE)
                    {
                        dmGui::MoveNodeAbove(scene, source, target);
                    }
                    else
                    {
                        dmGui::MoveNodeBelow(scene, source, target);
                    }
                    if (target != dmGui::INVALID_HANDLE)
                    {
                        ++relative_move_count;
                    }
                    else
                    {
                        ++absolute_move_count;
                    }
                }
            }
            break;
        default:
            break;
        }
        dmGui::RenderScene(scene, RenderNodesCount, &render_count);
        ASSERT_EQ(current_count, render_count);
        if (min_node_count > current_count)
            min_node_count = current_count;
        if (max_node_count < current_count)
            max_node_count = current_count;
    }
    printf("[STATS] current: %03d min: %03d max: %03d rel: %03d abs: %03d\n", current_count, min_node_count, max_node_count, relative_move_count, absolute_move_count);

    dmGui::DeleteScene(scene);
}

/**
 * Verify specific use cases of parenting nodes:
 * - single node (nop)
 *   - parent to nil
 *   - parent to self
 * - two nodes
 *   - initial order
 *   - parent first to second
 *   - parent second to first
 *   - unparent first
 *   - parent second to first
 * - three nodes
 *   - initial order
 *   - parent second to third
 */
TEST_F(dmGuiTest, Parenting)
{
    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);

    std::map<dmGui::HNode, uint16_t> order;

    // Edge case: single node
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    // parent to nil
    dmGui::SetNodeParent(m_Scene, n1, dmGui::INVALID_HANDLE);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    // parent to self
    dmGui::SetNodeParent(m_Scene, n1, n1);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);

    // Two nodes
    dmGui::HNode n2 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);
    // parent first to second
    dmGui::SetNodeParent(m_Scene, n1, n2);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(1u, order[n1]);
    ASSERT_EQ(0u, order[n2]);
    // parent second to first
    dmGui::SetNodeParent(m_Scene, n2, n1);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(1u, order[n1]);
    ASSERT_EQ(0u, order[n2]);
    // unparent first
    dmGui::SetNodeParent(m_Scene, n1, dmGui::INVALID_HANDLE);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(1u, order[n1]);
    ASSERT_EQ(0u, order[n2]);
    // parent second to first
    dmGui::SetNodeParent(m_Scene, n2, n1);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);

    // Three nodes
    dmGui::HNode n3 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);
    ASSERT_EQ(2u, order[n3]);
    // parent second to third
    dmGui::SetNodeParent(m_Scene, n2, n3);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(2u, order[n2]);
    ASSERT_EQ(1u, order[n3]);
}

void RenderNodesStoreTransform(dmGui::HScene scene, const dmGui::RenderEntry* nodes, const Vectormath::Aos::Matrix4* node_transforms,  const Vectormath::Aos::Vector4* node_colors,
        const dmGui::StencilScope** stencil_scopes, uint32_t node_count, void* context)
{
    Vectormath::Aos::Matrix4* out_transforms = (Vectormath::Aos::Matrix4*)context;
    memcpy(out_transforms, node_transforms, sizeof(Vectormath::Aos::Matrix4) * node_count);
}

#define ASSERT_MAT4(m1, m2)\
    for (uint32_t i = 0; i < 16; ++i)\
    {\
        int row = i / 4;\
        int col = i % 4;\
        ASSERT_NEAR(m1.getElem(row, col), m2.getElem(row, col), EPSILON);\
    }

/**
 * Verify that the rendered transforms are correct with VectorMath library as a reference
 * n1 == Vectormath::Aos::Matrix4
 */
TEST_F(dmGuiTest, NodeTransform)
{
    Vector3 size(1.0f, 1.0f, 1.0f);
    Vector3 pos(0.25f, 0.5f, 0.75f);
    Vectormath::Aos::Matrix4 transforms[1];
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, Point3(pos), size, dmGui::NODE_TYPE_BOX);
    dmGui::SetNodePivot(m_Scene, n1, dmGui::PIVOT_SW);

    Vectormath::Aos::Matrix4 ref_mat;
    ref_mat = Vectormath::Aos::Matrix4::identity();
    ref_mat.setTranslation(pos);
    dmGui::RenderScene(m_Scene, RenderNodesStoreTransform, transforms);
    ASSERT_MAT4(transforms[0], ref_mat);

    const float radians = 90.0f * M_PI / 180.0f;
    ref_mat *= Vectormath::Aos::Matrix4::rotation(radians * 0.50f, Vector3(0.0f, 1.0f, 0.0f));
    ref_mat *= Vectormath::Aos::Matrix4::rotation(radians * 1.00f, Vector3(0.0f, 0.0f, 1.0f));
    ref_mat *= Vectormath::Aos::Matrix4::rotation(radians * 0.25f, Vector3(1.0f, 0.0f, 0.0f));
    dmGui::SetNodeProperty(m_Scene, n1, dmGui::PROPERTY_ROTATION, Vector4(90.0f*0.25f, 90.0f*0.5f, 90.0f, 0.0f));
    dmGui::RenderScene(m_Scene, RenderNodesStoreTransform, transforms);
    ASSERT_MAT4(transforms[0], ref_mat);

    ref_mat *= Vectormath::Aos::Matrix4::scale(Vector3(0.25f, 0.5f, 0.75f));
    dmGui::SetNodeProperty(m_Scene, n1, dmGui::PROPERTY_SCALE, Vector4(0.25f, 0.5f, 0.75f, 1.0f));
    dmGui::RenderScene(m_Scene, RenderNodesStoreTransform, transforms);
    ASSERT_MAT4(transforms[0], ref_mat);
}

/**
 * Verify that the rendered transforms are correct for a hierarchy:
 * - n1
 *   - n2
 *
 * In three cases, the nodes have different pivots and positions, so that their render transforms will be identical:
 * - n1 center, n2 center, n3 center
 * - n1 south-west, n2 center, n3 south-west
 * - n1 west, n2 east, n3 west
 */
TEST_F(dmGuiTest, HierarchicalTransforms)
{
    // Setup
    Vector3 size(1, 1, 0);

    dmGui::HNode n1 = dmGui::NewNode(m_Scene, Point3(0.0f, 0.0f, 0.0f), size, dmGui::NODE_TYPE_BOX);
    dmGui::HNode n2 = dmGui::NewNode(m_Scene, Point3(0.0f, 0.0f, 0.0f), size, dmGui::NODE_TYPE_BOX);
    dmGui::HNode n3 = dmGui::NewNode(m_Scene, Point3(0.0f, 0.0f, 0.0f), size, dmGui::NODE_TYPE_BOX);
    // parent first to second, second to third
    dmGui::SetNodeParent(m_Scene, n3, n2);
    dmGui::SetNodeParent(m_Scene, n2, n1);

    Vectormath::Aos::Matrix4 transforms[3];

    dmGui::RenderScene(m_Scene, RenderNodesStoreTransform, transforms);
    ASSERT_MAT4(transforms[0], transforms[1]);
    ASSERT_MAT4(transforms[0], transforms[2]);

    dmGui::SetNodePivot(m_Scene, n1, dmGui::PIVOT_SW);
    dmGui::SetNodePosition(m_Scene, n2, Point3(size * 0.5f));
    dmGui::SetNodePivot(m_Scene, n3, dmGui::PIVOT_SW);
    dmGui::SetNodePosition(m_Scene, n3, Point3(-size * 0.5f));
    dmGui::RenderScene(m_Scene, RenderNodesStoreTransform, transforms);
    ASSERT_MAT4(transforms[0], transforms[1]);
    ASSERT_MAT4(transforms[0], transforms[2]);

    dmGui::SetNodePivot(m_Scene, n1, dmGui::PIVOT_W);
    dmGui::SetNodePivot(m_Scene, n2, dmGui::PIVOT_E);
    dmGui::SetNodePosition(m_Scene, n2, Point3(size.getX(), 0.0f, 0.0f));
    dmGui::SetNodePivot(m_Scene, n3, dmGui::PIVOT_W);
    dmGui::SetNodePosition(m_Scene, n3, Point3(-size.getY(), 0.0f, 0.0f));
    dmGui::RenderScene(m_Scene, RenderNodesStoreTransform, transforms);
    ASSERT_MAT4(transforms[0], transforms[1]);
    ASSERT_MAT4(transforms[0], transforms[2]);
}

#undef ASSERT_MAT4

struct TransformColorData
{
    Vectormath::Aos::Matrix4 m_Transform;
    Vectormath::Aos::Vector4 m_Color;
};

void RenderNodesStoreColorAndTransform(dmGui::HScene scene, const dmGui::RenderEntry* nodes, const Vectormath::Aos::Matrix4* node_transforms, const Vectormath::Aos::Vector4* node_colors,
        const dmGui::StencilScope** stencil_scopes, uint32_t node_count, void* context)
{
    TransformColorData* out_data = (TransformColorData*) context;
    for(uint32_t i = 0; i < node_count; i++)
    {
        out_data[i].m_Transform = node_transforms[i];
        out_data[i].m_Color = node_colors[i];
    }
}

/**
 * Verify that the rendered colors are correct for a hierarchy:
 * - n1
 *   - n2
 *   - n3
 * - n4
 *   - n5
 *     - n6
 *
 */
#define ASSERT_COLOR_EQ(expected, actual)\
    ASSERT_EQ(expected.getX(), actual.getX());\
    ASSERT_EQ(expected.getY(), actual.getY());\
    ASSERT_EQ(expected.getZ(), actual.getZ());\
    ASSERT_EQ(expected.getW(), actual.getW());

TEST_F(dmGuiTest, HierarchicalColors)
{
    Vector3 size(1, 1, 0);

    dmGui::HNode node[6];
    const size_t node_count = sizeof(node)/sizeof(dmGui::HNode);

    for(uint32_t i = 0; i < node_count; ++i) {
        node[i] = dmGui::NewNode(m_Scene, Point3(0.0f, 0.0f, 0.0f), size, dmGui::NODE_TYPE_BOX);
        dmGui::SetNodeInheritAlpha(m_Scene, node[i], true);
    }

    // test child tree
    dmGui::SetNodeParent(m_Scene, node[4], node[3]);
    dmGui::SetNodeParent(m_Scene, node[5], node[4]);
    dmGui::SetNodeProperty(m_Scene, node[3], dmGui::PROPERTY_COLOR, Vector4(0.5f, 0.5f, 0.5f, 0.5f));
    dmGui::SetNodeProperty(m_Scene, node[4], dmGui::PROPERTY_COLOR, Vector4(1.0f, 0.5f, 1.0f, 0.5f));
    dmGui::SetNodeProperty(m_Scene, node[5], dmGui::PROPERTY_COLOR, Vector4(1.0f, 1.0f, 1.0f, 0.25f));

    // test siblings
    dmGui::SetNodeParent(m_Scene, node[1], node[0]);
    dmGui::SetNodeParent(m_Scene, node[2], node[0]);
    dmGui::SetNodeProperty(m_Scene, node[0], dmGui::PROPERTY_COLOR, Vector4(0.5f, 0.5f, 0.5f, 0.5f));
    dmGui::SetNodeProperty(m_Scene, node[1], dmGui::PROPERTY_COLOR, Vector4(1.0f, 0.5f, 1.0f, 0.5f));
    dmGui::SetNodeProperty(m_Scene, node[2], dmGui::PROPERTY_COLOR, Vector4(1.0f, 1.0f, 1.0f, 0.25f));

    TransformColorData cbres[node_count];
    dmGui::RenderScene(m_Scene, RenderNodesStoreColorAndTransform, &cbres);

    ASSERT_COLOR_EQ(Vector4(0.5000f, 0.5000f, 0.5000f, 0.5000f), cbres[0].m_Color);
    ASSERT_COLOR_EQ(Vector4(1.0000f, 0.5000f, 1.0000f, 0.2500f), cbres[1].m_Color);
    ASSERT_COLOR_EQ(Vector4(1.0000f, 1.0000f, 1.0000f, 0.1250f), cbres[2].m_Color);

    ASSERT_COLOR_EQ(Vector4(0.5000f, 0.5000f, 0.5000f, 0.5000f), cbres[3].m_Color);
    ASSERT_COLOR_EQ(Vector4(1.0000f, 0.5000f, 1.0000f, 0.2500f), cbres[4].m_Color);
    ASSERT_COLOR_EQ(Vector4(1.0000f, 1.0000f, 1.0000f, 0.0625f), cbres[5].m_Color);
}

/**
 * Test coherence of dmGui::RenderScene internal node-cache by adding, deleting nodes and altering node
 * properties in two passes of rendering
 *
 * - n1
 *   - n2
 *     - n3
 *       - n4
 * - n5
 *   - n6
 *     - n7
 *       - n8
 *
 * Render
 * Change color and transform properties of n5-n8, delete n3, n4
 * Render
 *
 */
TEST_F(dmGuiTest, SceneTransformCacheCoherence)
{
    Vector3 size(1, 1, 0);

    dmGui::HNode node[8];
    const size_t node_count = sizeof(node)/sizeof(dmGui::HNode);
    const size_t node_count_h = node_count/2;
    dmGui::HNode dummy_node[node_count];

    for(uint32_t i = 0; i < node_count; ++i)
    {
        dummy_node[i] = dmGui::NewNode(m_Scene, Point3(0.0f, 0.0f, 0.0f), size, dmGui::NODE_TYPE_BOX);
    }

    float c,a;
    c = a = 1.0f;
    for(uint32_t i = 0; i < node_count_h; ++i)
    {
        node[i] = dmGui::NewNode(m_Scene, Point3(1.0f, 1.0f, 1.0f), size, dmGui::NODE_TYPE_BOX);
        dmGui::SetNodeInheritAlpha(m_Scene, node[i], true);
        dmGui::SetNodePivot(m_Scene, node[i], dmGui::PIVOT_SW);
        dmGui::SetNodeProperty(m_Scene, node[i], dmGui::PROPERTY_COLOR, Vector4(c, c, c, a));
        if(i == 0)
            a = 0.5f;
    }
    c = a = 0.5f;
    for(uint32_t i = node_count_h; i < node_count; ++i)
    {
        node[i] = dmGui::NewNode(m_Scene, Point3(0.5f, 0.5f, 0.5f), size, dmGui::NODE_TYPE_BOX);
        dmGui::SetNodeInheritAlpha(m_Scene, node[i], true);
        dmGui::SetNodePivot(m_Scene, node[i], dmGui::PIVOT_SW);
        dmGui::SetNodeProperty(m_Scene, node[i], dmGui::PROPERTY_COLOR, Vector4(c, c, c, a));
        if(i == node_count_h)
            a = 0.5f;
    }
    for(uint32_t i = 1; i < node_count_h; ++i)
    {
        dmGui::SetNodeParent(m_Scene, node[i], node[i-1]);
        dmGui::SetNodeParent(m_Scene, node[i+(node_count_h)], node[(i+(node_count_h))-1]);
    }

    for(uint32_t i = 0; i < node_count; ++i)
    {
        DeleteNode(m_Scene, dummy_node[i]);
    }

    TransformColorData cbres[node_count];
    memset(cbres, 0x0, sizeof(TransformColorData)*node_count);
    dmGui::RenderScene(m_Scene, RenderNodesStoreColorAndTransform, &cbres);

    c = a = 1.0f;
    for(uint32_t i = 0; i < node_count_h; ++i)
    {
        if(i > 0)
        {
            for(uint32_t e = 0; e < 3; e++)
                ASSERT_NEAR(cbres[i].m_Transform.getTranslation().getElem(e), cbres[i-1].m_Transform.getTranslation().getElem(e)+1.0f, EPSILON);
        }
        ASSERT_COLOR_EQ(Vector4(c,c,c,a), cbres[i].m_Color);
        a *= 0.5f;
    }
    c = a = 0.5f;
    for(uint32_t i = node_count_h; i < node_count; ++i)
    {
        if(i > node_count_h)
        {
            for(uint32_t e = 0; e < 3; e++)
                ASSERT_NEAR(cbres[i].m_Transform.getTranslation().getElem(e), cbres[i-1].m_Transform.getTranslation().getElem(e)+0.5f, EPSILON);
        }
        ASSERT_COLOR_EQ(Vector4(c,c,c,a), cbres[i].m_Color);
        a *= 0.5f;
    }

    c = a = 1.0f;
    for(uint32_t i = node_count_h; i < node_count; ++i)
    {
        dmGui::SetNodeProperty(m_Scene, node[i], dmGui::PROPERTY_COLOR, Vector4(c, c, c, a));
        dmGui::SetNodePosition(m_Scene, node[i], Point3(0.25f, 0.25f, 0.25f));
        if(i == node_count_h)
            a = 0.25f;
    }

    dmGui::DeleteNode(m_Scene, node[3]);
    dmGui::DeleteNode(m_Scene, node[2]);
    dmGui::RenderScene(m_Scene, RenderNodesStoreColorAndTransform, &cbres);

    c = a = 1.0f;
    for(uint32_t i = 0; i < node_count_h-2; ++i)
    {
        if(i > 0)
        {
            for(uint32_t e = 0; e < 3; e++)
                ASSERT_NEAR(cbres[i].m_Transform.getTranslation().getElem(e), cbres[i-1].m_Transform.getTranslation().getElem(e)+1.0f, EPSILON);
        }
        ASSERT_COLOR_EQ(Vector4(c,c,c,a), cbres[i].m_Color);
        a *= 0.5f;
    }
    c = a = 1.0f;
    for(uint32_t i = node_count_h-2; i < node_count-2; ++i)
    {
        if(i > node_count_h-2)
        {
            for(uint32_t e = 0; e < 3; e++)
                ASSERT_NEAR(cbres[i].m_Transform.getTranslation().getElem(e), cbres[i-1].m_Transform.getTranslation().getElem(e)+0.25f, EPSILON);
        }
        ASSERT_COLOR_EQ(Vector4(c,c,c,a), cbres[i].m_Color);
        a *= 0.25f;
    }
}

#undef ASSERT_COLOR_EQ

TEST_F(dmGuiTest, ScriptClippingFunctions)
{
    dmGui::HNode node = dmGui::NewNode(m_Scene, Point3(0,0,0), Vector3(1,1,0), dmGui::NODE_TYPE_BOX);
    ASSERT_NE((dmGui::HNode) 0, node);
    dmGui::SetNodeId(m_Scene, node, "clipping_node");
    dmGui::HNode get_node = dmGui::GetNodeById(m_Scene, "clipping_node");
    ASSERT_EQ(node, get_node);

    const char* s = "function init(self)\n"
                    "    local n = gui.get_node(\"clipping_node\")\n"
                    "    local mode = gui.get_clipping_mode(n)\n"
                    "    assert(mode == gui.CLIPPING_MODE_NONE)\n"
                    "    gui.set_clipping_mode(n, gui.CLIPPING_MODE_STENCIL)\n"
                    "    mode = gui.get_clipping_mode(n)\n"
                    "    assert(mode == gui.CLIPPING_MODE_STENCIL)\n"
                    "    assert(gui.get_clipping_visible(n) == true)\n"
                    "    gui.set_clipping_visible(n, false)\n"
                    "    assert(gui.get_clipping_visible(n) == false)\n"
                    "    assert(gui.get_clipping_inverted(n) == false)\n"
                    "    gui.set_clipping_inverted(n, true)\n"
                    "    assert(gui.get_clipping_inverted(n) == true)\n"
                    "end\n";

    ASSERT_TRUE(SetScript(m_Script, s));
    ASSERT_EQ(dmGui::RESULT_OK, dmGui::InitScene(m_Scene));
}

/**
 * Verify layer rendering order.
 * Hierarchy:
 * - n1 (l1)
 * - n2
 */
TEST_F(dmGuiTest, LayerRendering)
{
    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);

    dmGui::AddLayer(m_Scene, "l1");

    std::map<dmGui::HNode, uint16_t> order;

    // Initial case
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::HNode n2 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);

    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);

    // Reverse
    dmGui::SetNodeLayer(m_Scene, n1, "l1");

    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(1u, order[n1]);
    ASSERT_EQ(0u, order[n2]);
}

/**
 * Verify layer rendering order.
 * Hierarchy:
 * - n1 (l1)
 *   - n2
 * - n3 (l2)
 *   - n4
 * Layers:
 * - l1
 * - l2
 *
 * - initial order: n1, n2, n3, n4
 * - reverse layer order: n3, n4, n1, n2
 */
TEST_F(dmGuiTest, LayerRenderingHierarchies)
{
    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);

    dmGui::AddLayer(m_Scene, "l1");
    dmGui::AddLayer(m_Scene, "l2");

    std::map<dmGui::HNode, uint16_t> order;

    // Initial case
    dmGui::HNode n1 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeLayer(m_Scene, n1, "l1");
    dmGui::HNode n2 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeParent(m_Scene, n2, n1);
    dmGui::HNode n3 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeLayer(m_Scene, n3, "l2");
    dmGui::HNode n4 = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeParent(m_Scene, n4, n3);
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(0u, order[n1]);
    ASSERT_EQ(1u, order[n2]);
    ASSERT_EQ(2u, order[n3]);
    ASSERT_EQ(3u, order[n4]);

    // Reverse
    dmGui::SetNodeLayer(m_Scene, n1, "l2");
    dmGui::SetNodeLayer(m_Scene, n3, "l1");
    dmGui::RenderScene(m_Scene, RenderNodesOrder, &order);
    ASSERT_EQ(2u, order[n1]);
    ASSERT_EQ(3u, order[n2]);
    ASSERT_EQ(0u, order[n3]);
    ASSERT_EQ(1u, order[n4]);
}

TEST_F(dmGuiTest, NoRenderOfDisabledTree)
{
    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);

    uint32_t count;

    // Edge case: single node
    dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::HNode parent = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::HNode child = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::SetNodeParent(m_Scene, child, parent);
    dmGui::RenderScene(m_Scene, RenderNodesCount, &count);
    ASSERT_EQ(3u, count);

    dmGui::SetNodeEnabled(m_Scene, parent, false);
    dmGui::RenderScene(m_Scene, RenderNodesCount, &count);

    ASSERT_EQ(1u, count);
}

TEST_F(dmGuiTest, DeleteTree)
{
    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size * 0.5f);

    uint32_t count;

    dmGui::HNode parent = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);
    dmGui::HNode child = dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);

    dmGui::SetNodeParent(m_Scene, child, parent);
    dmGui::RenderScene(m_Scene, RenderNodesCount, &count);
    ASSERT_EQ(2u, count);

    dmGui::DeleteNode(m_Scene, parent);
    dmGui::RenderScene(m_Scene, RenderNodesCount, &count);
    ASSERT_EQ(0u, count);
    ASSERT_EQ(m_Scene->m_NodePool.Remaining(), m_Scene->m_NodePool.Capacity());
}

TEST_F(dmGuiTest, PhysResUpdatesTransform)
{
    // Setup
    Vector3 size(10, 10, 0);
    Point3 pos(size);

    dmGui::NewNode(m_Scene, pos, size, dmGui::NODE_TYPE_BOX);

    Matrix4 transform;
    dmGui::RenderScene(m_Scene, RenderNodesStoreTransform, &transform);

    Matrix4 next_transform;
    dmGui::RenderScene(m_Scene, RenderNodesStoreTransform, &next_transform);

    Vector4 p = transform.getCol3();
    Vector4 next_p = next_transform.getCol3();
    ASSERT_LT(lengthSqr(p - next_p), EPSILON);

    dmGui::SetPhysicalResolution(m_Context, 10, 10);
    dmGui::RenderScene(m_Scene, RenderNodesStoreTransform, &next_transform);

    next_p = next_transform.getCol3();
    ASSERT_GT(lengthSqr(p - next_p), EPSILON);
}

TEST_F(dmGuiTest, NewDeleteScene)
{
    dmGui::NewSceneParams params;
    dmGui::HScene scene2 = dmGui::NewScene(m_Context, &params);

    ASSERT_EQ(2u, m_Context->m_Scenes.Size());

    dmGui::DeleteScene(scene2);

    ASSERT_EQ(1u, m_Context->m_Scenes.Size());
}

int main(int argc, char **argv)
{
    dmDDF::RegisterAllTypes();
    testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}
