//========================================================================
// GLFW - An OpenGL framework
// Platform:    Any
// API version: 2.7
// WWW:         http://www.glfw.org/
//------------------------------------------------------------------------
// Copyright (c) 2002-2006 Marcus Geelnard
// Copyright (c) 2006-2010 Camilla Berglund <elmindreda@elmindreda.org>
//
// This software is provided 'as-is', without any express or implied
// warranty. In no event will the authors be held liable for any damages
// arising from the use of this software.
//
// Permission is granted to anyone to use this software for any purpose,
// including commercial applications, and to alter it and redistribute it
// freely, subject to the following restrictions:
//
// 1. The origin of this software must not be misrepresented; you must not
//    claim that you wrote the original software. If you use this software
//    in a product, an acknowledgment in the product documentation would
//    be appreciated but is not required.
//
// 2. Altered source versions must be plainly marked as such, and must not
//    be misrepresented as being the original software.
//
// 3. This notice may not be removed or altered from any source
//    distribution.
//
//========================================================================

#include "internal.h"


//========================================================================
// Return key state
//========================================================================

GLFWAPI int GLFWAPIENTRY glfwGetKey( int key )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return GLFW_RELEASE;
    }

    // Is it a valid key?
    if( key < 0 || key > GLFW_KEY_LAST )
    {
        return GLFW_RELEASE;
    }

    if( _glfwInput.Key[ key ] == GLFW_STICK )
    {
        // Sticky mode: release key now
        _glfwInput.Key[ key ] = GLFW_RELEASE;
        return GLFW_PRESS;
    }

    return (int) _glfwInput.Key[ key ];
}


//========================================================================
// Return mouse button state
//========================================================================

GLFWAPI int GLFWAPIENTRY glfwGetMouseButton( int button )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return GLFW_RELEASE;
    }

    // Is it a valid mouse button?
    if( button < 0 || button > GLFW_MOUSE_BUTTON_LAST )
    {
        return GLFW_RELEASE;
    }

    if( _glfwInput.MouseButton[ button ] == GLFW_STICK )
    {
        // Sticky mode: release mouse button now
        _glfwInput.MouseButton[ button ] = GLFW_RELEASE;
        return GLFW_PRESS;
    }

    return (int) _glfwInput.MouseButton[ button ];
}


//========================================================================
// Return mouse cursor position
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwGetMousePos( int *xpos, int *ypos )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Return mouse position
    if( xpos != NULL )
    {
        *xpos = _glfwInput.MousePosX;
    }
    if( ypos != NULL )
    {
        *ypos = _glfwInput.MousePosY;
    }
}


//========================================================================
// Sets the mouse cursor position
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwSetMousePos( int xpos, int ypos )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Don't do anything if the mouse position did not change
    if( xpos == _glfwInput.MousePosX && ypos == _glfwInput.MousePosY )
    {
        return;
    }

    // Set GLFW mouse position
    _glfwInput.MousePosX = xpos;
    _glfwInput.MousePosY = ypos;

    // If we have a locked mouse, do not change cursor position
    if( _glfwWin.mouseLock )
    {
        return;
    }

    // Update physical cursor position
    _glfwPlatformSetMouseCursorPos( xpos, ypos );
}


//========================================================================
// Return mouse wheel position
//========================================================================

GLFWAPI int GLFWAPIENTRY glfwGetMouseWheel( void )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return 0;
    }

    // Return mouse wheel position
    return _glfwInput.WheelPos;
}


//========================================================================
// Set mouse wheel position
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwSetMouseWheel( int pos )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Set mouse wheel position
    _glfwInput.WheelPos = pos;
}


//========================================================================
// Set callback function for keyboard input
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwSetKeyCallback( GLFWkeyfun cbfun )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Set callback function
    _glfwWin.keyCallback = cbfun;
}


//========================================================================
// Set callback function for character input
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwSetCharCallback( GLFWcharfun cbfun )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Set callback function
    _glfwWin.charCallback = cbfun;
}

//========================================================================
// Set callback function for uncommitted/marked text input
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwSetMarkedTextCallback( GLFWmarkedtextfun cbfun )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Set callback function
    _glfwWin.markedTextCallback = cbfun;
}

GLFWAPI void GLFWAPIENTRY glfwShowKeyboard( int show, int type, int auto_close )
{
    _glfwShowKeyboard(show, type, auto_close);
}

GLFWAPI void GLFWAPIENTRY glfwResetKeyboard( void )
{
    _glfwResetKeyboard();
}

//========================================================================
// Set callback function for mouse clicks
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwSetMouseButtonCallback( GLFWmousebuttonfun cbfun )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Set callback function
    _glfwWin.mouseButtonCallback = cbfun;
}


//========================================================================
// Set callback function for mouse moves
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwSetMousePosCallback( GLFWmouseposfun cbfun )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Set callback function
    _glfwWin.mousePosCallback = cbfun;

    // Call the callback function to let the application know the current
    // mouse position
    if( cbfun )
    {
        cbfun( _glfwInput.MousePosX, _glfwInput.MousePosY );
    }
}


//========================================================================
// Set callback function for mouse wheel
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwSetMouseWheelCallback( GLFWmousewheelfun cbfun )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Set callback function
    _glfwWin.mouseWheelCallback = cbfun;

    // Call the callback function to let the application know the current
    // mouse wheel position
    if( cbfun )
    {
        cbfun( _glfwInput.WheelPos );
    }
}

//========================================================================
// Set callback function for touch
//========================================================================

GLFWAPI void GLFWAPIENTRY glfwSetTouchCallback( GLFWtouchfun cbfun )
{
    if( !_glfwInitialized || !_glfwWin.opened )
    {
        return;
    }

    // Set callback function
    _glfwWin.touchCallback = cbfun;

    if( cbfun )
    {
        cbfun( _glfwInput.Touch, _glfwInput.TouchCount );
    }
}

GLFWAPI int GLFWAPIENTRY glfwGetAcceleration(float* x, float* y, float* z)
{
    return _glfwPlatformGetAcceleration(x, y, z);
}

GLFWAPI int GLFWAPIENTRY glfwGetTouch(GLFWTouch* touch, int count, int* out_count)
{
    int i, j;
    int n = _glfwInput.TouchCount;
    if (count < n)
        n = count;

    *out_count = n;

    for (i = 0; i < n; ++i) {
        touch[i] = _glfwInput.Touch[i];
    }

    // Now to give a view where BEGAN and CANCELLED/ENDED are only
    // seen once for every touch and call to glfwGetTouch, we do an update pass here.
    //
    // This should perhaps be done logically per frame, but since there is auto event polling
    // causing event polling to be 2 times per frame, that is no good location to do it.
    for (i=0;i<_glfwInput.TouchCount;i++) {
        switch (_glfwInput.Touch[i].Phase) {
            case GLFW_PHASE_CANCELLED:
            case GLFW_PHASE_ENDED:
                // These are erased so they do not appear a second time
                _glfwInput.TouchCount--;
                for (j=i;j<_glfwInput.TouchCount;j++)
                    _glfwInput.Touch[j] = _glfwInput.Touch[j+1];
                i--;
                break;
            case GLFW_PHASE_BEGAN:
                _glfwInput.Touch[i].Phase = GLFW_PHASE_STATIONARY;
                break;
            default:
                break;
        }
    }

    return 1;
}

