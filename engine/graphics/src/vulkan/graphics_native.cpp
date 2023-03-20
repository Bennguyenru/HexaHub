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

#include <dmsdk/graphics/glfw/glfw.h>
#include <graphics/glfw/glfw_native.h>

#include <dlib/log.h>
#include "../vulkan/graphics_vulkan_defines.h"
#include "../vulkan/graphics_vulkan_private.h"

namespace dmGraphics
{
    static const char*   g_extension_names[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,

    #if defined(VK_USE_PLATFORM_WIN32_KHR)
        VK_KHR_WIN32_SURFACE_EXTENSION_NAME,
    #elif defined(VK_USE_PLATFORM_ANDROID_KHR)
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    #elif defined(VK_USE_PLATFORM_XCB_KHR)
        VK_KHR_XCB_SURFACE_EXTENSION_NAME,
    #elif defined(VK_USE_PLATFORM_MACOS_MVK)
        VK_MVK_MACOS_SURFACE_EXTENSION_NAME,
        VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME,
    #elif defined(VK_USE_PLATFORM_IOS_MVK)
        VK_MVK_IOS_SURFACE_EXTENSION_NAME,
    #elif defined(VK_USE_PLATFORM_METAL_EXT)
        VK_EXT_METAL_SURFACE_EXTENSION_NAME,
    #endif
    };
    static const char* DM_VULKAN_LAYER_VALIDATION   = "VK_LAYER_KHRONOS_validation"; // "VK_LAYER_LUNARG_standard_validation";
    static const char* g_validation_layers[1];
    static const char* g_validation_layer_ext[]     = { VK_EXT_DEBUG_UTILS_EXTENSION_NAME, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME };


    extern VulkanContext* g_VulkanContext;

    const char** GetExtensionNames(uint16_t* num_extensions)
    {
        *num_extensions = sizeof(g_extension_names) / sizeof(g_extension_names[0]);
        return g_extension_names;
    }

    const char** GetValidationLayers(uint16_t* num_layers, bool use_validation, bool use_randerdoc)
    {
        uint16_t count = 0;
        if (use_validation)
        {
            g_validation_layers[count++] = DM_VULKAN_LAYER_VALIDATION;
        }
        *num_layers = count;
        return g_validation_layers;
    }

    const char** GetValidationLayersExt(uint16_t* num_layers)
    {
        *num_layers = sizeof(g_validation_layer_ext) / sizeof(g_validation_layer_ext[0]);
        return g_validation_layer_ext;
    }

    bool NativeInit(const ContextParams& params)
    {
#if ANDROID
        if (!LoadVulkanLibrary())
        {
            dmLogError("Could not load Vulkan functions.");
            return false;
        }
#endif

        if (glfwInit() == 0)
        {
            dmLogError("Could not initialize glfw.");
        }
        return true;
    }

    void NativeExit()
    {
        glfwTerminate();
    }

    void NativeBeginFrame(HContext context)
    {
    }

    void OnWindowResize(int width, int height)
    {
        assert(g_VulkanContext);
        g_VulkanContext->m_WindowWidth  = (uint32_t)width;
        g_VulkanContext->m_WindowHeight = (uint32_t)height;

        SwapChainChanged(g_VulkanContext, &g_VulkanContext->m_WindowWidth, &g_VulkanContext->m_WindowHeight, 0, 0);

        if (g_VulkanContext->m_WindowResizeCallback != 0x0)
        {
            g_VulkanContext->m_WindowResizeCallback(g_VulkanContext->m_WindowResizeCallbackUserData, (uint32_t)width, (uint32_t)height);
        }
    }

    int OnWindowClose()
    {
        assert(g_VulkanContext);
        if (g_VulkanContext->m_WindowCloseCallback != 0x0)
        {
            return g_VulkanContext->m_WindowCloseCallback(g_VulkanContext->m_WindowCloseCallbackUserData);
        }
        return 1;
    }

    void OnWindowFocus(int focus)
    {
        assert(g_VulkanContext);
        if (g_VulkanContext->m_WindowFocusCallback != 0x0)
        {
            g_VulkanContext->m_WindowFocusCallback(g_VulkanContext->m_WindowFocusCallbackUserData, focus);
        }
    }

    uint32_t VulkanGetWindowRefreshRate(HContext context)
    {
        assert(g_VulkanContext == (VulkanContext*) context);
        if (g_VulkanContext->m_WindowOpened)
        {
            return glfwGetWindowRefreshRate();
        }
        else
        {
            return 0;
        }
    }

    WindowResult VulkanOpenWindow(HContext context, WindowParams* params)
    {
        assert(g_VulkanContext->m_WindowSurface == VK_NULL_HANDLE);

        glfwOpenWindowHint(GLFW_CLIENT_API,   GLFW_NO_API);
        glfwOpenWindowHint(GLFW_FSAA_SAMPLES, params->m_Samples);

        int mode = params->m_Fullscreen ? GLFW_FULLSCREEN : GLFW_WINDOW;

        if (!glfwOpenWindow(params->m_Width, params->m_Height, 8, 8, 8, 8, 32, 8, mode))
        {
            return WINDOW_RESULT_WINDOW_OPEN_ERROR;
        }

        if (!InitializeVulkan(context, params))
        {
            return WINDOW_RESULT_WINDOW_OPEN_ERROR;
        }

    #if !defined(__EMSCRIPTEN__)
        glfwSetWindowTitle(params->m_Title);
    #endif

        glfwSetWindowBackgroundColor(params->m_BackgroundColor);

        glfwSetWindowSizeCallback(OnWindowResize);
        glfwSetWindowCloseCallback(OnWindowClose);
        glfwSetWindowFocusCallback(OnWindowFocus);

        g_VulkanContext->m_WindowOpened                  = 1;
        g_VulkanContext->m_Width                         = params->m_Width;
        g_VulkanContext->m_Height                        = params->m_Height;
        g_VulkanContext->m_WindowWidth                   = g_VulkanContext->m_SwapChain->m_ImageExtent.width;
        g_VulkanContext->m_WindowHeight                  = g_VulkanContext->m_SwapChain->m_ImageExtent.height;
        g_VulkanContext->m_WindowResizeCallback          = params->m_ResizeCallback;
        g_VulkanContext->m_WindowResizeCallbackUserData  = params->m_ResizeCallbackUserData;
        g_VulkanContext->m_WindowCloseCallback           = params->m_CloseCallback;
        g_VulkanContext->m_WindowCloseCallbackUserData   = params->m_CloseCallbackUserData;
        g_VulkanContext->m_WindowFocusCallback           = params->m_FocusCallback;
        g_VulkanContext->m_WindowFocusCallbackUserData   = params->m_FocusCallbackUserData;
        g_VulkanContext->m_WindowIconifyCallback         = params->m_IconifyCallback;
        g_VulkanContext->m_WindowIconifyCallbackUserData = params->m_IconifyCallbackUserData;
        g_VulkanContext->m_CurrentRenderTarget           = g_VulkanContext->m_MainRenderTarget;

        return WINDOW_RESULT_OK;
    }

    void VulkanCloseWindow(HContext context)
    {
        assert(g_VulkanContext == (VulkanContext*) context);
        if (g_VulkanContext->m_WindowOpened)
        {
            VkDevice vk_device = g_VulkanContext->m_LogicalDevice.m_Device;

            SynchronizeDevice(vk_device);

            glfwCloseWindow();

            g_VulkanContext->m_PipelineCache.Iterate(DestroyPipelineCacheCb, g_VulkanContext);

            DestroyDeviceBuffer(vk_device, &g_VulkanContext->m_MainTextureDepthStencil.m_DeviceBuffer.m_Handle);
            DestroyTexture(vk_device, &g_VulkanContext->m_MainTextureDepthStencil.m_Handle);
            DestroyTexture(vk_device, &g_VulkanContext->m_DefaultTexture2D->m_Handle);
            DestroyTexture(vk_device, &g_VulkanContext->m_DefaultTexture2DArray->m_Handle);
            DestroyTexture(vk_device, &g_VulkanContext->m_DefaultTextureCubeMap->m_Handle);

            vkDestroyRenderPass(vk_device, g_VulkanContext->m_MainRenderPass, 0);

            vkFreeCommandBuffers(vk_device, g_VulkanContext->m_LogicalDevice.m_CommandPool, g_VulkanContext->m_MainCommandBuffers.Size(), g_VulkanContext->m_MainCommandBuffers.Begin());
            vkFreeCommandBuffers(vk_device, g_VulkanContext->m_LogicalDevice.m_CommandPool, 1, &g_VulkanContext->m_MainCommandBufferUploadHelper);

            for (uint8_t i=0; i < g_VulkanContext->m_MainFrameBuffers.Size(); i++)
            {
                vkDestroyFramebuffer(vk_device, g_VulkanContext->m_MainFrameBuffers[i], 0);
            }

            for (uint8_t i=0; i < g_VulkanContext->m_TextureSamplers.Size(); i++)
            {
                DestroyTextureSampler(vk_device, &g_VulkanContext->m_TextureSamplers[i]);
            }

            for (uint8_t i=0; i < g_VulkanContext->m_MainScratchBuffers.Size(); i++)
            {
                DestroyDeviceBuffer(vk_device, &g_VulkanContext->m_MainScratchBuffers[i].m_DeviceBuffer.m_Handle);
            }

            for (uint8_t i=0; i < g_VulkanContext->m_MainDescriptorAllocators.Size(); i++)
            {
                DestroyDescriptorAllocator(vk_device, &g_VulkanContext->m_MainDescriptorAllocators[i].m_Handle);
            }

            for (uint8_t i=0; i < g_VulkanContext->m_MainCommandBuffers.Size(); i++)
            {
                FlushResourcesToDestroy(vk_device, g_VulkanContext->m_MainResourcesToDestroy[i]);
            }

            for (size_t i = 0; i < g_max_frames_in_flight; i++) {
                FrameResource& frame_resource = g_VulkanContext->m_FrameResources[i];
                vkDestroySemaphore(vk_device, frame_resource.m_RenderFinished, 0);
                vkDestroySemaphore(vk_device, frame_resource.m_ImageAvailable, 0);
                vkDestroyFence(vk_device, frame_resource.m_SubmitFence, 0);
            }

            DestroySwapChain(vk_device, g_VulkanContext->m_SwapChain);
            DestroyLogicalDevice(&g_VulkanContext->m_LogicalDevice);
            DestroyPhysicalDevice(&g_VulkanContext->m_PhysicalDevice);

            vkDestroySurfaceKHR(g_VulkanContext->m_Instance, g_VulkanContext->m_WindowSurface, 0);

            DestroyInstance(&g_VulkanContext->m_Instance);

            g_VulkanContext->m_WindowOpened = 0;

            if (g_VulkanContext->m_DynamicOffsetBuffer)
            {
                free(g_VulkanContext->m_DynamicOffsetBuffer);
            }

            DestroyTexture(vk_device, &g_VulkanContext->m_SwapChain->m_ResolveTexture->m_Handle);
            delete g_VulkanContext->m_SwapChain;
        }
    }

    void VulkanIconifyWindow(HContext context)
    {
        if (g_VulkanContext->m_WindowOpened)
        {
            glfwIconifyWindow();
        }
    }

    uint32_t VulkanGetWindowState(HContext context, WindowState state)
    {
        if (g_VulkanContext->m_WindowOpened)
        {
            return glfwGetWindowParam(state);
        }
        else
        {
            return 0;
        }
    }

    uint32_t VulkanGetDisplayDpi(HContext context)
    {
        return 0;
    }

    uint32_t VulkanGetWidth(HContext context)
    {
        return g_VulkanContext->m_Width;
    }

    uint32_t VulkanGetHeight(HContext context)
    {
        return g_VulkanContext->m_Height;
    }

    uint32_t VulkanGetWindowWidth(HContext context)
    {
        return g_VulkanContext->m_WindowWidth;
    }

    uint32_t VulkanGetWindowHeight(HContext context)
    {
        return g_VulkanContext->m_WindowHeight;
    }

    float VulkanGetDisplayScaleFactor(HContext context)
    {
        return glfwGetDisplayScaleFactor();
    }

    void VulkanGetNativeWindowSize(uint32_t* width, uint32_t* height)
    {
        int w, h;
        glfwGetWindowSize(&w, &h);
        *width = w;
        *height = h;
    }

    void VulkanSetWindowSize(HContext context, uint32_t width, uint32_t height)
    {
        if (g_VulkanContext->m_WindowOpened)
        {
            g_VulkanContext->m_Width  = width;
            g_VulkanContext->m_Height = height;
            glfwSetWindowSize((int)width, (int)height);
            int window_width, window_height;
            glfwGetWindowSize(&window_width, &window_height);
            g_VulkanContext->m_WindowWidth  = window_width;
            g_VulkanContext->m_WindowHeight = window_height;

            SwapChainChanged(g_VulkanContext, &g_VulkanContext->m_WindowWidth, &g_VulkanContext->m_WindowHeight, 0, 0);

            // The callback is not called from glfw when the size is set manually
            if (g_VulkanContext->m_WindowResizeCallback)
            {
                g_VulkanContext->m_WindowResizeCallback(g_VulkanContext->m_WindowResizeCallbackUserData, window_width, window_height);
            }
        }
    }

    void VulkanResizeWindow(HContext context, uint32_t width, uint32_t height)
    {
        if (g_VulkanContext->m_WindowOpened)
        {
            VulkanSetWindowSize(context, width, height);
        }
    }

    void NativeSwapBuffers(HContext context)
    {
    #if (defined(__arm__) || defined(__arm64__))
        glfwSwapBuffers();
    #endif
    }

    void VulkanSetSwapInterval(HContext context, uint32_t swap_interval)
    {
    }
}
