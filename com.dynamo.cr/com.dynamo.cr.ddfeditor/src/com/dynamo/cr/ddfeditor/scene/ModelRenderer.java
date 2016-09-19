package com.dynamo.cr.ddfeditor.scene;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.EnumSet;

import javax.media.opengl.GL;
import javax.media.opengl.GL2;
import javax.vecmath.Point3d;

import com.dynamo.cr.sceneed.core.INodeRenderer;
import com.dynamo.cr.sceneed.core.RenderContext;
import com.dynamo.cr.sceneed.core.RenderContext.Pass;
import com.dynamo.cr.sceneed.core.RenderData;

public class ModelRenderer implements INodeRenderer<ModelNode> {

    private static final float COLOR[] = new float[] { 1.0f, 1.0f, 1.0f, 1.0f };
    private static final EnumSet<Pass> passes = EnumSet.of(Pass.OUTLINE, Pass.TRANSPARENT, Pass.SELECTION);

    public ModelRenderer() {
    }

    @Override
    public void dispose(GL2 gl) {
    }

    @Override
    public void setup(RenderContext renderContext, ModelNode node) {
        MeshNode mesh = node.getMeshNode();
        if (mesh != null && passes.contains(renderContext.getPass())) {
            renderContext.add(this, node, new Point3d(), mesh);
        }
    }

    @Override
    public void render(RenderContext renderContext, ModelNode node,
            RenderData<ModelNode> renderData) {

        MeshNode mesh = node.getMeshNode();
        FloatBuffer pos = mesh.getPositions();
        IntBuffer indices = mesh.getIndices();

        GL2 gl = renderContext.getGL();

        gl.glColor4fv(renderContext.selectColor(node, COLOR), 0);
        gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
        gl.glVertexPointer(3, GL.GL_FLOAT, 0, pos);
        gl.glDrawElements(GL.GL_TRIANGLES, indices.capacity(), GL.GL_UNSIGNED_INT, indices);
        gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
    }

}
