#ifndef GAMEOBJECT_PROPS_H
#define GAMEOBJECT_PROPS_H

#include <stdint.h>

#include <dlib/hash.h>
#include <dlib/message.h>
#include <ddf/ddf.h>
#include <script/script.h>

#include "gameobject.h"

#include "../proto/gameobject/gameobject_ddf.h"

extern "C"
{
#include <lua/lua.h>
#include <lua/lauxlib.h>
}

namespace dmGameObject
{

    enum PropertyLayer
    {
        PROPERTY_LAYER_INSTANCE = 0,
        PROPERTY_LAYER_PROTOTYPE = 1,
        PROPERTY_LAYER_DEFAULT = 2,
        MAX_PROPERTY_LAYER_COUNT
    };

    struct Properties
    {
        Properties();

        PropertySet m_Set[MAX_PROPERTY_LAYER_COUNT];
        dmScript::ResolvePathCallback m_ResolvePathCallback;
        uintptr_t m_ResolvePathUserData;
        dmScript::GetURLCallback m_GetURLCallback;
    };

    struct NewPropertiesParams
    {
        NewPropertiesParams();

        dmScript::ResolvePathCallback m_ResolvePathCallback;
        uintptr_t m_ResolvePathUserData;
        dmScript::GetURLCallback m_GetURLCallback;
    };

    HProperties NewProperties(const NewPropertiesParams& params);
    void DeleteProperties(HProperties properties);

    void SetPropertySet(HProperties properties, PropertyLayer layer, const PropertySet& set);

    PropertyResult GetProperty(const HProperties properties, dmhash_t id, PropertyVar& var);
}

#endif // GAMEOBJECT_PROPS_H
