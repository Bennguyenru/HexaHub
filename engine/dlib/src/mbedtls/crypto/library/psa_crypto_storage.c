/*
 *  PSA persistent key storage
 */
/*  Copyright (C) 2018, ARM Limited, All Rights Reserved
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  This file is part of mbed TLS (https://tls.mbed.org)
 */

#if defined(MBEDTLS_CONFIG_FILE)
#include MBEDTLS_CONFIG_FILE
#else
#include "mbedtls/config.h"
#endif

#if defined(MBEDTLS_PSA_CRYPTO_STORAGE_C)

#include <stdlib.h>
#include <string.h>

#include "psa_crypto_service_integration.h"
#include "psa/crypto.h"
#include "psa_crypto_storage.h"
#include "mbedtls/platform_util.h"

#if defined(MBEDTLS_PSA_ITS_FILE_C)
#include "psa_crypto_its.h"
#else /* Native ITS implementation */
#include "psa/error.h"
#include "psa/internal_trusted_storage.h"
#endif

#if defined(MBEDTLS_PLATFORM_C)
#include "mbedtls/platform.h"
#else
#include <stdlib.h>
#define mbedtls_calloc   calloc
#define mbedtls_free     free
#endif



/****************************************************************/
/* Key storage */
/****************************************************************/

/* Determine a file name (ITS file identifier) for the given key file
 * identifier. The file name must be distinct from any file that is used
 * for a purpose other than storing a key. Currently, the only such file
 * is the random seed file whose name is PSA_CRYPTO_ITS_RANDOM_SEED_UID
 * and whose value is 0xFFFFFF52. */
static psa_storage_uid_t psa_its_identifier_of_slot( psa_key_file_id_t file_id )
{
#if defined(MBEDTLS_PSA_CRYPTO_KEY_FILE_ID_ENCODES_OWNER) && \
    defined(PSA_CRYPTO_SECURE)
    /* Encode the owner in the upper 32 bits. This means that if
     * owner values are nonzero (as they are on a PSA platform),
     * no key file will ever have a value less than 0x100000000, so
     * the whole range 0..0xffffffff is available for non-key files. */
    uint32_t unsigned_owner = (uint32_t) file_id.owner;
    return( (uint64_t) unsigned_owner << 32 | file_id.key_id );
#else
    /* Use the key id directly as a file name.
     * psa_is_key_file_id_valid() in psa_crypto_slot_management.c
     * is responsible for ensuring that key identifiers do not have a
     * value that is reserved for non-key files. */
    return( file_id );
#endif
}

/**
 * \brief Load persistent data for the given key slot number.
 *
 * This function reads data from a storage backend and returns the data in a
 * buffer.
 *
 * \param key               Persistent identifier of the key to be loaded. This
 *                          should be an occupied storage location.
 * \param[out] data         Buffer where the data is to be written.
 * \param data_size         Size of the \c data buffer in bytes.
 *
 * \retval PSA_SUCCESS
 * \retval PSA_ERROR_STORAGE_FAILURE
 * \retval PSA_ERROR_DOES_NOT_EXIST
 */
static psa_status_t psa_crypto_storage_load( const psa_key_file_id_t key,
                                             uint8_t *data,
                                             size_t data_size )
{
    psa_status_t status;
    psa_storage_uid_t data_identifier = psa_its_identifier_of_slot( key );
    struct psa_storage_info_t data_identifier_info;
    size_t data_length = 0;

    status = psa_its_get_info( data_identifier, &data_identifier_info );
    if( status  != PSA_SUCCESS )
        return( status );

    status = psa_its_get( data_identifier, 0, (uint32_t) data_size, data, &data_length );
    if( data_size  != data_length )
        return( PSA_ERROR_STORAGE_FAILURE );

    return( status );
}

int psa_is_key_present_in_storage( const psa_key_file_id_t key )
{
    psa_status_t ret;
    psa_storage_uid_t data_identifier = psa_its_identifier_of_slot( key );
    struct psa_storage_info_t data_identifier_info;

    ret = psa_its_get_info( data_identifier, &data_identifier_info );

    if( ret == PSA_ERROR_DOES_NOT_EXIST )
        return( 0 );
    return( 1 );
}

/**
 * \brief Store persistent data for the given key slot number.
 *
 * This function stores the given data buffer to a persistent storage.
 *
 * \param key           Persistent identifier of the key to be stored. This
 *                      should be an unoccupied storage location.
 * \param[in] data      Buffer containing the data to be stored.
 * \param data_length   The number of bytes
 *                      that make up the data.
 *
 * \retval PSA_SUCCESS
 * \retval PSA_ERROR_INSUFFICIENT_STORAGE
 * \retval PSA_ERROR_STORAGE_FAILURE
 * \retval PSA_ERROR_ALREADY_EXISTS
 */
static psa_status_t psa_crypto_storage_store( const psa_key_file_id_t key,
                                              const uint8_t *data,
                                              size_t data_length )
{
    psa_status_t status;
    psa_storage_uid_t data_identifier = psa_its_identifier_of_slot( key );
    struct psa_storage_info_t data_identifier_info;

    if( psa_is_key_present_in_storage( key ) == 1 )
        return( PSA_ERROR_ALREADY_EXISTS );

    status = psa_its_set( data_identifier, (uint32_t) data_length, data, 0 );
    if( status != PSA_SUCCESS )
    {
        return( PSA_ERROR_STORAGE_FAILURE );
    }

    status = psa_its_get_info( data_identifier, &data_identifier_info );
    if( status != PSA_SUCCESS )
    {
        goto exit;
    }

    if( data_identifier_info.size != data_length )
    {
        status = PSA_ERROR_STORAGE_FAILURE;
        goto exit;
    }

exit:
    if( status != PSA_SUCCESS )
        psa_its_remove( data_identifier );
    return( status );
}

psa_status_t psa_destroy_persistent_key( const psa_key_file_id_t key )
{
    psa_status_t ret;
    psa_storage_uid_t data_identifier = psa_its_identifier_of_slot( key );
    struct psa_storage_info_t data_identifier_info;

    ret = psa_its_get_info( data_identifier, &data_identifier_info );
    if( ret == PSA_ERROR_DOES_NOT_EXIST )
        return( PSA_SUCCESS );

    if( psa_its_remove( data_identifier ) != PSA_SUCCESS )
        return( PSA_ERROR_STORAGE_FAILURE );

    ret = psa_its_get_info( data_identifier, &data_identifier_info );
    if( ret != PSA_ERROR_DOES_NOT_EXIST )
        return( PSA_ERROR_STORAGE_FAILURE );

    return( PSA_SUCCESS );
}

/**
 * \brief Get data length for given key slot number.
 *
 * \param key               Persistent identifier whose stored data length
 *                          is to be obtained.
 * \param[out] data_length  The number of bytes that make up the data.
 *
 * \retval PSA_SUCCESS
 * \retval PSA_ERROR_STORAGE_FAILURE
 */
static psa_status_t psa_crypto_storage_get_data_length(
    const psa_key_file_id_t key,
    size_t *data_length )
{
    psa_status_t status;
    psa_storage_uid_t data_identifier = psa_its_identifier_of_slot( key );
    struct psa_storage_info_t data_identifier_info;

    status = psa_its_get_info( data_identifier, &data_identifier_info );
    if( status != PSA_SUCCESS )
        return( status );

    *data_length = (size_t) data_identifier_info.size;

    return( PSA_SUCCESS );
}

/*
 * 32-bit integer manipulation macros (little endian)
 */
#ifndef GET_UINT32_LE
#define GET_UINT32_LE( n, b, i )                        \
{                                                       \
    (n) = ( (uint32_t) (b)[(i)    ]       )             \
        | ( (uint32_t) (b)[(i) + 1] <<  8 )             \
        | ( (uint32_t) (b)[(i) + 2] << 16 )             \
        | ( (uint32_t) (b)[(i) + 3] << 24 );            \
}
#endif

#ifndef PUT_UINT32_LE
#define PUT_UINT32_LE( n, b, i )                                \
{                                                               \
    (b)[(i)    ] = (unsigned char) ( ( (n)       ) & 0xFF );    \
    (b)[(i) + 1] = (unsigned char) ( ( (n) >>  8 ) & 0xFF );    \
    (b)[(i) + 2] = (unsigned char) ( ( (n) >> 16 ) & 0xFF );    \
    (b)[(i) + 3] = (unsigned char) ( ( (n) >> 24 ) & 0xFF );    \
}
#endif

/**
 * Persistent key storage magic header.
 */
#define PSA_KEY_STORAGE_MAGIC_HEADER "PSA\0KEY"
#define PSA_KEY_STORAGE_MAGIC_HEADER_LENGTH ( sizeof( PSA_KEY_STORAGE_MAGIC_HEADER ) )

typedef struct {
    uint8_t magic[PSA_KEY_STORAGE_MAGIC_HEADER_LENGTH];
    uint8_t version[4];
    uint8_t lifetime[sizeof( psa_key_lifetime_t )];
    uint8_t type[sizeof( psa_key_type_t )];
    uint8_t policy[sizeof( psa_key_policy_t )];
    uint8_t data_len[4];
    uint8_t key_data[];
} psa_persistent_key_storage_format;

void psa_format_key_data_for_storage( const uint8_t *data,
                                      const size_t data_length,
                                      const psa_core_key_attributes_t *attr,
                                      uint8_t *storage_data )
{
    psa_persistent_key_storage_format *storage_format =
        (psa_persistent_key_storage_format *) storage_data;

    memcpy( storage_format->magic, PSA_KEY_STORAGE_MAGIC_HEADER, PSA_KEY_STORAGE_MAGIC_HEADER_LENGTH );
    PUT_UINT32_LE( 0, storage_format->version, 0 );
    PUT_UINT32_LE( attr->lifetime, storage_format->lifetime, 0 );
    PUT_UINT32_LE( attr->type, storage_format->type, 0 );
    PUT_UINT32_LE( attr->policy.usage, storage_format->policy, 0 );
    PUT_UINT32_LE( attr->policy.alg, storage_format->policy, sizeof( uint32_t ) );
    PUT_UINT32_LE( attr->policy.alg2, storage_format->policy, 2 * sizeof( uint32_t ) );
    PUT_UINT32_LE( data_length, storage_format->data_len, 0 );
    memcpy( storage_format->key_data, data, data_length );
}

static psa_status_t check_magic_header( const uint8_t *data )
{
    if( memcmp( data, PSA_KEY_STORAGE_MAGIC_HEADER,
                PSA_KEY_STORAGE_MAGIC_HEADER_LENGTH ) != 0 )
        return( PSA_ERROR_STORAGE_FAILURE );
    return( PSA_SUCCESS );
}

psa_status_t psa_parse_key_data_from_storage( const uint8_t *storage_data,
                                              size_t storage_data_length,
                                              uint8_t **key_data,
                                              size_t *key_data_length,
                                              psa_core_key_attributes_t *attr )
{
    psa_status_t status;
    const psa_persistent_key_storage_format *storage_format =
        (const psa_persistent_key_storage_format *)storage_data;
    uint32_t version;

    if( storage_data_length < sizeof(*storage_format) )
        return( PSA_ERROR_STORAGE_FAILURE );

    status = check_magic_header( storage_data );
    if( status != PSA_SUCCESS )
        return( status );

    GET_UINT32_LE( version, storage_format->version, 0 );
    if( version != 0 )
        return( PSA_ERROR_STORAGE_FAILURE );

    GET_UINT32_LE( *key_data_length, storage_format->data_len, 0 );
    if( *key_data_length > ( storage_data_length - sizeof(*storage_format) ) ||
        *key_data_length > PSA_CRYPTO_MAX_STORAGE_SIZE )
        return( PSA_ERROR_STORAGE_FAILURE );

    if( *key_data_length == 0 )
    {
        *key_data = NULL;
    }
    else
    {
        *key_data = mbedtls_calloc( 1, *key_data_length );
        if( *key_data == NULL )
            return( PSA_ERROR_INSUFFICIENT_MEMORY );
        memcpy( *key_data, storage_format->key_data, *key_data_length );
    }

    GET_UINT32_LE( attr->lifetime, storage_format->lifetime, 0 );
    GET_UINT32_LE( attr->type, storage_format->type, 0 );
    GET_UINT32_LE( attr->policy.usage, storage_format->policy, 0 );
    GET_UINT32_LE( attr->policy.alg, storage_format->policy, sizeof( uint32_t ) );
    GET_UINT32_LE( attr->policy.alg2, storage_format->policy, 2 * sizeof( uint32_t ) );

    return( PSA_SUCCESS );
}

psa_status_t psa_save_persistent_key( const psa_core_key_attributes_t *attr,
                                      const uint8_t *data,
                                      const size_t data_length )
{
    size_t storage_data_length;
    uint8_t *storage_data;
    psa_status_t status;

    if( data_length > PSA_CRYPTO_MAX_STORAGE_SIZE )
        return PSA_ERROR_INSUFFICIENT_STORAGE;
    storage_data_length = data_length + sizeof( psa_persistent_key_storage_format );

    storage_data = mbedtls_calloc( 1, storage_data_length );
    if( storage_data == NULL )
        return( PSA_ERROR_INSUFFICIENT_MEMORY );

    psa_format_key_data_for_storage( data, data_length, attr, storage_data );

    status = psa_crypto_storage_store( attr->id,
                                       storage_data, storage_data_length );

    mbedtls_free( storage_data );

    return( status );
}

void psa_free_persistent_key_data( uint8_t *key_data, size_t key_data_length )
{
    if( key_data != NULL )
    {
        mbedtls_platform_zeroize( key_data, key_data_length );
    }
    mbedtls_free( key_data );
}

psa_status_t psa_load_persistent_key( psa_core_key_attributes_t *attr,
                                      uint8_t **data,
                                      size_t *data_length )
{
    psa_status_t status = PSA_SUCCESS;
    uint8_t *loaded_data;
    size_t storage_data_length = 0;
    psa_key_id_t key = attr->id;

    status = psa_crypto_storage_get_data_length( key, &storage_data_length );
    if( status != PSA_SUCCESS )
        return( status );

    loaded_data = mbedtls_calloc( 1, storage_data_length );

    if( loaded_data == NULL )
        return( PSA_ERROR_INSUFFICIENT_MEMORY );

    status = psa_crypto_storage_load( key, loaded_data, storage_data_length );
    if( status != PSA_SUCCESS )
        goto exit;

    status = psa_parse_key_data_from_storage( loaded_data, storage_data_length,
                                              data, data_length, attr );

exit:
    mbedtls_free( loaded_data );
    return( status );
}



/****************************************************************/
/* Transactions */
/****************************************************************/

#if defined(PSA_CRYPTO_STORAGE_HAS_TRANSACTIONS)

psa_crypto_transaction_t psa_crypto_transaction;

psa_status_t psa_crypto_save_transaction( void )
{
    struct psa_storage_info_t p_info;
    psa_status_t status;
    status = psa_its_get_info( PSA_CRYPTO_ITS_RANDOM_SEED_UID, &p_info );
    if( status == PSA_SUCCESS )
    {
        /* This shouldn't happen: we're trying to start a transaction while
         * there is still a transaction that hasn't been replayed. */
        return( PSA_ERROR_CORRUPTION_DETECTED );
    }
    else if( status != PSA_ERROR_DOES_NOT_EXIST )
        return( status );
    return( psa_its_set( PSA_CRYPTO_ITS_TRANSACTION_UID,
                         sizeof( psa_crypto_transaction ),
                         &psa_crypto_transaction,
                         0 ) );
}

psa_status_t psa_crypto_load_transaction( void )
{
    psa_status_t status;
    size_t length;
    status = psa_its_get( PSA_CRYPTO_ITS_TRANSACTION_UID, 0,
                          sizeof( psa_crypto_transaction ),
                          &psa_crypto_transaction, &length );
    if( status != PSA_SUCCESS )
        return( status );
    if( length != sizeof( psa_crypto_transaction ) )
        return( PSA_ERROR_STORAGE_FAILURE );
    return( PSA_SUCCESS );
}

psa_status_t psa_crypto_stop_transaction( void )
{
    psa_status_t status = psa_its_remove( PSA_CRYPTO_ITS_TRANSACTION_UID );
    /* Whether or not updating the storage succeeded, the transaction is
     * finished now. It's too late to go back, so zero out the in-memory
     * data. */
    memset( &psa_crypto_transaction, 0, sizeof( psa_crypto_transaction ) );
    return( status );
}

#endif /* PSA_CRYPTO_STORAGE_HAS_TRANSACTIONS */



/****************************************************************/
/* Random generator state */
/****************************************************************/

#if defined(MBEDTLS_PSA_INJECT_ENTROPY)
psa_status_t mbedtls_psa_storage_inject_entropy( const unsigned char *seed,
                                                 size_t seed_size )
{
    psa_status_t status;
    struct psa_storage_info_t p_info;

    status = psa_its_get_info( PSA_CRYPTO_ITS_RANDOM_SEED_UID, &p_info );

    if( PSA_ERROR_DOES_NOT_EXIST == status ) /* No seed exists */
    {
        status = psa_its_set( PSA_CRYPTO_ITS_RANDOM_SEED_UID, seed_size, seed, 0 );
    }
    else if( PSA_SUCCESS == status )
    {
        /* You should not be here. Seed needs to be injected only once */
        status = PSA_ERROR_NOT_PERMITTED;
    }
    return( status );
}
#endif /* MBEDTLS_PSA_INJECT_ENTROPY */



/****************************************************************/
/* The end */
/****************************************************************/

#endif /* MBEDTLS_PSA_CRYPTO_STORAGE_C */
