#include "comp_collection_proxy.h"
#include "resources/res_collection_proxy.h"

#include <string.h>

#include <vectormath/cpp/vectormath_aos.h>

#include <dlib/log.h>
#include <dlib/hash.h>
#include <dlib/index_pool.h>

#include <gameobject/gameobject.h>
#include <gameobject/gameobject_ddf.h>

#include "../gamesys.h"
#include "../gamesys_private.h"

#include "gamesys_ddf.h"

namespace dmGameSystem
{
    using namespace Vectormath::Aos;

    const char* COLLECTION_PROXY_MAX_COUNT_KEY = "collection_proxy.max_count";

    struct CollectionProxyComponent
    {
        dmMessage::URL                  m_Unloader;
        CollectionProxyResource*        m_Resource;
        dmGameObject::HCollection       m_Collection;
        dmGameObject::HInstance         m_Instance;
        dmGameSystemDDF::TimeStepMode   m_TimeStepMode;
        float                           m_TimeStepFactor;
        float                           m_AccumulatedTime;
        uint32_t                        m_ComponentIndex : 8;
        uint32_t                        m_Initialized : 1;
        uint32_t                        m_Enabled : 1;
        uint32_t                        m_Unloaded : 1;
        uint32_t                        m_AddedToUpdate : 1;
    };

    struct CollectionProxyWorld
    {
        dmArray<CollectionProxyComponent>   m_Components;
        dmIndexPool32                       m_IndexPool;
    };

    dmGameObject::CreateResult CompCollectionProxyNewWorld(const dmGameObject::ComponentNewWorldParams& params)
    {
        CollectionProxyWorld* proxy_world = new CollectionProxyWorld();
        CollectionProxyContext* context = (CollectionProxyContext*)params.m_Context;
        const uint32_t component_count = context->m_MaxCollectionProxyCount;
        proxy_world->m_Components.SetCapacity(component_count);
        proxy_world->m_Components.SetSize(component_count);
        memset(&proxy_world->m_Components[0], 0, sizeof(CollectionProxyComponent) * component_count);
        proxy_world->m_IndexPool.SetCapacity(component_count);
        *params.m_World = proxy_world;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompCollectionProxyDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params)
    {
        CollectionProxyWorld* proxy_world = (CollectionProxyWorld*)params.m_World;
        CollectionProxyContext* context = (CollectionProxyContext*)params.m_Context;
        dmResource::HFactory factory = context->m_Factory;
        for (uint32_t i = 0; i < proxy_world->m_Components.Size(); ++i)
        {
            dmGameObject::HCollection collection = proxy_world->m_Components[i].m_Collection;
            if (collection != 0)
            {
                if (proxy_world->m_Components[i].m_Initialized)
                    dmGameObject::Final(collection);
                dmResource::Release(factory, collection);
            }
        }
        delete proxy_world;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompCollectionProxyCreate(const dmGameObject::ComponentCreateParams& params)
    {
        CollectionProxyWorld* proxy_world = (CollectionProxyWorld*)params.m_World;
        if (proxy_world->m_IndexPool.Remaining() > 0)
        {
            uint32_t index = proxy_world->m_IndexPool.Pop();
            CollectionProxyComponent* proxy = &proxy_world->m_Components[index];
            memset(proxy, 0, sizeof(CollectionProxyComponent));
            proxy->m_TimeStepFactor = 1.0f;
            proxy->m_Resource = (CollectionProxyResource*) params.m_Resource;
            proxy->m_Instance = params.m_Instance;
            proxy->m_ComponentIndex = params.m_ComponentIndex;
            *params.m_UserData = (uintptr_t) proxy;
            return dmGameObject::CREATE_RESULT_OK;
        }
        else
        {
            dmLogError("Collection proxy could not be created since the buffer is full (%d), tweak \"%s\" in the config file.", proxy_world->m_Components.Size(), COLLECTION_PROXY_MAX_COUNT_KEY);
            return dmGameObject::CREATE_RESULT_UNKNOWN_ERROR;
        }
    }

    dmGameObject::CreateResult CompCollectionProxyDestroy(const dmGameObject::ComponentDestroyParams& params)
    {
        CollectionProxyComponent* proxy = (CollectionProxyComponent*)*params.m_UserData;
        CollectionProxyContext* context = (CollectionProxyContext*)params.m_Context;
        if (proxy->m_Collection != 0)
        {
            if (proxy->m_Initialized)
                dmGameObject::Final(proxy->m_Collection);
            dmResource::Release(context->m_Factory, proxy->m_Collection);
        }
        CollectionProxyWorld* proxy_world = (CollectionProxyWorld*)params.m_World;
        uint32_t index = proxy - &proxy_world->m_Components[0];
        proxy_world->m_IndexPool.Push(index);
        memset(proxy, 0, sizeof(CollectionProxyComponent));
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompCollectionProxyAddToUpdate(const dmGameObject::ComponentAddToUpdateParams& params)
    {
        CollectionProxyComponent* proxy = (CollectionProxyComponent*)*params.m_UserData;
        proxy->m_AddedToUpdate = 1;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::UpdateResult CompCollectionProxyUpdate(const dmGameObject::ComponentsUpdateParams& params)
    {
        CollectionProxyWorld* proxy_world = (CollectionProxyWorld*)params.m_World;
        dmGameObject::UpdateResult result = dmGameObject::UPDATE_RESULT_OK;
        for (uint32_t i = 0; i < proxy_world->m_Components.Size(); ++i)
        {
            CollectionProxyComponent* proxy = &proxy_world->m_Components[i];
            if (!proxy->m_AddedToUpdate) {
                continue;
            }
            if (proxy->m_Collection != 0)
            {
                if (proxy->m_Enabled)
                {
                    dmGameObject::UpdateContext uc;

                    float warped_dt = params.m_UpdateContext->m_DT * proxy->m_TimeStepFactor;
                    switch (proxy->m_TimeStepMode)
                    {
                    case dmGameSystemDDF::TIME_STEP_MODE_CONTINUOUS:
                        uc.m_DT = warped_dt;
                        proxy->m_AccumulatedTime = 0.0f;
                        break;
                    case dmGameSystemDDF::TIME_STEP_MODE_DISCRETE:
                        proxy->m_AccumulatedTime += warped_dt;
                        if (proxy->m_AccumulatedTime >= params.m_UpdateContext->m_DT)
                        {
                            uc.m_DT = params.m_UpdateContext->m_DT;
                            proxy->m_AccumulatedTime -= params.m_UpdateContext->m_DT;
                        }
                        else
                        {
                            uc.m_DT = 0.0f;
                        }
                        break;
                    default:
                        break;
                    }

                    if (!dmGameObject::Update(proxy->m_Collection, &uc))
                        result = dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
                }
                else
                {
                    proxy->m_AccumulatedTime = 0.0f;
                }
            }
            if (proxy->m_Unloaded)
            {
                proxy->m_Unloaded = 0;
                if (dmMessage::IsSocketValid(proxy->m_Unloader.m_Socket))
                {
                    dmMessage::URL sender;
                    sender.m_Socket = dmGameObject::GetMessageSocket(dmGameObject::GetCollection(proxy->m_Instance));
                    sender.m_Path = dmGameObject::GetIdentifier(proxy->m_Instance);
                    dmGameObject::GetComponentId(proxy->m_Instance, proxy->m_ComponentIndex, &sender.m_Fragment);
                    dmMessage::Result msg_result = dmMessage::Post(&sender, &proxy->m_Unloader, dmHashString64("proxy_unloaded"), 0, 0, 0, 0);
                    if (msg_result != dmMessage::RESULT_OK)
                    {
                        dmLogWarning("proxy_unloaded could not be posted: %d", msg_result);
                    }
                }
            }
        }
        return result;
    }

    dmGameObject::UpdateResult CompCollectionProxyPostUpdate(const dmGameObject::ComponentsPostUpdateParams& params)
    {
        CollectionProxyWorld* proxy_world = (CollectionProxyWorld*)params.m_World;
        dmGameObject::UpdateResult result = dmGameObject::UPDATE_RESULT_OK;
        for (uint32_t i = 0; i < proxy_world->m_Components.Size(); ++i)
        {
            CollectionProxyComponent* proxy = &proxy_world->m_Components[i];
            if (proxy->m_Collection != 0)
            {
                if (proxy->m_Enabled)
                {
                    if (!dmGameObject::PostUpdate(proxy->m_Collection))
                        result = dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
                }
            }
        }
        return result;
    }

    /*# sets the time-step for update
     * <p>
     * Post this message to a collection-proxy-component to modify the time-step used when updating the collection controlled by the proxy.
     * The time-step is modified by a scaling <code>factor</code> and can be incremented either continuously or in discrete steps.
     * </p>
     * <p>
     * The continuous mode can be used for slow-motion or fast-forward effects.
     * </p>
     * <p>
     * The discrete mode is only useful when scaling the time-step to pass slower than real time (<code>factor</code> is below 1).
     * The time-step will then be set to 0 for as many frames as the scaling demands and then take on the full real-time-step for one frame,
     * to simulate pulses. E.g. if <code>factor</code> is set to <code>0.1</code> the time-step would be 0 for 9 frames, then be 1/60 for one
     * frame, 0 for 9 frames, and so on. The result in practice is that the game looks like it's updated at a much lower frequency than 60 Hz,
     * which can be useful for debugging when each frame needs to be inspected.
     * </p>
     *
     * @message
     * @name set_time_step
     * @param factor time-step scaling factor (number)
     * @param mode time-step mode: 0 for continuous and 1 for discrete (number)
     * @examples
     * <p>The examples assumes the script belongs to an instance with a collection-proxy-component with id "proxy".</p>
     * <p>
     * Update the collection twice as fast:
     * </p>
     * <pre>
     * msg.post("#proxy", "set_time_step", {factor = 2, mode = 0})
     * </pre>
     * <p>
     * Update the collection twice as slow:
     * </p>
     * <pre>
     * msg.post("#proxy", "set_time_step", {factor = 0.5, mode = 0})
     * </pre>
     * <p>
     * Simulate 1 FPS for the collection:
     * </p>
     * <pre>
     * msg.post("#proxy", "set_time_step", {factor = 1/60, mode = 1})
     * </pre>
     */

    dmGameObject::UpdateResult CompCollectionProxyOnMessage(const dmGameObject::ComponentOnMessageParams& params)
    {
        CollectionProxyComponent* proxy = (CollectionProxyComponent*) *params.m_UserData;
        CollectionProxyContext* context = (CollectionProxyContext*)params.m_Context;
        if (params.m_Message->m_Id == dmHashString64("load"))
        {
            if (proxy->m_Collection == 0)
            {
                proxy->m_Unloaded = 0;
                // TODO: asynchronous loading
                dmResource::Result result = dmResource::Get(context->m_Factory, proxy->m_Resource->m_DDF->m_Collection, (void**)&proxy->m_Collection);
                if (result != dmResource::RESULT_OK)
                {
                    dmLogError("The collection %s could not be loaded.", proxy->m_Resource->m_DDF->m_Collection);
                    return dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
                }
                if (dmMessage::IsSocketValid(params.m_Message->m_Sender.m_Socket))
                {
                    dmMessage::Result msg_result = dmMessage::Post(&params.m_Message->m_Receiver, &params.m_Message->m_Sender, dmHashString64("proxy_loaded"), 0, 0, 0, 0);
                    if (msg_result != dmMessage::RESULT_OK)
                    {
                        LogMessageError(params.m_Message, "proxy_loaded could not be posted: %d", msg_result);
                    }
                }
            }
            else
            {
                LogMessageError(params.m_Message, "The collection %s could not be loaded since it was already.", proxy->m_Resource->m_DDF->m_Collection);
            }
        }
        else if (params.m_Message->m_Id == dmHashString64("unload"))
        {
            if (proxy->m_Collection != 0)
            {
                dmResource::Release(context->m_Factory, proxy->m_Collection);
                proxy->m_Collection = 0;
                proxy->m_Initialized = 0;
                proxy->m_Enabled = 0;
                proxy->m_Unloaded = 1;
                proxy->m_Unloader = params.m_Message->m_Sender;
            }
            else
            {
                LogMessageError(params.m_Message, "The collection %s could not be unloaded since it was never loaded.", proxy->m_Resource->m_DDF->m_Collection);
            }
        }
        else if (params.m_Message->m_Id == dmHashString64("init"))
        {
            if (proxy->m_Collection != 0)
            {
                if (proxy->m_Initialized == 0)
                {
                    dmGameObject::Init(proxy->m_Collection);
                    proxy->m_Initialized = 1;
                }
                else
                {
                    LogMessageError(params.m_Message, "The collection %s could not be initialized since it has been already.", proxy->m_Resource->m_DDF->m_Collection);
                }
            }
            else
            {
                LogMessageError(params.m_Message, "The collection %s could not be initialized since it has not been loaded.", proxy->m_Resource->m_DDF->m_Collection);
            }
        }
        else if (params.m_Message->m_Id == dmHashString64("final"))
        {
            if (proxy->m_Initialized == 1 && proxy->m_Collection != 0x0)
            {
                dmGameObject::Final(proxy->m_Collection);
                proxy->m_Initialized = 0;
            }
            else
            {
                LogMessageError(params.m_Message, "The collection %s could not be finalized since it was never initialized.", proxy->m_Resource->m_DDF->m_Collection);
            }
        }
        else if (params.m_Message->m_Id == dmGameObjectDDF::Enable::m_DDFDescriptor->m_NameHash)
        {
            if (proxy->m_Collection != 0)
            {
                if (proxy->m_Enabled == 0)
                {
                    proxy->m_Enabled = 1;
                    if (proxy->m_Initialized == 0)
                    {
                        dmGameObject::Init(proxy->m_Collection);
                        proxy->m_Initialized = 1;
                    }
                }
                else
                {
                    LogMessageError(params.m_Message, "The collection %s could not be enabled since it is already.", proxy->m_Resource->m_DDF->m_Collection);
                }
            }
            else
            {
                LogMessageError(params.m_Message, "The collection %s could not be initialized since it has not been loaded.", proxy->m_Resource->m_DDF->m_Collection);
            }
        }
        else if (params.m_Message->m_Id == dmGameObjectDDF::Disable::m_DDFDescriptor->m_NameHash)
        {
            if (proxy->m_Enabled == 1)
            {
                proxy->m_Enabled = 0;
            }
            else
            {
                LogMessageError(params.m_Message, "The collection %s could not be disabled since it is not enabled.", proxy->m_Resource->m_DDF->m_Collection);
            }
        }
        else if ((dmDDF::Descriptor*)params.m_Message->m_Descriptor == dmGameSystemDDF::SetTimeStep::m_DDFDescriptor)
        {
            dmGameSystemDDF::SetTimeStep* ddf = (dmGameSystemDDF::SetTimeStep*)params.m_Message->m_Data;
            proxy->m_TimeStepFactor = ddf->m_Factor;
            proxy->m_TimeStepMode = ddf->m_Mode;
        }
        else if (params.m_Message->m_Id == dmHashString64("reset_time_step"))
        {
            proxy->m_TimeStepFactor = 1.0f;
            proxy->m_TimeStepMode = dmGameSystemDDF::TIME_STEP_MODE_CONTINUOUS;
        }

        return dmGameObject::UPDATE_RESULT_OK;
    }

    dmGameObject::InputResult CompCollectionProxyOnInput(const dmGameObject::ComponentOnInputParams& params)
    {
        CollectionProxyComponent* proxy = (CollectionProxyComponent*) *params.m_UserData;
        if (proxy->m_Enabled)
            dmGameObject::DispatchInput(proxy->m_Collection, (dmGameObject::InputAction*)params.m_InputAction, 1);
        return dmGameObject::INPUT_RESULT_IGNORED;
    }

    /*# tells a collection proxy to start loading the referenced collection
     * <p>
     * Post this message to a collection-proxy-component to start the loading of the referenced collection.
     * When the loading has completed, the message <code>proxy_loaded</code> will be sent back to the script.
     * </p>
     * <p>
     * A loaded collection must be initialized (message <code>init</code>) and enabled (message <code>enable</code>) in order to be simulated and drawn.
     * </p>
     * @message
     * @name load
     * @examples
     * <p>In this example we use a collection proxy to load/unload a level (collection).</p>
     * <p>The examples assume the script belongs to an instance with collection-proxy-component with id "proxy".</p>
     * <pre>
     * function on_message(self, message_id, message, sender)
     *     if message_id == hash("start_level") then
     *         -- some script tells us to start loading the level
     *         msg.post("#proxy", "load")
     *         -- store sender for later notification
     *         self.loader = sender
     *     elseif message_id == hash("proxy_loaded") then
     *         -- enable the collection and let the loader know
     *         msg.post(sender, "enable")
     *         msg.post(self.loader, message_id)
     *     end
     * end
     * </pre>
     */

    /*# tells a collection proxy to initialize the loaded collection
     * Post this message to a collection-proxy-component to initialize the game objects and components in the referenced collection.
     * Sending <code>enable</code> to an uninitialized collection proxy automatically initializes it.
     * The <code>init</code> message simply provides a higher level of control.
     *
     * @message
     * @name init
     * @examples
     * <p>In this example we use a collection proxy to load/unload a level (collection).</p>
     * <p>The examples assume the script belongs to an instance with collection-proxy-component with id "proxy".</p>
     * <pre>
     * function on_message(self, message_id, message, sender)
     *     if message_id == hash("load_level") then
     *         -- some script tells us to start loading the level
     *         msg.post("#proxy", "load")
     *         -- store sender for later notification
     *         self.loader = sender
     *     elseif message_id == hash("proxy_loaded") then
     *         -- only initialize the proxy at this point since we want to enable it at a later time for some reason
     *         msg.post(sender, "init")
     *         -- let loader know
     *         msg.post(self.loader, message_id)
     *     end
     * end
     * </pre>
     */

    /*# tells a collection proxy to enable the referenced collection
     * Post this message to a collection-proxy-component to enable the referenced collection, which in turn enables the contained game objects and components.
     * If the referenced collection was not initialized prior to this call, it will automatically be initialized.
     *
     * @message
     * @name enable
     * @examples
     * <p>In this example we use a collection proxy to load/unload a level (collection).</p>
     * <p>The examples assume the script belongs to an instance with collection-proxy-component with id "proxy".</p>
     * <pre>
     * function on_message(self, message_id, message, sender)
     *     if message_id == hash("start_level") then
     *         -- some script tells us to start loading the level
     *         msg.post("#proxy", "load")
     *         -- store sender for later notification
     *         self.loader = sender
     *     elseif message_id == hash("proxy_loaded") then
     *         -- enable the collection and let the loader know
     *         msg.post(sender, "enable")
     *         msg.post(self.loader, "level_started")
     *     end
     * end
     */

    /*# tells a collection proxy to disable the referenced collection
     * Post this message to a collection-proxy-component to disable the referenced collection, which in turn disables the contained game objects and components.
     *
     * @message
     * @name disable
     * @examples
     * <p>In this example we use a collection proxy to load/unload a level (collection).</p>
     * <p>The examples assumes the script belongs to an instance with a collection-proxy-component with id "proxy".</p>
     * <pre>
     * function on_message(self, message_id, message, sender)
     *     if message_id == hash("end_level") then
     *         local proxy = msg.url("#proxy")
     *         msg.post(proxy, "disable")
     *         msg.post(proxy, "final")
     *         msg.post(proxy, "unload")
     *         -- store sender for later notification
     *         self.unloader = sender
     *     elseif message_id == hash("proxy_unloaded") then
     *         -- let unloader know
     *         msg.post(self.unloader, "level_ended")
     *     end
     * end
     * </pre>
     */

    /*# tells a collection proxy to finalize the referenced collection
     * Post this message to a collection-proxy-component to finalize the referenced collection, which in turn finalizes the contained game objects and components.
     *
     * @message
     * @name final
     * @examples
     * <p>In this example we use a collection proxy to load/unload a level (collection).</p>
     * <p>The examples assumes the script belongs to an instance with a collection-proxy-component with id "proxy".</p>
     * <pre>
     * function on_message(self, message_id, message, sender)
     *     if message_id == hash("end_level") then
     *         local proxy = msg.url("#proxy")
     *         msg.post(proxy, "disable")
     *         msg.post(proxy, "final")
     *         msg.post(proxy, "unload")
     *         -- store sender for later notification
     *         self.unloader = sender
     *     elseif message_id == hash("proxy_unloaded") then
     *         -- let unloader know
     *         msg.post(self.unloader, "level_ended")
     *     end
     * end
     * </pre>
     */

    /*# tells a collection proxy to start unloading the referenced collection
     * <p>
     * Post this message to a collection-proxy-component to start the unloading of the referenced collection.
     * When the unloading has completed, the message <code>proxy_unloaded</code> will be sent back to the script.
     * </p>
     *
     * @message
     * @name unload
     * @examples
     * <p>In this example we use a collection proxy to load/unload a level (collection).</p>
     * <p>The examples assumes the script belongs to an instance with a collection-proxy-component with id "proxy".</p>
     * <pre>
     * function on_message(self, message_id, message, sender)
     *     if message_id == hash("end_level") then
     *         local proxy = msg.url("#proxy")
     *         msg.post(proxy, "disable")
     *         msg.post(proxy, "final")
     *         msg.post(proxy, "unload")
     *         -- store sender for later notification
     *         self.unloader = sender
     *     elseif message_id == hash("proxy_unloaded") then
     *         -- let unloader know
     *         msg.post(self.unloader, "level_ended")
     *     end
     * end
     * </pre>
     */
}
