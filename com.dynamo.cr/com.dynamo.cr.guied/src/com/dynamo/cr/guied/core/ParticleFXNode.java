package com.dynamo.cr.guied.core;

import java.io.InputStream;
import java.io.InputStreamReader;

import javax.media.opengl.GL2;
import javax.vecmath.Vector3d;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.swt.graphics.Image;

import com.dynamo.cr.guied.Activator;
import com.dynamo.cr.guied.util.GuiNodeStateBuilder;
import com.dynamo.cr.properties.Property;
import com.dynamo.cr.properties.Property.EditorType;
import com.dynamo.cr.sceneed.core.AABB;
import com.dynamo.cr.sceneed.core.ISceneModel;
import com.dynamo.particle.proto.Particle;
import com.dynamo.particle.proto.Particle.Emitter;
import com.dynamo.particle.proto.Particle.Modifier;
import com.dynamo.particle.proto.Particle.ParticleFX;
import com.dynamo.proto.DdfMath.Point3;
import com.google.protobuf.TextFormat;

@SuppressWarnings("serial")
public class ParticleFXNode extends ClippingNode {

    @Property(editorType = EditorType.DROP_DOWN, category = "")
    private String particlefx = "";
    
    private transient Particle.ParticleFX pfxDesc;
    private transient Object[] emitters;
    private transient Object[] modifiers;

    public Vector3d getSize() {
        return new Vector3d(1.0, 1.0, 0.0);
    }

    public void setSize() {
        return;
    }

    public boolean isTextureVisible() {
        return false;
    }

    public boolean isAlphaVisible() {
        return true;
    }
    public boolean isInheritAlphaVisible() {
        return true;
    }

    public boolean isSizeVisible() {
        return false;
    }

    public boolean isColorVisible() {
        //return true;
        return false;
    }

    public boolean isBlendModeVisible() {
        return false;
    }

    public boolean isPivotVisible() {
        return true;
    }

    public boolean isAdjustModeVisible() {
        return true;
    }

    public boolean isXanchorVisible() {
        return true;
    }

    public boolean isYanchorVisible() {
        return true;
    }

    public boolean isSizeModeVisible() {
        return false;
    }

    // ParticleFX property
    public String getParticlefx() {
        return this.particlefx;
    }
    
    public void setParticlefx(String particleFX) {
        this.particlefx = particleFX;
        reloadResources();
        GuiNodeStateBuilder.setField(this, "particlefx", particleFX);
    }
    
    public void resetParticlefx() {
        this.particlefx = (String)GuiNodeStateBuilder.resetField(this, "particlefx");
        reloadResources();
    }
    
    public boolean isParticlefxOverridden() {
        return GuiNodeStateBuilder.isFieldOverridden(this, "particlefx", this.particlefx);
    }
    
    public Object[] getParticlefxOptions() {
        ParticleFXScenesNode node = (ParticleFXScenesNode)getScene().getParticleFXScenesNode();
        return node.getParticleFXScenes(getModel()).toArray();
    }
    
    public Object[] getEmitters() {
        return this.emitters;
        //return this.emitters.toArray();
    }
    
    public Object[] getModifiers() {
        return this.emitters;
    }

    @Override
    public void dispose(GL2 gl) {
        super.dispose(gl);
    }
    
    @Override
    public void setModel(ISceneModel model) {
        super.setModel(model);
        if (model != null) {
            reloadResources();
        }
    }

    @Override
    public boolean handleReload(IFile file, boolean childWasReloaded) {
        return reloadResources();
    }

    @Override
    public Image getIcon() {
        if(GuiNodeStateBuilder.isStateSet(this)) {
            if(isTemplateNodeChild()) {
                return Activator.getDefault().getImageRegistry().get(Activator.BOX_NODE_OVERRIDDEN_TEMPLATE_IMAGE_ID);
            }
            return Activator.getDefault().getImageRegistry().get(Activator.BOX_NODE_OVERRIDDEN_IMAGE_ID);
        }
        return Activator.getDefault().getImageRegistry().get(Activator.BOX_NODE_IMAGE_ID);
    }

    public boolean isSizeEditable() {
        return false;
    }

    private void updateAABB() {
        AABB aabb = new AABB();
        aabb.setIdentity();
        
        int emitterCount = this.emitters.length;
        for (int i = 0; i < emitterCount; ++i)
        {
            Particle.Emitter e = (Emitter) this.emitters[i];
            Point3 pos = e.getPosition();
            aabb.union(pos.getX(), pos.getY(), pos.getZ());
        }
        
        // AbstractModifierNode::updateAABB()
        /*int s = 10;
        aabb.union(-s, -s, -s);
        aabb.union(s, s, s);
        setAABB(aabb);*/
        int modifierCount = this.modifiers.length;
        for (int i = 0; i < modifierCount; ++i)
        {
            Particle.Modifier m = (Modifier) this.modifiers[i];
            Point3 pos = m.getPosition();
            aabb.union(pos.getX(), pos.getY(), pos.getZ());
        }
        
        setAABB(aabb);
    }
    
    private ParticleFXSceneNode getParticleFXSceneNode() {
        ParticleFXSceneNode pfxSceneNode = ((ParticleFXScenesNode) getScene().getParticleFXScenesNode()).getParticleFXScenesNode(this.particlefx);
        if (pfxSceneNode != null) {
            TemplateNode parentTemplate = this.getParentTemplateNode();
            if (parentTemplate != null && parentTemplate.getTemplateScene() != null) {
                pfxSceneNode = ((ParticleFXScenesNode) parentTemplate.getTemplateScene().getParticleFXScenesNode()).getParticleFXScenesNode(this.particlefx);
            }
        }
        return pfxSceneNode;
    }
    
    private static ParticleFX loadParticleFXDesc(ISceneModel model, String path) {
        if (!path.isEmpty()) {
            InputStream in = null;
            try {
                IFile file = model.getFile(path);
                in = file.getContents();
                Particle.ParticleFX.Builder builder = Particle.ParticleFX.newBuilder();
                TextFormat.merge(new InputStreamReader(in), builder);
                return builder.build();
            } catch (Exception e) {
                // no reason to handle exception since having a null type is invalid state, will be caught in validateComponent
            } finally {
                IOUtils.closeQuietly(in);
            }
        }
        return null;
    }

    private boolean reloadResources() {
        ISceneModel model = getModel();
        if (model != null && getParticleFXSceneNode() != null) {
            this.pfxDesc = loadParticleFXDesc(model, getParticleFXSceneNode().getParticlefx());
            if (this.pfxDesc != null) {
                this.emitters = this.pfxDesc.getEmittersList().toArray();
                this.modifiers = this.pfxDesc.getModifiersList().toArray();
            }
            
            updateAABB();
            updateStatus();
        }
        
        return true;
    }
}