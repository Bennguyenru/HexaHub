#include "buffer.h"

#include <dlib/log.h>
#include <dlib/memory.h>

#include <string.h>
#include <assert.h>

namespace dmBuffer
{
    static const uint8_t ADDR_ALIGNMENT = 16;
    static const uint8_t GUARD_VALUES[] = {
        0xD3, 0xF0, 0x1D, 0xFF,
        0xD3, 0xF0, 0x1D, 0xFF,
        0xD3, 0xF0, 0x1D, 0xFF,
        0xD3, 0xF0, 0x1D, 0xFF,
    };
    static const uint8_t GUARD_SIZE = sizeof(GUARD_VALUES);

    static uint32_t GetSizeForValueType(ValueType value_type)
    {
        switch (value_type)
        {
            case BUFFER_TYPE_UINT8:
                return sizeof(uint8_t);
            case BUFFER_TYPE_UINT16:
                return sizeof(uint16_t);
            case BUFFER_TYPE_UINT32:
                return sizeof(uint32_t);
            case BUFFER_TYPE_UINT64:
                return sizeof(uint64_t);

            case BUFFER_TYPE_INT8:
                return sizeof(int8_t);
            case BUFFER_TYPE_INT16:
                return sizeof(int16_t);
            case BUFFER_TYPE_INT32:
                return sizeof(int32_t);
            case BUFFER_TYPE_INT64:
                return sizeof(int64_t);

            case BUFFER_TYPE_FLOAT32:
                return sizeof(float);
            case BUFFER_TYPE_FLOAT64:
                return sizeof(double);
        }

        // Should never happen, need to implement all value types above.
        assert(0);
        return 0;
    }

    static void WriteGuard(void* ptr)
    {
        memcpy(ptr, GUARD_VALUES, GUARD_SIZE);
    }

    static bool ValidateGuards(HBuffer buffer, const Buffer::Stream& stream)
    {
        const uintptr_t stream_buffer = (uintptr_t)buffer->m_Data + stream.m_Offset;
        uint32_t stream_size = stream.m_ValueCount * buffer->m_NumElements * GetSizeForValueType(stream.m_ValueType);
        return (memcmp((void*)(stream_buffer + stream_size), GUARD_VALUES, GUARD_SIZE) == 0);
    }

    static bool ValidateBuffer(HBuffer buffer)
    {
        for (int i = 0; i < buffer->m_NumStreams; ++i)
        {
            if (!ValidateGuards(buffer, buffer->m_Streams[i]))
            {
                return false;
            }
        }
        return true;
    }

    static void CreateStreams(HBuffer buffer, BufferDeclaration buffer_decl)
    {
        uint32_t num_elements = buffer->m_NumElements;
        uintptr_t data_start = (uintptr_t)buffer->m_Data;
        uintptr_t ptr = data_start;
        for (int i = 0; i < buffer->m_NumStreams; ++i)
        {
            const StreamDeclaration& decl = buffer_decl[i];
            ptr = DM_ALIGN(ptr, ADDR_ALIGNMENT);

            dmBuffer::Buffer::Stream& stream = buffer->m_Streams[i];
            stream.m_Name       = decl.m_Name;
            stream.m_ValueType  = decl.m_ValueType;
            stream.m_ValueCount = decl.m_ValueCount;
            stream.m_Offset     = ptr - data_start;

            // Write guard bytes after stream data
            uint32_t stream_size = num_elements * decl.m_ValueCount * GetSizeForValueType(decl.m_ValueType);
            ptr += stream_size;
            WriteGuard((void*)ptr);
            ptr += GUARD_SIZE;
        }
    }

    Result Allocate(uint32_t num_elements, const BufferDeclaration buffer_decl, uint8_t buffer_decl_count, HBuffer* out_buffer)
    {
        // Calculate total data allocation size needed
        uint32_t header_size = sizeof(Buffer) + sizeof(Buffer::Stream)*buffer_decl_count;
        uint32_t buffer_size = header_size;
        for (int i = 0; i < buffer_decl_count; ++i)
        {
            const StreamDeclaration& decl = buffer_decl[i];

            // Make sure each stream is aligned
            buffer_size = DM_ALIGN(buffer_size, ADDR_ALIGNMENT);
            assert(buffer_size % ADDR_ALIGNMENT == 0);

            // Calculate size of stream buffer
            uint32_t stream_size = num_elements * decl.m_ValueCount * GetSizeForValueType(decl.m_ValueType);
            if (!stream_size) {
                return RESULT_STREAM_SIZE_ERROR;
            }

            // Add current total stream size to total buffer size
            buffer_size += stream_size + GUARD_SIZE;
        }

        if (buffer_size == header_size) {
            return RESULT_BUFFER_SIZE_ERROR;
        }

        // Allocate buffer to fit Buffer-struct, Stream-array and buffer data
        void* data_block = 0x0;
        dmMemory::Result r = dmMemory::AlignedMalloc((void**)&data_block, ADDR_ALIGNMENT, buffer_size);
        if (r != dmMemory::RESULT_OK) {
            return RESULT_ALLOCATION_ERROR;
        }
        memset((void*)data_block, 0x0, buffer_size);

        // Get buffer from data block start
        HBuffer buffer = (Buffer*)data_block;
        buffer->m_NumElements = num_elements;
        buffer->m_NumStreams = buffer_decl_count;
        buffer->m_Streams = (Buffer::Stream*)((uintptr_t)data_block+sizeof(Buffer));
        buffer->m_Data = (void*)((uintptr_t)buffer->m_Streams+sizeof(Buffer::Stream)*buffer_decl_count);

        CreateStreams(buffer, buffer_decl);

        *out_buffer = buffer;
        return RESULT_OK;
    }

    void Free(HBuffer buffer)
    {
        if (buffer)
        {
            dmMemory::AlignedFree(buffer);
        }
    }

    static const Buffer::Stream* GetStream(HBuffer buffer, dmhash_t stream_name)
    {
        if (!buffer) {
            return 0x0;
        }

        for (int i = 0; i < buffer->m_NumStreams; ++i)
        {
            const Buffer::Stream* stream = &buffer->m_Streams[i];
            if (stream_name == stream->m_Name) {
                return stream;
            }
        }

        return 0x0;
    }

    Result GetStream(HBuffer buffer, dmhash_t stream_name, dmBuffer::ValueType type, uint32_t type_count, void **out_stream, uint32_t *out_stride, uint32_t *out_element_count)
    {
        // Get stream
        const Buffer::Stream* stream = GetStream(buffer, stream_name);
        if (stream == 0x0) {
            return RESULT_STREAM_DOESNT_EXIST;
        }

        // Validate guards
        if (!dmBuffer::ValidateGuards(buffer, *stream))
        {
            return RESULT_GUARD_INVALID;
        }

        // Validate expected type and value count
        if (stream->m_ValueType != type) {
            return dmBuffer::RESULT_STREAM_WRONG_TYPE;
        } else if (stream->m_ValueCount != type_count) {
            return dmBuffer::RESULT_STREAM_WRONG_COUNT;
        }

        // Calculate stride
        uint32_t type_size = GetSizeForValueType(type);
        *out_stride = type_size * type_count;

        *out_element_count = buffer->m_NumElements;
        *out_stream = (void*)((uintptr_t)buffer->m_Data + stream->m_Offset);

        return RESULT_OK;
    }
}
