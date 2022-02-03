// Copyright 2021 The Defold Foundation
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

#ifndef DM_HASH_H
#define DM_HASH_H

#include <dmsdk/dlib/hash.h>

/**
 * Max length for reverse hashing entries. Buffer length larger than this will not be stored for reverse hashing.
 */
const uint32_t DMHASH_MAX_REVERSE_LENGTH = 1024U;

extern "C"
{

/**
 * Calculate 64-bit hash value from buffer. Special version of dmHashBuffer64 with reverse always disabled.
 * Used in situations when no memory allocations can occur, eg profiling scenarios.
 * @param buffer Buffer
 * @param buffer_len Length of buffer
 * @return Hash value
 */
DM_DLLEXPORT uint64_t dmHashBufferNoReverse64(const void* buffer, uint32_t buffer_len);

/**
 * Calculate 32-bit hash value from buffer. Special version of dmHashBuffer32 with reverse always disabled.
 * Used in situations when no memory allocations can occur, eg profiling scenarios.
 * @param buffer Buffer
 * @param buffer_len Length of buffer
 * @return Hash value
 */
DM_DLLEXPORT uint32_t dmHashBufferNoReverse32(const void* buffer, uint32_t buffer_len);

/**
 * Enable/disable support for reverse hash lookup
 * @param enable true for enable
 */
DM_DLLEXPORT void dmHashEnableReverseHash(bool enable);

/**
 * Reverse hash lookup. Maps hash to original data. It is guaranteed that the returned
 * buffer is null-terminated. If the buffer contains a valid c-string
 * it can safely be used in printf and friends.
 * @param hash hash to lookup
 * @param length original data length. Optional argument and NULL-pointer is accepted.
 * @return pointer to buffer. 0 if no reverse exists or if reverse lookup is disabled
 */
DM_DLLEXPORT const void* dmHashReverse32(uint32_t hash, uint32_t* length);

/**
 * Reverse hash key entry removal.
 * @param hash hash key to erase
 */
DM_DLLEXPORT void dmHashReverseErase32(uint32_t hash);

/**
 * Reverse hash key entry removal.
 * @param hash hash key to erase
 */
DM_DLLEXPORT void dmHashReverseErase64(uint64_t hash);


}

#endif // DM_HASH_H
