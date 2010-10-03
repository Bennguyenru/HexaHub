#ifndef RESOURCE_H
#define RESOURCE_H

#include <ddf/ddf.h>

/*
 * TODO:
 * Add function to translate from factory to create result
 */

namespace dmResource
{
    /**
     * Empty flags
     */
    #define RESOURCE_FACTORY_FLAGS_EMPTY            (0)

    /**
     * Enable resource reloading support. Both over files and http.
     */
    #define RESOURCE_FACTORY_FLAGS_RELOAD_SUPPORT (1 << 0)

    /**
     * Enable internal http server support. Added this flag implicitly implies #RESOURCE_FACTORY_FLAGS_RELOAD_SUPPORT
     * URI:s supported
     *   Reload resource:  http://host:8001/reload/PATH
     *   Resources loaded: http://host:8001/
     */
    #define RESOURCE_FACTORY_FLAGS_HTTP_SERVER    (1 << 1)

    /**
     * Factory result
     */
    enum FactoryResult
    {
        FACTORY_RESULT_OK                        = 0,    //!< FACTORY_RESULT_OK
        FACTORY_RESULT_INVALID_DATA              = -1,   //!< FACTORY_RESULT_INVALID_DATA
        FACTORY_RESULT_DDF_ERROR                 = -2,   //!< FACTORY_RESULT_DDF_ERROR
        FACTORY_RESULT_RESOURCE_NOT_FOUND        = -3,   //!< FACTORY_RESULT_RESOURCE_NOT_FOUND
        FACTORY_RESULT_MISSING_FILE_EXTENSION    = -4,   //!< FACTORY_RESULT_MISSING_FILE_EXTENSION
        FACTORY_RESULT_ALREADY_REGISTERED        = -5,   //!< FACTORY_RESULT_ALREADY_REGISTERED
        FACTORY_RESULT_INVAL                     = -6,   //!< FACTORY_RESULT_INVAL
        FACTORY_RESULT_UNKNOWN_RESOURCE_TYPE     = -7,   //!< FACTORY_RESULT_UNKNOWN_RESOURCE_TYPE
        FACTORY_RESULT_OUT_OF_MEMORY             = -8,   //!< FACTORY_RESULT_OUT_OF_MEMORY
        FACTORY_RESULT_IO_ERROR                  = -9,   //!< FACTORY_RESULT_IO_ERROR
        FACTORY_RESULT_NOT_LOADED                = -10,  //!< FACTORY_RESULT_NOT_LOADED
        FACTORY_RESULT_OUT_OF_RESOURCES          = -11,  //!< FACTORY_RESULT_OUT_OF_RESOURCES
        FACTORY_RESULT_STREAMBUFFER_TOO_SMALL    = -12,  //!< FACTORY_RESULT_STREAMBUFFER_TOO_SMALL
        FACTORY_RESULT_UNKNOWN                   = -1000,//!< FACTORY_RESULT_UNKNOWN
    };

    /**
     * Resource kind
     */
    enum Kind
    {
        KIND_DDF_DATA,//!< KIND_DDF_DATA
        KIND_POINTER, //!< KIND_POINTER
    };

    /// Resource descriptor
    struct SResourceDescriptor
    {
        /// Hash of resource name
        uint64_t m_NameHash;

        /// Resource kind
        Kind     m_ResourceKind;

        /// Union of DDF descriptor and resource name
        union
        {
            dmDDF::Descriptor* m_Descriptor;
            const char*        m_ResourceTypeName;
        };

        /// Resource pointer. Must be unique and not NULL.
        void*    m_Resource;

        /// Reference count
        uint32_t m_ReferenceCount;

        /// For internal use only
        void*    m_ResourceType;        // For internal use.
        uint32_t m_ModificationTime;    // File modification time. For internal use.
    };

    /**
     * Factory handle
     */
    typedef struct SResourceFactory* HFactory;

    /**
     * Create result
     */
    enum CreateResult
    {
        CREATE_RESULT_OK                = 0,    //!< CREATE_RESULT_OK
        CREATE_RESULT_OUT_OF_MEMORY     = -1,   //!< CREATE_RESULT_OUT_OF_MEMORY
        CREATE_RESULT_FORMAT_ERROR      = -2,   //!< CREATE_RESULT_FORMAT_ERROR
        CREATE_RESULT_CONSTANT_ERROR    = -3,   //!< CREATE_RESULT_CONSTANT_ERROR
        CREATE_RESULT_UNKNOWN           = -1000,//!< CREATE_RESULT_UNKNOWN
    };

    /**
     * Resource create function
     * @param factory Factory handle
     * @param context User context
     * @param buffer Buffer
     * @param buffer_size Buffer size
     * @param resource Resource descriptor
     * @return CREATE_RESULT_OK on success
     */
    typedef CreateResult (*FResourceCreate)(HFactory factory,
                                           void* context,
                                           const void* buffer, uint32_t buffer_size,
                                           SResourceDescriptor* resource,
                                           const char* filename);

    /**
     * Resource destroy function
     * @param factory Factory handle
     * @param context User context
     * @param resource Resource handle
     * @return CREATE_RESULT_OK on success
     */
    typedef CreateResult (*FResourceDestroy)(HFactory factory,
                                            void* context,
                                            SResourceDescriptor* resource);

    /**
     * Resource recreate function. Recreate resource in-place.
     * @param factory Factory handle
     * @param context User context
     * @param buffer Buffer
     * @param buffer_size Buffer size
     * @param resource Resource descriptor
     * @return CREATE_RESULT_OK on success
     */
    typedef CreateResult (*FResourceRecreate)(HFactory factory,
                                              void* context,
                                              const void* buffer, uint32_t buffer_size,
                                              SResourceDescriptor* resource,
                                              const char* filename);

    /**
     * Set default NewFactoryParams params
     * @param params
     */
    void SetDefaultNewFactoryParams(struct NewFactoryParams* params);

    /**
     * New factory parmetes
     */
    struct NewFactoryParams
    {
        /// Maximum number of resource in factory. Default is 1024
        uint32_t m_MaxResources;

        /// Factory flags. Default is RESOURCE_FACTORY_FLAGS_EMPTY
        uint32_t m_Flags;

        /// Stream buffer size. Must be equal or greater to the largest resource file to load.
        /// Default is 4MB (4 * 1024 * 1024)
        uint32_t m_StreamBufferSize;

        uint32_t m_Reserved[7];

        NewFactoryParams()
        {
            SetDefaultNewFactoryParams(this);
        }
    };

    /**
     * Create a new factory
     * @param params New factory parmeters
     * @param uri Resource root uri, eg http://locahost:5000 or build/default/content
     * @return Factory handle. NULL if out of memory or invalid uri.
     */
    HFactory NewFactory(NewFactoryParams* params, const char* uri);

    /**
     * Delete a factory
     * @param factory Factory handle
     */
    void DeleteFactory(HFactory factory);

    /**
     * Update resource factory. Required to be called periodically when http server support is enabled.
     * @param factory Factory handle
     */
    void UpdateFactory(HFactory factory);

    /**
     * Register a resource type
     * @param factory Factory handle
     * @param extension File extension for resource
     * @param context User context
     * @param create_function Create function pointer
     * @param destroy_function Destroy function pointer
     * @param recreate_function Recreate function pointer. Optional, 0 if recrete is not supported.
     * @return FACTORY_RESULT_OK on success
     */
    FactoryResult RegisterType(HFactory factory,
                               const char* extension,
                               void* context,
                               FResourceCreate create_function,
                               FResourceDestroy destroy_function,
                               FResourceRecreate recreate_function);

    /**
     * Get a resource from factory
     * @param factory Factory handle
     * @param name Resource name
     * @param resource Created resource
     * @return FACTORY_RESULT_OK on success
     */
    FactoryResult Get(HFactory factory, const char* name, void** resource);

    /**
     * Reload all resource of specified type
     * @param factory Resource factory
     * @param type Resource type
     * @return FACTORY_RESULT_OK on success
     */
    FactoryResult ReloadType(HFactory factory, uint32_t type);

    /**
     * Get type for resource
     * @param factory Factory handle
     * @param resource Resource
     * @param type Returned type
     * @return FACTORY_RESULT_OK on success
     */
    FactoryResult GetType(HFactory factory, void* resource, uint32_t* type);

    /**
     * Get type from extension
     * @param factory Factory handle
     * @param extension File extension
     * @param type Returned type
     * @return FACTORY_RESULT_OK on success
     */
    FactoryResult GetTypeFromExtension(HFactory factory, const char* extension, uint32_t* type);

    /**
     * Get extension from type
     * @param factory Factory handle
     * @param type Resource type
     * @param extension Returned extension
     * @return FACTORY_RESULT_OK on success
     */
    FactoryResult GetExtensionFromType(HFactory factory, uint32_t type, const char** extension);

    /**
     * Get resource descriptor from resource (name)
     * @param factory Factory handle
     * @param name Resource name
     * @param descriptor Returned resource descriptor
     * @return FACTORY_RESULT_OK on success
     */
    FactoryResult GetDescriptor(HFactory factory, const char* name, SResourceDescriptor* descriptor);

    /**
     * Acquire resource, which basically increments the reference counting on the resource.
     * @param factory Factory handle
     * @param resource Resource
     */
    void Acquire(HFactory factory, void* resource);

    /**
     * Release resource
     * @param factory Factory handle
     * @param resource Resource
     */
    void Release(HFactory factory, void* resource);
}

#endif // RESOURCE_H
