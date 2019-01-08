#include "comp_tilegrid.h"

#include <new>
#include <dlib/log.h>
#include <dlib/hash.h>
#include <dlib/message.h>
#include <dlib/dstrings.h>
#include <dlib/math.h>
#include <dlib/time.h>
#include <graphics/graphics.h>
#include <render/render.h>
#include <gameobject/gameobject.h>
#include <gameobject/gameobject_ddf.h>
#include <vectormath/cpp/vectormath_aos.h>

#include "../proto/tile_ddf.h"
#include "../proto/physics_ddf.h"
#include "../gamesys_private.h"

extern unsigned char TILE_MAP_VPC[];
extern uint32_t TILE_MAP_VPC_SIZE;

extern unsigned char TILE_MAP_FPC[];
extern uint32_t TILE_MAP_FPC_SIZE;

namespace dmGameSystem
{
    using namespace Vectormath::Aos;

    TileGridComponent::TileGridComponent()
    : m_Instance(0)
    , m_TileGridResource(0)
    , m_Cells(0)
    , m_CellFlags(0)
    {

    }

    dmGameObject::CreateResult CompTileGridNewWorld(const dmGameObject::ComponentNewWorldParams& params)
    {
        TileGridWorld* world = new TileGridWorld;
        dmRender::HRenderContext render_context = (dmRender::HRenderContext)params.m_Context;
        dmGraphics::HContext graphics_context = dmRender::GetGraphicsContext(render_context);

        // TODO: Everything below here should be move to the "universe" when available
        // and hence shared among all the worlds
        dmGraphics::VertexElement ve[] =
        {
                {"position", 0, 3, dmGraphics::TYPE_FLOAT, false},
                {"texcoord0", 1, 2, dmGraphics::TYPE_FLOAT, false},
        };
        world->m_VertexDeclaration = dmGraphics::NewVertexDeclaration(graphics_context, ve, sizeof(ve) / sizeof(dmGraphics::VertexElement));

        *params.m_World = world;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompTileGridDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params)
    {
        TileGridWorld* world = (TileGridWorld*) params.m_World;
        dmGraphics::DeleteVertexDeclaration(world->m_VertexDeclaration);
        delete world;
        return dmGameObject::CREATE_RESULT_OK;
    }

    uint32_t CalculateCellIndex(uint32_t layer, int32_t cell_x, int32_t cell_y, uint32_t column_count, uint32_t row_count)
    {
        return layer * row_count * column_count + (cell_x + cell_y * column_count);
    }

    bool CreateTileGrid(TileGridComponent* tile_grid)
    {
        TileGridResource* resource = tile_grid->m_TileGridResource;
        dmGameSystemDDF::TileGrid* tile_grid_ddf = resource->m_TileGrid;
        uint32_t n_layers = tile_grid_ddf->m_Layers.m_Count;
        dmArray<TileGridComponent::Layer>& layers = tile_grid->m_Layers;
        if (layers.Size() < n_layers)
        {
            if (layers.Capacity() < n_layers)
                layers.SetCapacity(n_layers);
            layers.SetSize(n_layers);
            for (uint32_t i = 0; i < n_layers; ++i)
            {
                TileGridComponent::Layer& layer = layers[i];
                dmGameSystemDDF::TileLayer* layer_ddf = &tile_grid_ddf->m_Layers[i];
                layer.m_Id = dmHashString64(layer_ddf->m_Id);
                layer.m_Visible = layer_ddf->m_IsVisible;
            }
        }
        uint32_t cell_count = resource->m_ColumnCount * resource->m_RowCount * n_layers;
        if (tile_grid->m_Cells != 0x0)
        {
            delete [] tile_grid->m_Cells;
        }
        tile_grid->m_Cells = new uint16_t[cell_count];
        memset(tile_grid->m_Cells, 0xff, cell_count * sizeof(uint16_t));
        if (tile_grid->m_CellFlags != 0x0)
        {
            delete [] tile_grid->m_CellFlags;
        }
        tile_grid->m_CellFlags = new TileGridComponent::Flags[cell_count];
        memset(tile_grid->m_CellFlags, 0, cell_count * sizeof(TileGridComponent::Flags));
        int32_t min_x = resource->m_MinCellX;
        int32_t min_y = resource->m_MinCellY;
        uint32_t column_count = resource->m_ColumnCount;
        uint32_t row_count = resource->m_RowCount;
        for (uint32_t i = 0; i < n_layers; ++i)
        {
            dmGameSystemDDF::TileLayer* layer_ddf = &tile_grid_ddf->m_Layers[i];
            uint32_t n_cells = layer_ddf->m_Cell.m_Count;
            for (uint32_t j = 0; j < n_cells; ++j)
            {
                dmGameSystemDDF::TileCell* cell = &layer_ddf->m_Cell[j];
                uint32_t cell_index = CalculateCellIndex(i, cell->m_X - min_x, cell->m_Y - min_y, column_count, row_count);
                tile_grid->m_Cells[cell_index] = (uint16_t)cell->m_Tile;
            }
        }
        return true;
    }

    static void CreateRegions(TileGridComponent* component, TileGridResource* resource)
    {
        // Round up to closest multiple
        component->m_RegionsX = ((resource->m_ColumnCount + TILEGRID_REGION_WIDTH - 1) / TILEGRID_REGION_WIDTH);
        component->m_RegionsY = ((resource->m_RowCount + TILEGRID_REGION_HEIGHT - 1) / TILEGRID_REGION_HEIGHT);
        uint32_t region_count = component->m_RegionsX * component->m_RegionsY;

        component->m_Regions.SetCapacity(region_count);
        component->m_Regions.SetSize(region_count);
    }

    static void CreateRenderObjects(TileGridWorld* world, TileGridComponent* component, TileGridResource* resource, uint32_t region_count)
    {
        dmRender::HMaterial material = resource->m_Material;
        dmGraphics::BlendFactor source_blend_factor = dmGraphics::BLEND_FACTOR_SRC_ALPHA;
        dmGraphics::BlendFactor destination_blend_factor = dmGraphics::BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        dmGameSystemDDF::TileGrid::BlendMode blend_mode = resource->m_TileGrid->m_BlendMode;
        switch (blend_mode)
        {
            case dmGameSystemDDF::TileGrid::BLEND_MODE_ALPHA:
                source_blend_factor = dmGraphics::BLEND_FACTOR_ONE;
                destination_blend_factor = dmGraphics::BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            break;

            case dmGameSystemDDF::TileGrid::BLEND_MODE_ADD:
            case dmGameSystemDDF::TileGrid::BLEND_MODE_ADD_ALPHA:
                source_blend_factor = dmGraphics::BLEND_FACTOR_ONE;
                destination_blend_factor = dmGraphics::BLEND_FACTOR_ONE;
            break;

            case dmGameSystemDDF::TileGrid::BLEND_MODE_MULT:
                source_blend_factor = dmGraphics::BLEND_FACTOR_DST_COLOR;
                destination_blend_factor = dmGraphics::BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            break;

            default:
                dmLogError("Unknown blend mode: %d\n", blend_mode);
                assert(0);
            break;
        }

        for (uint32_t i = 0; i < region_count; ++i)
        {
            TileGridRegion* region = &component->m_Regions[i];
            memset(region, 0, sizeof(*region));
            region->m_Dirty = 1;

            dmRender::RenderObject* ro = &region->m_RenderObject;
            // NOTE: Run constructor explicitly with placement new
            new(ro) dmRender::RenderObject;

            ro->m_SourceBlendFactor = source_blend_factor;
            ro->m_DestinationBlendFactor = destination_blend_factor;
            ro->m_SetBlendFactors = 1;
            ro->m_VertexDeclaration = world->m_VertexDeclaration;
            ro->m_VertexBuffer = 0;
            ro->m_PrimitiveType = dmGraphics::PRIMITIVE_TRIANGLES;
            ro->m_Material = material;
        }
    }

    dmGameObject::CreateResult CompTileGridCreate(const dmGameObject::ComponentCreateParams& params)
    {
        TileGridResource* resource = (TileGridResource*) params.m_Resource;
        TileGridWorld* world = (TileGridWorld*) params.m_World;
        if (world->m_TileGrids.Full())
        {
            world->m_TileGrids.OffsetCapacity(16);
        }
        TileGridComponent* component = new TileGridComponent();
        component->m_Instance = params.m_Instance;
        component->m_TileGridResource = resource;
        component->m_Translation = Vector3(params.m_Position);
        component->m_Rotation = params.m_Rotation;
        component->m_Enabled = 1;
        if (!CreateTileGrid(component))
        {
            return dmGameObject::CREATE_RESULT_UNKNOWN_ERROR;
        }

        CreateRegions(component, resource);
        CreateRenderObjects(world, component, resource, component->m_Regions.Size());

        world->m_TileGrids.Push(component);
        *params.m_UserData = (uintptr_t) component;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompTileGridDestroy(const dmGameObject::ComponentDestroyParams& params)
    {
        TileGridComponent* tile_grid = (TileGridComponent*) *params.m_UserData;
        TileGridWorld* world = (TileGridWorld*) params.m_World;
        for (uint32_t i = 0; i < world->m_TileGrids.Size(); ++i)
        {
            if (world->m_TileGrids[i] == tile_grid)
            {
                dmArray<TileGridRegion>& regions = tile_grid->m_Regions;
                uint32_t n_regions = regions.Size();
                for (uint32_t ir = 0; ir < n_regions; ++ir)
                {
                    dmRender::RenderObject* ro = &regions[ir].m_RenderObject;
                    if (ro->m_VertexBuffer)
                    {
                        dmGraphics::DeleteVertexBuffer(ro->m_VertexBuffer);
                    }
                    delete[] (char*) regions[ir].m_ClientBuffer;
                }

                delete [] tile_grid->m_Cells;
                delete [] tile_grid->m_CellFlags;
                world->m_TileGrids.EraseSwap(i);
                delete tile_grid;
                return dmGameObject::CREATE_RESULT_OK;
            }
        }
        assert(false);
        return dmGameObject::CREATE_RESULT_UNKNOWN_ERROR;
    }

    static void CalculateCellBounds(int32_t cell_x, int32_t cell_y, int32_t cell_width, int32_t cell_height, float out_v[4])
    {
        out_v[0] = cell_x * cell_width;
        out_v[1] = cell_y * cell_height;
        out_v[2] = (cell_x + 1) * cell_width;
        out_v[3] = (cell_y + 1) * cell_height;
    }

    void CompTileGridUpdateRegion(dmRender::HRenderContext render_context, TileGridComponent* component, uint32_t region_x, uint32_t region_y)
    {
        TileGridResource* resource = component->m_TileGridResource;
        uint32_t region_index = region_y * component->m_RegionsX + region_x;
        TileGridRegion* region = &component->m_Regions[region_index];
        if (!region->m_Dirty)
        {
            return;
        }

        region->m_Dirty = false;

        dmGameSystemDDF::TileGrid* tile_grid_ddf = resource->m_TileGrid;
        dmGameSystemDDF::TextureSet* texture_set_ddf = resource->m_TextureSet->m_TextureSet;

        // TODO Cull against screen
        uint32_t column_count = resource->m_ColumnCount;
        uint32_t row_count = resource->m_RowCount;
        int32_t min_x = resource->m_MinCellX + region_x * TILEGRID_REGION_WIDTH;
        int32_t min_y = resource->m_MinCellY + region_y * TILEGRID_REGION_HEIGHT;
        int32_t max_x = dmMath::Min(min_x + (int32_t)TILEGRID_REGION_WIDTH, resource->m_MinCellX + (int32_t)column_count);
        int32_t max_y = dmMath::Min(min_y + (int32_t)TILEGRID_REGION_HEIGHT, resource->m_MinCellY + (int32_t)row_count);

        dmArray<TileGridComponent::Layer>& layers = component->m_Layers;
        uint32_t layer_count = layers.Size();

        uint32_t visible_tiles = 0;
        for (uint32_t j = 0; j < layer_count; ++j)
        {
            TileGridComponent::Layer* layer = &layers[j];
            if (layer->m_Visible)
            {
                for (int32_t y = min_y; y < max_y; ++y)
                {
                    for (int32_t x = min_x; x < max_x; ++x)
                    {
                        uint32_t cell = CalculateCellIndex(j, x - resource->m_MinCellX, y - resource->m_MinCellY, column_count, row_count);
                        uint16_t tile = component->m_Cells[cell];
                        if (tile != 0xffff)
                        {
                            ++visible_tiles;
                        }
                    }
                }
            }
        }

        struct Vertex
        {
            float x;
            float y;
            float z;
            float u;
            float v;
        };
        static int tex_coord_order[] = {
            0,1,2,2,3,0,
            3,2,1,1,0,3,    //h
            1,0,3,3,2,1,    //v
            2,3,0,0,1,2     //hv
        };

        const uint32_t VERTCIES_PER_TILE = 6;

        uint32_t buffer_size = sizeof(Vertex) * VERTCIES_PER_TILE * visible_tiles;
        if (region->m_ClientBufferSize < buffer_size)
        {
            if (region->m_ClientBuffer != 0x0)
            {
                delete [] (char*)region->m_ClientBuffer;
            }

            const uint32_t margin = 16;
            uint32_t allocation_size = sizeof(Vertex) * VERTCIES_PER_TILE * (visible_tiles + margin);
            region->m_ClientBuffer = new char[allocation_size];
            region->m_ClientBufferSize = allocation_size;
        }

        Vertex* v = &((Vertex*)region->m_ClientBuffer)[0];
        float p[4];

        uint32_t vertex_count = 0;
        const float* tex_coords = (const float*) resource->m_TextureSet->m_TextureSet->m_TexCoords.m_Data;
        for (uint32_t j = 0; j < layer_count; ++j)
        {
            TileGridComponent::Layer* layer = &layers[j];
            if (layer->m_Visible)
            {
                float z = tile_grid_ddf->m_Layers[j].m_Z;
                for (int32_t y = min_y; y < max_y; ++y)
                {
                    for (int32_t x = min_x; x < max_x; ++x)
                    {
                        uint32_t cell = CalculateCellIndex(j, x - resource->m_MinCellX, y - resource->m_MinCellY, column_count, row_count);
                        uint16_t tile = component->m_Cells[cell];
                        if (tile != 0xffff)
                        {
                            CalculateCellBounds(x, y, texture_set_ddf->m_TileWidth, texture_set_ddf->m_TileHeight, p);
                            const float* puv = &tex_coords[tile * 8];
                            uint32_t flip_flag = 0;

                            TileGridComponent::Flags flags = component->m_CellFlags[cell];
                            if (flags.m_FlipHorizontal)
                            {
                                flip_flag = 1;
                            }
                            if (flags.m_FlipVertical)
                            {
                                flip_flag |= 2;
                            }
                            const int* tex_lookup = &tex_coord_order[flip_flag * 6];

                            v->x = p[0]; v->y = p[1]; v->z = z;
                            v->u = puv[tex_lookup[0] * 2];
                            v->v = puv[tex_lookup[0] * 2 + 1];
                            ++v;

                            v->x = p[0]; v->y = p[3]; v->z = z;
                            v->u = puv[tex_lookup[1] * 2];
                            v->v = puv[tex_lookup[1] * 2 + 1];
                            ++v;

                            v->x = p[2]; v->y = p[3]; v->z = z;
                            v->u = puv[tex_lookup[2] * 2];
                            v->v = puv[tex_lookup[2] * 2 + 1];
                            ++v;

                            v->x = p[2]; v->y = p[3]; v->z = z;
                            v->u = puv[tex_lookup[3] * 2];
                            v->v = puv[tex_lookup[3] * 2 + 1];
                            ++v;

                            v->x = p[2]; v->y = p[1]; v->z = z;
                            v->u = puv[tex_lookup[4] * 2];
                            v->v = puv[tex_lookup[4] * 2 + 1];
                            ++v;

                            v->x = p[0]; v->y = p[1]; v->z = z;
                            v->u = puv[tex_lookup[5] * 2];
                            v->v = puv[tex_lookup[5] * 2 + 1];
                            ++v;
                            vertex_count += VERTCIES_PER_TILE;
                        }
                    }
                }
            }
        }

        dmRender::RenderObject* ro = &region->m_RenderObject;

        if (ro->m_VertexBuffer == 0)
        {
            ro->m_VertexBuffer = dmGraphics::NewVertexBuffer(dmRender::GetGraphicsContext(render_context), 0, 0x0, dmGraphics::BUFFER_USAGE_STREAM_DRAW);
        }
        ro->m_VertexStart = 0;
        ro->m_VertexCount = vertex_count;

        // Clear the data to avoid locks (according to internet rumors)
        dmGraphics::SetVertexBufferData(ro->m_VertexBuffer, 0, 0x0, dmGraphics::BUFFER_USAGE_STREAM_DRAW);
        dmGraphics::SetVertexBufferData(ro->m_VertexBuffer, vertex_count * sizeof(Vertex), region->m_ClientBuffer, dmGraphics::BUFFER_USAGE_STREAM_DRAW);
    }

    dmGameObject::CreateResult CompTileGridAddToUpdate(const dmGameObject::ComponentAddToUpdateParams& params) {
        TileGridComponent* component = (TileGridComponent*) *params.m_UserData;
        component->m_AddedToUpdate = true;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::UpdateResult CompTileGridUpdate(const dmGameObject::ComponentsUpdateParams& params, dmGameObject::ComponentsUpdateResult& update_result)
    {
        return dmGameObject::UPDATE_RESULT_OK;
    }

    static void RenderListDispatch(dmRender::RenderListDispatchParams const &params)
    {
        if (params.m_Operation == dmRender::RENDER_LIST_OPERATION_BATCH)
        {
            assert((params.m_End - params.m_Begin) == 1);

            TileGridComponent *tile_grid = (TileGridComponent*) params.m_Buf[*params.m_Begin].m_UserData;
            TileGridResource* resource = tile_grid->m_TileGridResource;
            dmGraphics::HTexture texture = resource->m_TextureSet->m_Texture;

            for (uint32_t rx = 0; rx < tile_grid->m_RegionsX; ++rx)
            {
                for (uint32_t ry = 0; ry < tile_grid->m_RegionsY; ++ry)
                {
                    CompTileGridUpdateRegion(params.m_Context, tile_grid, rx, ry);

                    uint32_t region_index = ry * tile_grid->m_RegionsX + rx;
                    TileGridRegion* region = &tile_grid->m_Regions[region_index];
                    dmRender::RenderObject* ro = &region->m_RenderObject;
                    if (ro->m_VertexCount > 0)
                    {
                        ro->m_WorldTransform = tile_grid->m_RenderWorldTransform;
                        ro->m_Textures[0] = texture;
                        dmRender::AddToRender(params.m_Context, ro);
                    }
                }
            }
        }
    }

    dmGameObject::UpdateResult CompTileGridRender(const dmGameObject::ComponentsRenderParams& params)
    {
        dmRender::HRenderContext render_context = (dmRender::HRenderContext)params.m_Context;
        TileGridWorld* world = (TileGridWorld*) params.m_World;

        dmArray<TileGridComponent*>& tile_grids = world->m_TileGrids;
        uint32_t n = tile_grids.Size();

        // Each component instance gets its own entry

        dmRender::RenderListEntry* render_list = dmRender::RenderListAlloc(render_context, n);
        dmRender::HRenderListDispatch dispatch = dmRender::RenderListMakeDispatch(render_context, &RenderListDispatch, world);
        dmRender::RenderListEntry* write_ptr = render_list;

        for (uint32_t i = 0; i < n; ++i)
        {
            TileGridComponent* tile_grid = tile_grids[i];
            if (!tile_grid->m_Enabled || !tile_grid->m_AddedToUpdate) {
                continue;
            }

            Matrix4 local(tile_grid->m_Rotation, Vector3(tile_grid->m_Translation));
            const Matrix4& go_world = dmGameObject::GetWorldMatrix(tile_grid->m_Instance);
            if (dmGameObject::ScaleAlongZ(tile_grid->m_Instance))
            {
                tile_grid->m_RenderWorldTransform = go_world * local;
            }
            else
            {
                tile_grid->m_RenderWorldTransform = dmTransform::MulNoScaleZ(go_world, local);
            }

            const Vector4 trans = tile_grid->m_RenderWorldTransform.getCol(3);
            write_ptr->m_WorldPosition = Point3(trans.getX(), trans.getY(), trans.getZ());
            write_ptr->m_UserData = (uintptr_t) tile_grid;
            write_ptr->m_TagMask = dmRender::GetMaterialTagMask(tile_grid->m_TileGridResource->m_Material);
            write_ptr->m_BatchKey = i;
            write_ptr->m_Dispatch = dispatch;
            write_ptr->m_MinorOrder = 0;
            write_ptr->m_MajorOrder = dmRender::RENDER_ORDER_WORLD;
            ++write_ptr;
        }

        dmRender::RenderListSubmit(render_context, render_list, write_ptr);
        return dmGameObject::UPDATE_RESULT_OK;
    }

    uint32_t GetLayerIndex(const TileGridComponent* component, dmhash_t layer_id)
    {
        uint32_t layer_count = component->m_Layers.Size();
        uint32_t layer_index = ~0u;
        for (uint32_t i = 0; i < layer_count; ++i)
        {
            if (layer_id == component->m_Layers[i].m_Id)
            {
                layer_index = i;
                break;
            }
        }
        return layer_index;
    }

    dmGameObject::UpdateResult CompTileGridOnMessage(const dmGameObject::ComponentOnMessageParams& params)
    {
        TileGridComponent* component = (TileGridComponent*) *params.m_UserData;
        if (params.m_Message->m_Id == dmGameSystemDDF::SetTile::m_DDFDescriptor->m_NameHash)
        {
            dmGameSystemDDF::SetTile* st = (dmGameSystemDDF::SetTile*) params.m_Message->m_Data;
            uint32_t layer_count = component->m_Layers.Size();
            uint32_t layer_index = ~0u;
            for (uint32_t i = 0; i < layer_count; ++i)
            {
                if (st->m_LayerId == component->m_Layers[i].m_Id)
                {
                    layer_index = i;
                    break;
                }
            }
            if (layer_index == ~0u)
            {
                dmLogError("Could not find layer %s when handling message %s.", dmHashReverseSafe64(st->m_LayerId), dmGameSystemDDF::SetTile::m_DDFDescriptor->m_Name);
                return dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
            }
            dmGameObject::HInstance instance = component->m_Instance;
            dmTransform::Transform inv_world(dmTransform::Inv(dmGameObject::GetWorldTransform(instance)));
            Point3 cell = st->m_Position;
            if (dmGameObject::ScaleAlongZ(instance))
            {
                cell = dmTransform::Apply(inv_world, cell);
            }
            else
            {
                cell = dmTransform::ApplyNoScaleZ(inv_world, cell);
            }
            TileGridResource* resource = component->m_TileGridResource;
            dmGameSystemDDF::TextureSet* texture_set = resource->m_TextureSet->m_TextureSet;
            cell = mulPerElem(cell, Point3(1.0f / texture_set->m_TileWidth, 1.0f / texture_set->m_TileHeight, 0.0f));
            int32_t cell_x = (int32_t)floor(cell.getX()) + st->m_Dx - resource->m_MinCellX;
            int32_t cell_y = (int32_t)floor(cell.getY()) + st->m_Dy - resource->m_MinCellY;
            if (cell_x < 0 || cell_x >= (int32_t)resource->m_ColumnCount || cell_y < 0 || cell_y >= (int32_t)resource->m_RowCount)
            {
                dmLogError("Could not set the tile since the supplied tile was out of range.");
                return dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
            }
            uint32_t cell_index = CalculateCellIndex(layer_index, cell_x, cell_y, resource->m_ColumnCount, resource->m_RowCount);
            uint32_t region_x = cell_x / TILEGRID_REGION_WIDTH;
            uint32_t region_y = cell_y / TILEGRID_REGION_HEIGHT;
            uint32_t region_index = region_y * component->m_RegionsX + region_x;
            TileGridRegion* region = &component->m_Regions[region_index];
            region->m_Dirty = true;

            /*
             * NOTE AND BEWARE: Empty tile is encoded as 0xffffffff
             * That's why tile-index is subtracted by 1
             * See B2GRIDSHAPE_EMPTY_CELL in b2GridShape.h
             */
            uint32_t tile = st->m_Tile - 1;
            component->m_Cells[cell_index] = (uint16_t)tile;
            // Broadcast to any collision object components
            // TODO Filter broadcast to only collision objects
            dmPhysicsDDF::SetGridShapeHull set_hull_ddf;
            set_hull_ddf.m_Shape = layer_index;
            set_hull_ddf.m_Column = cell_x;
            set_hull_ddf.m_Row = cell_y;
            set_hull_ddf.m_Hull = tile;
            dmhash_t message_id = dmPhysicsDDF::SetGridShapeHull::m_DDFDescriptor->m_NameHash;
            uintptr_t descriptor = (uintptr_t)dmPhysicsDDF::SetGridShapeHull::m_DDFDescriptor;
            uint32_t data_size = sizeof(dmPhysicsDDF::SetGridShapeHull);
            dmMessage::URL receiver = params.m_Message->m_Receiver;
            receiver.m_Fragment = 0;
            dmMessage::Result result = dmMessage::Post(&params.m_Message->m_Receiver, &receiver, message_id, 0, descriptor, &set_hull_ddf, data_size, 0);
            if (result != dmMessage::RESULT_OK)
            {
                LogMessageError(params.m_Message, "Could not send %s to components, result: %d.", dmPhysicsDDF::SetGridShapeHull::m_DDFDescriptor->m_Name, result);
                return dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
            }
        }
        else if (params.m_Message->m_Id == dmGameSystemDDF::SetConstantTileMap::m_DDFDescriptor->m_NameHash)
        {
            dmGameSystemDDF::SetConstantTileMap* ddf = (dmGameSystemDDF::SetConstantTileMap*)params.m_Message->m_Data;
            uint32_t region_count = component->m_Regions.Size();
            for (uint32_t i = 0; i < region_count; ++i)
            {
                TileGridRegion* region = &component->m_Regions[i];
                dmRender::EnableRenderObjectConstant(&region->m_RenderObject, ddf->m_NameHash, ddf->m_Value);
            }
        }
        else if (params.m_Message->m_Id == dmGameSystemDDF::ResetConstantTileMap::m_DDFDescriptor->m_NameHash)
        {
            dmGameSystemDDF::ResetConstantTileMap* ddf = (dmGameSystemDDF::ResetConstantTileMap*)params.m_Message->m_Data;
            uint32_t region_count = component->m_Regions.Size();
            for (uint32_t i = 0; i < region_count; ++i)
            {
                TileGridRegion* region = &component->m_Regions[i];
                dmRender::DisableRenderObjectConstant(&region->m_RenderObject, ddf->m_NameHash);
            }
        }
        else if (params.m_Message->m_Id == dmGameObjectDDF::Enable::m_DDFDescriptor->m_NameHash)
        {
            component->m_Enabled = 1;
        }
        else if (params.m_Message->m_Id == dmGameObjectDDF::Disable::m_DDFDescriptor->m_NameHash)
        {
            component->m_Enabled = 0;
        }

        return dmGameObject::UPDATE_RESULT_OK;
    }

    void CompTileGridOnReload(const dmGameObject::ComponentOnReloadParams& params)
    {
        TileGridWorld* world = (TileGridWorld*)params.m_World;
        TileGridComponent* component = (TileGridComponent*)*params.m_UserData;
        component->m_TileGridResource = (TileGridResource*)params.m_Resource;

        dmGameSystemDDF::TileGrid* tile_grid_ddf = component->m_TileGridResource->m_TileGrid;
        if (tile_grid_ddf->m_Layers.m_Count <= component->m_Layers.Capacity())
        {
            component->m_Layers.SetSize(tile_grid_ddf->m_Layers.m_Count);
        }
        else
        {
            component->m_Layers.OffsetCapacity(tile_grid_ddf->m_Layers.m_Count - component->m_Layers.Capacity());
        }

        if (!CreateTileGrid(component))
        {
            dmLogError("%s", "Could not recreate tile grid component, not reloaded.");
        }

        CreateRegions(component, component->m_TileGridResource);
        CreateRenderObjects(world, component, component->m_TileGridResource, component->m_Regions.Size());
    }

    static bool CompTileGridGetConstantCallback(void* user_data, dmhash_t name_hash, dmRender::Constant** out_constant)
    {
        TileGridComponent* component = (TileGridComponent*)user_data;
        uint32_t region_count = component->m_Regions.Size();
        for (uint32_t i = 0; i < region_count; ++i)
        {
            TileGridRegion* region = &component->m_Regions[i];
            for (uint32_t j = 0; j < dmRender::RenderObject::MAX_CONSTANT_COUNT; ++j)
            {
                dmRender::Constant& constant = region->m_RenderObject.m_Constants[j];
                if (constant.m_Location != -1 && constant.m_NameHash == name_hash)
                {
                    *out_constant = &constant;
                    return true;
                }
            }
        }
        return false;
    }

    static void CompTileGridSetConstantCallback(void* user_data, dmhash_t name_hash, uint32_t* element_index, const dmGameObject::PropertyVar& var)
    {
        TileGridComponent* component = (TileGridComponent*)user_data;
        uint32_t region_count = component->m_Regions.Size();
        Vector4 val;
        if (element_index == 0x0)
        {
            val = Vector4(var.m_V4[0], var.m_V4[1], var.m_V4[2] ,var.m_V4[3]);
        }
        else
        {
            dmRender::Constant c;
            dmRender::GetMaterialProgramConstant(component->m_TileGridResource->m_Material, name_hash, c);
            val = c.m_Value;
        }
        for (uint32_t i = 0; i < region_count; ++i)
        {
            TileGridRegion* region = &component->m_Regions[i];
            if (element_index != 0x0)
            {
                Vector4* v = 0x0;
                for (uint32_t j = 0; j < dmRender::RenderObject::MAX_CONSTANT_COUNT; ++j)
                {
                    dmRender::Constant* c = &region->m_RenderObject.m_Constants[j];
                    if (c->m_Location != -1 && c->m_NameHash == name_hash)
                    {
                        v = &c->m_Value;
                        break;
                    }
                }
                if (v != 0x0)
                    val = *v;
                val.setElem(*element_index, var.m_Number);
            }
            dmRender::EnableRenderObjectConstant(&region->m_RenderObject, name_hash, val);
        }
    }

    dmGameObject::PropertyResult CompTileGridGetProperty(const dmGameObject::ComponentGetPropertyParams& params, dmGameObject::PropertyDesc& out_value)
    {
        TileGridComponent* component = (TileGridComponent*)*params.m_UserData;
        return GetMaterialConstant(component->m_TileGridResource->m_Material, params.m_PropertyId, out_value, true, CompTileGridGetConstantCallback, component);
    }

    dmGameObject::PropertyResult CompTileGridSetProperty(const dmGameObject::ComponentSetPropertyParams& params)
    {
        TileGridComponent* component = (TileGridComponent*)*params.m_UserData;
        return SetMaterialConstant(component->m_TileGridResource->m_Material, params.m_PropertyId, params.m_Value, CompTileGridSetConstantCallback, component);
    }
}
