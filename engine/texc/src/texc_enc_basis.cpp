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


#include <dlib/log.h>

#include "texc.h"
#include "texc_private.h"

#include <basis/encoder/basisu_enc.h>
#include <basis/encoder/basisu_comp.h>
#include <basis/encoder/basisu_frontend.h>

#include <basis/transcoder/basisu_global_selector_palette.h>

namespace dmTexc
{
    static void SetCompressionLevel(CompressionType compression_type, CompressionLevel compression_level, basisu::basis_compressor_params& comp_params)
    {
        if (compression_type == CT_BASIS_ETC1S)
        {
            switch(compression_level)
            {
                case CL_FAST:   comp_params.m_compression_level = 0; break;
                case CL_HIGH:   comp_params.m_compression_level = 3;
                case CL_BEST:   comp_params.m_compression_level = basisu::BASISU_MAX_COMPRESSION_LEVEL; break;
                case CL_NORMAL:
                default:        comp_params.m_compression_level = basisu::BASISU_DEFAULT_COMPRESSION_LEVEL; break;
            }
        }
        else {

            switch(compression_level)
            {
                case CL_FAST:   comp_params.m_pack_uastc_flags = basisu::cPackUASTCLevelFastest; break;
                case CL_HIGH:   comp_params.m_pack_uastc_flags = basisu::cPackUASTCLevelSlower; break;
                case CL_BEST:   comp_params.m_pack_uastc_flags = basisu::cPackUASTCLevelSlower; break;
                case CL_NORMAL:
                default:        comp_params.m_pack_uastc_flags = basisu::cPackUASTCLevelDefault; break;
            }
        }
    }

    static bool EncodeBasis(Texture* texture, int num_threads, PixelFormat pixel_format, CompressionType compression_type, CompressionLevel compression_level)
    {
        (void)pixel_format;

        basist::etc1_global_selector_codebook sel_codebook(basist::g_global_selector_cb_size, basist::g_global_selector_cb);

        basisu::job_pool jpool(num_threads);

        basisu::basis_compressor_params comp_params;

        comp_params.m_read_source_images = false;
        comp_params.m_write_output_basis_files = false;
        comp_params.m_pSel_codebook = &sel_codebook;
        comp_params.m_pJob_pool = &jpool;
        comp_params.m_multithreading = num_threads > 1;
        comp_params.m_uastc = compression_type == CT_BASIS_UASTC;
        comp_params.m_mip_gen = texture->m_BasisGenMipmaps;

        comp_params.m_source_images.push_back(texture->m_BasisImage);

        SetCompressionLevel(compression_type, compression_level, comp_params);

        basisu::basis_compressor compressor;
        if (!compressor.init(comp_params))
        {
            dmLogError("basis_compressor::init() failed!\n");
            return false;
        }

        basisu::interval_timer tm;
        tm.start();

        basisu::basis_compressor::error_code ec = compressor.process();

        tm.stop();

        if (ec == basisu::basis_compressor::cECSuccess)
        {
            dmLogDebug("Compression succeeded in %3.3f secs\n", tm.get_elapsed_secs());
        }
        else
        {
            switch (ec)
            {
                case basisu::basis_compressor::cECFailedReadingSourceImages: dmLogError("Compressor failed reading a source image!\n"); break;
                case basisu::basis_compressor::cECFailedValidating: dmLogError("Compressor failed 2darray/cubemap/video validation checks!\n"); break;
                case basisu::basis_compressor::cECFailedEncodeUASTC: dmLogError("Compressor UASTC encode failed!\n"); break;
                case basisu::basis_compressor::cECFailedFrontEnd: dmLogError("Compressor frontend stage failed!\n"); break;
                case basisu::basis_compressor::cECFailedFontendExtract: dmLogError("Compressor frontend data extraction failed!\n"); break;
                case basisu::basis_compressor::cECFailedBackend: dmLogError("Compressor backend stage failed!\n"); break;
                case basisu::basis_compressor::cECFailedCreateBasisFile: dmLogError("Compressor failed creating Basis file data!\n"); break;
                case basisu::basis_compressor::cECFailedWritingOutput: dmLogError("Compressor failed writing to output Basis file!\n"); break;
                case basisu::basis_compressor::cECFailedUASTCRDOPostProcess: dmLogError("Compressor failed during the UASTC post process step!\n"); break;
                default: dmLogError("basis_compress::process() failed!\n"); break;
            }

            return false;
        }

        //const std::vector<basisu::image_stats>& stats = compressor.get_stats();
        //double bits_per_texel = compressor.get_basis_bits_per_texel();
        // Statistics:

        // basisu::image_metrics im;
        // im.calc(a, b, 0, 3);
        // im.print("RGB    ");

        // im.calc(a, b, 0, 4);
        // im.print("RGBA   ");

        // im.calc(a, b, 0, 1);
        // im.print("R      ");

        // im.calc(a, b, 1, 1);
        // im.print("G      ");

        // im.calc(a, b, 2, 1);
        // im.print("B      ");

        // im.calc(a, b, 3, 1);
        // im.print("A      ");

        // OUTPUT
        //const uint8_vec& comp_data = m_basis_file.get_compressed_data();

        const basisu::uint8_vec& data = compressor.get_output_basis_file();
        texture->m_BasisFile.SetCapacity(data.size());
        texture->m_BasisFile.SetSize(data.size());
        memcpy(texture->m_BasisFile.Begin(), &data[0], data.size());

        return true;
    }

    bool CreateBasis(Texture* texture, uint32_t width, uint32_t height, PixelFormat pixel_format, ColorSpace color_space, CompressionType compression_type, void* data)
    {
        static int first = 1;
        if (first)
        {
            basisu::basisu_encoder_init();
        }

        uint32_t size = width * height * 4;
        uint8_t* base_image = new uint8_t[size];
        if (!ConvertToRGBA8888((uint8_t*)data, width, height, pixel_format, base_image))
        {
            delete[] base_image;
            return false;
        }

        int components = 4;
        texture->m_CompressionFlags = 0;
        texture->m_PixelFormat = pixel_format;
        texture->m_ColorSpace = color_space;
        texture->m_Width = width;
        texture->m_Height = height;
        texture->m_BasisGenMipmaps = false;
        texture->m_BasisImage.init((uint8_t*)base_image, width, height, components);
        delete[] base_image;
        return true;
    }

    void DestroyBasis(Texture* texture)
    {
        (void)texture;
    }

    bool GenMipMapsBasis(Texture* texture)
    {
        texture->m_BasisGenMipmaps = true;
        return true; // we're actually delaying it until later
    }

    bool ResizeBasis(Texture* texture, uint32_t width, uint32_t height)
    {
        basisu::image tmp(width, height);
        basisu::image_resample(texture->m_BasisImage, tmp);
        texture->m_BasisImage.swap(tmp);
        return true;
    }

    uint32_t GetTotalDataSizeBasis(Texture* texture)
    {
        return texture->m_BasisFile.Size();
    }

    uint32_t GetDataBasis(Texture* texture, void* out_data, uint32_t out_data_size)
    {
        memcpy(out_data, texture->m_BasisFile.Begin(), out_data_size);
        return out_data_size;
    }

    bool PreMultiplyAlphaBasis(Texture* texture)
    {
        if (!texture->m_BasisImage.has_alpha())
            return true;

        uint32_t w = texture->m_BasisImage.get_width();
        uint32_t h = texture->m_BasisImage.get_height();
        basisu::color_rgba* pixels = texture->m_BasisImage.get_ptr();

        PreMultiplyAlpha((uint8_t*)pixels, w, h);
        return true;
    }

    bool FlipBasis(Texture* texture, FlipAxis flip_axis)
    {
        basisu::color_rgba* pixels = texture->m_BasisImage.get_ptr();
        switch(flip_axis)
        {
        case FLIP_AXIS_Y:   FlipImageY_RGBA8888((uint32_t*)pixels, texture->m_Width, texture->m_Height);
                            return true;
        case FLIP_AXIS_X:   FlipImageX_RGBA8888((uint32_t*)pixels, texture->m_Width, texture->m_Height);
                            return true;
        default:
            dmLogError("Unexpected flip direction: %d", flip_axis);
            return false;
        }

        dmLogError("Unexpected flip direction: %d", flip_axis);
        return false;
    }


    bool GetHeaderBasis(Texture* texture, Header* out_header)
    {
        return false;
    }


    void GetEncoderBasis(Encoder* encoder)
    {
        encoder->m_FnCreate             = CreateBasis;
        encoder->m_FnDestroy            = DestroyBasis;
        encoder->m_FnResize             = ResizeBasis;
        encoder->m_FnGenMipMaps         = GenMipMapsBasis;
        encoder->m_FnEncode             = EncodeBasis;
        encoder->m_FnGetTotalDataSize   = GetTotalDataSizeBasis;
        encoder->m_FnGetData            = GetDataBasis;
        encoder->m_FnPreMultiplyAlpha   = PreMultiplyAlphaBasis;
        encoder->m_FnFlip               = FlipBasis;
        encoder->m_FnGetHeader          = GetHeaderBasis;
    }
}
