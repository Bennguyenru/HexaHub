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

#include <jc_test/jc_test.h>

#include "res_lua.h"

TEST(LuaTest, TestPatchBytesUpTo255)
{
    uint8_t bytecode32[] = { 0x1b, 0x4c, 0x4a, 0x02, 0x00, 0x11, 0x40, 0x6d, 0x61, 0x69, 0x6e, 0x2f, 0x6d, 0x61, 0x69, 0x6e, 0x2e, 0x73, 0x63, 0x72, 0x69, 0x70, 0x74, 0x32, 0x00, 0x01, 0x03, 0x00, 0x02, 0x00, 0x04, 0x0c, 0x01, 0x02, 0x36, 0x01, 0x00, 0x00, 0x27, 0x02, 0x01, 0x00, 0x42, 0x01, 0x02, 0x01, 0x4b, 0x00, 0x01, 0x00, 0x0a, 0x68, 0x65, 0x6c, 0x6c, 0x6f, 0x0a, 0x70, 0x72, 0x69, 0x6e, 0x74, 0x01, 0x01, 0x01, 0x02, 0x73, 0x65, 0x6c, 0x66, 0x00, 0x00, 0x05, 0x00, 0x20, 0x03, 0x00, 0x01, 0x00, 0x02, 0x00, 0x03, 0x04, 0x00, 0x04, 0x33, 0x00, 0x00, 0x00, 0x37, 0x00, 0x01, 0x00, 0x4b, 0x00, 0x01, 0x00, 0x09, 0x69, 0x6e, 0x69, 0x74, 0x00, 0x03, 0x01, 0x03, 0x00, 0x00 };
    uint8_t bytecode64[] = { 0x1b, 0x4c, 0x4a, 0x02, 0x08, 0x11, 0x40, 0x6d, 0x61, 0x69, 0x6e, 0x2f, 0x6d, 0x61, 0x69, 0x6e, 0x2e, 0x73, 0x63, 0x72, 0x69, 0x70, 0x74, 0x32, 0x00, 0x01, 0x04, 0x00, 0x02, 0x00, 0x04, 0x0c, 0x01, 0x02, 0x36, 0x01, 0x00, 0x00, 0x27, 0x03, 0x01, 0x00, 0x42, 0x01, 0x02, 0x01, 0x4b, 0x00, 0x01, 0x00, 0x0a, 0x68, 0x65, 0x6c, 0x6c, 0x6f, 0x0a, 0x70, 0x72, 0x69, 0x6e, 0x74, 0x01, 0x01, 0x01, 0x02, 0x73, 0x65, 0x6c, 0x66, 0x00, 0x00, 0x05, 0x00, 0x20, 0x03, 0x00, 0x01, 0x00, 0x02, 0x00, 0x03, 0x04, 0x00, 0x04, 0x33, 0x00, 0x00, 0x00, 0x37, 0x00, 0x01, 0x00, 0x4b, 0x00, 0x01, 0x00, 0x09, 0x69, 0x6e, 0x69, 0x74, 0x00, 0x03, 0x01, 0x03, 0x00, 0x00 };
    uint8_t delta[]      = { 0x04, 0x01, 0x00, 0x1a, 0x01, 0x03, 0x27, 0x01, 0x02 };

    dmLuaDDF::LuaSource source;
    source.m_Bytecode.m_Data = bytecode64;
    source.m_Bytecode.m_Count = sizeof(bytecode64);
    source.m_Delta.m_Data = delta;
    source.m_Delta.m_Count = sizeof(delta);

    dmGameObject::PatchBytes(source.m_Bytecode.m_Data, source.m_Bytecode.m_Count, source.m_Delta.m_Data, source.m_Delta.m_Count);

    for(int i=0; i<source.m_Bytecode.m_Count; i++) {
        uint8_t a = source.m_Bytecode.m_Data[i];
        uint8_t b = bytecode32[i];
        ASSERT_EQ(a, b);
    }
}

TEST(LuaTest, TestPatchBytesUpTo65535)
{
    uint8_t bytecode32[] = { 0x1b, 0x4c, 0x4a, 0x02, 0x00, 0x13, 0x40, 0x6d, 0x65, 0x74, 0x72, 0x69, 0x63, 0x73, 0x2f, 0x6d, 0x65, 0x6d, 0x2e, 0x73, 0x63, 0x72, 0x69, 0x70, 0x74, 0x76, 0x00, 0x01, 0x05, 0x01, 0x05, 0x00, 0x0a, 0x16, 0x06, 0x02, 0x2d, 0x01, 0x00, 0x00, 0x39, 0x01, 0x01, 0x01, 0x2b, 0x02, 0x00, 0x00, 0x36, 0x03, 0x02, 0x00, 0x39, 0x03, 0x03, 0x03, 0x42, 0x03, 0x01, 0x02, 0x39, 0x04, 0x04, 0x00, 0x42, 0x01, 0x04, 0x02, 0x3d, 0x01, 0x00, 0x00, 0x4b, 0x00, 0x01, 0x00, 0x00, 0xc0, 0x0a, 0x63, 0x6f, 0x6c, 0x6f, 0x72, 0x17, 0x67, 0x65, 0x74, 0x5f, 0x77, 0x6f, 0x72, 0x6c, 0x64, 0x5f, 0x70, 0x6f, 0x73, 0x69, 0x74, 0x69, 0x6f, 0x6e, 0x07, 0x67, 0x6f, 0x0b, 0x63, 0x72, 0x65, 0x61, 0x74, 0x65, 0x0d, 0x69, 0x6e, 0x73, 0x74, 0x61, 0x6e, 0x63, 0x65, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x6d, 0x65, 0x6d, 0x00, 0x73, 0x65, 0x6c, 0x66, 0x00, 0x00, 0x0b, 0x00, 0x63, 0x00, 0x02, 0x03, 0x00, 0x04, 0x00, 0x0a, 0x17, 0x0a, 0x05, 0x39, 0x02, 0x00, 0x00, 0x39, 0x02, 0x01, 0x02, 0x42, 0x02, 0x01, 0x01, 0x39, 0x02, 0x02, 0x00, 0x0f, 0x00, 0x02, 0x00, 0x58, 0x03, 0x03, 0x80, 0x39, 0x02, 0x00, 0x00, 0x39, 0x02, 0x03, 0x02, 0x42, 0x02, 0x01, 0x01, 0x4b, 0x00, 0x01, 0x00, 0x09, 0x64, 0x72, 0x61, 0x77, 0x09, 0x73, 0x68, 0x6f, 0x77, 0x0b, 0x75, 0x70, 0x64, 0x61, 0x74, 0x65, 0x0d, 0x69, 0x6e, 0x73, 0x74, 0x61, 0x6e, 0x63, 0x65, 0x01, 0x01, 0x01, 0x02, 0x02, 0x02, 0x03, 0x03, 0x03, 0x05, 0x73, 0x65, 0x6c, 0x66, 0x00, 0x00, 0x0b, 0x64, 0x74, 0x00, 0x00, 0x0b, 0x00, 0x60, 0x03, 0x00, 0x02, 0x00, 0x06, 0x00, 0x09, 0x10, 0x00, 0x10, 0x36, 0x00, 0x00, 0x00, 0x27, 0x01, 0x01, 0x00, 0x42, 0x00, 0x02, 0x02, 0x33, 0x01, 0x02, 0x00, 0x37, 0x01, 0x03, 0x00, 0x33, 0x01, 0x04, 0x00, 0x37, 0x01, 0x05, 0x00, 0x32, 0x00, 0x00, 0x80, 0x4b, 0x00, 0x01, 0x00, 0x0b, 0x75, 0x70, 0x64, 0x61, 0x74, 0x65, 0x00, 0x09, 0x69, 0x6e, 0x69, 0x74, 0x00, 0x10, 0x6d, 0x65, 0x74, 0x72, 0x69, 0x63, 0x73, 0x2e, 0x6d, 0x65, 0x6d, 0x0c, 0x72, 0x65, 0x71, 0x75, 0x69, 0x72, 0x65, 0x04, 0x04, 0x04, 0x08, 0x06, 0x0f, 0x0a, 0x0f, 0x0f, 0x6d, 0x65, 0x6d, 0x00, 0x04, 0x06, 0x00, 0x00 };
    uint8_t bytecode64[] = { 0x1b, 0x4c, 0x4a, 0x02, 0x08, 0x13, 0x40, 0x6d, 0x65, 0x74, 0x72, 0x69, 0x63, 0x73, 0x2f, 0x6d, 0x65, 0x6d, 0x2e, 0x73, 0x63, 0x72, 0x69, 0x70, 0x74, 0x76, 0x00, 0x01, 0x06, 0x01, 0x05, 0x00, 0x0a, 0x16, 0x06, 0x02, 0x2d, 0x01, 0x00, 0x00, 0x39, 0x01, 0x01, 0x01, 0x2b, 0x03, 0x00, 0x00, 0x36, 0x04, 0x02, 0x00, 0x39, 0x04, 0x03, 0x04, 0x42, 0x04, 0x01, 0x02, 0x39, 0x05, 0x04, 0x00, 0x42, 0x01, 0x04, 0x02, 0x3d, 0x01, 0x00, 0x00, 0x4b, 0x00, 0x01, 0x00, 0x00, 0xc0, 0x0a, 0x63, 0x6f, 0x6c, 0x6f, 0x72, 0x17, 0x67, 0x65, 0x74, 0x5f, 0x77, 0x6f, 0x72, 0x6c, 0x64, 0x5f, 0x70, 0x6f, 0x73, 0x69, 0x74, 0x69, 0x6f, 0x6e, 0x07, 0x67, 0x6f, 0x0b, 0x63, 0x72, 0x65, 0x61, 0x74, 0x65, 0x0d, 0x69, 0x6e, 0x73, 0x74, 0x61, 0x6e, 0x63, 0x65, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x6d, 0x65, 0x6d, 0x00, 0x73, 0x65, 0x6c, 0x66, 0x00, 0x00, 0x0b, 0x00, 0x63, 0x00, 0x02, 0x04, 0x00, 0x04, 0x00, 0x0a, 0x17, 0x0a, 0x05, 0x39, 0x02, 0x00, 0x00, 0x39, 0x02, 0x01, 0x02, 0x42, 0x02, 0x01, 0x01, 0x39, 0x02, 0x02, 0x00, 0x0f, 0x00, 0x02, 0x00, 0x58, 0x03, 0x03, 0x80, 0x39, 0x02, 0x00, 0x00, 0x39, 0x02, 0x03, 0x02, 0x42, 0x02, 0x01, 0x01, 0x4b, 0x00, 0x01, 0x00, 0x09, 0x64, 0x72, 0x61, 0x77, 0x09, 0x73, 0x68, 0x6f, 0x77, 0x0b, 0x75, 0x70, 0x64, 0x61, 0x74, 0x65, 0x0d, 0x69, 0x6e, 0x73, 0x74, 0x61, 0x6e, 0x63, 0x65, 0x01, 0x01, 0x01, 0x02, 0x02, 0x02, 0x03, 0x03, 0x03, 0x05, 0x73, 0x65, 0x6c, 0x66, 0x00, 0x00, 0x0b, 0x64, 0x74, 0x00, 0x00, 0x0b, 0x00, 0x60, 0x03, 0x00, 0x03, 0x00, 0x06, 0x00, 0x09, 0x10, 0x00, 0x10, 0x36, 0x00, 0x00, 0x00, 0x27, 0x02, 0x01, 0x00, 0x42, 0x00, 0x02, 0x02, 0x33, 0x01, 0x02, 0x00, 0x37, 0x01, 0x03, 0x00, 0x33, 0x01, 0x04, 0x00, 0x37, 0x01, 0x05, 0x00, 0x32, 0x00, 0x00, 0x80, 0x4b, 0x00, 0x01, 0x00, 0x0b, 0x75, 0x70, 0x64, 0x61, 0x74, 0x65, 0x00, 0x09, 0x69, 0x6e, 0x69, 0x74, 0x00, 0x10, 0x6d, 0x65, 0x74, 0x72, 0x69, 0x63, 0x73, 0x2e, 0x6d, 0x65, 0x6d, 0x0c, 0x72, 0x65, 0x71, 0x75, 0x69, 0x72, 0x65, 0x04, 0x04, 0x04, 0x08, 0x06, 0x0f, 0x0a, 0x0f, 0x0f, 0x6d, 0x65, 0x6d, 0x00, 0x04, 0x06, 0x00, 0x00 };
    uint8_t delta[]      = { 0x04, 0x00, 0x01, 0x00, 0x1c, 0x00, 0x01, 0x05, 0x2d, 0x00, 0x01, 0x02, 0x31, 0x00, 0x01, 0x03, 0x35, 0x00, 0x01, 0x03, 0x37, 0x00, 0x01, 0x03, 0x39, 0x00, 0x01, 0x03, 0x3d, 0x00, 0x01, 0x04, 0x93, 0x00, 0x01, 0x03, 0xf7, 0x00, 0x01, 0x02, 0x04, 0x01, 0x01, 0x01 };

    dmLuaDDF::LuaSource source;
    source.m_Bytecode.m_Data = bytecode64;
    source.m_Bytecode.m_Count = sizeof(bytecode64);
    source.m_Delta.m_Data = delta;
    source.m_Delta.m_Count = sizeof(delta);

    dmGameObject::PatchBytes(source.m_Bytecode.m_Data, source.m_Bytecode.m_Count, source.m_Delta.m_Data, source.m_Delta.m_Count);

    for(int i=0; i<source.m_Bytecode.m_Count; i++) {
        uint8_t a = source.m_Bytecode.m_Data[i];
        uint8_t b = bytecode32[i];
        ASSERT_EQ(a, b);
    }
}
