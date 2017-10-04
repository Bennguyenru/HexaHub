#ifndef DM_GAMESYS_FACTORY_H
#define DM_GAMESYS_FACTORY_H

#include <gameobject/gameobject.h>

#include "../resources/res_factory.h"

namespace dmGameSystem
{
    struct FactoryComponent
    {
        void Init();

        FactoryResource*    m_Resource;

        dmResource::HPreloader      m_Preloader;
        int m_PreloaderCallbackRef;
        int m_PreloaderSelfRef;
        int m_PreloaderURLRef;
        uint32_t m_Loading : 1;

        uint32_t m_AddedToUpdate : 1;
    };

    dmGameObject::CreateResult CompFactoryNewWorld(const dmGameObject::ComponentNewWorldParams& params);

    dmGameObject::CreateResult CompFactoryDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params);

    dmGameObject::CreateResult CompFactoryCreate(const dmGameObject::ComponentCreateParams& params);

    dmGameObject::CreateResult CompFactoryDestroy(const dmGameObject::ComponentDestroyParams& params);

    dmGameObject::CreateResult CompFactoryAddToUpdate(const dmGameObject::ComponentAddToUpdateParams& params);

    dmGameObject::UpdateResult CompFactoryUpdate(const dmGameObject::ComponentsUpdateParams& params);

    dmGameObject::UpdateResult CompFactoryOnMessage(const dmGameObject::ComponentOnMessageParams& params);

    dmGameObject::HPrototype CompFactoryGetPrototype(dmGameObject::HCollection collection, FactoryComponent* component);

    bool CompFactoryLoad(dmGameObject::HCollection collection, FactoryComponent* component);

    bool CompFactoryUnload(dmGameObject::HCollection collection, FactoryComponent* component);
}

#endif
