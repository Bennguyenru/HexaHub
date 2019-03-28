#ifndef DM_GRAPHICS_PRIVATE_H
#define DM_GRAPHICS_PRIVATE_H

#include <stdint.h>
#include "graphics.h"

namespace dmGraphics
{
    uint64_t GetDrawCount();
    void SetForceFragmentReloadFail(bool should_fail);
    void SetForceVertexReloadFail(bool should_fail);
    uint32_t GetTextureFormatBPP(TextureFormat format);
}

#endif // #ifndef DM_GRAPHICS_PRIVATE_H
