#include "res_collection.h"

#include <dlib/dstrings.h>
#include <dlib/log.h>

#include "gameobject.h"
#include "gameobject_private.h"
#include "gameobject_props.h"
#include "gameobject_props_ddf.h"

#include "../proto/gameobject_ddf.h"

namespace dmGameObject
{
    dmResource::Result ResCollectionCreate(dmResource::HFactory factory,
                                                void* context,
                                                const void* buffer, uint32_t buffer_size,
                                                dmResource::SResourceDescriptor* resource,
                                                const char* filename)
    {
        Register* regist = (Register*) context;

        dmGameObjectDDF::CollectionDesc* collection_desc;
        dmDDF::Result e = dmDDF::LoadMessage<dmGameObjectDDF::CollectionDesc>(buffer, buffer_size, &collection_desc);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }
        dmResource::Result res = dmResource::RESULT_OK;

        // NOTE: Be careful about control flow. See below with dmMutex::Unlock, return, etc
        dmMutex::Lock(regist->m_Mutex);

        // TODO: How to configure 1024. In collection?
        // The size is also used in comp_anim.cpp (AnimWorld::m_InstanceToIndex)
        HCollection collection = NewCollection(collection_desc->m_Name, factory, regist, 1024);
        if (collection == 0)
        {
            dmMutex::Unlock(regist->m_Mutex);
            return dmResource::RESULT_OUT_OF_RESOURCES;
        }
        collection->m_ScaleAlongZ = collection_desc->m_ScaleAlongZ;

        for (uint32_t i = 0; i < collection_desc->m_Instances.m_Count; ++i)
        {
            const dmGameObjectDDF::InstanceDesc& instance_desc = collection_desc->m_Instances[i];
            Prototype* proto = 0x0;
            dmResource::HFactory factory = collection->m_Factory;
            dmGameObject::HInstance instance = 0x0;
            if (instance_desc.m_Prototype != 0x0)
            {
                dmResource::Result error = dmResource::Get(factory, instance_desc.m_Prototype, (void**)&proto);
                if (error == dmResource::RESULT_OK) {
                    instance = dmGameObject::NewInstance(collection, proto, instance_desc.m_Prototype);
                    if (instance == 0) {
                        dmResource::Release(factory, proto);
                    }
                }
            }
            if (instance != 0x0)
            {
                instance->m_ScaleAlongZ = collection_desc->m_ScaleAlongZ;
                instance->m_Transform = dmTransform::Transform(Vector3(instance_desc.m_Position), instance_desc.m_Rotation, instance_desc.m_Scale);

                dmHashInit64(&instance->m_CollectionPathHashState, true);
                const char* path_end = strrchr(instance_desc.m_Id, *ID_SEPARATOR);
                if (path_end == 0x0)
                {
                    dmLogError("The id of %s has an incorrect format, missing path specifier.", instance_desc.m_Id);
                }
                else
                {
                    dmHashUpdateBuffer64(&instance->m_CollectionPathHashState, instance_desc.m_Id, path_end - instance_desc.m_Id + 1);
                }

                if (dmGameObject::SetIdentifier(collection, instance, instance_desc.m_Id) != dmGameObject::RESULT_OK)
                {
                    dmLogError("Unable to set identifier %s. Name clash?", instance_desc.m_Id);
                }
            }
            else
            {
                dmLogError("Could not instantiate game object from prototype %s.", instance_desc.m_Prototype);
                res = dmResource::RESULT_FORMAT_ERROR; // TODO: Could be out-of-resources as well..
                goto bail;
            }
        }

        // Setup hierarchy
        for (uint32_t i = 0; i < collection_desc->m_Instances.m_Count; ++i)
        {
            const dmGameObjectDDF::InstanceDesc& instance_desc = collection_desc->m_Instances[i];

            dmGameObject::HInstance parent = dmGameObject::GetInstanceFromIdentifier(collection, dmHashString64(instance_desc.m_Id));
            assert(parent);

            for (uint32_t j = 0; j < instance_desc.m_Children.m_Count; ++j)
            {
                dmGameObject::HInstance child = dmGameObject::GetInstanceFromIdentifier(collection, dmGameObject::GetAbsoluteIdentifier(parent, instance_desc.m_Children[j], strlen(instance_desc.m_Children[j])));
                if (child)
                {
                    dmGameObject::Result r = dmGameObject::SetParent(child, parent);
                    if (r != dmGameObject::RESULT_OK)
                    {
                        dmLogError("Unable to set %s as parent to %s (%d)", instance_desc.m_Id, instance_desc.m_Children[j], r);
                    }
                }
                else
                {
                    dmLogError("Child not found: %s", instance_desc.m_Children[j]);
                }
            }
        }

        dmGameObject::UpdateTransforms(collection);

        // Create components and set properties
        for (uint32_t i = 0; i < collection_desc->m_Instances.m_Count; ++i)
        {
            const dmGameObjectDDF::InstanceDesc& instance_desc = collection_desc->m_Instances[i];

            dmGameObject::HInstance instance = dmGameObject::GetInstanceFromIdentifier(collection, dmHashString64(instance_desc.m_Id));

            bool result = dmGameObject::CreateComponents(collection, instance);
            if (result) {
                // Set properties
                uint32_t component_instance_data_index = 0;
                dmArray<Prototype::Component>& components = instance->m_Prototype->m_Components;
                uint32_t comp_count = components.Size();
                for (uint32_t comp_i = 0; comp_i < comp_count; ++comp_i)
                {
                    Prototype::Component& component = components[comp_i];
                    ComponentType* type = component.m_Type;
                    if (type->m_SetPropertiesFunction != 0x0)
                    {
                        if (!type->m_InstanceHasUserData)
                        {
                            dmLogError("Unable to set properties for the component '%s' in game object '%s' since it has no ability to store them.", (const char*)dmHashReverse64(component.m_Id, 0x0), instance_desc.m_Id);
                            res = dmResource::RESULT_FORMAT_ERROR;
                            goto bail;
                        }
                        ComponentSetPropertiesParams params;
                        params.m_Instance = instance;
                        uint32_t comp_prop_count = instance_desc.m_ComponentProperties.m_Count;
                        for (uint32_t prop_i = 0; prop_i < comp_prop_count; ++prop_i)
                        {
                            const dmGameObjectDDF::ComponentPropertyDesc& comp_prop = instance_desc.m_ComponentProperties[prop_i];
                            if (dmHashString64(comp_prop.m_Id) == component.m_Id)
                            {
                                bool r = CreatePropertySetUserData(&comp_prop.m_PropertyDecls, &params.m_PropertySet.m_UserData);
                                if (!r)
                                {
                                    dmLogError("Could not read properties of game object '%s' in collection %s.", instance_desc.m_Id, filename);
                                    res = dmResource::RESULT_FORMAT_ERROR;
                                    goto bail;
                                }
                                else
                                {
                                    params.m_PropertySet.m_GetPropertyCallback = GetPropertyCallbackDDF;
                                    params.m_PropertySet.m_FreeUserDataCallback = DestroyPropertySetUserData;
                                }
                                break;
                            }
                        }
                        uintptr_t* component_instance_data = &instance->m_ComponentInstanceUserData[component_instance_data_index];
                        params.m_UserData = component_instance_data;
                        type->m_SetPropertiesFunction(params);
                    }
                    if (component.m_Type->m_InstanceHasUserData)
                        ++component_instance_data_index;
                }
            } else {
                dmGameObject::UndoNewInstance(collection, instance);
                res = dmResource::RESULT_FORMAT_ERROR;
            }
        }

        if (collection_desc->m_CollectionInstances.m_Count != 0)
            dmLogError("Sub collections must be merged before loading.");

        resource->m_Resource = (void*) collection;
bail:
        dmDDF::FreeMessage(collection_desc);

        if (res != dmResource::RESULT_OK)
        {
            // Loading of root-collection is responsible for deleting
            DeleteCollection(collection);
        }

        dmMutex::Unlock(regist->m_Mutex);
        return res;
    }

    dmResource::Result ResCollectionDestroy(dmResource::HFactory factory,
                                                 void* context,
                                                 dmResource::SResourceDescriptor* resource)
    {
        HCollection collection = (HCollection) resource->m_Resource;
        DeleteCollection(collection);
        return dmResource::RESULT_OK;
    }
}
