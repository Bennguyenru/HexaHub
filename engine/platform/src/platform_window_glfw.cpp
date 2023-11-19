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

#include <assert.h>

#include "platform.h"

#include <dmsdk/graphics/glfw/glfw.h>

#include <dlib/platform.h>
#include <dlib/log.h>

namespace dmPlatform
{
    struct Window
    {
        WindowParams                  m_CreateParams;
        WindowAddKeyboardCharCallback m_AddKeyboarCharCallBack;
        void*                         m_AddKeyboarCharCallBackUserData;
        WindowSetMarkedTextCallback   m_SetMarkedTextCallback;
        void*                         m_SetMarkedTextCallbackUserData;
        WindowDeviceChangedCallback   m_DeviceChangedCallback;
        void*                         m_DeviceChangedCallbackUserData;
        int32_t                       m_Width;
        int32_t                       m_Height;
        uint32_t                      m_WindowOpened          : 1;
        uint32_t                      m_SwapIntervalSupported : 1;
    };

    // Needed by glfw2.7
    static Window* g_Window = 0;

    static void OnWindowResize(int width, int height)
    {
        assert(g_Window);
        g_Window->m_Width  = (uint32_t) width;
        g_Window->m_Height = (uint32_t) height;
        if (g_Window->m_CreateParams.m_ResizeCallback != 0x0)
        {
            g_Window->m_CreateParams.m_ResizeCallback(g_Window->m_CreateParams.m_ResizeCallbackUserData, (uint32_t)width, (uint32_t)height);
        }
    }

    static int OnWindowClose()
    {
        assert(g_Window);
        if (g_Window->m_CreateParams.m_CloseCallback != 0x0)
        {
            return g_Window->m_CreateParams.m_CloseCallback(g_Window->m_CreateParams.m_CloseCallbackUserData);
        }
        // Close by default
        return 1;
    }

    static void OnWindowFocus(int focus)
    {
        assert(g_Window);
        if (g_Window->m_CreateParams.m_FocusCallback != 0x0)
        {
            g_Window->m_CreateParams.m_FocusCallback(g_Window->m_CreateParams.m_FocusCallbackUserData, focus);
        }
    }

    static void OnWindowIconify(int iconify)
    {
        assert(g_Window);
        if (g_Window->m_CreateParams.m_IconifyCallback != 0x0)
        {
            g_Window->m_CreateParams.m_IconifyCallback(g_Window->m_CreateParams.m_IconifyCallbackUserData, iconify);
        }
    }

    static void OnAddCharacterCallback(int chr, int _)
    {
        if (g_Window->m_AddKeyboarCharCallBack)
        {
            g_Window->m_AddKeyboarCharCallBack(g_Window->m_AddKeyboarCharCallBackUserData, chr);
        }
    }

    static void OnMarkedTextCallback(char* text)
    {
        if (g_Window->m_SetMarkedTextCallback)
        {
            g_Window->m_SetMarkedTextCallback(g_Window->m_SetMarkedTextCallbackUserData, text);
        }
    }

    static void OnDeviceChangedCallback(int status)
    {
        if (g_Window->m_DeviceChangedCallback)
        {
            g_Window->m_DeviceChangedCallback(g_Window->m_DeviceChangedCallbackUserData, status);
        }
    }

    HWindow NewWindow(const WindowParams& params)
    {
        if (g_Window == 0)
        {
            Window* wnd = new Window;
            memset(wnd, 0, sizeof(Window));

            wnd->m_CreateParams = params;

            if (glfwInit() == GL_FALSE)
            {
                dmLogError("Could not initialize glfw.");
                return 0;
            }

            if (glfwSetCharCallback(OnAddCharacterCallback) == 0)
            {
                dmLogFatal("could not set glfw char callback.");
            }
            if (glfwSetMarkedTextCallback(OnMarkedTextCallback) == 0)
            {
                dmLogFatal("could not set glfw marked text callback.");
            }
            if (glfwSetDeviceChangedCallback(OnDeviceChangedCallback) == 0)
            {
                dmLogFatal("coult not set glfw gamepad connection callback.");
            }

            g_Window = wnd;

            return wnd;
        }

        return 0;
    }

    PlatformResult OpenWindowOpenGL(Window* wnd)
    {
        if (wnd->m_CreateParams.m_HighDPI)
        {
            glfwOpenWindowHint(GLFW_WINDOW_HIGH_DPI, 1);
        }

        glfwOpenWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        glfwOpenWindowHint(GLFW_FSAA_SAMPLES, wnd->m_CreateParams.m_Samples);

#if defined(ANDROID)
        // Seems to work fine anyway without any hints
        // which is good, since we want to fallback from OpenGLES 3 to 2
#elif defined(__linux__)
        glfwOpenWindowHint(GLFW_OPENGL_VERSION_MAJOR, 3);
        glfwOpenWindowHint(GLFW_OPENGL_VERSION_MINOR, 3);
#elif defined(_WIN32)
        glfwOpenWindowHint(GLFW_OPENGL_VERSION_MAJOR, 3);
        glfwOpenWindowHint(GLFW_OPENGL_VERSION_MINOR, 3);
#elif defined(__MACH__)
        glfwOpenWindowHint(GLFW_OPENGL_VERSION_MAJOR, 3);
    #if defined(DM_PLATFORM_IOS)
        glfwOpenWindowHint(GLFW_OPENGL_VERSION_MINOR, 0); // 3.0 on iOS
    #else
        glfwOpenWindowHint(GLFW_OPENGL_VERSION_MINOR, 2); // 3.2 on macOS (actually picks 4.1 anyways)
    #endif
#endif

        bool is_desktop = false;
#if defined(DM_PLATFORM_WINDOWS) || (defined(DM_PLATFORM_LINUX) && !defined(ANDROID)) || defined(DM_PLATFORM_MACOS)
        is_desktop = true;
#endif
        if (is_desktop) {
            glfwOpenWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);
            glfwOpenWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        }

        int mode = GLFW_WINDOW;
        if (wnd->m_CreateParams.m_Fullscreen)
        {
            mode = GLFW_FULLSCREEN;
        }
        if (!glfwOpenWindow(wnd->m_CreateParams.m_Width, wnd->m_CreateParams.m_Height, 8, 8, 8, 8, 32, 8, mode))
        {
            if (is_desktop)
            {
                dmLogWarning("Trying OpenGL 3.1 compat mode");

                // Try a second time, this time without core profile, and lower the minor version.
                // And GLFW clears hints, so we have to set them again.
                if (wnd->m_CreateParams.m_HighDPI) {
                    glfwOpenWindowHint(GLFW_WINDOW_HIGH_DPI, 1);
                }
                glfwOpenWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
                glfwOpenWindowHint(GLFW_FSAA_SAMPLES, wnd->m_CreateParams.m_Samples);

                // We currently cannot go lower since we support shader model 140
                glfwOpenWindowHint(GLFW_OPENGL_VERSION_MAJOR, 3);
                glfwOpenWindowHint(GLFW_OPENGL_VERSION_MINOR, 1);

                glfwOpenWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);

                if (!glfwOpenWindow(wnd->m_CreateParams.m_Width, wnd->m_CreateParams.m_Height, 8, 8, 8, 8, 32, 8, mode))
                {
                    return PLATFORM_RESULT_WINDOW_OPEN_ERROR;
                }
            }
            else
            {
                return PLATFORM_RESULT_WINDOW_OPEN_ERROR;
            }
        }

    #if !defined(DM_PLATFORM_WEB)
        glfwSetWindowTitle(wnd->m_CreateParams.m_Title);
    #endif

        glfwSetWindowBackgroundColor(wnd->m_CreateParams.m_BackgroundColor);
        glfwSetWindowSizeCallback(OnWindowResize);
        glfwSetWindowCloseCallback(OnWindowClose);
        glfwSetWindowFocusCallback(OnWindowFocus);
        glfwSetWindowIconifyCallback(OnWindowIconify);
        glfwSwapInterval(1);
        glfwGetWindowSize(&wnd->m_Width, &wnd->m_Height);

        wnd->m_WindowOpened          = 1;
        wnd->m_SwapIntervalSupported = 1;

        return PLATFORM_RESULT_OK;
    }

    PlatformResult OpenWindowVulkan(Window* wnd)
    {
        glfwOpenWindowHint(GLFW_CLIENT_API,   GLFW_NO_API);
        glfwOpenWindowHint(GLFW_FSAA_SAMPLES, wnd->m_CreateParams.m_Samples);

        int mode = wnd->m_CreateParams.m_Fullscreen ? GLFW_FULLSCREEN : GLFW_WINDOW;

        if (!glfwOpenWindow(wnd->m_CreateParams.m_Width, wnd->m_CreateParams.m_Height, 8, 8, 8, 8, 32, 8, mode))
        {
            return PLATFORM_RESULT_WINDOW_OPEN_ERROR;
        }

        glfwSetWindowTitle(wnd->m_CreateParams.m_Title);
        glfwSetWindowBackgroundColor(wnd->m_CreateParams.m_BackgroundColor);
        glfwSetWindowSizeCallback(OnWindowResize);
        glfwSetWindowCloseCallback(OnWindowClose);
        glfwSetWindowFocusCallback(OnWindowFocus);
        glfwSetWindowIconifyCallback(OnWindowIconify);

        wnd->m_WindowOpened = 1;

        return PLATFORM_RESULT_OK;
    }

    PlatformResult OpenWindow(HWindow window)
    {
        if (window->m_WindowOpened)
        {
            return PLATFORM_RESULT_WINDOW_ALREADY_OPENED;
        }

        switch(window->m_CreateParams.m_GraphicsApi)
        {
            case PLATFORM_GRAPHICS_API_OPENGL: return OpenWindowOpenGL(window);
            case PLATFORM_GRAPHICS_API_VULKAN: return OpenWindowVulkan(window);
            default: assert(0);
        }

        return PLATFORM_RESULT_WINDOW_OPEN_ERROR;
    }

    void CloseWindow(HWindow window)
    {
        glfwCloseWindow();
    }

    void DeleteWindow(HWindow window)
    {
        delete window;
        g_Window = 0;

        glfwTerminate();
    }

    void SetWindowSize(HWindow window, uint32_t width, uint32_t height)
    {
        glfwSetWindowSize((int)width, (int)height);
        int window_width, window_height;
        glfwGetWindowSize(&window_width, &window_height);
        window->m_Width  = window_width;
        window->m_Height = window_height;

        // The callback is not called from glfw when the size is set manually
        if (window->m_CreateParams.m_ResizeCallback)
        {
            window->m_CreateParams.m_ResizeCallback(window->m_CreateParams.m_ResizeCallbackUserData, window_width, window_height);
        }
    }

    uint32_t GetWindowWidth(HWindow window)
    {
        return (uint32_t) window->m_Width;
    }
    uint32_t GetWindowHeight(HWindow window)
    {
        return (uint32_t) window->m_Height;
    }

    static int WindowStateToGLFW(WindowState state)
    {
        switch(state)
        {
            case WINDOW_STATE_OPENED:           return 0x00020001;
            case WINDOW_STATE_ACTIVE:           return 0x00020002;
            case WINDOW_STATE_ICONIFIED:        return 0x00020003;
            case WINDOW_STATE_ACCELERATED:      return 0x00020004;
            case WINDOW_STATE_RED_BITS:         return 0x00020005;
            case WINDOW_STATE_GREEN_BITS:       return 0x00020006;
            case WINDOW_STATE_BLUE_BITS:        return 0x00020007;
            case WINDOW_STATE_ALPHA_BITS:       return 0x00020008;
            case WINDOW_STATE_DEPTH_BITS:       return 0x00020009;
            case WINDOW_STATE_STENCIL_BITS:     return 0x0002000A;
            case WINDOW_STATE_REFRESH_RATE:     return 0x0002000B;
            case WINDOW_STATE_ACCUM_RED_BITS:   return 0x0002000C;
            case WINDOW_STATE_ACCUM_GREEN_BITS: return 0x0002000D;
            case WINDOW_STATE_ACCUM_BLUE_BITS:  return 0x0002000E;
            case WINDOW_STATE_ACCUM_ALPHA_BITS: return 0x0002000F;
            case WINDOW_STATE_AUX_BUFFERS:      return 0x00020010;
            case WINDOW_STATE_STEREO:           return 0x00020011;
            case WINDOW_STATE_WINDOW_NO_RESIZE: return 0x00020012;
            case WINDOW_STATE_FSAA_SAMPLES:     return 0x00020013;
            default:assert(0);break;
        }
        return -1;
    }

    uint32_t GetWindowState(HWindow window, WindowState state)
    {
        // JG: Not sure this is needed, or if it's already supported via the glfwGetWindowParam fn
        if (state == WINDOW_STATE_REFRESH_RATE)
        {
            return glfwGetWindowRefreshRate();
        }
        return window->m_WindowOpened ? glfwGetWindowParam(WindowStateToGLFW(state)) : 0;
    }

    void IconifyWindow(HWindow window)
    {
        if (window->m_WindowOpened)
        {
            glfwIconifyWindow();
        }
    }

    float GetDisplayScaleFactor(HWindow window)
    {
        return glfwGetDisplayScaleFactor();
    }

    void SetSwapInterval(HWindow window, uint32_t swap_interval)
    {
        if (window->m_SwapIntervalSupported)
        {
            glfwSwapInterval(swap_interval);
        }
    }

    void SetKeyboardCharCallback(HWindow window, WindowAddKeyboardCharCallback cb, void* user_data)
    {
        window->m_AddKeyboarCharCallBack         = cb;
        window->m_AddKeyboarCharCallBackUserData = user_data;
    }

    void SetKeyboardMarkedTextCallback(HWindow window, WindowSetMarkedTextCallback cb, void* user_data)
    {
        window->m_SetMarkedTextCallback         = cb;
        window->m_SetMarkedTextCallbackUserData = user_data;
    }

    void SetKeyboardDeviceChangedCallback(HWindow window, WindowDeviceChangedCallback cb, void* user_data)
    {
        window->m_DeviceChangedCallback         = cb;
        window->m_DeviceChangedCallbackUserData = user_data;
    }
}
