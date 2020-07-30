#!/usr/bin/env python
# ----------------------------------------------------------------------------
# pyglet
# Copyright (c) 2006-2007 Alex Holkner
# All rights reserved.
# 
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions 
# are met:
#
#  * Redistributions of source code must retain the above copyright
#    notice, this list of conditions and the following disclaimer.
#  * Redistributions in binary form must reproduce the above copyright 
#    notice, this list of conditions and the following disclaimer in
#    the documentation and/or other materials provided with the
#    distribution.
#  * Neither the name of the pyglet nor the names of its
#    contributors may be used to endorse or promote products
#    derived from this software without specific prior written
#    permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
# "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
# LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
# FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
# COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
# INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
# BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
# LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
# CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
# LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
# ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
# POSSIBILITY OF SUCH DAMAGE.
# ----------------------------------------------------------------------------

'''DDS texture loader.

Reference: http://msdn2.microsoft.com/en-us/library/bb172993.aspx
'''

__docformat__ = 'restructuredtext'
__version__ = '$Id$'

import struct

class DDSException(Exception):
    pass

# dwFlags of DDSURFACEDESC2
DDSD_CAPS           = 0x00000001
DDSD_HEIGHT         = 0x00000002
DDSD_WIDTH          = 0x00000004
DDSD_PITCH          = 0x00000008
DDSD_PIXELFORMAT    = 0x00001000
DDSD_MIPMAPCOUNT    = 0x00020000
DDSD_LINEARSIZE     = 0x00080000
DDSD_DEPTH          = 0x00800000

# ddpfPixelFormat of DDSURFACEDESC2
DDPF_ALPHAPIXELS    = 0x00000001
DDPF_FOURCC 	    = 0x00000004
DDPF_RGB 	    = 0x00000040

# dwCaps1 of DDSCAPS2
DDSCAPS_COMPLEX     = 0x00000008
DDSCAPS_TEXTURE     = 0x00001000
DDSCAPS_MIPMAP 	    = 0x00400000

# dwCaps2 of DDSCAPS2
DDSCAPS2_CUBEMAP 	    = 0x00000200
DDSCAPS2_CUBEMAP_POSITIVEX  = 0x00000400
DDSCAPS2_CUBEMAP_NEGATIVEX  = 0x00000800
DDSCAPS2_CUBEMAP_POSITIVEY  = 0x00001000
DDSCAPS2_CUBEMAP_NEGATIVEY  = 0x00002000
DDSCAPS2_CUBEMAP_POSITIVEZ  = 0x00004000
DDSCAPS2_CUBEMAP_NEGATIVEZ  = 0x00008000
DDSCAPS2_VOLUME 	    = 0x00200000

class _filestruct(object):
    def __init__(self, data):
        if len(data) < self.get_size():
            raise DDSException('Not a DDS file')
        items = struct.unpack(self.get_format(), data)
        for field, value in map(None, self._fields, items):
            setattr(self, field[0], value)

    def __repr__(self):
        name = self.__class__.__name__
        return '%s(%s)' % \
            (name, (', \n%s' % (' ' * (len(name) + 1))).join( \
                      ['%s = %s' % (field[0], repr(getattr(self, field[0]))) \
                       for field in self._fields]))

    @classmethod
    def get_format(cls):
        return '<' + ''.join([f[1] for f in cls._fields])

    @classmethod
    def get_size(cls):
        return struct.calcsize(cls.get_format())

class DDSURFACEDESC2(_filestruct):
    _fields = [
        ('dwMagic', '4s'),
        ('dwSize', 'I'),
        ('dwFlags', 'I'),
        ('dwHeight', 'I'),
        ('dwWidth', 'I'),
        ('dwPitchOrLinearSize', 'I'),
        ('dwDepth', 'I'),
        ('dwMipMapCount', 'I'),
        ('dwReserved1', '44s'),
        ('ddpfPixelFormat', '32s'),
        ('dwCaps1', 'I'),
        ('dwCaps2', 'I'),
        ('dwCapsReserved', '8s'),
        ('dwReserved2', 'I')
    ]

    def __init__(self, data):
        super(DDSURFACEDESC2, self).__init__(data)
        self.ddpfPixelFormat = DDPIXELFORMAT(self.ddpfPixelFormat)


class DDPIXELFORMAT(_filestruct):
    _fields = [
        ('dwSize', 'I'),
        ('dwFlags', 'I'),
        ('dwFourCC', '4s'),
        ('dwRGBBitCount', 'I'),
        ('dwRBitMask', 'I'),
        ('dwGBitMask', 'I'),
        ('dwBBitMask', 'I'),
        ('dwRGBAlphaBitMask', 'I')
    ]

GL_COMPRESSED_RGB_S3TC_DXT1_EXT = 33776         # GL/glext.h:2087
GL_COMPRESSED_RGBA_S3TC_DXT1_EXT = 33777        # GL/glext.h:2088
GL_COMPRESSED_RGBA_S3TC_DXT3_EXT = 33778        # GL/glext.h:2089
GL_COMPRESSED_RGBA_S3TC_DXT5_EXT = 33779        # GL/glext.h:2090

_compression_formats = {
    ('DXT1', False): (GL_COMPRESSED_RGB_S3TC_DXT1_EXT),
    ('DXT1', True):  (GL_COMPRESSED_RGBA_S3TC_DXT1_EXT),
    ('DXT3', False): (GL_COMPRESSED_RGBA_S3TC_DXT3_EXT),
    ('DXT3', True):  (GL_COMPRESSED_RGBA_S3TC_DXT3_EXT),
    ('DXT5', False): (GL_COMPRESSED_RGBA_S3TC_DXT5_EXT),
    ('DXT5', True):  (GL_COMPRESSED_RGBA_S3TC_DXT5_EXT),
}

class DDSImage(object):
    def __init__(self, width, height, four_cc, alpha, mip_maps):
        self.Width = width
        self.Height = height
        self.FourCC = four_cc
        self.Alpha = alpha
        self.MipMaps = mip_maps

def decode(file):
    header = file.read(DDSURFACEDESC2.get_size())
    desc = DDSURFACEDESC2(header)
    if desc.dwMagic != 'DDS ' or desc.dwSize != 124:
        raise DDSException('Invalid DDS file (incorrect header).')

    width = desc.dwWidth
    height = desc.dwHeight
    compressed = False
    volume = False
    mipmaps = 1

    if desc.dwFlags & DDSD_PITCH:
        pitch = desc.dwPitchOrLinearSize
    elif desc.dwFlags & DDSD_LINEARSIZE:
        image_size = desc.dwPitchOrLinearSize
        compressed = True

    if desc.dwFlags & DDSD_DEPTH:
        raise DDSException('Volume DDS files unsupported')
        volume = True
        depth = desc.dwDepth

    if desc.dwFlags & DDSD_MIPMAPCOUNT:
        mipmaps = desc.dwMipMapCount

    if desc.ddpfPixelFormat.dwSize != 32:
        raise DDSException('Invalid DDS file (incorrect pixel format).')

    if desc.dwCaps2 & DDSCAPS2_CUBEMAP:
        raise DDSException('Cubemap DDS files unsupported')

    if not desc.ddpfPixelFormat.dwFlags & DDPF_FOURCC:
        raise DDSException('Uncompressed DDS textures not supported.')

    has_alpha = desc.ddpfPixelFormat.dwRGBAlphaBitMask != 0

    format = None
    format = _compression_formats.get(
        (desc.ddpfPixelFormat.dwFourCC, has_alpha), None)
    if not format:
        raise DDSException('Unsupported texture compression %s' % \
            desc.ddpfPixelFormat.dwFourCC)

    if format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT:
        block_size = 8
    else:
        block_size = 16

    datas = []
    w, h = width, height
    for i in range(mipmaps):
        if not w and not h:
            break
        if not w:
            w = 1
        if not h:
            h = 1
        size = ((w + 3) / 4) * ((h + 3) / 4) * block_size
        data = file.read(size)
        datas.append(data)
        w >>= 1
        h >>= 1

    return DDSImage(width, height, desc.ddpfPixelFormat.dwFourCC, has_alpha, datas)
