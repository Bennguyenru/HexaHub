package com.dynamo.bob.pipeline;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.imageio.ImageIO;

import org.apache.commons.io.FilenameUtils;

import com.dynamo.bob.Builder;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Task;
import com.dynamo.bob.Task.TaskBuilder;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.textureset.TextureSetGenerator.TextureSetResult;
import com.dynamo.bob.tile.TileSetGenerator;
import com.dynamo.graphics.proto.Graphics.TextureImage;
import com.dynamo.textureset.proto.TextureSetProto.TextureSet;
import com.dynamo.tile.proto.Tile.TileSet;
import com.google.protobuf.TextFormat;

@BuilderParams(name = "TileSet", inExts = {".tileset", ".tilesource"}, outExt = ".texturesetc")
public class TileSetBuilder extends Builder<Void>  {

    @Override
    public Task<Void> create(IResource input) throws IOException, CompileExceptionError {
        TileSet.Builder builder = TileSet.newBuilder();
        TextFormat.merge(new InputStreamReader(new ByteArrayInputStream(input.getContent())), builder);
        TileSet tileSet = builder.build();
        String imgPath = tileSet.getImage();
        String collisionPath = tileSet.getCollision();
        IResource image = this.project.getResource(imgPath);
        IResource collision = this.project.getResource(collisionPath);
        if (image.exists() || collision.exists()) {
            TaskBuilder<Void> taskBuilder = Task.<Void>newBuilder(this)
                    .setName(params.name())
                    .addInput(input)
                    .addOutput(input.changeExt(params.outExt()));
            String texturePath = String.format("%s__%s_tilesource.%s", FilenameUtils.getPath(input.getPath()),
                    FilenameUtils.getBaseName(input.getPath()),
 "texturec");
            taskBuilder.addOutput(input.getResource(texturePath).output());
            if (image.exists()) {
                taskBuilder.addInput(image);
            }
            if (collision.exists()) {
                taskBuilder.addInput(collision);
            }
            return taskBuilder.build();
        } else {
            if (!imgPath.isEmpty()) {
                BuilderUtil.checkResource(this.project, input, "image", imgPath);
            } else if (!collisionPath.isEmpty()) {
                BuilderUtil.checkResource(this.project, input, "collision", collisionPath);
            } else {
                throw new CompileExceptionError(input, 0, Messages.TileSetBuilder_MISSING_IMAGE_AND_COLLISION);
            }
            // will not be reached
            return null;
        }
    }

    @Override
    public void build(Task<Void> task) throws CompileExceptionError,
            IOException {

        TileSet.Builder builder = TileSet.newBuilder();
        ProtoUtil.merge(task.input(0), builder);
        TileSet tileSet = builder.build();

        String imgPath = tileSet.getImage();
        String collisionPath = tileSet.getCollision();
        IResource imageRes = this.project.getResource(imgPath);
        IResource collisionRes = this.project.getResource(collisionPath);

        BufferedImage image = null;
        if (imageRes.exists()) {
            image = ImageIO.read(new ByteArrayInputStream(imageRes.getContent()));
        }
        if (image != null && (image.getWidth() < tileSet.getTileWidth() || image.getHeight() < tileSet.getTileHeight())) {
            throw new CompileExceptionError(task.input(0), -1, String.format(
                    "the image dimensions (%dx%d) are smaller than the tile dimensions (%dx%d)", image.getWidth(),
                    image.getHeight(), tileSet.getTileWidth(), tileSet.getTileHeight()));
        }

        BufferedImage collisionImage = null;
        if (collisionRes.exists()) {
            BufferedImage origImage = ImageIO.read(new ByteArrayInputStream(collisionRes.getContent()));
            collisionImage = new BufferedImage(origImage.getWidth(), origImage.getHeight(),
                    BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D g2d = collisionImage.createGraphics();
            g2d.drawImage(origImage, 0, 0, null);
            g2d.dispose();
        }

        if (image != null && collisionImage != null
                && (image.getWidth() != collisionImage.getWidth() || image.getHeight() != collisionImage.getHeight())) {
            throw new CompileExceptionError(task.input(0), -1, String.format(
                    "the image and collision image has different dimensions: (%dx%d) vs (%dx%d)", image.getWidth(),
                    image.getHeight(), collisionImage.getWidth(), collisionImage.getHeight()));
        }
        if (collisionImage != null && !collisionImage.getColorModel().hasAlpha()) {
            throw new CompileExceptionError(task.input(0), -1, "the collision image does not have an alpha channel");
        }
        TextureSetResult result = TileSetGenerator.generate(tileSet, image, collisionImage, false, false);
        TextureSet.Builder textureSetBuilder = result.builder;

        int buildDirLen = project.getBuildDirectory().length();
        String texturePath = task.output(1).getPath().substring(buildDirLen);
        TextureSet textureSet = textureSetBuilder.setTexture(texturePath).build();

        TextureImage texture;
        try {
            texture = TextureGenerator.generate(result.image);
        } catch (TextureGeneratorException e) {
            throw new CompileExceptionError(task.input(0), -1, e.getMessage(), e);
        }

        task.output(0).setContent(textureSet.toByteArray());
        task.output(1).setContent(texture.toByteArray());
    }
}
