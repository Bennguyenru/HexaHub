// Copyright 2020 The Defold Foundation
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


#include <dlib/array.h>
#include <dlib/math.h>
#include <dlib/zlib.h>

#include "texc.h"
#include "texc_private.h"

namespace dmTexc
{
    static bool DeflateWriter(void* context, const void* buffer, uint32_t buffer_size)
    {
        dmArray<uint8_t>* out = (dmArray<uint8_t>*) context;
        if (out->Remaining() < buffer_size) {
            int r = out->Remaining();
            int offset = dmMath::Max((int) buffer_size - r, 32 * 1024);
            out->OffsetCapacity(offset);
        }
        out->PushArray((const uint8_t*) buffer, buffer_size);
        return true;
    }

    static HBuffer CompressBuffer_Deflate(void* data, uint32_t size)
    {
        dmArray<uint8_t> out_data_array;
        out_data_array.SetCapacity(32 * 1024);
        dmZlib::DeflateBuffer(data, size, 9, &out_data_array, DeflateWriter);
        uint8_t* out_data = (uint8_t*)malloc(out_data_array.Size());
        memcpy(out_data, &out_data_array[0], out_data_array.Size());

        TextureData* out = new TextureData;
        out->m_Data = out_data;
        out->m_IsCompressed = 3;
        out->m_ByteSize = out_data_array.Size();
        return out;
    }

    HBuffer CompressBuffer(void* data, uint32_t size)
    {
        TextureData* out = (TextureData*)CompressBuffer_Deflate(data, size);

        if (out->m_ByteSize > size)
        {
            free(out->m_Data);
            out->m_IsCompressed = 0;
            out->m_ByteSize = size;
            out->m_Data = (uint8_t*)malloc(size);
            memcpy(out->m_Data, data, size);
        }
        return out;
    }

    uint32_t GetTotalBufferDataSize(HBuffer _buffer)
    {
        TextureData* buffer = (TextureData*) _buffer;
        return (uint32_t)buffer->m_ByteSize + 1;
    }

    uint32_t GetBufferData(HBuffer _buffer, void* _out_data, uint32_t out_data_size)
    {
        TextureData* buffer = (TextureData*) _buffer;
        uint8_t* out_data = (uint8_t*)_out_data;
        out_data[0] = buffer->m_IsCompressed;
        memcpy(out_data+1, buffer->m_Data, dmMath::Min(out_data_size, (uint32_t)buffer->m_ByteSize));
        return dmMath::Min(out_data_size, (uint32_t)buffer->m_ByteSize + 1);
    }

    void DestroyBuffer(HBuffer buffer)
    {
        TextureData* t = (TextureData*)buffer;
        if (t->m_Data) {
            free(t->m_Data);
        }

        delete t;
    }
}
