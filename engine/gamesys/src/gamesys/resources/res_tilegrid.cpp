#include "res_tilegrid.h"

#include <dlib/log.h>
#include <dlib/math.h>
#include <vectormath/ppu/cpp/vec_aos.h>

#include "gamesys.h"
#include "gamesys_ddf.h"

namespace dmGameSystem
{
    using namespace Vectormath::Aos;

    dmResource::Result AcquireResources(dmPhysics::HContext2D context, dmResource::HFactory factory, dmGameSystemDDF::TileGrid* tile_grid_ddf,
                          TileGridResource* tile_grid, const char* filename, bool reload)
    {
        if (reload)
        {
            // Explicitly reload tileset (textureset) dependency
            dmLogError("AcquireResources! reload: %i", reload);
            dmResource::Result r = dmResource::ReloadResource(factory, tile_grid_ddf->m_TileSet, 0);
            if (r != dmResource::RESULT_OK)
            {
                return r;
            }
        }

        dmResource::Result r = dmResource::Get(factory, tile_grid_ddf->m_TileSet, (void**)&tile_grid->m_TextureSet);
        if (r != dmResource::RESULT_OK)
        {
            return r;
        }
        r = dmResource::Get(factory, tile_grid_ddf->m_Material, (void**)&tile_grid->m_Material);
        if (r != dmResource::RESULT_OK)
        {
            return r;
        }
        // Add-alpha is deprecated because of premultiplied alpha and replaced by Add
        if (tile_grid_ddf->m_BlendMode == dmGameSystemDDF::TileGrid::BLEND_MODE_ADD_ALPHA)
            tile_grid_ddf->m_BlendMode = dmGameSystemDDF::TileGrid::BLEND_MODE_ADD;
        tile_grid->m_TileGrid = tile_grid_ddf;
        TextureSetResource* texture_set = tile_grid->m_TextureSet;

        // find boundaries
        int32_t min_x = INT32_MAX;
        int32_t min_y = INT32_MAX;
        int32_t max_x = INT32_MIN;
        int32_t max_y = INT32_MIN;
        for (uint32_t i = 0; i < tile_grid_ddf->m_Layers.m_Count; ++i)
        {
            dmGameSystemDDF::TileLayer* layer = &tile_grid_ddf->m_Layers[i];
            uint32_t cell_count = layer->m_Cell.m_Count;
            for (uint32_t j = 0; j < cell_count; ++j)
            {
                dmGameSystemDDF::TileCell* cell = &layer->m_Cell[j];
                min_x = dmMath::Min(min_x, cell->m_X);
                min_y = dmMath::Min(min_y, cell->m_Y);
                max_x = dmMath::Max(max_x, cell->m_X + 1);
                max_y = dmMath::Max(max_y, cell->m_Y + 1);
            }
        }
        tile_grid->m_ColumnCount = max_x - min_x;
        tile_grid->m_RowCount = max_y - min_y;
        tile_grid->m_MinCellX = min_x;
        tile_grid->m_MinCellY = min_y;

        dmGameSystemDDF::TextureSet* texture_set_ddf = texture_set->m_TextureSet;
        dmPhysics::HHullSet2D hull_set = texture_set->m_HullSet;
        if (hull_set != 0x0)
        {
            // Calculate AABB for offset
            Point3 offset(0.0f, 0.0f, 0.0f);
            uint32_t layer_count = tile_grid_ddf->m_Layers.m_Count;
            tile_grid->m_GridShapes.SetCapacity(layer_count);
            tile_grid->m_GridShapes.SetSize(layer_count);
            uint32_t cell_width = texture_set_ddf->m_TileWidth;
            uint32_t cell_height = texture_set_ddf->m_TileHeight;
            offset.setX(cell_width * 0.5f * (min_x + max_x));
            offset.setY(cell_height * 0.5f * (min_y + max_y));
            for (uint32_t i = 0; i < layer_count; ++i)
            {
                tile_grid->m_GridShapes[i] = dmPhysics::NewGridShape2D(context, hull_set, offset, cell_width, cell_height, tile_grid->m_RowCount, tile_grid->m_ColumnCount);
            }
        }
        return r;
    }

    void ReleaseResources(dmResource::HFactory factory, TileGridResource* tile_grid)
    {
        if (tile_grid->m_TextureSet)
            dmResource::Release(factory, tile_grid->m_TextureSet);

        if (tile_grid->m_Material)
            dmResource::Release(factory, tile_grid->m_Material);

        if (tile_grid->m_TileGrid)
            dmDDF::FreeMessage(tile_grid->m_TileGrid);

        uint32_t n = tile_grid->m_GridShapes.Size();
        for (uint32_t i = 0; i < n; ++i)
        {
            if (tile_grid->m_GridShapes[i])
                dmPhysics::DeleteCollisionShape2D(tile_grid->m_GridShapes[i]);
        }
    }

    static uint32_t GetResourceSize(TileGridResource* res, uint32_t ddf_size)
    {
        uint32_t size = sizeof(TileGridResource);
        size += ddf_size;
        size += res->m_GridShapes.Capacity() * sizeof(dmPhysics::HCollisionShape2D);    // TODO: Get size of CollisionShape2D
        return size;
    }

    dmResource::Result ResTileGridPreload(const dmResource::ResourcePreloadParams& params)
    {
        dmGameSystemDDF::TileGrid* tile_grid_ddf;
        dmDDF::Result e  = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &tile_grid_ddf);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }

        dmResource::PreloadHint(params.m_HintInfo, tile_grid_ddf->m_TileSet);
        dmResource::PreloadHint(params.m_HintInfo, tile_grid_ddf->m_Material);

        *params.m_PreloadData = tile_grid_ddf;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResTileGridCreate(const dmResource::ResourceCreateParams& params)
    {
        TileGridResource* tile_grid = new TileGridResource();
        dmGameSystemDDF::TileGrid* tile_grid_ddf = (dmGameSystemDDF::TileGrid*) params.m_PreloadData;

        dmResource::Result r = AcquireResources(((PhysicsContext*) params.m_Context)->m_Context2D, params.m_Factory, tile_grid_ddf, tile_grid, params.m_Filename, false);
        if (r == dmResource::RESULT_OK)
        {
            params.m_Resource->m_Resource = (void*) tile_grid;
            params.m_Resource->m_ResourceSize = GetResourceSize(tile_grid, params.m_BufferSize);
        }
        else
        {
            ReleaseResources(params.m_Factory, tile_grid);
            delete tile_grid;
        }
        return r;
    }

    dmResource::Result ResTileGridDestroy(const dmResource::ResourceDestroyParams& params)
    {
        TileGridResource* tile_grid = (TileGridResource*) params.m_Resource->m_Resource;
        ReleaseResources(params.m_Factory, tile_grid);
        delete tile_grid;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResTileGridRecreate(const dmResource::ResourceRecreateParams& params)
    {
        dmLogWarning("ResTileGridRecreate start!");
        dmGameSystemDDF::TileGrid* tile_grid_ddf;
        dmDDF::Result e = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &tile_grid_ddf);
        if (e != dmDDF::RESULT_OK)
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }

        TileGridResource* tile_grid = (TileGridResource*) params.m_Resource->m_Resource;
        TileGridResource tmp_tile_grid;

        dmResource::Result r = AcquireResources(((PhysicsContext*) params.m_Context)->m_Context2D, params.m_Factory, tile_grid_ddf, &tmp_tile_grid, params.m_Filename, true);
        if (r == dmResource::RESULT_OK)
        {
            // dmLogWarning("RESULT_OK!");
            // uint32_t layer_count = tile_grid->m_TileGrid->m_Layers.m_Count;
            // dmLogWarning("old gridshape count: %u, new gridshape count: %u", tile_grid->m_GridShapes.Size(), tmp_tile_grid.m_GridShapes.Size());
            
            // Don't want to release grid shapes, but instead swap content.
            // Release remaining resources explicitly instead.
            dmLogError("Releasing tile grid texture set and material...");
            if (tile_grid->m_TextureSet)
                dmResource::Release(params.m_Factory, tile_grid->m_TextureSet);
            if (tile_grid->m_Material)
                dmResource::Release(params.m_Factory, tile_grid->m_Material);
            if (tile_grid->m_TileGrid)
                dmDDF::FreeMessage(tile_grid->m_TileGrid);
            dmLogError("DONE!");

            // dmLogWarning("old row count: %u, new row count: %u", tile_grid->m_RowCount, tmp_tile_grid.m_RowCount);
            // dmLogWarning("old col count: %u, new col count: %u", tile_grid->m_ColumnCount, tmp_tile_grid.m_ColumnCount);

            tile_grid->m_TileGrid = tmp_tile_grid.m_TileGrid;
            tile_grid->m_Material = tmp_tile_grid.m_Material;
            tile_grid->m_ColumnCount = tmp_tile_grid.m_ColumnCount;
            tile_grid->m_RowCount = tmp_tile_grid.m_RowCount;
            tile_grid->m_MinCellX = tmp_tile_grid.m_MinCellX;
            tile_grid->m_MinCellY = tmp_tile_grid.m_MinCellY;

            // One grid shape per layer
            uint32_t layer_count_old = tile_grid->m_GridShapes.Size();
            uint32_t layer_count_new = tmp_tile_grid.m_GridShapes.Size();
            uint32_t layer_count     = layer_count_new;//dmMath::Min(layer_count_old, layer_count_new);

            if (layer_count_old < layer_count_new)
            {                
                dmLogWarning("Reloaded tilemap '%s' has more layers that original tilemap. Only original layers will be reloaded.", dmHashReverseSafe64(params.m_Resource->m_NameHash));
                // if (tile_grid->m_GridShapes.Size() < layer_count_new)
                // {
                //     uint32_t capacity = tile_grid->m_GridShapes.Capacity();
                //     dmLogInfo("Offsetting capacity with: %u", layer_count_new - capacity);
                //     tile_grid->m_GridShapes.OffsetCapacity(layer_count_new - capacity);
                //     tile_grid->m_GridShapes.SetSize(layer_count_new);
                //     for (int i = capacity; i < layer_count_new; ++i)
                //     {
                //         tile_grid->m_GridShapes[i] = tmp_tile_grid.m_GridShapes[i];
                //     }
                //     layer_count = layer_count_old;
                // }
            }
            else if (layer_count_old > layer_count_new)
            {
                // Fewer layers in new tilemap, reset untouched layers in old? Mark as empty? Do nothing?
            }
            // else
            // {
            //     // Same num of layers in new/old, this is fine
            // }

            dmLogWarning("old layer count: %u, new layer count: %u, selected: %u", layer_count_old, layer_count_new, layer_count);
            for (uint32_t i = 0; i < layer_count; ++i)
            {
                // TODO friday, add indirection to grid shapes here, should allow us to swap out (delete and re-alloc) everything without worrying
                // of physics holding stale refs maybe?
                dmLogInfo("i = %u", i);
                dmPhysics::SwapFreeGridShape2DHullSet(tile_grid->m_GridShapes[i], tmp_tile_grid.m_GridShapes[i]);
            }

            tile_grid->m_GridShapes.SetSize(layer_count);
            tile_grid->m_Dirty = 1;

            params.m_Resource->m_ResourceSize = GetResourceSize(tile_grid, params.m_BufferSize);
        }
        else
        {
            dmLogWarning("Failed AcquireResources, result: %u", r);
            ReleaseResources(params.m_Factory, &tmp_tile_grid);
        }
        return r;
    }
}
