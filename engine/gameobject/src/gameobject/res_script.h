#ifndef DM_GAMEOBJECT_RES_SCRIPT_H
#define DM_GAMEOBJECT_RES_SCRIPT_H

#include <stdint.h>

#include <resource/resource.h>

#include "../proto/lua_ddf.h"
#include "gameobject_script.h"

namespace dmGameObject
{
    dmResource::Result ResScriptPreload(const dmResource::ResourcePreloadParams& params);

    dmResource::Result ResScriptCreate(const dmResource::ResourceCreateParams& params);

    dmResource::Result ResScriptDestroy(const dmResource::ResourceDestroyParams& params);

    dmResource::Result ResScriptRecreate(const dmResource::ResourceRecreateParams& params);
}

#endif // DM_GAMEOBJECT_RES_SCRIPT_H
