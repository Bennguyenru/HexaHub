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

#include "android_util.h"

#include "android_log.h"

#include <pthread.h>

typedef struct EglAttribSetting_t {
    EGLint m_Attribute;
    EGLint m_Value;
} EglAttribSetting;

static int add_egl_attrib(EglAttribSetting* buffer, int size, int offset, const EglAttribSetting* setting)
{
    int result;
    if (0 <= offset && size > offset)
    {
        buffer[offset] = *setting;
        result = offset + 1;
    }
    else
    {
        LOGV("Exhausted egl attrib buffer");
        result = -1;
    }
    return result;
}

static int add_egl_base_attrib(EglAttribSetting* buffer, int size, int offset)
{
    const EglAttribSetting surface = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT};
    return add_egl_attrib(buffer, size, offset, &surface);
}

static int add_egl_colour_attrib(EglAttribSetting* buffer, int size, int offset)
{
    const EglAttribSetting colour[] = {
        {EGL_BLUE_SIZE, 8},
        {EGL_GREEN_SIZE, 8},
        {EGL_RED_SIZE, 8}
    };
    int i;
    const int num_entries = sizeof(colour) / sizeof(EglAttribSetting);
    for (i=0; i<num_entries; ++i)
    {
        offset = add_egl_attrib(buffer, size, offset, &colour[i]);
        if (0 > offset)
            break;
    }
    return offset;
}

static int add_egl_depth_attrib(EglAttribSetting* buffer, int size, int offset)
{
    const EglAttribSetting depth = {EGL_DEPTH_SIZE, 16};
    return add_egl_attrib(buffer, size, offset, &depth);
}

static int add_egl_stencil_attrib(EglAttribSetting* buffer, int size, int offset)
{
    // TODO: Tegra support.
    const EglAttribSetting stencil = {EGL_STENCIL_SIZE, 8};
    return add_egl_attrib(buffer, size, offset, &stencil);
}

static int add_egl_concluding_attrib(EglAttribSetting* buffer, int size, int offset)
{
    const EglAttribSetting conclusion[] = {
        // NOTE: In order to run on emulator
        // EGL_CONFORMANT must not be specified
        {EGL_CONFORMANT, EGL_OPENGL_ES2_BIT},
        {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT},
        {EGL_NONE, 0}
    };
    int i = 0;
    const int num_entries = sizeof(conclusion) / sizeof(EglAttribSetting);
    for (i=0; i<num_entries; ++i) {
        offset = add_egl_attrib(buffer, size, offset, &conclusion[i]);
        if (0 > offset)
            break;
    }
    return offset;
}

static EGLint choose_egl_config(EGLDisplay display, EGLConfig* config)
{
    EGLint result = 0;
    const int max_settings = 9;
    EglAttribSetting settings[max_settings];

    int offset = 0;
    int stencil_offset;

    offset = add_egl_base_attrib((EglAttribSetting*)&settings, max_settings, offset);
    offset = add_egl_colour_attrib((EglAttribSetting*)&settings, max_settings, offset);
    offset = add_egl_depth_attrib((EglAttribSetting*)&settings, max_settings, offset);
    stencil_offset = offset;
    offset = add_egl_stencil_attrib((EglAttribSetting*)&settings, max_settings, offset);
    offset = add_egl_concluding_attrib((EglAttribSetting*)&settings, max_settings, offset);

    eglChooseConfig(display, (const EGLint *)&settings[0], config, 1, &result);
    CHECK_EGL_ERROR
    if (0 == result)
    {
        // Something along this sort of line when adding Tegra support?
        LOGV("egl config choice failed - removing stencil");
        add_egl_concluding_attrib((EglAttribSetting*)&settings, max_settings, stencil_offset);
        eglChooseConfig(display, (const EGLint *)&settings[0], config, 1, &result);
        CHECK_EGL_ERROR
    }

    return result;
}

int init_gl(_GLFWwin_android* win)
{
    LOGV("init_gl");

    /*
     * NOTE: The example simple_gles2 doesn't work with EGL_CONTEXT_CLIENT_VERSION
     * set to 2 in emulator. Might work on real device though
     */
    const EGLint contextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2, // GLES 2.x support
            EGL_NONE, // terminator
    };

    EGLint numConfigs;
    EGLConfig config;
    EGLContext context;
    EGLint format;

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    CHECK_EGL_ERROR
    eglInitialize(display, 0, 0);
    CHECK_EGL_ERROR


    numConfigs = choose_egl_config(display, &config);
    // No configs found, error out
    if (numConfigs == 0)
    {
        return 0;
    }

    eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &format);
    CHECK_EGL_ERROR
    ANativeWindow_setBuffersGeometry(win->app->window, 0, 0, format);

    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    CHECK_EGL_ERROR

    win->display = display;
    win->context = context;
    win->config = config;


    {
        // create aux context if possible
        LOGV("create_gl_aux_context..");
        win->aux_context = EGL_NO_CONTEXT;
        win->aux_surface = EGL_NO_SURFACE;
        EGLContext aux_context = eglCreateContext(display, config, context, contextAttribs);
        if(aux_context != EGL_NO_CONTEXT)
        {
            EGLint attribpbf[] =
            {
                    EGL_HEIGHT, 1,
                    EGL_WIDTH, 1,
                    EGL_NONE
            };
            EGLSurface aux_surface = eglCreatePbufferSurface(display, config, attribpbf);
            if(aux_surface == EGL_NO_SURFACE)
            {
                eglDestroyContext(display, aux_context);
                LOGV("create_gl_aux_context unsupported");
            }
            else
            {
                win->aux_context = aux_context;
                win->aux_surface = aux_surface;
                LOGV("create_gl_aux_context success");
            }
        }
    }

    create_gl_surface(win);

    return 1;
}

void final_gl(_GLFWwin_android* win)
{
    LOGV("final_gl");
    if (win->display != EGL_NO_DISPLAY)
    {
        if (win->aux_context != EGL_NO_CONTEXT)
        {
            eglDestroySurface(win->display, win->aux_surface);
            eglDestroyContext(win->display, win->aux_context);
        }

        if (win->context != EGL_NO_CONTEXT)
        {
            eglMakeCurrent(win->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroyContext(win->display, win->context);
            CHECK_EGL_ERROR
            win->context = EGL_NO_CONTEXT;
        }
        eglTerminate(win->display);
        CHECK_EGL_ERROR
        win->display = EGL_NO_DISPLAY;
    }
}

void create_gl_surface(_GLFWwin_android* win)
{
    LOGV("create_gl_surface");
    if (win->display != EGL_NO_DISPLAY)
    {
        EGLSurface surface = win->surface;
        if (surface == EGL_NO_SURFACE)
        {
            surface = eglCreateWindowSurface(win->display, win->config, win->app->window, NULL);
            CHECK_EGL_ERROR
        }
        win->surface = surface;
    }
}

void make_current(_GLFWwin_android* win)
{
    EGLBoolean res = eglMakeCurrent(win->display, win->surface, win->surface, win->context);
    assert(res == EGL_TRUE);
    CHECK_EGL_ERROR
}

void update_width_height_info(_GLFWwin* win, _GLFWwin_android* win_android, int force)
{
    EGLint w, h;
    eglQuerySurface(win_android->display, win_android->surface, EGL_WIDTH, &w);
    CHECK_EGL_ERROR
    eglQuerySurface(win_android->display, win_android->surface, EGL_HEIGHT, &h);
    CHECK_EGL_ERROR

    if (force || (win->width != w || win->height != h))
    {
        LOGV("window size changed from %dx%d to %dx%d", _glfwWin.width, _glfwWin.height, w, h);
        if (win->windowSizeCallback)
        {
            win->windowSizeCallback(w, h);
        }
        win->width = w;
        win->height = h;
    }
}

void destroy_gl_surface(_GLFWwin_android* win)
{
    LOGV("destroy_gl_surface");
    if (win->display != EGL_NO_DISPLAY)
    {
        eglMakeCurrent(win->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (win->surface != EGL_NO_SURFACE)
        {
            eglDestroySurface(win->display, win->surface);
            win->surface = EGL_NO_SURFACE;
            CHECK_EGL_ERROR
        }
    }
}

int query_gl_aux_context(_GLFWwin_android* win)
{
    return (win->aux_context == EGL_NO_CONTEXT) ? 0 : 1;
}

void* acquire_gl_aux_context(_GLFWwin_android* win)
{
    if (win->aux_context == EGL_NO_CONTEXT)
    {
        LOGV("Unable to make OpenGL aux context current, is NULL");
        return 0;
    }
    EGLBoolean res = eglMakeCurrent(win->display, win->aux_surface, win->aux_surface, win->aux_context);
    if( res != EGL_TRUE )
    {
        LOGV("Unable to make OpenGL aux context current, eglMakeCurrent failed");
        return 0;
    }
    return win->aux_context;
}


void unacquire_gl_aux_context(_GLFWwin_android* win)
{
    if (win->aux_context == EGL_NO_CONTEXT)
        return;
    eglMakeCurrent(win->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}


