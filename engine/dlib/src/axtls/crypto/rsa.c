/*
 * Copyright (c) 2007-2014, Cameron Rich
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
 * Implements the RSA public encryption algorithm. Uses the bigint library to
 * perform its calculations.
 */

#include <stdio.h>
#include <string.h>
#include <time.h>
#include <stdlib.h>
#include <axtls/ssl/os_port.h>
#include <axtls/crypto/crypto.h>

void DM_RSA_priv_key_new(DM_RSA_CTX **ctx,
        const uint8_t *modulus, int mod_len,
        const uint8_t *pub_exp, int pub_len,
        const uint8_t *priv_exp, int priv_len
#if CONFIG_BIGINT_CRT
      , const uint8_t *p, int p_len,
        const uint8_t *q, int q_len,
        const uint8_t *dP, int dP_len,
        const uint8_t *dQ, int dQ_len,
        const uint8_t *qInv, int qInv_len
#endif
    )
{
    DM_RSA_CTX *rsa_ctx;
    DM_BI_CTX *bi_ctx;
    DM_RSA_pub_key_new(ctx, modulus, mod_len, pub_exp, pub_len);
    rsa_ctx = *ctx;
    bi_ctx = rsa_ctx->bi_ctx;
    rsa_ctx->d = DM_bi_import(bi_ctx, priv_exp, priv_len);
    DM_bi_permanent(rsa_ctx->d);

#ifdef CONFIG_BIGINT_CRT
    rsa_ctx->p = DM_bi_import(bi_ctx, p, p_len);
    rsa_ctx->q = DM_bi_import(bi_ctx, q, q_len);
    rsa_ctx->dP = DM_bi_import(bi_ctx, dP, dP_len);
    rsa_ctx->dQ = DM_bi_import(bi_ctx, dQ, dQ_len);
    rsa_ctx->qInv = DM_bi_import(bi_ctx, qInv, qInv_len);
    DM_bi_permanent(rsa_ctx->dP);
    DM_bi_permanent(rsa_ctx->dQ);
    DM_bi_permanent(rsa_ctx->qInv);
    DM_bi_set_mod(bi_ctx, rsa_ctx->p, BIGINT_P_OFFSET);
    DM_bi_set_mod(bi_ctx, rsa_ctx->q, BIGINT_Q_OFFSET);
#endif
}

void DM_RSA_pub_key_new(DM_RSA_CTX **ctx,
        const uint8_t *modulus, int mod_len,
        const uint8_t *pub_exp, int pub_len)
{
    DM_RSA_CTX *rsa_ctx;
    DM_BI_CTX *bi_ctx;

    if (*ctx)   /* if we load multiple certs, dump the old one */
        DM_RSA_free(*ctx);

    bi_ctx = DM_bi_initialize();
    *ctx = (DM_RSA_CTX *)calloc(1, sizeof(DM_RSA_CTX));
    rsa_ctx = *ctx;
    rsa_ctx->bi_ctx = bi_ctx;
    rsa_ctx->num_octets = mod_len;
    rsa_ctx->m = DM_bi_import(bi_ctx, modulus, mod_len);
    DM_bi_set_mod(bi_ctx, rsa_ctx->m, BIGINT_M_OFFSET);
    rsa_ctx->e = DM_bi_import(bi_ctx, pub_exp, pub_len);
    DM_bi_permanent(rsa_ctx->e);
}

/**
 * Free up any RSA context resources.
 */
void DM_RSA_free(DM_RSA_CTX *rsa_ctx)
{
    DM_BI_CTX *bi_ctx;
    if (rsa_ctx == NULL)                /* deal with ptrs that are null */
        return;

    bi_ctx = rsa_ctx->bi_ctx;

    DM_bi_depermanent(rsa_ctx->e);
    DM_bi_free(bi_ctx, rsa_ctx->e);
    DM_bi_free_mod(rsa_ctx->bi_ctx, BIGINT_M_OFFSET);

    if (rsa_ctx->d)
    {
        DM_bi_depermanent(rsa_ctx->d);
        DM_bi_free(bi_ctx, rsa_ctx->d);
#ifdef CONFIG_BIGINT_CRT
        DM_bi_depermanent(rsa_ctx->dP);
        DM_bi_depermanent(rsa_ctx->dQ);
        DM_bi_depermanent(rsa_ctx->qInv);
        DM_bi_free(bi_ctx, rsa_ctx->dP);
        DM_bi_free(bi_ctx, rsa_ctx->dQ);
        DM_bi_free(bi_ctx, rsa_ctx->qInv);
        DM_bi_free_mod(rsa_ctx->bi_ctx, BIGINT_P_OFFSET);
        DM_bi_free_mod(rsa_ctx->bi_ctx, BIGINT_Q_OFFSET);
#endif
    }

    DM_bi_terminate(bi_ctx);
    free(rsa_ctx);
}

/**
 * @brief Use PKCS1.5 for decryption/verification.
 * @param ctx [in] The context
 * @param in_data [in] The data to decrypt (must be < modulus size-11)
 * @param out_data [out] The decrypted data.
 * @param out_len [int] The size of the decrypted buffer in bytes
 * @param is_decryption [in] Decryption or verify operation.
 * @return  The number of bytes that were originally encrypted. -1 on error.
 * @see http://www.rsasecurity.com/rsalabs/node.asp?id=2125
 */
int DM_RSA_decrypt(const DM_RSA_CTX *ctx, const uint8_t *in_data,
                            uint8_t *out_data, int out_len, int is_decryption)
{
    const int byte_size = ctx->num_octets;
    int i = 0, size;
    bigint *decrypted_bi, *dat_bi;
    uint8_t *block = (uint8_t *)alloca(byte_size);
    int pad_count = 0;

    if (out_len < byte_size)        /* check output has enough size */
        return -1;

    memset(out_data, 0, out_len);   /* initialise */

    /* decrypt */
    dat_bi = DM_bi_import(ctx->bi_ctx, in_data, byte_size);
#ifdef CONFIG_SSL_CERT_VERIFICATION
    decrypted_bi = is_decryption ?  /* decrypt or verify? */
            DM_RSA_private(ctx, dat_bi) : DM_RSA_public(ctx, dat_bi);
#else   /* always a decryption */
    decrypted_bi = DM_RSA_private(ctx, dat_bi);
#endif

    /* convert to a normal block */
    DM_bi_export(ctx->bi_ctx, decrypted_bi, block, byte_size);

    if (block[i++] != 0)             /* leading 0? */
        return -1;

#ifdef CONFIG_SSL_CERT_VERIFICATION
    if (is_decryption == 0) /* PKCS1.5 signing pads with "0xff"s */
    {
        if (block[i++] != 0x01)     /* BT correct? */
            return -1;

        while (block[i++] == 0xff && i < byte_size)
            pad_count++;
    }
    else                    /* PKCS1.5 encryption padding is random */
#endif
    {
        if (block[i++] != 0x02)     /* BT correct? */
            return -1;

        while (block[i++] && i < byte_size)
            pad_count++;
    }

    /* check separator byte 0x00 - and padding must be 8 or more bytes */
    if (i == byte_size || pad_count < 8)
        return -1;

    size = byte_size - i;

    /* get only the bit we want */
    memcpy(out_data, &block[i], size);
    return size;
}

/**
 * Performs m = c^d mod n
 */
bigint *DM_RSA_private(const DM_RSA_CTX *c, bigint *bi_msg)
{
#ifdef CONFIG_BIGINT_CRT
    return DM_bi_crt(c->bi_ctx, bi_msg, c->dP, c->dQ, c->p, c->q, c->qInv);
#else
    DM_BI_CTX *ctx = c->bi_ctx;
    ctx->mod_offset = BIGINT_M_OFFSET;
    return DM_bi_mod_power(ctx, bi_msg, c->d);
#endif
}

#ifdef CONFIG_SSL_FULL_MODE
/**
 * Used for diagnostics.
 */
void DM_RSA_print(const DM_RSA_CTX *rsa_ctx)
{
    if (rsa_ctx == NULL)
        return;

    printf("-----------------   RSA DEBUG   ----------------\n");
    printf("Size:\t%d\n", rsa_ctx->num_octets);
    DM_bi_print("Modulus", rsa_ctx->m);
    DM_bi_print("Public Key", rsa_ctx->e);
    DM_bi_print("Private Key", rsa_ctx->d);
}
#endif

#if defined(CONFIG_SSL_CERT_VERIFICATION) || defined(CONFIG_SSL_GENERATE_X509_CERT)
/**
 * Performs c = m^e mod n
 */
bigint *DM_RSA_public(const DM_RSA_CTX * c, bigint *bi_msg)
{
    c->bi_ctx->mod_offset = BIGINT_M_OFFSET;
    return DM_bi_mod_power(c->bi_ctx, bi_msg, c->e);
}

/**
 * Use PKCS1.5 for encryption/signing.
 * see http://www.rsasecurity.com/rsalabs/node.asp?id=2125
 */
int DM_RSA_encrypt(const DM_RSA_CTX *ctx, const uint8_t *in_data, uint16_t in_len,
        uint8_t *out_data, int is_signing)
{
    int byte_size = ctx->num_octets;
    int num_pads_needed = byte_size-in_len-3;
    bigint *dat_bi, *encrypt_bi;

    /* note: in_len+11 must be > byte_size */
    out_data[0] = 0;     /* ensure encryption block is < modulus */

    if (is_signing)
    {
        out_data[1] = 1;        /* PKCS1.5 signing pads with "0xff"'s */
        memset(&out_data[2], 0xff, num_pads_needed);
    }
    else /* randomize the encryption padding with non-zero bytes */
    {
        out_data[1] = 2;
        if (DM_get_random_NZ(num_pads_needed, &out_data[2]) < 0)
            return -1;
    }

    out_data[2+num_pads_needed] = 0;
    memcpy(&out_data[3+num_pads_needed], in_data, in_len);

    /* now encrypt it */
    dat_bi = DM_bi_import(ctx->bi_ctx, out_data, byte_size);
    encrypt_bi = is_signing ? DM_RSA_private(ctx, dat_bi) :
                              DM_RSA_public(ctx, dat_bi);
    DM_bi_export(ctx->bi_ctx, encrypt_bi, out_data, byte_size);

    /* save a few bytes of memory */
    DM_bi_clear_cache(ctx->bi_ctx);
    return byte_size;
}

#endif  /* CONFIG_SSL_CERT_VERIFICATION */
