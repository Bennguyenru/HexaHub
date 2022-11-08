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

#include "internal.h"

int _glfwPlatformGetJoystickParam( int joy, int param )
{
    return 0;
}
int _glfwPlatformGetJoystickPos( int joy, float *pos, int numaxes )
{
    return 0;
}
int _glfwPlatformGetJoystickButtons( int joy, unsigned char *buttons, int numbuttons )
{
    return 0;
}
int _glfwPlatformGetJoystickDeviceId( int joy, char** device_id )
{
    (void) joy;
    (void) device_id;
    return GL_FALSE;
}

int _glfwPlatformGetJoystickAlternativeDeviceId( int joy, char** alternative_device_id )
{
    return _glfwPlatformGetJoystickDeviceId(joy, alternative_device_id);
}

int _glfwPlatformGetJoystickGenericDeviceId( int joy, char** generic_device_id )
{
    return _glfwPlatformGetJoystickDeviceId(joy, generic_device_id);
}
