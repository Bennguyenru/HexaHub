package com.dynamo.cr.guied.ui;

import java.util.EnumSet;

import javax.media.opengl.GL;
import javax.media.opengl.GL2;
import javax.vecmath.Point3d;

import com.dynamo.cr.guied.core.BoxNode;
import com.dynamo.cr.guied.core.ClippingNode;
import com.dynamo.cr.guied.core.ClippingNode.ClippingState;
import com.dynamo.cr.guied.util.Clipping;
import com.dynamo.cr.sceneed.core.AABB;
import com.dynamo.cr.sceneed.core.INodeRenderer;
import com.dynamo.cr.sceneed.core.RenderContext;
import com.dynamo.cr.sceneed.core.RenderContext.Pass;
import com.dynamo.cr.sceneed.core.RenderData;
import com.dynamo.gui.proto.Gui.NodeDesc.Pivot;
import com.jogamp.opengl.util.texture.Texture;

public class BoxNodeRenderer implements INodeRenderer<BoxNode> {

    private static final EnumSet<Pass> passes = EnumSet.of(Pass.OUTLINE, Pass.TRANSPARENT, Pass.SELECTION);

    @Override
    public void dispose(GL2 gl) {
    }

    @Override
    public void setup(RenderContext renderContext, BoxNode node) {
        if (passes.contains(renderContext.getPass())) {
            if((renderContext.getPass() == Pass.OUTLINE) && (node.isClipping()) && (!Clipping.getShowClippingNodes())) {
                return;
            }
            ClippingState clippingState = node.getClippingState();
            if (clippingState != null) {
                RenderData<BoxNode> data = renderContext.add(this, node, new Point3d(), clippingState);
                data.setIndex(node.getClippingKey());
            }
            if (!node.isClipping() || node.getClippingVisible()) {
                ClippingState childState = null;
                ClippingNode clipper = node.getClosestParentClippingNode();
                if (clipper != null) {
                    childState = clipper.getChildClippingState();
                }
                RenderData<BoxNode> data = renderContext.add(this, node, new Point3d(), childState);
                data.setIndex(node.getRenderKey());
            }
        }
    }

    private double pivotOffsetX(BoxNode node, double width) {
        Pivot p = node.getPivot();

        switch (p) {
        case PIVOT_CENTER:
        case PIVOT_S:
        case PIVOT_N:
            return width * 0.5;

        case PIVOT_NE:
        case PIVOT_E:
        case PIVOT_SE:
            return width;

        case PIVOT_SW:
        case PIVOT_W:
        case PIVOT_NW:
            return 0;
        }

        assert false;
        return Double.MAX_VALUE;
    }

    private double pivotOffsetY(BoxNode node, double height) {
        Pivot p = node.getPivot();

        switch (p) {
        case PIVOT_CENTER:
        case PIVOT_E:
        case PIVOT_W:
            return height * 0.5;

        case PIVOT_N:
        case PIVOT_NE:
        case PIVOT_NW:
            return height;

        case PIVOT_S:
        case PIVOT_SW:
        case PIVOT_SE:
            return 0;
        }

        assert false;
        return Double.MAX_VALUE;
    }

    @Override
    public void render(RenderContext renderContext, BoxNode node, RenderData<BoxNode> renderData) {
        GL2 gl = renderContext.getGL();

        Texture texture = null;
        if (node.getTextureHandle() != null) {
            texture = node.getTextureHandle().getTexture(gl);
        }

        boolean clipping = renderData.getUserData() != null;
        if (clipping && renderData.getPass() == Pass.TRANSPARENT) {
            Clipping.beginClipping(gl);
            ClippingState state = (ClippingState)renderData.getUserData();
            Clipping.setupClipping(gl, state);
        }

        boolean transparent = renderData.getPass() == Pass.TRANSPARENT;
        if (transparent) {
            if (texture != null) {
                texture.bind(gl);
                texture.setTexParameteri(gl, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
                texture.setTexParameteri(gl, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
                texture.setTexParameteri(gl, GL.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP);
                texture.setTexParameteri(gl, GL.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP);
                texture.enable(gl);
            }

            switch (node.getBlendMode()) {
            case BLEND_MODE_ALPHA:
                gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
                break;
            case BLEND_MODE_ADD:
            case BLEND_MODE_ADD_ALPHA:
                gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE);
                break;
            case BLEND_MODE_MULT:
                gl.glBlendFunc(GL.GL_ZERO, GL.GL_SRC_COLOR);
                break;
            }
        }

        if (renderContext.getPass() == Pass.OUTLINE && node.isClipping()) {
            if (renderContext.isSelected(node)) {
                gl.glColor4fv(Clipping.OUTLINE_SELECTED_COLOR, 0);
            } else {
                gl.glColor4fv(Clipping.OUTLINE_COLOR, 0);
            }
        } else {
            float[] color = node.calcNormRGBA();
            gl.glColor4fv(renderContext.selectColor(node, color), 0);
        }

        double us[] = new double[4];
        double vs[] = new double[4];
        double ys[] = new double[4];
        double xs[] = new double[4];

        double x0 = -pivotOffsetX(node, node.getSize().x);
        double y0 = -pivotOffsetY(node, node.getSize().y);
        double x1 = x0 + node.getSize().x;
        double y1 = y0 + node.getSize().y;

        xs[0] = x0;
        xs[1] = x0 + node.getSlice9().x;
        xs[2] = x1 - node.getSlice9().z;
        xs[3] = x1;

        ys[0] = y0;
        ys[1] = y0 + node.getSlice9().w;
        ys[2] = y1 - node.getSlice9().y;
        ys[3] = y1;

        float sU = 0;
        float sV = 0;

        if (texture != null)
        {
            sU = 1.0f / (float)texture.getImageWidth();
            sV = 1.0f / (float)texture.getImageHeight();
        }

        us[0] = 0;
        us[1] = sU * node.getSlice9().x;
        us[2] = 1.0f - sU * node.getSlice9().z;
        us[3] = 1.0f;

        vs[0] = 1;
        vs[1] = 1 - sV * node.getSlice9().w;
        vs[2] = sV * node.getSlice9().y;
        vs[3] = 0.0f;

        gl.glBegin(GL2.GL_QUADS);

        for (int i=0;i<3;i++)
        {
            for (int j=0;j<3;j++)
            {
                gl.glTexCoord2d(us[i], vs[j]);
                gl.glVertex2d(xs[i], ys[j]);

                gl.glTexCoord2d(us[i+1], vs[j]);
                gl.glVertex2d(xs[i+1], ys[j]);

                gl.glTexCoord2d(us[i+1], vs[j+1]);
                gl.glVertex2d(xs[i+1], ys[j+1]);

                gl.glTexCoord2d(us[i], vs[j+1]);
                gl.glVertex2d(xs[i], ys[j+1]);
            }
        }
        gl.glEnd();

        // Update AABB
        AABB aabb = new AABB();
        aabb.union(x0, y0, 0.0);
        aabb.union(x1, y1, 0.0);
        node.setAABB(aabb);

        if (transparent) {
            if (texture != null) {
                texture.disable(gl);
            }
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        }

        if (clipping && renderData.getPass() == Pass.TRANSPARENT) {
            Clipping.endClipping(gl);
        }
    }

}
