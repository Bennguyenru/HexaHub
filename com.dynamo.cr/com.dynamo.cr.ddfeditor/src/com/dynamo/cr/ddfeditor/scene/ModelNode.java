package com.dynamo.cr.ddfeditor.scene;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.media.opengl.GL2;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import com.dynamo.bob.pipeline.ColladaUtil;
import com.dynamo.cr.go.core.ComponentTypeNode;
import com.dynamo.cr.properties.NotEmpty;
import com.dynamo.cr.properties.Property;
import com.dynamo.cr.properties.Property.EditorType;
import com.dynamo.cr.properties.Resource;
import com.dynamo.cr.sceneed.core.AABB;
import com.dynamo.cr.sceneed.core.ISceneModel;
import com.dynamo.cr.sceneed.core.Node;
import com.dynamo.cr.sceneed.core.TextureHandle;
import com.dynamo.model.proto.ModelProto.ModelDesc;
import com.dynamo.model.proto.ModelProto.ModelDesc.Builder;
import com.dynamo.rig.proto.Rig.Bone;
import com.dynamo.rig.proto.Rig.Skeleton;
import com.google.protobuf.Message;

@SuppressWarnings("serial")
public class ModelNode extends ComponentTypeNode {

    private transient MeshNode meshNode;
    private transient TextureHandle textureHandle;

    @Property(editorType=EditorType.RESOURCE, extensions={"dae"})
    @Resource
    @NotEmpty
    private String mesh = "";

    @Property(editorType=EditorType.RESOURCE, extensions={"material"})
    @Resource
    @NotEmpty
    private String material = "";

    @Property(editorType=EditorType.RESOURCE, extensions={"png", "tga", "jpg", "jpeg", "gif", "bmp", "cubemap"})
    @Resource
    private String texture = "";

    @Property(editorType=EditorType.RESOURCE, extensions={"dae"})
    @Resource
    private String skeleton = "";

    @Property(editorType=EditorType.RESOURCE, extensions={"dae"})
    @Resource
    private String animations = "";

    @Property(editorType = EditorType.DROP_DOWN, category = "")
    private String defaultAnimation = "";
    private ArrayList<String> animationOptions = new ArrayList<String>();


    @Override
    public void dispose(GL2 gl) {
        super.dispose(gl);
        textureHandle.clear(gl);
    }

    public TextureHandle getTextureHandle() {
        return textureHandle;
    }

    private void updateTexture() {
        if(this.textureHandle == null) {
            this.textureHandle = new TextureHandle();
        }
        if(this.texture.isEmpty()) {
            this.textureHandle.setImage(null);
            return;
        }
        BufferedImage image = getModel().getImage(this.texture);
        this.textureHandle.setImage(image);
    }


    public ModelNode(ModelDesc modelDesc) {
        super();
        setFlags(Flags.TRANSFORMABLE);
        mesh = modelDesc.getMesh();
        material = modelDesc.getMaterial();
        if (modelDesc.getTexturesCount() > 0) {
            texture = modelDesc.getTextures(0);
        }
        skeleton = modelDesc.getSkeleton();
        animations = modelDesc.getAnimations();
        defaultAnimation = modelDesc.getDefaultAnimation();
    }

    @Override
    public void setModel(ISceneModel model) {
        super.setModel(model);
        reload();
    }

    @Override
    protected IStatus validateNode() {
        if (meshNode != null) {
            return meshNode.validateNode();
        } else {
            return Status.OK_STATUS;
        }
    }

    @Override
    public boolean handleReload(IFile file, boolean childWasReloaded) {
        if (!this.mesh.isEmpty()) {
            updateTexture();

            IFile resFile = getModel().getFile(this.mesh);
            if (resFile.exists() && resFile.equals(file)) {
                reload();
                return true;
            }

            if(!this.skeleton.isEmpty()) {
                resFile = getModel().getFile(this.skeleton);
                if (resFile.exists() && resFile.equals(file)) {
                    reload();
                    return true;
                }
            }

            if(!this.animations.isEmpty()) {
                resFile = getModel().getFile(this.animations);
                if (resFile.exists() && resFile.equals(file)) {
                    reload();
                    return true;
                }
            }

        }

        return false;
    }


    private void updateAnimations() {
        animationOptions.clear();
        try {
            IFile animFile = getModel().getFile(this.animations.isEmpty() ? this.mesh : this.animations);
            ColladaUtil.loadAnimationIds(animFile.getContents(), animationOptions);
        } catch (Exception e) {
            animationOptions.clear();
        }
        if(animationOptions.contains(defaultAnimation)) {
            return;
        }
        defaultAnimation = animationOptions.isEmpty() ? "" : animationOptions.get(0);
        setDefaultAnimation(defaultAnimation);
    }

    public String getDefaultAnimation() {
        return this.defaultAnimation;
    }

    public void setDefaultAnimation(String defaultAnimation) {
        this.defaultAnimation = defaultAnimation;
        updateStatus();
        updateAABB();
    }

    public Object[] getDefaultAnimationOptions() {
        if(animationOptions.isEmpty()) {
            return new Object[0];
        }
        return animationOptions.toArray();
    }

    private Node addBone(int boneIndex, Node node, List<Bone> bones, List<String> boneIds) {
        ModelBoneNode boneNode = new ModelBoneNode(boneIds.get(boneIndex));
        if(node != null) {
            node.addChild(boneNode);
        }
        int i = 0;
        for(; i < bones.size(); i++) {
            if(bones.get(i).getParent() == boneIndex) {
                addBone(i, boneNode, bones, boneIds);
            }
        }
        return boneNode;
    }

    private void updateBones() {
        clearChildren();

        Skeleton.Builder skeletonBuilder = Skeleton.newBuilder();
        ArrayList<String> boneIds = new ArrayList<String>();
        try {
            IFile skeletonFile = getModel().getFile(this.skeleton.isEmpty() ? this.mesh : this.skeleton);
            ColladaUtil.loadSkeleton(skeletonFile.getContents(), skeletonBuilder, boneIds);
        } catch (Exception e) {
            updateStatus();
            return;
        }

        int rootBoneIndex = 0;
        List<Bone> bones = skeletonBuilder.getBonesList();
        for(; rootBoneIndex < bones.size(); rootBoneIndex++) {
            if(bones.get(rootBoneIndex).getParent() == 0xffff) {
                break;
            }
        }
        if(rootBoneIndex < bones.size()) {
            Node rootSkeletonNode = addBone(rootBoneIndex, this, bones, boneIds);
            this.addChild(rootSkeletonNode);
        }
    }

    private boolean reload() {
        if (getModel() == null) {
            return false;
        }
        updateTexture();

        if (!mesh.equals("")) {
            try {
                meshNode = (MeshNode) getModel().loadNode(mesh);
            } catch (Exception e) {
                updateStatus();
                return false;
            }
        }

        updateBones();
        updateAnimations();
        updateAABB();
        updateStatus();
        return true;
    }

    private void updateAABB() {
        if (meshNode != null && meshNode.getPositions() != null) {
            AABB aabb = new AABB();

            FloatBuffer pos = meshNode.getPositions();
            for (int i = 0; i < pos.limit(); i+=3) {
                float x = pos.get(i+0);
                float y = pos.get(i+1);
                float z = pos.get(i+2);
                aabb.union(x, y, z);
            }
            setAABB(aabb);
        }
    }

    public void setMesh(String mesh) {
        this.mesh = mesh;
        reload();
    }

    public String getMesh() {
        return mesh;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public String getMaterial() {
        return material;
    }

    public void setTexture(String texture) {
        this.texture = texture;
        updateTexture();
    }

    public String getTexture() {
        return texture;
    }

    public void setSkeleton(String mesh) {
        this.skeleton = mesh;
        reload();
    }

    public String getSkeleton() {
        return skeleton;
    }

    public void setAnimations(String mesh) {
        this.animations = mesh;
        reload();
    }

    public String getAnimations() {
        return animations;
    }

    public MeshNode getMeshNode() {
        return meshNode;
    }

    public void setMeshNode(MeshNode meshNode) {
        this.meshNode = meshNode;
    }

    public Message buildMessage() {
        Builder b = ModelDesc.newBuilder()
            .setMesh(this.mesh)
            .setMaterial(this.material)
            .setSkeleton(this.skeleton)
            .setAnimations(this.animations)
            .setDefaultAnimation(this.defaultAnimation);

        if (texture.length() > 0) {
            b.addTextures(texture);
        }
        return b.build();
    }

}
