package com.dynamo.cr.tileeditor.scene;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.media.opengl.GL2;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dynamo.bob.textureset.TextureSetGenerator.TextureSetResult;
import com.dynamo.bob.tile.ConvexHull;
import com.dynamo.bob.tile.TileSetGenerator;
import com.dynamo.bob.tile.TileSetUtil;
import com.dynamo.bob.tile.TileSetUtil.ConvexHulls;
import com.dynamo.cr.editor.ui.EditorUIPlugin;
import com.dynamo.cr.properties.GreaterEqualThanZero;
import com.dynamo.cr.properties.NotEmpty;
import com.dynamo.cr.properties.Property;
import com.dynamo.cr.properties.Property.EditorType;
import com.dynamo.cr.properties.Range;
import com.dynamo.cr.properties.Resource;
import com.dynamo.cr.properties.ValidatorUtil;
import com.dynamo.cr.sceneed.core.ISceneModel;
import com.dynamo.cr.sceneed.core.Node;
import com.dynamo.cr.sceneed.core.TextureHandle;
import com.dynamo.textureset.proto.TextureSetProto.TextureSet;
import com.dynamo.textureset.proto.TextureSetProto.TextureSet.Builder;
import com.dynamo.tile.proto.Tile.TileSet;

@SuppressWarnings("serial")
public class TileSetNode extends TextureSetNode {

    private static Logger logger = LoggerFactory.getLogger(TileSetNode.class);

    // TODO: Should be configurable
    private static final int PLANE_COUNT = 16;

    @Property(editorType=EditorType.RESOURCE, extensions={"jpg", "png"})
    @Resource
    private String image = "";

    @Property
    @Range(min=1)
    private int tileWidth;

    @Property
    @Range(min=1)
    private int tileHeight;

    @Property
    @Range(min=0)
    private int tileMargin;

    @Property
    @Range(min=0)
    private int tileSpacing;

    @Property
    @GreaterEqualThanZero
    private int extrudeBorders;

    @Property
    @GreaterEqualThanZero
    private int innerPadding;

    @Property(editorType=EditorType.RESOURCE, extensions={"jpg", "png"})
    @Resource
    private String collision = "";

    @Property
    @NotEmpty(severity = IStatus.ERROR)
    private String materialTag = "";

    private List<CollisionGroupNode> tileCollisionGroups;
    // Used to simulate a shorter list of convex hulls.
    // convexHulls need to be retained in some cases to keep collision groups and keep undo functionality
    private int tileCollisionGroupCount;
    private List<ConvexHull> convexHulls;
    private float[] convexHullPoints = new float[0];

    // Graphics resources
    private transient BufferedImage loadedImage;
    private transient BufferedImage loadedCollision;
    private transient TextureHandle textureHandle;

    private transient RuntimeTextureSet runtimeTextureSet = new RuntimeTextureSet();

    public TileSetNode() {
        this.tileCollisionGroups = new ArrayList<CollisionGroupNode>();
        this.tileCollisionGroupCount = 0;
        this.convexHulls = new ArrayList<ConvexHull>();

        this.textureHandle = null;
    }

    @Override
    public void dispose(GL2 gl) {
        super.dispose(gl);
        this.textureHandle.clear(gl);
        this.runtimeTextureSet.dispose(gl);
    }

    public String getImage() {
        return image;
    }

    public IStatus validateImage() {
        if (this.image.isEmpty() && this.collision.isEmpty()) {
            return ValidatorUtil.createStatus(this, "image", IStatus.INFO, "EMPTY", null);
        }
        IStatus status = verifyImageDimensions();
        if (!status.isOK())
            return status;
        else
            return verifyTileDimensions(true, true);
    }

    public void setImage(String image) {
        if (!this.image.equals(image)) {
            this.image = image;
            this.loadedImage = null;
            if (!this.image.isEmpty()) {
                updateImage();
            }
            updateData();
        }
    }

    public int getTileWidth() {
        return this.tileWidth;
    }

    protected IStatus validateTileWidth() {
        return verifyTileDimensions(true, false);
    }

    public void setTileWidth(int tileWidth) {
        if (this.tileWidth != tileWidth) {
            this.tileWidth = tileWidth;
            updateData();
        }
    }

    public int getTileHeight() {
        return this.tileHeight;
    }

    protected IStatus validateTileHeight() {
        return verifyTileDimensions(false, true);
    }

    public void setTileHeight(int tileHeight) {
        if (this.tileHeight != tileHeight) {
            this.tileHeight = tileHeight;
            updateData();
        }
    }

    public int getTileMargin() {
        return this.tileMargin;
    }

    protected IStatus validateTileMargin() {
        if (this.tileMargin > 0)
            return verifyTileDimensions(true, true);
        else
            return Status.OK_STATUS;
    }

    public void setTileMargin(int tileMargin) {
        if (this.tileMargin != tileMargin) {
            this.tileMargin = tileMargin;
            updateData();
        }
    }

    public int getTileSpacing() {
        return this.tileSpacing;
    }

    public void setTileSpacing(int tileSpacing) {
        if (this.tileSpacing != tileSpacing) {
            this.tileSpacing = tileSpacing;
            updateData();
        }
    }

    public int getExtrudeBorders() {
        return this.extrudeBorders;
    }

    public void setExtrudeBorders(int extrudeBorders) {
        if (this.extrudeBorders != extrudeBorders) {
            this.extrudeBorders = extrudeBorders;
            updateData();
        }
    }

    public int getInnerPadding() {
        return this.innerPadding;
    }

    public void setInnerPadding(int innerPadding) {
        if (this.innerPadding != innerPadding) {
            this.innerPadding = innerPadding;
            updateData();
        }
    }

    public String getCollision() {
        return this.collision;
    }

    public IStatus validateCollision() {
        if (this.image.isEmpty() && this.collision.isEmpty()) {
            return ValidatorUtil.createStatus(this, "collision", IStatus.INFO, "EMPTY", null);
        }
        IStatus status = verifyImageDimensions();
        if (!status.isOK())
            return status;
        else
            return verifyTileDimensions(true, true);
    }

    public void setCollision(String collision) {
        if (!this.collision.equals(collision)) {
            this.collision = collision;
            this.loadedCollision = null;
            if (this.collision != null && !this.collision.equals("")) {
                updateCollision();
            }
            updateData();
        }
    }

    public String getMaterialTag() {
        return this.materialTag;
    }

    public void setMaterialTag(String materialTag) {
        this.materialTag = materialTag;
    }

    public boolean isMaterialTagVisible() {
        // Hide material tag since it's deprecated
        return false;
    }

    @Override
    protected void childAdded(Node child) {
        sortChildren();
    }

    public List<CollisionGroupNode> getCollisionGroups() {
        List<CollisionGroupNode> groups = new ArrayList<CollisionGroupNode>();
        for (Node child : getChildren()) {
            if (child instanceof CollisionGroupNode) {
                groups.add((CollisionGroupNode)child);
            }
        }
        return groups;
    }

    public List<AnimationNode> getAnimations() {
        List<AnimationNode> animations = new ArrayList<AnimationNode>();
        for (Node child : getChildren()) {
            if (child instanceof AnimationNode) {
                animations.add((AnimationNode)child);
            }
        }
        return animations;
    }

    public List<ConvexHull> getConvexHulls() {
        return Collections.unmodifiableList(this.convexHulls);
    }

    public List<CollisionGroupNode> getTileCollisionGroups() {
        int n = this.tileCollisionGroupCount;
        List<CollisionGroupNode> result = new ArrayList<CollisionGroupNode>(n);
        for (int i = 0; i < n; ++i) {
            CollisionGroupNode group = this.tileCollisionGroups.get(i);
            if (group != null && group.getParent() != null) {
                result.add(group);
            } else {
                result.add(null);
            }
        }
        return result;
    }

    public void setTileCollisionGroups(List<CollisionGroupNode> tileCollisionGroups) {
        this.tileCollisionGroups = new ArrayList<CollisionGroupNode>(tileCollisionGroups);
    }

    public float[] getConvexHullPoints() {
        return this.convexHullPoints;
    }

    public BufferedImage getLoadedImage() {
        return this.loadedImage;
    }

    public BufferedImage getLoadedCollision() {
        return this.loadedCollision;
    }

    @Override
    public TextureHandle getTextureHandle() {
        return this.textureHandle;
    }

    private void updateVertexData() {
        TileSetLoader loader = new TileSetLoader();
        try {
            TileSet tileSet = (TileSet) loader.buildMessage(null, this, new NullProgressMonitor());
            TextureSetResult result = TileSetGenerator.generate(tileSet, loadedImage, loadedCollision, true, true);
            if (result != null) {
                Builder textureSetBuilder = result.builder;
                TextureSet textureSet = textureSetBuilder.setTexture("").build();
                runtimeTextureSet.update(textureSet, result.uvTransforms);
                if (this.textureHandle == null) {
                    this.textureHandle = new TextureHandle();
                }
                this.textureHandle.setImage(result.image);
            }
        } catch (Exception e) {
            logger.error("Error occurred while creating tile source vertex-data", e);
        }
    }

    @Override
    public void setModel(ISceneModel model) {
        super.setModel(model);
        if (model != null) {
            updateImage();
            updateCollision();
            updateData();
        }
    }

    private void updateImage() {
        if (!this.image.isEmpty() && getModel() != null) {
            this.loadedImage = getModel().getImage(this.image);
        }
    }

    private void updateCollision() {
        if (!this.collision.isEmpty() && getModel() != null) {
            this.loadedCollision = getModel().getImage(this.collision);
        }
    }

    private IStatus verifyImageDimensions() {
        if (this.loadedImage != null && this.loadedCollision != null) {
            if (this.loadedImage.getWidth() != this.loadedCollision.getWidth() || this.loadedImage.getHeight() != this.loadedCollision.getHeight()) {
                return createErrorStatus(Messages.TileSetNode_DIFF_IMG_DIMS, new Object[] {
                        this.loadedImage.getWidth(),
                        this.loadedImage.getHeight(),
                        this.loadedCollision.getWidth(),
                        this.loadedCollision.getHeight() });
            }
        }
        return Status.OK_STATUS;
    }

    private IStatus verifyTileDimensions(boolean verifyWidth, boolean verifyHeight) {
        if ((verifyWidth || verifyHeight) && (this.loadedImage != null || this.loadedCollision != null)) {
            int imageWidth = 0;
            int imageHeight = 0;
            if (this.loadedImage != null) {
                imageWidth = this.loadedImage.getWidth();
                imageHeight = this.loadedImage.getHeight();
            } else {
                imageWidth = this.loadedCollision.getWidth();
                imageHeight = this.loadedCollision.getHeight();
            }
            if (verifyWidth) {
                int totalTileWidth = this.tileWidth + this.tileMargin;
                if (totalTileWidth > imageWidth) {
                    return createErrorStatus(Messages.TileSetNode_TILE_WIDTH_GT_IMG, new Object[] {
                            totalTileWidth, imageWidth });
                }
            }
            if (verifyHeight) {
                int totalTileHeight = this.tileHeight + this.tileMargin;
                if (totalTileHeight > imageHeight) {
                    return createErrorStatus(Messages.TileSetNode_TILE_HEIGHT_GT_IMG, new Object[] {
                            totalTileHeight, imageHeight });
                }
            }
        }
        return Status.OK_STATUS;
    }

    private IStatus createErrorStatus(String message, Object[] binding) {
        return new Status(IStatus.ERROR, EditorUIPlugin.PLUGIN_ID, NLS.bind(message, binding));
    }

    public void updateData() {
        if (getModel() != null) {
            updateConvexHulls();
            updateVertexData();
        }
    }

    public void updateConvexHulls() {
        if (getModel() != null) {
            ConvexHulls result = null;
            if (this.loadedCollision != null && this.tileWidth > 0 && this.tileHeight > 0 && this.tileMargin >= 0 && this.tileSpacing >= 0) {
                result = TileSetUtil.calculateConvexHulls(loadedCollision.getAlphaRaster(), PLANE_COUNT,
                    loadedCollision.getWidth(), loadedCollision.getHeight(),
                    tileWidth, tileHeight, tileMargin, tileSpacing);
            }
            if (result != null) {
                int tileCount = result.hulls.length;
                this.convexHulls = Arrays.asList(result.hulls);
                // Add additional slots for tile collision groups (never shrink
                // the tile collision group list since we might lose edited
                // groups and break undo)
                for (int i = this.tileCollisionGroups.size(); i < tileCount; ++i) {
                    this.tileCollisionGroups.add(null);
                }
                // Simulate a shorter list (see convexHullCount and getConvexHulls)
                this.tileCollisionGroupCount = tileCount;
                this.convexHullPoints = result.points;
                return;
            }
        }
        this.convexHullPoints = new float[0];
        if (!this.convexHulls.isEmpty()) {
            this.convexHulls = new ArrayList<ConvexHull>();
        }
        this.tileCollisionGroupCount = 0;
    }

    @Override
    public boolean handleReload(IFile file, boolean childWasReloaded) {
        boolean reloaded = false;
        if (image != null && image.length() > 0) {
            IFile imgFile = getModel().getFile(image);
            if (file.equals(imgFile)) {
                updateImage();
                reloaded = true;
            }
        }
        if (collision != null && collision.length() > 0) {
            IFile collisionFile = getModel().getFile(collision);
            if (file.equals(collisionFile)) {
                updateCollision();
                reloaded = true;
            }
        }
        if (reloaded) {
            updateData();
        }
        return reloaded;
    }

    public int calculateTileCount() {
        int imageWidth = 0;
        int imageHeight = 0;
        if (this.loadedImage != null) {
            imageWidth = this.loadedImage.getWidth();
            imageHeight = this.loadedImage.getHeight();
        } else if (this.loadedCollision != null) {
            imageWidth = this.loadedCollision.getWidth();
            imageHeight = this.loadedCollision.getHeight();
        }
        if (imageWidth > 0 && imageHeight > 0) {
            int tilesPerRow = TileSetUtil.calculateTileCount(this.tileWidth, imageWidth, this.tileMargin, this.tileSpacing);
            int tilesPerColumn = TileSetUtil.calculateTileCount(this.tileHeight, imageHeight, this.tileMargin, this.tileSpacing);
            return tilesPerRow * tilesPerColumn;
        }
        return 0;
    }

    public String getChildId(Node node) {
        if (node instanceof AnimationNode) {
            return ((AnimationNode)node).getId();
        } else if (node instanceof CollisionGroupNode) {
            return ((CollisionGroupNode)node).getId();
        } else {
            return null;
        }
    }

    public void sortChildren() {
        sortChildren(new Comparator<Node>() {
            @Override
            public int compare(Node o1, Node o2) {
                if ((o1 instanceof AnimationNode && o2 instanceof CollisionGroupNode)
                        || (o1 instanceof CollisionGroupNode && o2 instanceof AnimationNode)) {
                    if (o1 instanceof CollisionGroupNode) {
                        return 1;
                    } else {
                        return -1;
                    }
                } else {
                    String id1 = getChildId(o1);
                    String id2 = getChildId(o2);
                    return id1.compareTo(id2);
                }

            }
        });
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.writeObject(this.image);
        out.writeInt(this.tileWidth);
        out.writeInt(this.tileHeight);
        out.writeInt(this.tileMargin);
        out.writeInt(this.tileSpacing);
        out.writeObject(this.collision);
        out.writeObject(this.materialTag);
        out.writeObject(this.tileCollisionGroups);
    }

    @SuppressWarnings({"unchecked"})
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        this.image = (String)in.readObject();
        this.tileWidth = in.readInt();
        this.tileHeight = in.readInt();
        this.tileMargin = in.readInt();
        this.tileSpacing = in.readInt();
        this.collision = (String)in.readObject();
        this.materialTag = (String)in.readObject();
        this.tileCollisionGroups = (List<CollisionGroupNode>)in.readObject();
        this.convexHulls = new ArrayList<ConvexHull>();
        this.runtimeTextureSet = new RuntimeTextureSet();
    }

    @Override
    public RuntimeTextureSet getRuntimeTextureSet() {
        return runtimeTextureSet;
    }

    @Override
    public List<String> getAnimationIds() {
        List<Node> children = getChildren();
        List<String> animationIds = new ArrayList<String>(children.size());
        for (Node child : children) {
            if (child instanceof AnimationNode) {
                animationIds.add(((AnimationNode)child).getId());
            }
        }
        return animationIds;
    }
}
