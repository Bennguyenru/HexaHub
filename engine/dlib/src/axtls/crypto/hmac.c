/*
 * Copyright (c) 2007-2016, Cameron Rich
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * Neither the name of the axTLS project nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * HMAC implementation - This code was originally taken from RFC2104
 * See http://www.ietf.org/rfc/rfc2104.txt and
 * http://www.faqs.org/rfcs/rfc2202.html
 */

#include <string.h>
#include <axtls/ssl/os_port.h>
#include <axtls/crypto/crypto.h>

/**
 * Perform HMAC-MD5
 * NOTE: does not handle keys larger than the block size.
 */
void DM_hmac_md5(const uint8_t *msg, int length, const uint8_t *key,
        int key_len, uint8_t *digest)
{
    DM_MD5_CTX context;
    uint8_t k_ipad[64];
    uint8_t k_opad[64];
    int i;

    memset(k_ipad, 0, sizeof k_ipad);
    memset(k_opad, 0, sizeof k_opad);
    memcpy(k_ipad, key, key_len);
    memcpy(k_opad, key, key_len);

    for (i = 0; i < 64; i++)
    {
        k_ipad[i] ^= 0x36;
        k_opad[i] ^= 0x5c;
    }

    DM_MD5_Init(&context);
    DM_MD5_Update(&context, k_ipad, 64);
    DM_MD5_Update(&context, msg, length);
    DM_MD5_Final(digest, &context);
    DM_MD5_Init(&context);
    DM_MD5_Update(&context, k_opad, 64);
    DM_MD5_Update(&context, digest, MD5_SIZE);
    DM_MD5_Final(digest, &context);
}

/**
 * Perform HMAC-SHA1
 * NOTE: does not handle keys larger than the block size.
 */
void DM_hmac_sha1(const uint8_t *msg, int length, const uint8_t *key,
        int key_len, uint8_t *digest)
{
    DM_SHA1_CTX context;
    uint8_t k_ipad[64];
    uint8_t k_opad[64];
    int i;

    memset(k_ipad, 0, sizeof k_ipad);
    memset(k_opad, 0, sizeof k_opad);
    memcpy(k_ipad, key, key_len);
    memcpy(k_opad, key, key_len);

    for (i = 0; i < 64; i++)
    {
        k_ipad[i] ^= 0x36;
        k_opad[i] ^= 0x5c;
    }

    DM_SHA1_Init(&context);
    DM_SHA1_Update(&context, k_ipad, 64);
    DM_SHA1_Update(&context, msg, length);
    DM_SHA1_Final(digest, &context);
    DM_SHA1_Init(&context);
    DM_SHA1_Update(&context, k_opad, 64);
    DM_SHA1_Update(&context, digest, SHA1_SIZE);
    DM_SHA1_Final(digest, &context);
}

/**
 * Perform HMAC-SHA256
 * NOTE: does not handle keys larger than the block size.
 */
void DM_hmac_sha256(const uint8_t *msg, int length, const uint8_t *key,
        int key_len, uint8_t *digest)
{
    DM_SHA256_CTX context;
    uint8_t k_ipad[64];
    uint8_t k_opad[64];
    int i;

    memset(k_ipad, 0, sizeof k_ipad);
    memset(k_opad, 0, sizeof k_opad);
    memcpy(k_ipad, key, key_len);
    memcpy(k_opad, key, key_len);

    for (i = 0; i < 64; i++)
    {
        k_ipad[i] ^= 0x36;
        k_opad[i] ^= 0x5c;
    }

    DM_SHA256_Init(&context);
    DM_SHA256_Update(&context, k_ipad, 64);
    DM_SHA256_Update(&context, msg, length);
    DM_SHA256_Final(digest, &context);
    DM_SHA256_Init(&context);
    DM_SHA256_Update(&context, k_opad, 64);
    DM_SHA256_Update(&context, digest, SHA256_SIZE);
    DM_SHA256_Final(digest, &context);
}
