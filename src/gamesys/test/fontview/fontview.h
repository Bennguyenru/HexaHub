#ifndef DM_RENDER_FONTVIEW_H
#define DM_RENDER_FONTVIEW_H

#include <stdint.h>

#include <resource/resource.h>

#include <graphics/graphics_device.h>

#include <render/render.h>
#include <render/font_renderer.h>

#include <render/render_ddf.h>

struct Context
{
    const char* m_TestString;
    dmGraphics::HDevice m_GraphicsDevice;
    dmResource::HFactory m_Factory;
    dmRender::HFont m_Font;
    dmRender::HRenderContext m_RenderContext;
    dmRender::HFontRenderer m_FontRenderer;
    uint32_t m_ScreenWidth;
    uint32_t m_ScreenHeight;
};

bool Init(Context* context, int argc, char *argv[]);
int32_t Run(Context* context);
void Finalize(Context* context);

#endif // DM_RENDER_FONTVIEW_H
