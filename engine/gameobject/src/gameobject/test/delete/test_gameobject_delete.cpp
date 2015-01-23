#include <gtest/gtest.h>

#include <algorithm>
#include <map>
#include <vector>

#include <resource/resource.h>

#include "../gameobject.h"
#include "../gameobject_private.h"
#include "gameobject/test/delete/test_gameobject_delete_ddf.h"

class DeleteTest : public ::testing::Test
{
protected:
    virtual void SetUp()
    {
        m_UpdateContext.m_DT = 1.0f / 60.0f;

        dmResource::NewFactoryParams params;
        params.m_MaxResources = 16;
        params.m_Flags = RESOURCE_FACTORY_FLAGS_EMPTY;
        m_Factory = dmResource::NewFactory(&params, "build/default/src/gameobject/test/delete");
        m_ScriptContext = dmScript::NewContext(0, 0);
        dmScript::Initialize(m_ScriptContext);
        dmGameObject::Initialize(m_ScriptContext);
        m_Register = dmGameObject::NewRegister();
        dmGameObject::RegisterResourceTypes(m_Factory, m_Register, m_ScriptContext, &m_ModuleContext);
        dmGameObject::RegisterComponentTypes(m_Factory, m_Register, m_ScriptContext);
        m_Collection = dmGameObject::NewCollection("collection", m_Factory, m_Register, 1024);

        dmResource::Result e;
        e = dmResource::RegisterType(m_Factory, "deleteself", this, ResDeleteSelfCreate, ResDeleteSelfDestroy, 0);
        ASSERT_EQ(dmResource::RESULT_OK, e);

        dmResource::ResourceType resource_type;
        e = dmResource::GetTypeFromExtension(m_Factory, "deleteself", &resource_type);
        ASSERT_EQ(dmResource::RESULT_OK, e);
        dmGameObject::ComponentType ds_type;
        ds_type.m_Name = "deleteself";
        ds_type.m_ResourceType = resource_type;
        ds_type.m_Context = this;
        ds_type.m_UpdateFunction = DeleteSelfComponentsUpdate;
        dmGameObject::Result result = dmGameObject::RegisterComponentType(m_Register, ds_type);
        ASSERT_EQ(dmGameObject::RESULT_OK, result);
    }

    virtual void TearDown()
    {
        dmGameObject::DeleteCollection(m_Collection);
        dmGameObject::PostUpdate(m_Register);
        dmScript::Finalize(m_ScriptContext);
        dmScript::DeleteContext(m_ScriptContext);
        dmResource::DeleteFactory(m_Factory);
        dmGameObject::DeleteRegister(m_Register);
    }

    static dmResource::Result ResDeleteSelfCreate(dmResource::HFactory factory, void* context, const void* buffer, uint32_t buffer_size, dmResource::SResourceDescriptor* resource, const char* filename);
    static dmResource::Result ResDeleteSelfDestroy(dmResource::HFactory factory, void* context, dmResource::SResourceDescriptor* resource);

    static dmGameObject::UpdateResult     DeleteSelfComponentsUpdate(const dmGameObject::ComponentsUpdateParams& params);

public:

    std::map<uint64_t, uint32_t> m_CreateCountMap;
    std::map<uint64_t, uint32_t> m_DestroyCountMap;

    // Data DeleteSelf test
    std::vector<dmGameObject::HInstance> m_SelfInstancesToDelete;
    std::vector<dmGameObject::HInstance> m_DeleteSelfInstances;
    std::vector<int> m_DeleteSelfIndices;
    std::map<int, dmGameObject::HInstance> m_DeleteSelfIndexToInstance;

    dmScript::HContext m_ScriptContext;
    dmGameObject::UpdateContext m_UpdateContext;
    dmGameObject::HRegister m_Register;
    dmGameObject::HCollection m_Collection;
    dmResource::HFactory m_Factory;
    dmGameObject::ModuleContext m_ModuleContext;
};

dmResource::Result DeleteTest::ResDeleteSelfCreate(dmResource::HFactory factory, void* context, const void* buffer, uint32_t buffer_size, dmResource::SResourceDescriptor* resource, const char* filename)
{
    DeleteTest* game_object_test = (DeleteTest*) context;
    game_object_test->m_CreateCountMap[TestGameObjectDDF::DeleteSelfResource::m_DDFHash]++;

    TestGameObjectDDF::DeleteSelfResource* obj;
    dmDDF::Result e = dmDDF::LoadMessage<TestGameObjectDDF::DeleteSelfResource>(buffer, buffer_size, &obj);
    if (e == dmDDF::RESULT_OK)
    {
        resource->m_Resource = (void*) obj;
        return dmResource::RESULT_OK;
    }
    else
    {
        return dmResource::RESULT_FORMAT_ERROR;
    }
}

dmResource::Result DeleteTest::ResDeleteSelfDestroy(dmResource::HFactory factory, void* context, dmResource::SResourceDescriptor* resource)
{
    DeleteTest* game_object_test = (DeleteTest*) context;
    game_object_test->m_DestroyCountMap[TestGameObjectDDF::DeleteSelfResource::m_DDFHash]++;

    dmDDF::FreeMessage((void*) resource->m_Resource);
    return dmResource::RESULT_OK;
}

dmGameObject::UpdateResult DeleteTest::DeleteSelfComponentsUpdate(const dmGameObject::ComponentsUpdateParams& params)
{
    DeleteTest* game_object_test = (DeleteTest*) params.m_Context;

    for (uint32_t i = 0; i < game_object_test->m_SelfInstancesToDelete.size(); ++i)
    {
        dmGameObject::Delete(game_object_test->m_Collection, game_object_test->m_SelfInstancesToDelete[i]);
        // Test "double delete"
        dmGameObject::Delete(game_object_test->m_Collection, game_object_test->m_SelfInstancesToDelete[i]);
    }

    for (uint32_t i = 0; i < game_object_test->m_DeleteSelfIndices.size(); ++i)
    {
        int index = game_object_test->m_DeleteSelfIndices[i];

        dmGameObject::HInstance go = game_object_test->m_DeleteSelfIndexToInstance[index];
        if (index != (int)dmGameObject::GetPosition(go).getX())
            return dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
    }

    return dmGameObject::UPDATE_RESULT_OK;
}

TEST_F(DeleteTest, AutoDelete)
{
    for (int i = 0; i < 512; ++i)
    {
        dmGameObject::HInstance go = dmGameObject::New(m_Collection, "/go.goc");
        ASSERT_NE((void*) 0, (void*) go);
    }
}

TEST_F(DeleteTest, DeleteSelf)
{
    /*
     * NOTE: We do not have any .deleteself resources on disk even though we register the type
     * Component instances of type 'A' is used here. We need a specific ComponentUpdate though. (DeleteSelfComponentsUpdate)
     * See New(..., goproto01.goc") below.
     */
    for (int iter = 0; iter < 4; ++iter)
    {
        m_DeleteSelfInstances.clear();
        m_DeleteSelfIndexToInstance.clear();

        for (int i = 0; i < 512; ++i)
        {
            dmGameObject::HInstance go = dmGameObject::New(m_Collection, "/go.goc");
            dmGameObject::SetPosition(go, Vectormath::Aos::Point3((float) i,(float) i, (float) i));
            ASSERT_NE((void*) 0, (void*) go);
            m_DeleteSelfInstances.push_back(go);
            m_DeleteSelfIndexToInstance[i] = go;
            m_DeleteSelfIndices.push_back(i);
        }

        std::random_shuffle(m_DeleteSelfIndices.begin(), m_DeleteSelfIndices.end());

        while (m_DeleteSelfIndices.size() > 0)
        {
            for (int i = 0; i < 16; ++i)
            {
                int index = *(m_DeleteSelfIndices.end() - i - 1);
                m_SelfInstancesToDelete.push_back(m_DeleteSelfIndexToInstance[index]);
            }
            bool ret = dmGameObject::Update(m_Collection, &m_UpdateContext);
            ASSERT_TRUE(ret);
            ret = dmGameObject::PostUpdate(m_Collection);
            ASSERT_TRUE(ret);
            for (int i = 0; i < 16; ++i)
            {
                m_DeleteSelfIndices.pop_back();
            }

            m_SelfInstancesToDelete.clear();
        }
    }
}

TEST_F(DeleteTest, TestScriptDelete)
{
    dmGameObject::HInstance instance = dmGameObject::New(m_Collection, "/delete.goc");
    ASSERT_NE((void*)0, (void*)instance);
    ASSERT_NE(0, m_Collection->m_InstanceIndices.Size());
    ASSERT_TRUE(dmGameObject::Update(m_Collection, &m_UpdateContext));
    ASSERT_TRUE(dmGameObject::PostUpdate(m_Collection));
    ASSERT_EQ(0, m_Collection->m_InstanceIndices.Size());
}

TEST_F(DeleteTest, TestScriptDeleteOther)
{
    dmGameObject::HInstance instance = dmGameObject::New(m_Collection, "/delete_other.goc");
    ASSERT_NE((void*)0, (void*)instance);
    instance = dmGameObject::New(m_Collection, "/go.goc");
    dmGameObject::SetIdentifier(m_Collection, instance, "test_id");
    ASSERT_NE((void*)0, (void*)instance);
    ASSERT_NE(1, m_Collection->m_InstanceIndices.Size());
    ASSERT_TRUE(dmGameObject::Update(m_Collection, &m_UpdateContext));
    ASSERT_TRUE(dmGameObject::PostUpdate(m_Collection));
    ASSERT_EQ(1, m_Collection->m_InstanceIndices.Size());
}

TEST_F(DeleteTest, TestScriptDeleteNonExistent)
{
    dmGameObject::HInstance instance = dmGameObject::New(m_Collection, "/delete_non_existent.goc");
    ASSERT_NE((void*)0, (void*)instance);
    instance = dmGameObject::New(m_Collection, "/go.goc");
    dmGameObject::SetIdentifier(m_Collection, instance, "test_id");
    ASSERT_NE((void*)0, (void*)instance);
    ASSERT_NE(1, m_Collection->m_InstanceIndices.Size());
    ASSERT_FALSE(dmGameObject::Update(m_Collection, &m_UpdateContext));
    ASSERT_TRUE(dmGameObject::PostUpdate(m_Collection));
    ASSERT_EQ(2, m_Collection->m_InstanceIndices.Size());
}

int main(int argc, char **argv)
{
    testing::InitGoogleTest(&argc, argv);

    int ret = RUN_ALL_TESTS();
    return ret;
}
