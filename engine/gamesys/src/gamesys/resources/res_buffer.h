// Copyright 2020-2022 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#ifndef DM_GAMESYS_RES_BUFFER_H
#define DM_GAMESYS_RES_BUFFER_H

#include <dmsdk/resource/resource.h>
#include <dmsdk/gamesys/resources/res_buffer.h>

namespace dmGameSystem
{
    struct ResBufferReCreateParams
    {
        dmBuffer::HBuffer  m_Buffer;
        uint8_t*           m_DDFData;
        uint32_t           m_DDFDataSize;
    };

    dmResource::Result ResBufferPreload(const dmResource::ResourcePreloadParams& params);

    dmResource::Result ResBufferCreate(const dmResource::ResourceCreateParams& params);

    dmResource::Result ResBufferDestroy(const dmResource::ResourceDestroyParams& params);

    dmResource::Result ResBufferRecreate(const dmResource::ResourceRecreateParams& params);
}

#endif // DM_GAMESYS_RES_BUFFER_H
