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

#include <dlib/buffer.h>
#include <dlib/dstrings.h>
#include <dlib/hash.h>
#include <dlib/log.h>
#include <gamesys/mesh_ddf.h>
#include <gamesys/texture_set_ddf.h>
#include <graphics/graphics_ddf.h>
#include <liveupdate/liveupdate.h>
#include <render/font_renderer.h>
#include <resource/resource.h>
#include <gameobject/gameobject.h>

#include "script_resource.h"
#include "../gamesys.h"
#include "../resources/res_buffer.h"
#include "../resources/res_texture.h"
#include "../resources/res_textureset.h"
#include "script_resource_liveupdate.h"

#include <dmsdk/script/script.h>
#include <dmsdk/gamesys/script.h>

namespace dmGameSystem
{

/*# Resource API documentation
 *
 * Functions and constants to access resources.
 *
 * @document
 * @name Resource
 * @namespace resource
 */

/*# reference to material resource
 *
 * Constructor-like function with two purposes:
 *
 * - Load the specified resource as part of loading the script
 * - Return a hash to the run-time version of the resource
 *
 * [icon:attention] This function can only be called within [ref:go.property] function calls.
 *
 * @name resource.material
 * @param [path] [type:string] optional resource path string to the resource
 * @return path [type:hash] a path hash to the binary version of the resource
 * @examples
 *
 * Load a material and set it to a sprite:
 *
 * ```lua
 * go.property("my_material", resource.material("/material.material"))
 * function init(self)
 *   go.set("#sprite", "material", self.my_material)
 * end
 * ```
 */

/*# reference to font resource
 *
 * Constructor-like function with two purposes:
 *
 * - Load the specified resource as part of loading the script
 * - Return a hash to the run-time version of the resource
 *
 * [icon:attention] This function can only be called within [ref:go.property] function calls.
 *
 * @name resource.font
 * @param [path] [type:string] optional resource path string to the resource
 * @return path [type:hash] a path hash to the binary version of the resource
 * @examples
 *
 * Load a font and set it to a label:
 *
 * ```lua
 * go.property("my_font", resource.font("/font.font"))
 * function init(self)
 *   go.set("#label", "font", self.my_font)
 * end
 * ```
 *
 * Load a font and set it to a gui:
 *
 * ```lua
 * go.property("my_font", resource.font("/font.font"))
 * function init(self)
 *   go.set("#gui", "fonts", self.my_font, {key = "my_font"})
 * end
 * ```
 */

/*# reference to texture resource
 *
 * Constructor-like function with two purposes:
 *
 * - Load the specified resource as part of loading the script
 * - Return a hash to the run-time version of the resource
 *
 * [icon:attention] This function can only be called within [ref:go.property] function calls.
 *
 * @name resource.texture
 * @param [path] [type:string] optional resource path string to the resource
 * @return path [type:hash] a path hash to the binary version of the resource
 * @examples
 *
 * Load a texture and set it to a model:
 *
 * ```lua
 * go.property("my_texture", resource.texture("/texture.png"))
 * function init(self)
 *   go.set("#model", "texture0", self.my_texture)
 * end
 * ```
 */

/*# reference to atlas resource
 *
 * Constructor-like function with two purposes:
 *
 * - Load the specified resource as part of loading the script
 * - Return a hash to the run-time version of the resource
 *
 * [icon:attention] This function can only be called within [ref:go.property] function calls.
 *
 * @name resource.atlas
 * @param [path] [type:string] optional resource path string to the resource
 * @return path [type:hash] a path hash to the binary version of the resource
 * @examples
 *
 * Load an atlas and set it to a sprite:
 *
 * ```lua
 * go.property("my_atlas", resource.atlas("/atlas.atlas"))
 * function init(self)
 *   go.set("#sprite", "image", self.my_atlas)
 * end
 * ```
 *
 * Load an atlas and set it to a gui:
 *
 * ```lua
 * go.property("my_atlas", resource.atlas("/atlas.atlas"))
 * function init(self)
 *   go.set("#gui", "textures", self.my_atlas, {key = "my_atlas"})
 * end
 * ```
 */

/*# reference to buffer resource
 *
 * Constructor-like function with two purposes:
 *
 * - Load the specified resource as part of loading the script
 * - Return a hash to the run-time version of the resource
 *
 * [icon:attention] This function can only be called within [ref:go.property] function calls.
 *
 * @name resource.buffer
 * @param [path] [type:string] optional resource path string to the resource
 * @return path [type:hash] a path hash to the binary version of the resource
 * @examples
 *
 * Set a unique buffer it to a sprite:
 *
 * ```lua
 * go.property("my_buffer", resource.buffer("/cube.buffer"))
 * function init(self)
 *   go.set("#mesh", "vertices", self.my_buffer)
 * end
 * ```
 */

/*# reference to tile source resource
 *
 * Constructor-like function with two purposes:
 *
 * - Load the specified resource as part of loading the script
 * - Return a hash to the run-time version of the resource
 *
 * [icon:attention] This function can only be called within [ref:go.property] function calls.
 *
 * @name resource.tile_source
 * @param [path] [type:string] optional resource path string to the resource
 * @return path [type:hash] a path hash to the binary version of the resource
 * @examples
 *
 * Load tile source and set it to a tile map:
 *
 * ```lua
 * go.property("my_tile_source", resource.tile_source("/tilesource.tilesource"))
 * function init(self)
 *   go.set("#tilemap", "tile_source", self.my_tile_source)
 * end
 * ```
 */

struct ResourceModule
{
    dmResource::HFactory m_Factory;
} g_ResourceModule;

static int ReportPathError(lua_State* L, dmResource::Result result, dmhash_t path_hash)
{
    char msg[256];
    const char* format = 0;
    switch(result)
    {
    case dmResource::RESULT_RESOURCE_NOT_FOUND: format = "The resource was not found (%d): %llu, %s"; break;
    case dmResource::RESULT_NOT_SUPPORTED:      format = "The resource type does not support this operation (%d): %llu, %s"; break;
    default:                                    format = "The resource was not updated (%d): %llu, %s"; break;
    }
    dmSnPrintf(msg, sizeof(msg), format, result, (unsigned long long)path_hash, dmHashReverseSafe64(path_hash));
    return luaL_error(L, "%s", msg);
}

static void* CheckResource(lua_State* L, dmResource::HFactory factory, dmhash_t path_hash, const char* resource_ext)
{
    dmResource::SResourceDescriptor* rd = dmResource::FindByHash(factory, path_hash);
    if (!rd) {
        luaL_error(L, "Could not get %s type resource: %s", resource_ext, dmHashReverseSafe64(path_hash));
        return 0;
    }

    dmResource::ResourceType resource_type;
    dmResource::Result r = dmResource::GetType(factory, rd->m_Resource, &resource_type);
    if( r != dmResource::RESULT_OK )
    {
        ReportPathError(L, r, path_hash);
    }

    dmResource::ResourceType expected_resource_type;
    r = dmResource::GetTypeFromExtension(factory, resource_ext, &expected_resource_type);
    if( r != dmResource::RESULT_OK )
    {
        ReportPathError(L, r, path_hash);
    }

    if (resource_type != expected_resource_type) {
        luaL_error(L, "Resource %s is not of type %s.", dmHashReverseSafe64(path_hash), resource_ext);
        return 0;
    }

    return rd->m_Resource;
}

static dmhash_t GetCanonicalPathHash(const char* path)
{
    char canonical_path[dmResource::RESOURCE_PATH_MAX];
    uint32_t path_len  = dmResource::GetCanonicalPath(path, canonical_path);
    return dmHashBuffer64(canonical_path, path_len);
}

/*# Set a resource
 * Sets the resource data for a specific resource
 *
 * @name resource.set
 *
 * @param path [type:string|hash] The path to the resource
 * @param buffer [type:buffer] The buffer of precreated data, suitable for the intended resource type
 *
 * @examples
 *
 * Assuming the folder "/res" is added to the project custom resources:
 *
 * ```lua
 * -- load a texture resource and set it on a sprite
 * local buffer = resource.load("/res/new.texturec")
 * resource.set(go.get("#sprite", "texture0"), buffer)
 * ```
 */
static int Set(lua_State* L)
{
    int top = lua_gettop(L);

    dmhash_t path_hash = dmScript::CheckHashOrString(L, 1);
    dmScript::LuaHBuffer* buffer = dmScript::CheckBuffer(L, 2);

    uint32_t datasize = 0;
    void* data = 0;
    dmBuffer::GetBytes(buffer->m_Buffer, &data, &datasize);

    dmResource::Result r = dmResource::SetResource(g_ResourceModule.m_Factory, path_hash, data, datasize);
    if( r != dmResource::RESULT_OK )
    {
        assert(top == lua_gettop(L));
        return ReportPathError(L, r, path_hash);
    }
    assert(top == lua_gettop(L));
    return 0;
}

/*# load a resource
 * Loads the resource data for a specific resource.
 *
 * @name resource.load
 *
 * @param path [type:string] The path to the resource
 * @return buffer [type:buffer] Returns the buffer stored on disc
 *
 * @examples
 *
 * ```lua
 * -- read custom resource data into buffer
 * local buffer = resource.load("/resources/datafile")
 * ```
 *
 * In order for the engine to include custom resources in the build process, you need
 * to specify them in the "game.project" settings file:
 *
 * ```ini
 * [project]
 * title = My project
 * version = 0.1
 * custom_resources = resources/,assets/level_data.json
 * ```
 */
static int Load(lua_State* L)
{
    int top = lua_gettop(L);
    const char* name = luaL_checkstring(L, 1);

    void* resource = 0;
    uint32_t resourcesize = 0;
    dmResource::Result r = dmResource::GetRaw(g_ResourceModule.m_Factory, name, &resource, &resourcesize);

    if( r != dmResource::RESULT_OK )
    {
        assert(top == lua_gettop(L));
        return ReportPathError(L, r, dmHashString64(name));
    }

    dmBuffer::StreamDeclaration streams_decl[] = {
        {dmHashString64("data"), dmBuffer::VALUE_TYPE_UINT8, 1}
    };

    dmBuffer::HBuffer buffer = 0;
    dmBuffer::Create(resourcesize, streams_decl, 1, &buffer);

    uint8_t* data = 0;
    uint32_t datasize = 0;
    dmBuffer::GetBytes(buffer, (void**)&data, &datasize);

    memcpy(data, resource, resourcesize);

    dmScript::LuaHBuffer luabuf(buffer, dmScript::OWNER_LUA);
    dmScript::PushBuffer(L, luabuf);
    assert(top + 1 == lua_gettop(L));
    return 1;
}


static int DoCheckError(lua_State* L, const char* attr_name, const char* expected_type)
{
    char msg[256];
    dmSnPrintf(msg, sizeof(msg), "Wrong type for table attribute '%s'. Expected %s, got %s", attr_name, expected_type, luaL_typename(L, -1) );
    return luaL_error(L, "%s", msg);
}

////////////////////////////////////

template<typename T>
T CheckValue(lua_State* L, int index, const char* attr_name)
{
    (void)L;
    (void)index;
    (void)attr_name;
    T t = 0;
    return t; // SHouldn't be used
}

template<>
bool CheckValue<bool>(lua_State* L, int index, const char* attr_name)
{
    if (!lua_isboolean(L, index)) {
        return DoCheckError(L, attr_name, "boolean");
    }
    return lua_toboolean(L, index);
}

template<>
float CheckValue<float>(lua_State* L, int index, const char* attr_name)
{
    if (!lua_isnumber(L, index)) {
        return DoCheckError(L, attr_name, "number");
    }
    return lua_tonumber(L, index);
}

template<>
int CheckValue<int>(lua_State* L, int index, const char* attr_name)
{
    if (!lua_isnumber(L, index)) {
        return DoCheckError(L, attr_name, "integer");
    }
    return lua_tointeger(L, index);
}

template<typename T>
T CheckValueDefault(lua_State* L, int index, const char* attr_name, T default_value)
{
    if (lua_isnil(L, -1)) {
        return default_value;
    }
    return CheckValue<T>(L, -1, attr_name);
}

////////////////////////////////////

template<typename T>
T CheckTableValue(lua_State* L, int index, const char* name)
{
    lua_pushstring(L, name);
    lua_gettable(L, index);
    T result = CheckValue<T>(L, -1, name);
    lua_pop(L, 1);
    return result;
}

template<typename T>
T CheckTableValue(lua_State* L, int index, const char* name, T default_value)
{
    lua_pushstring(L, name);
    lua_gettable(L, index);
    T result = CheckValueDefault<T>(L, -1, name, default_value);
    lua_pop(L, 1);
    return result;
}

////////////////////////////////////

// static bool CheckTableBoolean(lua_State* L, int index, const char* name)
// {
//     return CheckTableValue<bool>(L, index, name);
// }
static bool CheckTableBoolean(lua_State* L, int index, const char* name, bool default_value)
{
    return CheckTableValue<bool>(L, index, name, default_value);
}
static int CheckTableInteger(lua_State* L, int index, const char* name)
{
    return CheckTableValue<int>(L, index, name);
}
static int CheckTableInteger(lua_State* L, int index, const char* name, int default_value)
{
    return CheckTableValue<int>(L, index, name, default_value);
}
// static float CheckTableNumber(lua_State* L, int index, const char* name)
// {
//     return CheckTableValue<float>(L, index, name);
// }
static float CheckTableNumber(lua_State* L, int index, const char* name, float default_value)
{
    return CheckTableValue<float>(L, index, name, default_value);
}


static int GraphicsTextureFormatToImageFormat(int textureformat)
{
    switch(textureformat)
    {
        case dmGraphics::TEXTURE_FORMAT_LUMINANCE:          return dmGraphics::TextureImage::TEXTURE_FORMAT_LUMINANCE;
        case dmGraphics::TEXTURE_FORMAT_RGB:                return dmGraphics::TextureImage::TEXTURE_FORMAT_RGB;
        case dmGraphics::TEXTURE_FORMAT_RGBA:               return dmGraphics::TextureImage::TEXTURE_FORMAT_RGBA;
        case dmGraphics::TEXTURE_FORMAT_RGB_PVRTC_2BPPV1:   return dmGraphics::TextureImage::TEXTURE_FORMAT_RGB_PVRTC_2BPPV1;
        case dmGraphics::TEXTURE_FORMAT_RGB_PVRTC_4BPPV1:   return dmGraphics::TextureImage::TEXTURE_FORMAT_RGB_PVRTC_4BPPV1;
        case dmGraphics::TEXTURE_FORMAT_RGBA_PVRTC_2BPPV1:  return dmGraphics::TextureImage::TEXTURE_FORMAT_RGBA_PVRTC_2BPPV1;
        case dmGraphics::TEXTURE_FORMAT_RGBA_PVRTC_4BPPV1:  return dmGraphics::TextureImage::TEXTURE_FORMAT_RGBA_PVRTC_4BPPV1;
        case dmGraphics::TEXTURE_FORMAT_RGB_ETC1:           return dmGraphics::TextureImage::TEXTURE_FORMAT_RGB_ETC1;
    };
    assert(false);
    return -1;
}

static int GraphicsTextureTypeToImageType(int texturetype)
{
    if (texturetype == dmGraphics::TEXTURE_TYPE_2D)
    {
        return dmGraphics::TextureImage::TYPE_2D;
    }
    else if (texturetype == dmGraphics::TEXTURE_TYPE_CUBE_MAP)
    {
        return dmGraphics::TextureImage::TYPE_CUBEMAP;
    }
    assert(false);
    return -1;
}

static void MakeTextureImage(uint16_t width, uint16_t height, uint8_t max_mipmaps, uint8_t bitspp,
    dmGraphics::TextureImage::Type type, dmGraphics::TextureImage::TextureFormat format, dmGraphics::TextureImage* texture_image)
{
    uint32_t* mip_map_sizes   = new uint32_t[max_mipmaps];
    uint32_t* mip_map_offsets = new uint32_t[max_mipmaps];
    uint8_t layer_count       = type == dmGraphics::TextureImage::TYPE_CUBEMAP ? 6 : 1;

    uint32_t data_size = 0;
    uint16_t mm_width  = width;
    uint16_t mm_height = height;
    for (uint32_t i = 0; i < max_mipmaps; ++i)
    {
        mip_map_sizes[i]   = dmMath::Max(mm_width, mm_height);
        mip_map_offsets[i] = (data_size / 8);
        data_size += mm_width * mm_height * bitspp * layer_count;
        mm_width  /= 2;
        mm_height /= 2;
    }
    assert(data_size > 0);

    data_size                *= layer_count;
    uint32_t image_data_size  = data_size / 8; // bits -> bytes for compression formats
    uint8_t* image_data       = new uint8_t[image_data_size];

    dmGraphics::TextureImage::Image* image = new dmGraphics::TextureImage::Image();
    texture_image->m_Alternatives.m_Data   = image;
    texture_image->m_Alternatives.m_Count  = 1;
    texture_image->m_Type                  = type;

    image->m_Width                = width;
    image->m_Height               = height;
    image->m_OriginalWidth        = width;
    image->m_OriginalHeight       = height;
    image->m_Format               = format;
    image->m_CompressionType      = dmGraphics::TextureImage::COMPRESSION_TYPE_DEFAULT;
    image->m_CompressionFlags     = 0;
    image->m_Data.m_Data          = image_data;
    image->m_Data.m_Count         = image_data_size;

    image->m_MipMapOffset.m_Data  = mip_map_offsets;
    image->m_MipMapOffset.m_Count = max_mipmaps;
    image->m_MipMapSize.m_Data    = mip_map_sizes;
    image->m_MipMapSize.m_Count   = max_mipmaps;
}

static void DestroyTextureImage(dmGraphics::TextureImage& texture_image)
{
    for (int i = 0; i < texture_image.m_Alternatives.m_Count; ++i)
    {
        dmGraphics::TextureImage::Image& image = texture_image.m_Alternatives.m_Data[i];
        delete[] image.m_MipMapOffset.m_Data;
        delete[] image.m_MipMapSize.m_Data;
        delete[] image.m_Data.m_Data;
    }
    delete[] texture_image.m_Alternatives.m_Data;
}

/*# create a texture
 * Creates a new texture resource.
 *
 * @name resource.create_texture
 *
 * @param path [type:string] The path to the resource.
 * @param table [type:table] A table containing info about how to create the texture. Supported entries:
 *
 * `type`
 * : [type:number] The texture type. Supported values:
 *
 * - `resource.TEXTURE_TYPE_2D`
 * - `resource.TEXTURE_TYPE_CUBE_MAP`
 *
 * `width`
 * : [type:number] The width of the texture (in pixels)
 *
 * `height`
 * : [type:number] The width of the texture (in pixels)
 *
 * `format`
 * : [type:number] The texture format. Supported values:
 *
 * - `resource.TEXTURE_FORMAT_LUMINANCE`
 * - `resource.TEXTURE_FORMAT_RGB`
 * - `resource.TEXTURE_FORMAT_RGBA`
 *
 * `max_mipmaps`
 * : [type:number] optional max number of mipmaps. Defaults to zero, i.e no mipmap support
 *
 * @return path [type:hash] The path to the resource.
 *
 * @examples
 * How to create an 128x128 RGBA texture resource and assign it to a model
 *
 * ```lua
 * function init(self)
 *     local tparams = {
 *        width          = 128,
 *        height         = 128,
 *        type           = resource.TEXTURE_TYPE_2D,
 *        format         = resource.TEXTURE_FORMAT_RGBA,
 *    }
 *    local my_texture_id = resource.create_texture(path, tparams)
 *    go.set("#model", "texture0", my_texture_id)
 * end
 * ```
 */
static int CreateTexture(lua_State* L)
{
    // This function pushes the hash of the resource created
    int top                  = lua_gettop(L);
    const char* path_str     = luaL_checkstring(L, 1);
    const char* texturec_ext = ".texturec";

    char buf_ext[64];
    const char* path_ext = dmResource::GetExtFromPath(path_str, buf_ext, sizeof(buf_ext));

    if (path_ext == 0x0 || dmStrCaseCmp(path_ext, texturec_ext) != 0)
    {
        luaL_error(L, "Unable to create texture, path '%s' must have the %s extension", path_str, texturec_ext);
        return 0;
    }

    dmhash_t canonical_path_hash = GetCanonicalPathHash(path_str);
    if (dmResource::FindByHash(g_ResourceModule.m_Factory, canonical_path_hash))
    {
        luaL_error(L, "Unable to create texture, a resource is already registered at path '%s'", path_str);
        return 0;
    }

    dmGameObject::HInstance sender_instance = dmScript::CheckGOInstance(L);
    dmGameObject::HCollection collection    = dmGameObject::GetCollection(sender_instance);

    luaL_checktype(L, 2, LUA_TTABLE);
    uint32_t type        = (uint32_t) CheckTableInteger(L, 2, "type");
    uint32_t width       = (uint32_t) CheckTableInteger(L, 2, "width");
    uint32_t height      = (uint32_t) CheckTableInteger(L, 2, "height");
    uint32_t format      = (uint32_t) CheckTableInteger(L, 2, "format");
    uint32_t max_mipmaps = (uint32_t) CheckTableInteger(L, 2, "max_mipmaps", 0);

    uint8_t max_mipmaps_actual = dmGraphics::GetMipmapCount(dmMath::Max(width, height));

    if (max_mipmaps > max_mipmaps_actual)
    {
        dmLogWarning("Max mipmaps %d requested for texture %s, but max mipmaps supported for size (%d, %d) is %d",
            max_mipmaps, path_str, width, height, max_mipmaps_actual);
        max_mipmaps = max_mipmaps_actual;
    }

    // Max mipmap count is inclusive, so need at least 1
    max_mipmaps                                        = dmMath::Max((uint32_t) 1, max_mipmaps);
    uint32_t tex_bpp                                   = dmGraphics::GetTextureFormatBitsPerPixel((dmGraphics::TextureFormat) format);
    dmGraphics::TextureImage::Type tex_type            = (dmGraphics::TextureImage::Type) GraphicsTextureTypeToImageType(type);
    dmGraphics::TextureImage::TextureFormat tex_format = (dmGraphics::TextureImage::TextureFormat) GraphicsTextureFormatToImageFormat(format);
    dmGraphics::TextureImage texture_image             = {};
    MakeTextureImage(width, height, max_mipmaps, tex_bpp, tex_type, tex_format, &texture_image);

    dmArray<uint8_t> ddf_buffer;
    dmDDF::Result ddf_result = dmDDF::SaveMessageToArray(&texture_image, dmGraphics::TextureImage::m_DDFDescriptor, ddf_buffer);
    assert(ddf_result == dmDDF::RESULT_OK);

    void* resource = 0x0;
    dmResource::Result res = dmResource::CreateResource(g_ResourceModule.m_Factory, path_str, ddf_buffer.Begin(), ddf_buffer.Size(), &resource);

    DestroyTextureImage(texture_image);

    if (res != dmResource::RESULT_OK)
    {
        assert(top == lua_gettop(L));
        return ReportPathError(L, res, canonical_path_hash);
    }

    dmGameObject::AddDynamicResourceHash(collection, canonical_path_hash);

    dmScript::PushHash(L, canonical_path_hash);
    assert((top+1) == lua_gettop(L));
    return 1;
}

/*# release a resource
 * Release a resource.
 *
 * @warning This is a potentially dangerous operation, releasing resources currently being used can cause unexpected behaviour.
 *
 * @name resource.release
 *
 * @param path [type:hash|string] The path to the resource.
 *
 */
static int ReleaseResource(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);
    dmhash_t path_hash = dmScript::CheckHashOrString(L, 1);

    dmResource::SResourceDescriptor* rd = dmResource::FindByHash(g_ResourceModule.m_Factory, path_hash);
    if (!rd)
    {
        return DM_LUA_ERROR("Could not get resource: %s", dmHashReverseSafe64(path_hash));
    }

    dmGameObject::HInstance sender_instance = dmScript::CheckGOInstance(L);
    dmGameObject::HCollection collection    = dmGameObject::GetCollection(sender_instance);

    // This will remove the entry in the collections list of dynamically allocated resource (if it exists),
    // but we do the actual release here since we allow releasing arbitrary resources now
    dmGameObject::RemoveDynamicResourceHash(collection, path_hash);
    dmResource::Release(g_ResourceModule.m_Factory, rd->m_Resource);

    return 0;
}

/*# set a texture
 * Sets the pixel data for a specific texture.
 *
 * @name resource.set_texture
 *
 * @param path [type:hash|string] The path to the resource
 * @param table [type:table] A table containing info about the texture. Supported entries:
 *
 * `type`
 * : [type:number] The texture type. Supported values:
 *
 * - `resource.TEXTURE_TYPE_2D`
 * - `resource.TEXTURE_TYPE_CUBE_MAP`
 *
 * `width`
 * : [type:number] The width of the texture (in pixels)
 *
 * `height`
 * : [type:number] The width of the texture (in pixels)
 *
 * `format`
 * : [type:number] The texture format. Supported values:
 *
 * - `resource.TEXTURE_FORMAT_LUMINANCE`
 * - `resource.TEXTURE_FORMAT_RGB`
 * - `resource.TEXTURE_FORMAT_RGBA`
 *
 * `x`
 * : [type:number] optional x offset of the texture (in pixels)
 *
 * `y`
 * : [type:number] optional y offset of the texture (in pixels)
 *
 * `mipmap`
 * : [type:number] optional mipmap to upload the data to
 *
 * @param buffer [type:buffer] The buffer of precreated pixel data
 *
 * [icon:attention] To update a cube map texture you need to pass in six times the amount of data via the buffer, since a cube map has six sides!
 *
 * @examples
 * How to set all pixels of an atlas
 *
 * ```lua
 * function init(self)
 *   self.height = 128
 *   self.width = 128
 *   self.buffer = buffer.create(self.width * self.height, { {name=hash("rgb"), type=buffer.VALUE_TYPE_UINT8, count=3} } )
 *   self.stream = buffer.get_stream(self.buffer, hash("rgb"))
 *
 *   for y=1,self.height do
 *       for x=1,self.width do
 *           local index = (y-1) * self.width * 3 + (x-1) * 3 + 1
 *           self.stream[index + 0] = 0xff
 *           self.stream[index + 1] = 0x80
 *           self.stream[index + 2] = 0x10
 *       end
 *   end
 *
 *   local resource_path = go.get("#sprite", "texture0")
 *   local args = { width=self.width, height=self.height, type=resource.TEXTURE_TYPE_2D, format=resource.TEXTURE_FORMAT_RGB, num_mip_maps=1 }
 *   resource.set_texture( resource_path, args, self.buffer )
 * end
 * ```
 *
 * @examples
 * How to update a specific region of an atlas by using the x,y values. Assumes the already set atlas is a 128x128 texture.
 *
 * ```lua
 * function init(self)
 *   self.x = 16
 *   self.y = 16
 *   self.height = 128 - self.x * 2
 *   self.width = 128 - self.y * 2
 *   self.buffer = buffer.create(self.width * self.height, { {name=hash("rgb"), type=buffer.VALUE_TYPE_UINT8, count=3} } )
 *   self.stream = buffer.get_stream(self.buffer, hash("rgb"))
 *
 *   for y=1,self.height do
 *       for x=1,self.width do
 *           local index = (y-1) * self.width * 3 + (x-1) * 3 + 1
 *           self.stream[index + 0] = 0xff
 *           self.stream[index + 1] = 0x80
 *           self.stream[index + 2] = 0x10
 *       end
 *   end
 *
 *   local resource_path = go.get("#sprite", "texture0")
 *   local args = { width=self.width, height=self.height, x=self.x, y=self.y, type=resource.TEXTURE_TYPE_2D, format=resource.TEXTURE_FORMAT_RGB, num_mip_maps=1 }
 *   resource.set_texture( resource_path, args, self.buffer )
 * end
 * ```
 */
static int SetTexture(lua_State* L)
{
    // Note: We only support uploading a single mipmap for a single slice at a time
    const uint32_t NUM_MIP_MAPS = 1;
    const int32_t DEFAULT_INT_NOT_SET = -1;

    int top = lua_gettop(L);

    dmhash_t path_hash = dmScript::CheckHashOrString(L, 1);

    luaL_checktype(L, 2, LUA_TTABLE);
    uint32_t type   = (uint32_t) CheckTableInteger(L, 2, "type");
    uint32_t width  = (uint32_t) CheckTableInteger(L, 2, "width");
    uint32_t height = (uint32_t) CheckTableInteger(L, 2, "height");
    uint32_t format = (uint32_t) CheckTableInteger(L, 2, "format");
    uint32_t mipmap = (uint32_t) CheckTableInteger(L, 2, "mipmap", 0);
    int32_t x       = (int32_t)  CheckTableInteger(L, 2, "x", DEFAULT_INT_NOT_SET);
    int32_t y       = (int32_t)  CheckTableInteger(L, 2, "y", DEFAULT_INT_NOT_SET);

    bool sub_update = x != DEFAULT_INT_NOT_SET || y != DEFAULT_INT_NOT_SET;
    x               = dmMath::Max(0, x);
    y               = dmMath::Max(0, y);

    dmScript::LuaHBuffer* buffer = dmScript::CheckBuffer(L, 3);

    uint8_t* data = 0;
    uint32_t datasize = 0;
    dmBuffer::GetBytes(buffer->m_Buffer, (void**)&data, &datasize);

    dmGraphics::TextureImage::Image image  = {};
    dmGraphics::TextureImage texture_image = {};
    texture_image.m_Alternatives.m_Data    = &image;
    texture_image.m_Alternatives.m_Count   = 1;
    texture_image.m_Type                   = (dmGraphics::TextureImage::Type) GraphicsTextureTypeToImageType(type);

    image.m_Width                = width;
    image.m_Height               = height;
    image.m_OriginalWidth        = width;
    image.m_OriginalHeight       = height;
    image.m_Format               = (dmGraphics::TextureImage::TextureFormat)GraphicsTextureFormatToImageFormat(format);
    image.m_CompressionType      = dmGraphics::TextureImage::COMPRESSION_TYPE_DEFAULT;
    image.m_CompressionFlags     = 0;
    image.m_Data.m_Data          = data;
    image.m_Data.m_Count         = datasize;

    uint32_t mip_map_offsets     = 0;
    uint32_t mip_map_sizes       = datasize;
    image.m_MipMapOffset.m_Data  = &mip_map_offsets;
    image.m_MipMapOffset.m_Count = NUM_MIP_MAPS;
    image.m_MipMapSize.m_Data    = &mip_map_sizes;
    image.m_MipMapSize.m_Count   = NUM_MIP_MAPS;

    ResTextureReCreateParams recreate_params;
    recreate_params.m_TextureImage = &texture_image;

    ResTextureUploadParams& upload_params = recreate_params.m_UploadParams;
    upload_params.m_X                     = x;
    upload_params.m_Y                     = y;
    upload_params.m_MipMap                = mipmap;
    upload_params.m_SubUpdate             = sub_update;
    upload_params.m_UploadSpecificMipmap  = 1;

    dmResource::Result r = dmResource::SetResource(g_ResourceModule.m_Factory, path_hash, (void*) &recreate_params);

    if( r != dmResource::RESULT_OK )
    {
        assert(top == lua_gettop(L));
        return ReportPathError(L, r, path_hash);
    }

    assert(top == lua_gettop(L));
    return 0;
}

// Allocates a new array and fills it with data from a lua table at top of stack.
// Only supports number values. Note: Doesn't do any error checking!
template<typename T>
static void MakeNumberArrayFromLuaTable(lua_State* L, const char* field, void** data_out, uint32_t* count_out)
{
    lua_getfield(L, -1, field);
    int num_entries = lua_objlen(L, -1);
    T* data_ptr = new T[num_entries];

    lua_pushnil(L);
    while (lua_next(L, -2))
    {
        int32_t table_index     = lua_tonumber(L, -2);
        data_ptr[table_index-1] = lua_tonumber(L, -1);
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
    *data_out  = data_ptr;
    *count_out = num_entries;
}

static void DestroyTextureSet(dmGameSystemDDF::TextureSet& texture_set)
{
    delete[] texture_set.m_Animations.m_Data;
    delete[] texture_set.m_Geometries.m_Data;
    delete[] texture_set.m_FrameIndices.m_Data;
}

// These lookup functions are needed because the values for the two enums are different,
// so we can't rely on the raw value to convert between them
static dmGameObject::Playback DDFPlaybackToGameObjectPlayback(dmGameSystemDDF::Playback playback)
{
    switch(playback)
    {
        case dmGameSystemDDF::PLAYBACK_NONE          : return dmGameObject::PLAYBACK_NONE;
        case dmGameSystemDDF::PLAYBACK_ONCE_FORWARD  : return dmGameObject::PLAYBACK_ONCE_FORWARD;
        case dmGameSystemDDF::PLAYBACK_ONCE_BACKWARD : return dmGameObject::PLAYBACK_ONCE_BACKWARD;
        case dmGameSystemDDF::PLAYBACK_ONCE_PINGPONG : return dmGameObject::PLAYBACK_ONCE_PINGPONG;
        case dmGameSystemDDF::PLAYBACK_LOOP_FORWARD  : return dmGameObject::PLAYBACK_LOOP_FORWARD;
        case dmGameSystemDDF::PLAYBACK_LOOP_BACKWARD : return dmGameObject::PLAYBACK_LOOP_BACKWARD;
        case dmGameSystemDDF::PLAYBACK_LOOP_PINGPONG : return dmGameObject::PLAYBACK_LOOP_PINGPONG;
        default:break;
    }
    assert(0);
    return (dmGameObject::Playback) -1;
}

static dmGameSystemDDF::Playback GameObjectPlaybackToDDFPlayback(dmGameObject::Playback playback)
{
    switch(playback)
    {
        case dmGameObject::PLAYBACK_NONE:          return dmGameSystemDDF::PLAYBACK_NONE;
        case dmGameObject::PLAYBACK_ONCE_FORWARD:  return dmGameSystemDDF::PLAYBACK_ONCE_FORWARD;
        case dmGameObject::PLAYBACK_ONCE_BACKWARD: return dmGameSystemDDF::PLAYBACK_ONCE_BACKWARD;
        case dmGameObject::PLAYBACK_ONCE_PINGPONG: return dmGameSystemDDF::PLAYBACK_ONCE_PINGPONG;
        case dmGameObject::PLAYBACK_LOOP_FORWARD:  return dmGameSystemDDF::PLAYBACK_LOOP_FORWARD;
        case dmGameObject::PLAYBACK_LOOP_BACKWARD: return dmGameSystemDDF::PLAYBACK_LOOP_BACKWARD;
        case dmGameObject::PLAYBACK_LOOP_PINGPONG: return dmGameSystemDDF::PLAYBACK_LOOP_PINGPONG;
        default:break;
    }
    assert(0);
    return (dmGameSystemDDF::Playback) -1;
}

static void ValidateSetAtlasAnimations(lua_State* L, uint32_t* num_animations_out)
{
    int top = lua_gettop(L);
    uint32_t num_animations = 0;

    lua_getfield(L, -1, "animations");
    luaL_checktype(L, -1, LUA_TTABLE);

    lua_pushnil(L);
    while (lua_next(L, -2))
    {
        luaL_checktype(L, -1, LUA_TTABLE);
        luaL_checkinteger(L, -2);

        // Note: checkstring can change the lua stack, so we use isstring instead
        lua_getfield(L, -1, "id");
        if (!lua_isstring(L, -1))
        {
            luaL_error(L, "Invalid 'id' in animations table at index [%d], either missing or wrong type", num_animations + 1);
        }
        lua_pop(L, 1);

        #define CHECK_FIELD_INTEGER(field_name, required) \
            lua_getfield(L, -1, field_name); \
            if (required || !lua_isnil(L,-1)) \
                luaL_checkinteger(L, -1); \
            lua_pop(L, 1);

        CHECK_FIELD_INTEGER("width", true);
        CHECK_FIELD_INTEGER("height", true);
        CHECK_FIELD_INTEGER("frame_start", true);
        CHECK_FIELD_INTEGER("frame_end", true);
        CHECK_FIELD_INTEGER("playback", false);
        CHECK_FIELD_INTEGER("fps", false );

        #undef CHECK_FIELD_INTEGER

        lua_pop(L, 1);

        num_animations++;
    }

    lua_pop(L, 1);
    *num_animations_out = num_animations;

    assert(lua_gettop(L) == top);
}

static void ValidateSetAtlasGeometries(lua_State* L, uint32_t* num_geometries_out)
{
    int top = lua_gettop(L);
    uint32_t num_geometries = 0;

    lua_getfield(L, -1, "geometries");
    luaL_checktype(L, -1, LUA_TTABLE);

    lua_pushnil(L);
    while (lua_next(L, -2))
    {
        luaL_checktype(L, -1, LUA_TTABLE);
        luaL_checkinteger(L, -2);

        #define CHECK_NUMBER_ARRAY(field_name) \
            { \
                lua_getfield(L, -1, field_name); \
                luaL_checktype(L, -1, LUA_TTABLE); \
                lua_pushnil(L); \
                while (lua_next(L, -2)) \
                { \
                    luaL_checkinteger(L, -1); \
                    luaL_checktype(L, -2, LUA_TNUMBER); \
                    lua_pop(L, 1); \
                } \
                lua_pop(L, 1); \
            }

        CHECK_NUMBER_ARRAY("vertices");
        CHECK_NUMBER_ARRAY("uvs");
        CHECK_NUMBER_ARRAY("indices");

        #undef CHECK_NUMBER_ARRAY

        lua_pop(L, 1);

        num_geometries++;
    }
    lua_pop(L, 1);

    *num_geometries_out = num_geometries;

    assert(lua_gettop(L) == top);
}

static int SetAtlasData(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    dmhash_t path_hash = dmScript::CheckHashOrString(L, 1);
    void* atlas_res    = CheckResource(L, g_ResourceModule.m_Factory, path_hash, "texturesetc");
    assert(atlas_res);

    dmGameSystemDDF::TextureSet texture_set_ddf = {};
    uint32_t frame_index_count                  = 0;
    uint32_t tex_width                          = 0;
    uint32_t tex_height                         = 0;
    uint32_t num_geometries                     = 0;
    uint32_t num_animations                     = 0;

    luaL_checktype(L, 2, LUA_TTABLE);
    lua_pushvalue(L, 2);

    // Set texture resource path
    lua_getfield(L, -1, "texture");
    {
        const char* texture_path         = luaL_checkstring(L, -1);
        dmhash_t tex_canonical_path_hash = GetCanonicalPathHash(texture_path);
        void* texture_res                = CheckResource(L, g_ResourceModule.m_Factory, tex_canonical_path_hash, "texturec");
        assert(texture_res);
        texture_set_ddf.m_Texture        = texture_path;
        dmGraphics::HTexture texture     = (dmGraphics::HTexture) texture_res;
        tex_width                        = dmGraphics::GetTextureWidth(texture);
        tex_height                       = dmGraphics::GetTextureHeight(texture);
    }
    lua_pop(L, 1); // "texture"

    // Note: We do separate passes over the lua state to validate the data in the args table,
    //       this is because we need to allocate dynamic memory and can't use luaL_check** functions
    //       since they longjmp away so we can't release the memory..
    ValidateSetAtlasGeometries(L, &num_geometries);
    ValidateSetAtlasAnimations(L, &num_animations);

    if (num_animations > 0 && num_geometries == 0)
    {
        return DM_LUA_ERROR("Setting atlas with animations requires geometry");
    }

    texture_set_ddf.m_Geometries.m_Data  = new dmGameSystemDDF::SpriteGeometry[num_geometries];
    texture_set_ddf.m_Geometries.m_Count = num_geometries;
    memset(texture_set_ddf.m_Geometries.m_Data, 0, sizeof(dmGameSystemDDF::SpriteGeometry) * num_geometries);

    texture_set_ddf.m_Animations.m_Data  = new dmGameSystemDDF::TextureSetAnimation[num_animations];
    texture_set_ddf.m_Animations.m_Count = num_animations;
    memset(texture_set_ddf.m_Animations.m_Data, 0, sizeof(dmGameSystemDDF::TextureSetAnimation) * num_animations);

    if (num_geometries > 0)
    {
        lua_getfield(L, -1, "geometries");
        for (int i = 0; i < num_geometries; ++i)
        {
            lua_pushnumber(L, i+1);
            lua_gettable(L, -2);
            assert(lua_istable(L, -1));

            dmGameSystemDDF::SpriteGeometry& geometry = texture_set_ddf.m_Geometries[i];
            MakeNumberArrayFromLuaTable<float>(L, "vertices", (void**) &geometry.m_Vertices.m_Data, &geometry.m_Vertices.m_Count);
            MakeNumberArrayFromLuaTable<float>(L, "uvs", (void**) &geometry.m_Uvs.m_Data, &geometry.m_Uvs.m_Count);
            MakeNumberArrayFromLuaTable<int>(L, "indices", (void**) &geometry.m_Indices.m_Data, &geometry.m_Indices.m_Count);

            lua_pop(L, 1);

            frame_index_count++;
        }
        lua_pop(L, 1); // geometries
    }

    if (num_animations > 0)
    {
        lua_getfield(L, -1, "animations");
        for (int i = 0; i < num_animations; ++i)
        {
            lua_pushnumber(L, i+1);
            lua_gettable(L, -2);
            assert(lua_istable(L, -1));

            dmGameSystemDDF::TextureSetAnimation& animation = texture_set_ddf.m_Animations[i];

            // Default values, might be overridden later
            animation.m_Fps      = 30;
            animation.m_Playback = dmGameSystemDDF::PLAYBACK_ONCE_FORWARD;

            lua_getfield(L, -1, "id");
            animation.m_Id = lua_tostring(L, -1);
            lua_pop(L, 1);

            lua_getfield(L, -1, "width");
            animation.m_Width = lua_tointeger(L, -1);
            lua_pop(L, 1);

            lua_getfield(L, -1, "height");
            animation.m_Height = lua_tointeger(L, -1);
            lua_pop(L, 1);

            lua_getfield(L, -1, "frame_start");
            int frame_start = lua_tointeger(L, -1);
            lua_pop(L, 1);

            lua_getfield(L, -1, "frame_end");
            int frame_end = lua_tointeger(L, -1);
            lua_pop(L, 1);

            // Get optional arguments
            lua_getfield(L, -1, "playback");
            if (lua_isnumber(L, -1))
            {
                animation.m_Playback = GameObjectPlaybackToDDFPlayback((dmGameObject::Playback) lua_tointeger(L,-1));
            }
            lua_pop(L, 1);

            lua_getfield(L, -1, "fps");
            if (lua_isnumber(L, -1))
            {
                animation.m_Fps = lua_tointeger(L, -1);
            }
            lua_pop(L, 1);

            lua_pop(L, 1);

            // Check ranges now that we have all geometry entries
            if (frame_start < 1 || frame_start > (num_geometries+1)) // +1 for lua indexing
            {
                DestroyTextureSet(texture_set_ddf);
                return DM_LUA_ERROR("Invalid frame_start in animation [%d], index %d is outside of geometry bounds 0..%d",
                    i, frame_start, num_geometries);
            }

            if (frame_end < 1 || frame_end > (num_geometries+1)) // +1 for lua indexing
            {
                DestroyTextureSet(texture_set_ddf);
                return DM_LUA_ERROR("Invalid frame_end in animation [%d], index %d is outside of geometry bounds 0..%d",
                    i, frame_end, num_geometries);
            }

            int frame_interval = frame_end - frame_start;
            if (frame_interval <= 0)
            {
                DestroyTextureSet(texture_set_ddf);
                return DM_LUA_ERROR("Invalid frame interval in animation [%d], start - end = %d", i, frame_interval);
            }

            // Correct frame start/end
            animation.m_Start = frame_start + num_geometries - 1;
            animation.m_End   = frame_end + num_geometries - 1;
            frame_index_count += frame_interval;
        }
        lua_pop(L, 1); // animations
    }

    lua_pop(L, 1); // args table

    texture_set_ddf.m_UseGeometries        = 1;
    texture_set_ddf.m_FrameIndices.m_Data  = new uint32_t[frame_index_count];
    texture_set_ddf.m_FrameIndices.m_Count = frame_index_count;
    memset(texture_set_ddf.m_FrameIndices.m_Data, 0, sizeof(uint32_t) * frame_index_count);

    uint32_t frame_index = 0;
    for (int i = 0; i < num_geometries; ++i)
    {
        texture_set_ddf.m_FrameIndices[frame_index++] = i;
    }

    for (int i = 0; i < texture_set_ddf.m_Animations.m_Count; ++i)
    {
        uint32_t frame_start = texture_set_ddf.m_Animations[i].m_Start;
        uint32_t frame_count = texture_set_ddf.m_Animations[i].m_End - frame_start;

        // Values stored in the frame indices table refer to entries in the
        // m_Geometry table of the DDF, so we need to adjust the values so
        // that the start and end values are based from zero
        for (int j = 0; j < frame_count; ++j)
        {
            texture_set_ddf.m_FrameIndices[frame_index++] = frame_start + j - num_geometries;
        }
    }

    dmArray<uint8_t> ddf_buffer;
    dmDDF::Result ddf_result = dmDDF::SaveMessageToArray(&texture_set_ddf, dmGameSystemDDF::TextureSet::m_DDFDescriptor, ddf_buffer);
    assert(ddf_result == dmDDF::RESULT_OK);

    dmResource::Result r = dmResource::SetResource(g_ResourceModule.m_Factory, path_hash, ddf_buffer.Begin(), ddf_buffer.Size());

    DestroyTextureSet(texture_set_ddf);

    if(r != dmResource::RESULT_OK)
    {
        return ReportPathError(L, r, path_hash);
    }

    /*
    #define DESTROY_TEXTURE_SET_AND_RAISE_ERROR(...) \
        DestroyTextureSet(texture_set_ddf); \
        luaL_error(L, __VA_ARGS__);

    // We can't use the normal luaL_check*** functions after we allocate dynamic memory for the texture set,
    // because the error check will longjmp away and never release that memory
    #define CHECK_LUA_FIELD_TYPE(l_index, l_type, ...) \
        if (lua_type(L, l_index) != l_type) { \
            DESTROY_TEXTURE_SET_AND_RAISE_ERROR(__VA_ARGS__); \
        }

    int num_geometries = 0;
    // Set geometry data
    lua_getfield(L, -1, "geometries");
    CHECK_LUA_FIELD_TYPE(-1, LUA_TTABLE, "Invalid field 'geometries', table expected");
    {
        num_geometries = lua_objlen(L, -1);
        texture_set_ddf.m_Geometries.m_Data  = new dmGameSystemDDF::SpriteGeometry[num_geometries];
        texture_set_ddf.m_Geometries.m_Count = num_geometries;
        memset(texture_set_ddf.m_Geometries.m_Data, 0, sizeof(dmGameSystemDDF::SpriteGeometry) * num_geometries);

        uint32_t field_index = 0;

        lua_pushnil(L);
        while (lua_next(L, -2))
        {
            CHECK_LUA_FIELD_TYPE(-1, LUA_TTABLE, "Invalid field at %d, table expected", field_index);
            CHECK_LUA_FIELD_TYPE(-2, LUA_TNUMBER, "Invalid field 'width' for animation %d, number expected", table_index_lua);

            int32_t geometry_index                    = lua_tonumber(L, -2) - 1;
            dmGameSystemDDF::SpriteGeometry& geometry = texture_set_ddf.m_Geometries[geometry_index];

            if (!MakeNumberArrayFromLuaTable<float>(L, "vertices", (void**) &geometry.m_Vertices.m_Data, &geometry.m_Vertices.m_Count))
            {
                DESTROY_TEXTURE_SET_AND_RAISE_ERROR("Invalid field 'vertices' for geometry %d", geometry_index);
            }
            if (!MakeNumberArrayFromLuaTable<float>(L, "uvs", (void**) &geometry.m_Uvs.m_Data, &geometry.m_Uvs.m_Count))
            {
                DESTROY_TEXTURE_SET_AND_RAISE_ERROR("Invalid field 'uvs' for geometry %d", geometry_index);
            }
            if (!MakeNumberArrayFromLuaTable<int>(L, "indices", (void**) &geometry.m_Indices.m_Data, &geometry.m_Indices.m_Count))
            {
                DESTROY_TEXTURE_SET_AND_RAISE_ERROR("Invalid field 'indices' for geometry %d", geometry_index);
            }

            frame_index_count++;

            lua_pop(L, 1);
        }
    }
    lua_pop(L, 1);

    // Set animation data
    lua_getfield(L, -1, "animations");
    luaL_checktype(L, -1, LUA_TTABLE);
    {
        int num_animations = lua_objlen(L, -1);
        if (num_animations > 0)
        {
            texture_set_ddf.m_Animations.m_Data  = new dmGameSystemDDF::TextureSetAnimation[num_animations];
            texture_set_ddf.m_Animations.m_Count = num_animations;
            memset(texture_set_ddf.m_Animations.m_Data, 0, sizeof(dmGameSystemDDF::TextureSetAnimation) * num_animations);

            lua_pushnil(L);
            while (lua_next(L, -2))
            {
                luaL_checktype(L, -1, LUA_TTABLE);
                CHECK_LUA_FIELD_TYPE(-2, LUA_TNUMBER, "Invalid key for animation, keys must be numbers");
                int32_t table_index_lua = lua_tonumber(L, -2);

                dmGameSystemDDF::TextureSetAnimation& animation = texture_set_ddf.m_Animations[table_index_lua-1];

                // Default values, might be overridden later
                animation.m_Fps      = 30;
                animation.m_Playback = dmGameSystemDDF::PLAYBACK_ONCE_FORWARD;

                lua_getfield(L, -1, "id");
                CHECK_LUA_FIELD_TYPE(-1, LUA_TSTRING, "Invalid field 'id' for animation %d, string expected", table_index_lua);
                animation.m_Id = lua_tostring(L, -1);
                lua_pop(L, 1);

                lua_getfield(L, -1, "width");
                CHECK_LUA_FIELD_TYPE(-1, LUA_TNUMBER, "Invalid field 'width' for animation %d, number expected", table_index_lua);
                animation.m_Width = lua_tointeger(L, -1);
                lua_pop(L, 1);

                lua_getfield(L, -1, "height");
                CHECK_LUA_FIELD_TYPE(-1, LUA_TNUMBER, "Invalid field 'height' for animation %d, number expected", table_index_lua);
                animation.m_Height = lua_tointeger(L, -1);
                lua_pop(L, 1);

                lua_getfield(L, -1, "frame_start");
                CHECK_LUA_FIELD_TYPE(-1, LUA_TNUMBER, "Invalid field 'frame_start' for animation %d", table_index_lua);
                animation.m_Start = lua_tointeger(L, -1) - 1 + num_geometries;
                lua_pop(L, 1);

                lua_getfield(L, -1, "frame_end");
                CHECK_LUA_FIELD_TYPE(-1, LUA_TNUMBER, "Invalid field 'frame_end' for animation %d", table_index_lua);
                animation.m_End = lua_tointeger(L, -1) - 1 + num_geometries;
                lua_pop(L, 1);

                frame_index_count += animation.m_End - animation.m_Start;

                lua_getfield(L, -1, "playback");
                if (lua_isnumber(L, -1))
                {
                    animation.m_Playback = GameObjectPlaybackToDDFPlayback((dmGameObject::Playback) lua_tointeger(L,-1));
                }
                lua_pop(L, 1);

                lua_getfield(L, -1, "fps");
                if (lua_isnumber(L, -1))
                {
                    animation.m_Fps = lua_tointeger(L, -1);
                }
                lua_pop(L, 1);

                lua_pop(L, 1);
            }
        }
    }
    lua_pop(L, 1);

    lua_pop(L, 1);

    #undef CHECK_LUA_FIELD_TYPE
    #undef DESTROY_TEXTURE_SET_AND_RAISE_ERROR
    */

    return 0;
}

static int GetAtlasData(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 1);

    dmhash_t path_hash = dmScript::CheckHashOrString(L, 1);

    TextureSetResource* texture_set_res = (TextureSetResource*) CheckResource(L, g_ResourceModule.m_Factory, path_hash, "texturesetc");
    assert(texture_set_res);

    dmGameSystemDDF::TextureSet* texture_set = texture_set_res->m_TextureSet;
    assert(texture_set);

    lua_newtable(L);

    // JG: This returns a hash, but should we return the actual path?
    //     Which requires us to keep the m_ResourceToHash table in factories around in non-debug..
    lua_pushliteral(L, "texture");
    dmScript::PushHash(L, texture_set_res->m_TexturePath);
    lua_rawset(L, -3);

    lua_pushliteral(L, "animations");
    lua_newtable(L);

    uint32_t num_geometries = texture_set->m_Geometries.m_Count;

    for (int i = 0; i < texture_set->m_Animations.m_Count; ++i)
    {
        dmGameSystemDDF::TextureSetAnimation& anim = texture_set->m_Animations[i];

        lua_pushinteger(L, (lua_Integer) (i+1));
        lua_newtable(L);

        lua_pushliteral(L, "id");
        lua_pushstring(L, anim.m_Id);
        lua_rawset(L, -3);

        #define SET_LUA_INTEGER(id, val) \
            lua_pushliteral(L, id); \
            lua_pushinteger(L, val); \
            lua_rawset(L, -3);

        SET_LUA_INTEGER("width", anim.m_Width);
        SET_LUA_INTEGER("height", anim.m_Height);
        SET_LUA_INTEGER("fps", anim.m_Fps);
        SET_LUA_INTEGER("playback", (lua_Integer) DDFPlaybackToGameObjectPlayback(anim.m_Playback));
        SET_LUA_INTEGER("flip_horizontal", anim.m_FlipHorizontal);
        SET_LUA_INTEGER("flip_vertical", anim.m_FlipVertical);

        // Note: To make setting atlas data more intuitive we remove the geometry count
        //       from the indices, otherwise the user has to know about the number of geometries
        //       and offset this by themselves since this is what the build pipeline does,
        //       and what the rest of the engine expects..
        SET_LUA_INTEGER("frame_start", anim.m_Start + 1 - num_geometries);
        SET_LUA_INTEGER("frame_end", anim.m_End + 1 - num_geometries);

        #undef SET_LUA_INTEGER

        lua_rawset(L, -3);
    }

    lua_rawset(L, -3);

    {
        lua_pushliteral(L, "geometries");
        lua_newtable(L);

        for (int i = 0; i < num_geometries; ++i)
        {
            dmGameSystemDDF::SpriteGeometry& geom = texture_set->m_Geometries[i];

            lua_pushinteger(L, (lua_Integer) (i+1));
            lua_newtable(L);

            {
                lua_pushliteral(L, "vertices");
                lua_newtable(L);
                for (int j = 0; j < geom.m_Vertices.m_Count; ++j)
                {
                    lua_pushinteger(L, j + 1);
                    lua_pushnumber(L, geom.m_Vertices[j]);
                    lua_rawset(L, -3);
                }
                lua_rawset(L, -3);

                lua_pushliteral(L, "uvs");
                lua_newtable(L);
                for (int j = 0; j < geom.m_Uvs.m_Count; ++j)
                {
                    lua_pushinteger(L, j + 1);
                    lua_pushnumber(L, geom.m_Uvs[j]);
                    lua_rawset(L, -3);
                }
                lua_rawset(L, -3);

                lua_pushliteral(L, "indices");
                lua_newtable(L);
                for (int j = 0; j < geom.m_Indices.m_Count; ++j)
                {
                    lua_pushinteger(L, j + 1);
                    lua_pushinteger(L, geom.m_Indices[j]);
                    lua_rawset(L, -3);
                }
                lua_rawset(L, -3);
            }

            lua_rawset(L, -3);
        }
        lua_rawset(L, -3);
    }

    return 1;
}

/*# Update internal sound resource
 * Update internal sound resource (wavc/oggc) with new data
 *
 * @name resource.set_sound
 *
 * @param path [type:hash|string] The path to the resource
 * @param buffer [type:string] A lua string containing the binary sound data
 */
static int SetSound(lua_State* L) {
    DM_LUA_STACK_CHECK(L, 0);

    // get resource path as hash
    const dmhash_t path_hash = dmScript::CheckHashOrString(L, 1);
    // get the sound buffer
    luaL_checktype(L, 2, LUA_TSTRING);
    size_t buffer_size;
    const char* buffer = lua_tolstring(L, 2, &buffer_size);

    dmResource::Result r = dmResource::SetResource(g_ResourceModule.m_Factory, path_hash, (void*) buffer, buffer_size);

    if( r != dmResource::RESULT_OK ) {
        return ReportPathError(L, r, path_hash);
    }

    return 0;
}

/*# get resource buffer
 * gets the buffer from a resource
 *
 * @name resource.get_buffer
 *
 * @param path [type:hash|string] The path to the resource
 * @return buffer [type:buffer] The resource buffer
 *
 * @examples
 * How to get the data from a buffer
 *
 * ```lua
 * function init(self)
 *
 *     local res_path = go.get("#mesh", "vertices")
 *     local buf = resource.get_buffer(res_path)
 *     local stream_positions = buffer.get_stream(self.buffer, "position")
 *
 *     for i=1,#stream_positions do
 *         print(i, stream_positions[i])
 *     end
 * end
 * ```
 */
static int GetBuffer(lua_State* L)
{
    int top = lua_gettop(L);
    dmhash_t path_hash = dmScript::CheckHashOrString(L, 1);

    void* resource = CheckResource(L, g_ResourceModule.m_Factory, path_hash, "bufferc");

    dmGameSystem::BufferResource* buffer_resource = (dmGameSystem::BufferResource*)resource;
    dmResource::IncRef(g_ResourceModule.m_Factory, buffer_resource);
    dmScript::LuaHBuffer luabuf((void*)buffer_resource);
    PushBuffer(L, luabuf);

    assert(top + 1 == lua_gettop(L));
    return 1;
}

/*# set resource buffer
 * sets the buffer of a resource
 *
 * @name resource.set_buffer
 *
 * @param path [type:hash|string] The path to the resource
 * @param buffer [type:buffer] The resource buffer
 *
 * @examples
 * How to set the data from a buffer
 *
 * ```lua
 * local function fill_stream(stream, verts)
 *     for key, value in ipairs(verts) do
 *         stream[key] = verts[key]
 *     end
 * end
 *
 * function init(self)
 *
 *     local res_path = go.get("#mesh", "vertices")
 *
 *     local positions = {
 *          1, -1, 0,
 *          1,  1, 0,
 *          -1, -1, 0
 *     }
 *
 *     local num_verts = #positions / 3
 *
 *     -- create a new buffer
 *     local buf = buffer.create(num_verts, {
 *         { name = hash("position"), type=buffer.VALUE_TYPE_FLOAT32, count = 3 }
 *     })
 *
 *     local buf = resource.get_buffer(res_path)
 *     local stream_positions = buffer.get_stream(buf, "position")
 *
 *     fill_stream(stream_positions, positions)
 *
 *     resource.set_buffer(res_path, buf)
 * end
 * ```
 */
static int SetBuffer(lua_State* L)
{
    int top = lua_gettop(L);
    dmhash_t path_hash = dmScript::CheckHashOrString(L, 1);
    dmScript::LuaHBuffer* luabuf = dmScript::CheckBuffer(L, 2);
    dmBuffer::HBuffer src_buffer = luabuf->m_Buffer;
    if (luabuf->m_Owner == dmScript::OWNER_RES) {
        src_buffer = ((BufferResource*)luabuf->m_BufferRes)->m_Buffer;
    }

    void* resource = CheckResource(L, g_ResourceModule.m_Factory, path_hash, "bufferc");

    dmGameSystem::BufferResource* buffer_resource = (dmGameSystem::BufferResource*)resource;
    dmBuffer::HBuffer dst_buffer = buffer_resource->m_Buffer;

    // Make sure the destination buffer has enough size (otherwise, resize it).
    // TODO: Check if incoming buffer size is smaller than current size -> don't allocate new dmbuffer,
    //       but copy smaller data and change "size".
    uint32_t dst_count = 0;
    dmBuffer::Result br = dmBuffer::GetCount(dst_buffer, &dst_count);
    if (br != dmBuffer::RESULT_OK) {
        return luaL_error(L, "Unable to get buffer size for %s: %s (%d).", dmHashReverseSafe64(path_hash), dmBuffer::GetResultString(br), br);
    }
    uint32_t src_count = 0;
    br = dmBuffer::GetCount(src_buffer, &src_count);
    if (br != dmBuffer::RESULT_OK) {
        return luaL_error(L, "Unable to get buffer size for source buffer: %s (%d).", dmBuffer::GetResultString(br), br);
    }

    bool new_buffer_needed = dst_count != src_count;
    if (new_buffer_needed) {
        // Need to create a new buffer to copy data to.

        // Copy stream declaration
        uint32_t stream_count = buffer_resource->m_BufferDDF->m_Streams.m_Count;
        dmBuffer::StreamDeclaration* streams_decl = (dmBuffer::StreamDeclaration*)malloc(stream_count * sizeof(dmBuffer::StreamDeclaration));
        for (uint32_t i = 0; i < stream_count; ++i)
        {
            const dmBufferDDF::StreamDesc& ddf_stream = buffer_resource->m_BufferDDF->m_Streams[i];
            streams_decl[i].m_Name = dmHashString64(ddf_stream.m_Name);
            streams_decl[i].m_Type = (dmBuffer::ValueType)ddf_stream.m_ValueType;
            streams_decl[i].m_Count = ddf_stream.m_ValueCount;
        }

        br = dmBuffer::Create(src_count, streams_decl, stream_count, &dst_buffer);
        free(streams_decl);

        if (br != dmBuffer::RESULT_OK) {
            return luaL_error(L, "Unable to create copy buffer: %s (%d).", dmBuffer::GetResultString(br), br);
        }
    }

    // Copy supplied data to buffer
    br = dmBuffer::Copy(dst_buffer, src_buffer);
    if (br != dmBuffer::RESULT_OK) {
        if (new_buffer_needed) {
            dmBuffer::Destroy(dst_buffer);
        }
        return luaL_error(L, "Could not copy data from buffer: %s (%d).", dmBuffer::GetResultString(br), br);
    }

    // If we created a new buffer, make sure to destroy the old one.
    if (new_buffer_needed) {
        dmBuffer::Destroy(buffer_resource->m_Buffer);
        buffer_resource->m_Buffer = dst_buffer;
        buffer_resource->m_ElementCount = src_count;
    }

    // Update the content version
    dmBuffer::UpdateContentVersion(dst_buffer);
    dmBuffer::GetContentVersion(buffer_resource->m_Buffer, &buffer_resource->m_Version);
    buffer_resource->m_NameHash = path_hash;

    assert(top == lua_gettop(L));
    return 0;
}

static void PushTextMetricsTable(lua_State* L, const dmRender::TextMetrics* metrics)
{
    lua_createtable(L, 0, 4);
    lua_pushliteral(L, "width");
    lua_pushnumber(L, metrics->m_Width);
    lua_rawset(L, -3);
    lua_pushliteral(L, "height");
    lua_pushnumber(L, metrics->m_Height);
    lua_rawset(L, -3);
    lua_pushliteral(L, "max_ascent");
    lua_pushnumber(L, metrics->m_MaxAscent);
    lua_rawset(L, -3);
    lua_pushliteral(L, "max_descent");
    lua_pushnumber(L, metrics->m_MaxDescent);
    lua_rawset(L, -3);
}

/*#  gets the text metrics for a font
 *
 * Gets the text metrics from a font
 *
 * @name resource.get_text_metrics
 * @param url [type:hash] the font to get the (unscaled) metrics from
 * @param text [type:string] text to measure
 * @param [options] [type:table] A table containing parameters for the text. Supported entries:
 *
 * `width`
 * : [type:integer] The width of the text field. Not used if `line_break` is false.
 *
 * `leading`
 * : [type:number] The leading (default 1.0)
 *
 * `tracking`
 * : [type:number] The leading (default 0.0)
 *
 * `line_break`
 * : [type:boolean] If the calculation should consider line breaks (default false)
 *
 * @return metrics [type:table] a table with the following fields:
 *
 * - width
 * - height
 * - max_ascent
 * - max_descent
 *
 * @examples
 *
 * ```lua
 * function init(self)
 *     local font = go.get("#label", "font")
 *     local metrics = resource.get_text_metrics(font, "The quick brown fox\n jumps over the lazy dog")
 *     pprint(metrics)
 * end
 * ```
 */
static int GetTextMetrics(lua_State* L)
{
    int top = lua_gettop(L);

    dmhash_t path_hash = dmScript::CheckHashOrString(L, 1);

    size_t len = 0;
    const char* text = luaL_checklstring(L, 2, &len);

    dmRender::HFontMap font_map = (dmRender::HFontMap)CheckResource(L, g_ResourceModule.m_Factory, path_hash, "fontc");;

    bool line_break = false;
    float leading = 1.0f;
    float tracking = 0.0f;
    float width = 100000.0f;
    if (top >= 3)
    {
        int table_index = 3;
        luaL_checktype(L, table_index, LUA_TTABLE); // Make sure it's actually a table
        width = (float)CheckTableNumber(L, table_index, "width", width);
        leading = (float)CheckTableNumber(L, table_index, "leading", leading);
        tracking = (float)CheckTableNumber(L, table_index, "tracking", tracking);
        line_break = CheckTableBoolean(L, table_index, "line_break", line_break);
    }

    dmRender::TextMetrics metrics;
    dmRender::GetTextMetrics(font_map, text, width, line_break, leading, tracking, &metrics);
    PushTextMetricsTable(L, &metrics);
    return 1;
}

#define DEPRECATE_LU_FUNCTION(LUA_NAME, CPP_NAME) \
    static int Deprecated_ ## CPP_NAME(lua_State* L) \
    { \
        dmLogOnceWarning(dmScript::DEPRECATION_FUNCTION_FMT, "resource", LUA_NAME, "liveupdate", LUA_NAME); \
        return dmLiveUpdate:: CPP_NAME (L); \
    }

DEPRECATE_LU_FUNCTION("get_current_manifest", Resource_GetCurrentManifest);
DEPRECATE_LU_FUNCTION("is_using_liveupdate_data", Resource_IsUsingLiveUpdateData);
DEPRECATE_LU_FUNCTION("store_resource", Resource_StoreResource);
DEPRECATE_LU_FUNCTION("store_manifest", Resource_StoreManifest);
DEPRECATE_LU_FUNCTION("store_archive", Resource_StoreArchive);

static const luaL_reg Module_methods[] =
{
    {"set", Set},
    {"load", Load},
    {"create_texture", CreateTexture},
    {"release", ReleaseResource},
    {"set_atlas", SetAtlasData},
    {"get_atlas", GetAtlasData},
    {"set_texture", SetTexture},
    {"set_sound", SetSound},
    {"get_buffer", GetBuffer},
    {"set_buffer", SetBuffer},
    {"get_text_metrics", GetTextMetrics},

    // LiveUpdate functionality in resource namespace
    {"get_current_manifest", Deprecated_Resource_GetCurrentManifest},
    {"is_using_liveupdate_data", Deprecated_Resource_IsUsingLiveUpdateData},
    {"store_resource", Deprecated_Resource_StoreResource},
    {"store_manifest", Deprecated_Resource_StoreManifest},
    {"store_archive", Deprecated_Resource_StoreArchive},

    {0, 0}
};

/*# 2D texture type
 *
 * @name resource.TEXTURE_TYPE_2D
 * @variable
 */

/*# Cube map texture type
 *
 * @name resource.TEXTURE_TYPE_CUBE_MAP
 * @variable
 */

/*# luminance type texture format
 *
 * @name resource.TEXTURE_FORMAT_LUMINANCE
 * @variable
 */

/*# RGB type texture format
 *
 * @name resource.TEXTURE_FORMAT_RGB
 * @variable
 */

/*# RGBA type texture format
 *
 * @name resource.TEXTURE_FORMAT_RGBA
 * @variable
 */

 /*# LIVEUPDATE_OK
 *
 * @name resource.LIVEUPDATE_OK
 * @variable
 */

 /*# LIVEUPDATE_INVALID_RESOURCE
 * The handled resource is invalid.
 *
 * @name resource.LIVEUPDATE_INVALID_RESOURCE
 * @variable
 */

 /*# LIVEUPDATE_VERSION_MISMATCH
 * Mismatch between manifest expected version and actual version.
 *
 * @name resource.LIVEUPDATE_VERSION_MISMATCH
 * @variable
 */

 /*# LIVEUPDATE_ENGINE_VERSION_MISMATCH
 * Mismatch between running engine version and engine versions supported by manifest.
 *
 * @name resource.LIVEUPDATE_ENGINE_VERSION_MISMATCH
 * @variable
 */

 /*# LIVEUPDATE_SIGNATURE_MISMATCH
 * Mismatch between manifest expected signature and actual signature.
 *
 * @name resource.LIVEUPDATE_SIGNATURE_MISMATCH
 * @variable
 */

 /*# LIVEUPDATE_SCHEME_MISMATCH
 * Mismatch between scheme used to load resources. Resources are loaded with a different scheme than from manifest, for example over HTTP or directly from file. This is typically the case when running the game directly from the editor instead of from a bundle.
 *
 * @name resource.LIVEUPDATE_SCHEME_MISMATCH
 * @variable
 */

 /*# LIVEUPDATE_BUNDLED_RESOURCE_MISMATCH
 * Mismatch between between expected bundled resources and actual bundled resources. The manifest expects a resource to be in the bundle, but it was not found in the bundle. This is typically the case when a non-excluded resource was modified between publishing the bundle and publishing the manifest.
 *
 * @name resource.LIVEUPDATE_BUNDLED_RESOURCE_MISMATCH
 * @variable
 */

 /*# LIVEUPDATE_FORMAT_ERROR
 * Failed to parse manifest data buffer. The manifest was probably produced by a different engine version.
 *
 * @name resource.LIVEUPDATE_FORMAT_ERROR
 * @variable
 */
static void LuaInit(lua_State* L)
{
    int top = lua_gettop(L);
    luaL_register(L, "resource", Module_methods);

#define SETGRAPHICSCONSTANT(name) \
    lua_pushnumber(L, (lua_Number) dmGraphics:: name); \
    lua_setfield(L, -2, #name); \

    SETGRAPHICSCONSTANT(TEXTURE_TYPE_2D);
    SETGRAPHICSCONSTANT(TEXTURE_TYPE_CUBE_MAP);

    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_LUMINANCE);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGB);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGBA);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_DEPTH);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_STENCIL);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGB_PVRTC_2BPPV1);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGB_PVRTC_4BPPV1);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGBA_PVRTC_2BPPV1);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGBA_PVRTC_4BPPV1);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGB_ETC1);

    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGBA_ETC2);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGBA_ASTC_4x4);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGB_BC1);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGBA_BC3);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_R_BC4);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RG_BC5);
    SETGRAPHICSCONSTANT(TEXTURE_FORMAT_RGBA_BC7);

#undef SETGRAPHICSCONSTANT

#define SETCONSTANT(name, val) \
        lua_pushnumber(L, (lua_Number) val); \
        lua_setfield(L, -2, #name);\

    SETCONSTANT(LIVEUPDATE_OK, dmLiveUpdate::RESULT_OK);
    SETCONSTANT(LIVEUPDATE_INVALID_RESOURCE, dmLiveUpdate::RESULT_INVALID_RESOURCE);
    SETCONSTANT(LIVEUPDATE_VERSION_MISMATCH, dmLiveUpdate::RESULT_VERSION_MISMATCH);
    SETCONSTANT(LIVEUPDATE_ENGINE_VERSION_MISMATCH, dmLiveUpdate::RESULT_ENGINE_VERSION_MISMATCH);
    SETCONSTANT(LIVEUPDATE_SIGNATURE_MISMATCH, dmLiveUpdate::RESULT_SIGNATURE_MISMATCH);
    SETCONSTANT(LIVEUPDATE_SCHEME_MISMATCH, dmLiveUpdate::RESULT_SCHEME_MISMATCH);
    SETCONSTANT(LIVEUPDATE_BUNDLED_RESOURCE_MISMATCH, dmLiveUpdate::RESULT_BUNDLED_RESOURCE_MISMATCH);
    SETCONSTANT(LIVEUPDATE_FORMAT_ERROR, dmLiveUpdate::RESULT_FORMAT_ERROR);

#undef SETCONSTANT

    lua_pop(L, 1);
    assert(top == lua_gettop(L));
}

void ScriptResourceRegister(const ScriptLibContext& context)
{
    LuaInit(context.m_LuaState);
    g_ResourceModule.m_Factory = context.m_Factory;
}

void ScriptResourceFinalize(const ScriptLibContext& context)
{
}

} // namespace dmGameSystem
