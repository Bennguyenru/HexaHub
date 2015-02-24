package com.dynamo.cr.tileeditor.scene;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.media.opengl.GL2;
import javax.vecmath.Vector2f;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import com.dynamo.bob.tile.TileSetUtil;
import com.dynamo.cr.go.core.ComponentTypeNode;
import com.dynamo.cr.properties.NotEmpty;
import com.dynamo.cr.properties.Property;
import com.dynamo.cr.properties.Property.EditorType;
import com.dynamo.cr.properties.Resource;
import com.dynamo.cr.sceneed.core.AABB;
import com.dynamo.cr.sceneed.core.ISceneModel;
import com.dynamo.cr.sceneed.core.Node;
import com.dynamo.cr.tileeditor.Activator;
import com.dynamo.cr.tileeditor.core.Layer;
import com.dynamo.tile.proto.Tile.TileGrid.BlendMode;

@SuppressWarnings("serial")
public class TileGridNode extends ComponentTypeNode {

    @Property(editorType=EditorType.RESOURCE, extensions={"tileset", "tilesource"})
    @Resource
    @NotEmpty
    private String tileSource = "";

    @Property(editorType = EditorType.RESOURCE, extensions = { "material" })
    @Resource
    @NotEmpty
    private String material = "";

    @Property
    private BlendMode blendMode = BlendMode.BLEND_MODE_ALPHA;

    private transient TileSetNode tileSetNode = null;

    // Graphics resources
    private transient FloatBuffer vertexData;

    @Override
    public void dispose(GL2 gl) {
        super.dispose(gl);
        if (this.tileSetNode != null) {
            this.tileSetNode.dispose(gl);
        }
    }

    @Override
    public void parentSet() {
        setTransformable(getParent() != null);
    }

    public String getTileSource() {
        return tileSource;
    }

    public void setTileSource(String tileSource) {
        if (!this.tileSource.equals(tileSource)) {
            this.tileSource = tileSource;
            reloadTileSource();
        }
    }

    public IStatus validateTileSource() {
        if (this.tileSetNode != null) {
            this.tileSetNode.updateStatus();
            IStatus status = this.tileSetNode.getStatus();
            if (!status.isOK()) {
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID, Messages.SpriteNode_tileSet_INVALID_REFERENCE);
            }
        } else if (!this.tileSource.isEmpty()) {
            return new Status(IStatus.ERROR, Activator.PLUGIN_ID, Messages.SpriteNode_tileSet_CONTENT_ERROR);
        }
        return Status.OK_STATUS;
    }

    public List<LayerNode> getLayers() {
        List<LayerNode> layers = new ArrayList<LayerNode>();
        List<Node> children = getChildren();
        for (Node child : children) {
            layers.add((LayerNode)child);
        }
        return layers;
    }

    public void addLayer(LayerNode layer) {
        addChild(layer);
    }

    public TileSetNode getTileSetNode() {
        return this.tileSetNode;
    }

    public String getMaterial() {
        return this.material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public BlendMode getBlendMode() {
        return this.blendMode;
    }

    public void setBlendMode(BlendMode blendMode) {
        this.blendMode = blendMode;
    }

    public FloatBuffer getVertexData() {
        return this.vertexData;
    }

    @Override
    public void setModel(ISceneModel model) {
        super.setModel(model);
        if (model != null && this.tileSetNode == null) {
            reloadTileSource();
        }
        if (this.tileSetNode != null) {
            this.tileSetNode.setModel(model);
        }
    }

    @Override
    public boolean handleReload(IFile file, boolean childWasReloaded) {
        boolean reloaded = false;
        if (!this.tileSource.isEmpty()) {
            IFile tileSetFile = getModel().getFile(this.tileSource);
            if (tileSetFile.exists() && tileSetFile.equals(file)) {
                if (reloadTileSource()) {
                    reloaded = true;
                }
            }
        }
        if (this.tileSetNode != null) {
            if (this.tileSetNode.handleReload(file, childWasReloaded)) {
                updateStatus();
                updateVertexData();
                reloaded = true;
            }
        }
        return reloaded;
    }

    private boolean reloadTileSource() {
        ISceneModel model = getModel();
        if (model != null) {
            this.tileSetNode = null;
            if (!this.tileSource.isEmpty()) {
                try {
                    Node node = model.loadNode(this.tileSource);
                    if (node instanceof TileSetNode) {
                        this.tileSetNode = (TileSetNode)node;
                        this.tileSetNode.setModel(getModel());
                    }
                } catch (Exception e) {
                    // no reason to handle exception since having a null type is invalid state, will be caught in validateComponent below
                }
            }
            updateStatus();
            updateVertexData();
            // attempted to reload
            return true;
        }
        return false;
    }

    private void updateVertexData() {
        if (this.tileSetNode == null || this.tileSetNode.getLoadedImage() == null || !getStatus().isOK() || !this.tileSetNode.getStatus().isOK()) {
            this.vertexData = null;
            return;
        }

        TileSetUtil.Metrics metrics = calculateMetrics(this.tileSetNode);
        if (metrics == null) {
            this.vertexData = null;
            return;
        }

        int totalCellCount = 0;
        List<LayerNode> layers = getLayers();
        for (LayerNode layer : layers) {
            Map<Long, Integer> cells = layer.getCells();
            totalCellCount += cells.size();
        }

        final int tileWidth = this.tileSetNode.getTileWidth();
        final int tileHeight = this.tileSetNode.getTileHeight();

        final int vertexCount = totalCellCount * 4;
        final int componentCount = 5;
        this.vertexData = FloatBuffer.allocate(vertexCount * componentCount);

        RuntimeTextureSet rts = tileSetNode.getRuntimeTextureSet();

        AABB aabb = new AABB();
        FloatBuffer v = this.vertexData;
        for (LayerNode layer : layers) {
            Map<Long, Integer> cells = layer.getCells();
            int n = cells.size();
            if (n > 0) {
                final float z = layer.getZ();
                for (Map.Entry<Long, Integer> entry : cells.entrySet()) {
                    int x = Layer.toCellX(entry.getKey());
                    int y = Layer.toCellY(entry.getKey());
                    float x0 = x * tileWidth;
                    float x1 = x0 + tileWidth;
                    float y0 = y * tileHeight;
                    float y1 = y0 + tileHeight;
                    int tile = entry.getValue();

                    Vector2f minUV = rts.getMin(tile);
                    Vector2f maxUV = rts.getMax(tile);

                    v.put(minUV.x); v.put(maxUV.y); v.put(x0); v.put(y0); v.put(z);
                    v.put(minUV.x); v.put(minUV.y); v.put(x0); v.put(y1); v.put(z);
                    v.put(maxUV.x); v.put(minUV.y); v.put(x1); v.put(y1); v.put(z);
                    v.put(maxUV.x); v.put(maxUV.y); v.put(x1); v.put(y0); v.put(z);

                    aabb.union(x0, y0, z);
                    aabb.union(x1, y1, z);
                }
            }
        }

        v.flip();
        setAABB(aabb);
    }

    private static TileSetUtil.Metrics calculateMetrics(TileSetNode node) {
        return TileSetUtil.calculateMetrics(node.getLoadedImage(), node.getTileWidth(), node.getTileHeight(), node.getTileMargin(), node.getTileSpacing(), null, 1.0f, 0.0f);
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.writeObject(this.tileSource);
        out.writeObject(this.tileSetNode);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        this.tileSource = (String)in.readObject();
        this.tileSetNode = (TileSetNode)in.readObject();
    }
}
