#ifndef DISPLAY_PROFILES_PRIVATE_H
#define DISPLAY_PROFILES_PRIVATE_H

#include <stdint.h>

#include <dlib/sys.h>

#include <ddf/ddf.h>


#include "render.h"
#include "render/render_ddf.h"

namespace dmRender
{
    struct DisplayProfiles
    {
        struct Qualifier
        {
            float m_Width;
            float m_Height;
            float m_Dpi;
            uint32_t m_NumDeviceModels;
            char** m_DeviceModels;

            Qualifier()
            {
                memset(this, 0x0, sizeof(*this));
            }
        };

        struct Profile
        {
            dmhash_t m_Id;
            uint32_t m_QualifierCount;
            struct Qualifier* m_Qualifiers;
        };

        dmArray<Profile> m_Profiles;
        dmArray<Qualifier> m_Qualifiers;
        dmhash_t m_NameHash;
    };

    bool DeviceModelMatch(DisplayProfiles::Qualifier *qualifier, dmSys::SystemInfo *sys_info);
}

#endif // DISPLAY_PROFILES_PRIVATE_H

