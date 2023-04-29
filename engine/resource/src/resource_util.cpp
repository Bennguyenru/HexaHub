// Copyright 2020-2023 The Defold Foundation
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

#include "resource_util.h"

#include <dlib/crypt.h>
#include <dlib/dstrings.h>
#include <dlib/log.h>

namespace dmResource
{

static Result DecryptWithXtea(void* buffer, uint32_t buffer_len);
const char* KEY = "aQj8CScgNP4VsfXK";

FDecryptResource g_ResourceDecryption = DecryptWithXtea; // Currently global since we didn't use the resource factory as the context!

void RegisterResourceDecryptionFunction(FDecryptResource decrypt_resource)
{
    g_ResourceDecryption = decrypt_resource;
}

static dmResource::Result DecryptWithXtea(void* buffer, uint32_t buffer_len)
{
    dmCrypt::Result cr = dmCrypt::Decrypt(dmCrypt::ALGORITHM_XTEA, (uint8_t*) buffer, buffer_len, (const uint8_t*) KEY, strlen(KEY));
    if (cr != dmCrypt::RESULT_OK)
    {
        return dmResource::RESULT_UNKNOWN_ERROR;
    }
    return dmResource::RESULT_OK;
}

dmResource::Result DecryptBuffer(void* buffer, uint32_t buffer_len)
{
    return g_ResourceDecryption(buffer, buffer_len);
}

uint32_t HashLength(dmLiveUpdateDDF::HashAlgorithm algorithm)
{
    const uint32_t bitlen[5] = { 0U, 128U, 160U, 256U, 512U };
    return bitlen[(int) algorithm] / 8U;
}

void BytesToHexString(const uint8_t* byte_buf, uint32_t byte_buf_len, char* out_buf, uint32_t out_len)
{
    if (out_buf != NULL && out_len > 0)
    {
        uint32_t out_len_cond = (out_len + 1) / 2;
        out_buf[0] = 0x0;
        for (uint32_t i = 0; i < byte_buf_len; ++i)
        {
            char current[3];
            dmSnPrintf(current, 3, "%02x", byte_buf[i]);

            if (i < out_len_cond)
                strncat(out_buf, current, 1);
            if (i+1 < out_len_cond)
                strncat(out_buf, current+1, 1);
            else
                break;
        }
    }
}


Result MemCompare(const uint8_t* digest, uint32_t len, const uint8_t* expected_digest, uint32_t expected_len)
{
    if (expected_len != len)
    {
        dmLogError("Length mismatch in hash comparison. Expected %u, got %u", expected_len, len);
        return RESULT_FORMAT_ERROR;
    }
    if (memcmp(digest, expected_digest, len) != 0)
    {
        return RESULT_FORMAT_ERROR;
    }
    return RESULT_OK;
}


}
