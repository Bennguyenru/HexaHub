package com.dynamo.bob.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.vecmath.Point3d;
import javax.vecmath.Quat4d;

import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.ProtoBuilder;
import com.dynamo.bob.ProtoParams;
import com.dynamo.bob.Task;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.util.BobNLS;
import com.dynamo.bob.util.MathUtil;
import com.dynamo.camera.proto.Camera.CameraDesc;
import com.dynamo.gamesystem.proto.GameSystem.CollectionProxyDesc;
import com.dynamo.gamesystem.proto.GameSystem.FactoryDesc;
import com.dynamo.gamesystem.proto.GameSystem.LightDesc;
import com.dynamo.gui.proto.Gui.NodeDesc;
import com.dynamo.gui.proto.Gui.SceneDesc;
import com.dynamo.gui.proto.Gui.SceneDesc.FontDesc;
import com.dynamo.gui.proto.Gui.SceneDesc.LayerDesc;
import com.dynamo.gui.proto.Gui.SceneDesc.TextureDesc;
import com.dynamo.input.proto.Input.GamepadMaps;
import com.dynamo.input.proto.Input.InputBinding;
import com.dynamo.model.proto.Model.ModelDesc;
import com.dynamo.particle.proto.Particle.Emitter;
import com.dynamo.particle.proto.Particle.Modifier;
import com.dynamo.particle.proto.Particle.ParticleFX;
import com.dynamo.physics.proto.Physics.CollisionObjectDesc;
import com.dynamo.physics.proto.Physics.CollisionShape;
import com.dynamo.physics.proto.Physics.CollisionShape.Shape;
import com.dynamo.physics.proto.Physics.ConvexShape;
import com.dynamo.proto.DdfMath.Point3;
import com.dynamo.proto.DdfMath.Quat;
import com.dynamo.render.proto.Material.MaterialDesc;
import com.dynamo.render.proto.Render.RenderPrototypeDesc;
import com.dynamo.sound.proto.Sound.SoundDesc;
import com.dynamo.sprite.proto.Sprite.SpriteDesc;
import com.dynamo.tile.proto.Tile.TileGrid;

public class ProtoBuilders {

    private static String[] textureSrcExts = {".png", ".jpg", ".tga", ".cubemap"};

    static String replaceTextureName(String str) {
        String out = str;
        for (String srcExt : textureSrcExts) {
            out = BuilderUtil.replaceExt(out, srcExt, ".texturec");
        }
        return out;
    }

    @ProtoParams(messageClass = CollectionProxyDesc.class)
    @BuilderParams(name="CollectionProxy", inExts=".collectionproxy", outExt=".collectionproxyc")
    public static class CollectionProxyBuilder extends ProtoBuilder<CollectionProxyDesc.Builder> {
        @Override
        protected CollectionProxyDesc.Builder transform(Task<Void> task, IResource resource, CollectionProxyDesc.Builder messageBuilder) throws CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "collection", messageBuilder.getCollection());
            return messageBuilder.setCollection(BuilderUtil.replaceExt(messageBuilder.getCollection(), ".collection", ".collectionc"));
        }
    }

    @ProtoParams(messageClass = ModelDesc.class)
    @BuilderParams(name="Model", inExts=".model", outExt=".modelc")
    public static class ModelBuilder extends ProtoBuilder<ModelDesc.Builder> {
        @Override
        protected ModelDesc.Builder transform(Task<Void> task, IResource resource, ModelDesc.Builder messageBuilder) throws CompileExceptionError {

            BuilderUtil.checkResource(this.project, resource, "mesh", messageBuilder.getMesh());
            messageBuilder.setMesh(BuilderUtil.replaceExt(messageBuilder.getMesh(), ".dae", ".meshc"));
            BuilderUtil.checkResource(this.project, resource, "material", messageBuilder.getMaterial());
            messageBuilder.setMaterial(BuilderUtil.replaceExt(messageBuilder.getMaterial(), ".material", ".materialc"));
            List<String> newTextureList = new ArrayList<String>();
            for (String t : messageBuilder.getTexturesList()) {
                newTextureList.add(replaceTextureName(t));
            }
            messageBuilder.clearTextures();
            messageBuilder.addAllTextures(newTextureList);
            return messageBuilder;
        }
    }

    @ProtoParams(messageClass = ConvexShape.class)
    @BuilderParams(name="ConvexShape", inExts=".convexshape", outExt=".convexshapec")
    public static class ConvexShapeBuilder extends ProtoBuilder<ConvexShape.Builder> {}


    @ProtoParams(messageClass = CollisionObjectDesc.class)
    @BuilderParams(name="CollisionObjectDesc", inExts=".collisionobject", outExt=".collisionobjectc")
    public static class CollisionObjectBuilder extends ProtoBuilder<CollisionObjectDesc.Builder> {

        @Override
        protected CollisionObjectDesc.Builder transform(Task<Void> task, IResource resource, CollisionObjectDesc.Builder messageBuilder) throws IOException, CompileExceptionError {
            if (messageBuilder.getEmbeddedCollisionShape().getShapesCount() == 0) {
                BuilderUtil.checkResource(this.project, resource, "collision shape", messageBuilder.getCollisionShape());
            }
            // Merge convex shape resource with collision object
            // NOTE: Special case for tilegrid resources. They are left as is
            if (messageBuilder.hasCollisionShape() && !messageBuilder.getCollisionShape().isEmpty() && !(messageBuilder.getCollisionShape().endsWith(".tilegrid") || messageBuilder.getCollisionShape().endsWith(".tilemap"))) {
                IResource shapeResource = project.getResource(messageBuilder.getCollisionShape().substring(1));
                ConvexShape.Builder cb = ConvexShape.newBuilder();
                ProtoUtil.merge(shapeResource, cb);
                CollisionShape.Builder eb = CollisionShape.newBuilder().mergeFrom(messageBuilder.getEmbeddedCollisionShape());
                Shape.Builder sb = Shape.newBuilder()
                        .setShapeType(CollisionShape.Type.valueOf(cb.getShapeType().getNumber()))
                        .setPosition(Point3.newBuilder())
                        .setRotation(Quat.newBuilder().setW(1))
                        .setIndex(eb.getDataCount())
                        .setCount(cb.getDataCount());
                eb.addShapes(sb);
                eb.addAllData(cb.getDataList());
                messageBuilder.setEmbeddedCollisionShape(eb);
                messageBuilder.setCollisionShape("");
            }

            messageBuilder.setCollisionShape(BuilderUtil.replaceExt(messageBuilder.getCollisionShape(), ".convexshape", ".convexshapec"));
            messageBuilder.setCollisionShape(BuilderUtil.replaceExt(messageBuilder.getCollisionShape(), ".tilegrid", ".tilegridc"));
            messageBuilder.setCollisionShape(BuilderUtil.replaceExt(messageBuilder.getCollisionShape(), ".tilemap", ".tilegridc"));
            return messageBuilder;
        }
    }

    @ProtoParams(messageClass = SceneDesc.class)
    @BuilderParams(name="Gui", inExts=".gui", outExt=".guic")
    public static class GuiBuilder extends ProtoBuilder<SceneDesc.Builder> {
        @Override
        protected SceneDesc.Builder transform(Task<Void> task, IResource input, SceneDesc.Builder messageBuilder) throws IOException, CompileExceptionError {
            messageBuilder.setScript(BuilderUtil.replaceExt(messageBuilder.getScript(), ".gui_script", ".gui_scriptc"));
            messageBuilder.setMaterial(BuilderUtil.replaceExt(messageBuilder.getMaterial(), ".material", ".materialc"));
            Set<String> fontNames = new HashSet<String>();
            Set<String> textureNames = new HashSet<String>();
            Set<String> layerNames = new HashSet<String>();

            List<FontDesc> newFontList = new ArrayList<FontDesc>();
            for (FontDesc f : messageBuilder.getFontsList()) {
                if (fontNames.contains(f.getName())) {
                    throw new CompileExceptionError(input, 0, BobNLS.bind(Messages.GuiBuilder_DUPLICATED_FONT,
                            f.getName()));
                }
                fontNames.add(f.getName());
                newFontList.add(FontDesc.newBuilder().mergeFrom(f).setFont(BuilderUtil.replaceExt(f.getFont(), ".font", ".fontc")).build());
            }
            messageBuilder.clearFonts();
            messageBuilder.addAllFonts(newFontList);

            List<TextureDesc> newTextureList = new ArrayList<TextureDesc>();
            for (TextureDesc f : messageBuilder.getTexturesList()) {
                if (textureNames.contains(f.getName())) {
                    throw new CompileExceptionError(input, 0, BobNLS.bind(Messages.GuiBuilder_DUPLICATED_TEXTURE,
                            f.getName()));
                }
                textureNames.add(f.getName());
                newTextureList.add(TextureDesc.newBuilder().mergeFrom(f).setTexture(replaceTextureName(f.getTexture())).build());
            }
            messageBuilder.clearTextures();
            messageBuilder.addAllTextures(newTextureList);

            for (LayerDesc f : messageBuilder.getLayersList()) {
                if (layerNames.contains(f.getName())) {
                    throw new CompileExceptionError(input, 0, BobNLS.bind(Messages.GuiBuilder_DUPLICATED_LAYER,
                            f.getName()));
                }
                layerNames.add(f.getName());
            }
            for (NodeDesc n : messageBuilder.getNodesList()) {
                if (n.hasTexture() && !n.getTexture().isEmpty()) {
                    if (!textureNames.contains(n.getTexture())) {
                        throw new CompileExceptionError(input, 0, BobNLS.bind(Messages.GuiBuilder_MISSING_TEXTURE, n.getTexture()));
                    }
                }

                if (n.hasFont() && !n.getFont().isEmpty()) {
                    if (!fontNames.contains(n.getFont())) {
                        throw new CompileExceptionError(input, 0, BobNLS.bind(Messages.GuiBuilder_MISSING_FONT, n.getFont()));
                    }
                }

                if (n.hasLayer() && !n.getLayer().isEmpty()) {
                    if (!layerNames.contains(n.getLayer())) {
                        throw new CompileExceptionError(input, 0, BobNLS.bind(Messages.GuiBuilder_MISSING_LAYER,
                                n.getLayer()));
                    }
                }

            }

            return messageBuilder;
        }
    }

    @ProtoParams(messageClass = CameraDesc.class)
    @BuilderParams(name="Camera", inExts=".camera", outExt=".camerac")
    public static class CameraBuilder extends ProtoBuilder<CameraDesc.Builder> {}

    @ProtoParams(messageClass = InputBinding.class)
    @BuilderParams(name="InputBinding", inExts=".input_binding", outExt=".input_bindingc")
    public static class InputBindingBuilder extends ProtoBuilder<InputBinding.Builder> {}

    @ProtoParams(messageClass = GamepadMaps.class)
    @BuilderParams(name="GamepadMaps", inExts=".gamepads", outExt=".gamepadsc")
    public static class GamepadMapsBuilder extends ProtoBuilder<GamepadMaps.Builder> {}

    @ProtoParams(messageClass = FactoryDesc.class)
    @BuilderParams(name="Factory", inExts=".factory", outExt=".factoryc")
    public static class FactoryBuilder extends ProtoBuilder<FactoryDesc.Builder> {
        @Override
        protected FactoryDesc.Builder transform(Task<Void> task, IResource resource, FactoryDesc.Builder messageBuilder) throws IOException,
                CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "prototype", messageBuilder.getPrototype());
            return messageBuilder.setPrototype(BuilderUtil.replaceExt(messageBuilder.getPrototype(), ".go", ".goc"));
        }
    }

    @ProtoParams(messageClass = LightDesc.class)
    @BuilderParams(name="Light", inExts=".light", outExt=".lightc")
    public static class LightBuilder extends ProtoBuilder<LightDesc.Builder> {}

    @ProtoParams(messageClass = RenderPrototypeDesc.class)
    @BuilderParams(name="Render", inExts=".render", outExt=".renderc")
    public static class RenderPrototypeBuilder extends ProtoBuilder<RenderPrototypeDesc.Builder> {
        @Override
        protected RenderPrototypeDesc.Builder transform(Task<Void> task, IResource resource, RenderPrototypeDesc.Builder messageBuilder)
                throws IOException, CompileExceptionError {

            BuilderUtil.checkResource(this.project, resource, "script", messageBuilder.getScript());
            messageBuilder.setScript(BuilderUtil.replaceExt(messageBuilder.getScript(), ".render_script", ".render_scriptc"));

            List<RenderPrototypeDesc.MaterialDesc> newMaterialList = new ArrayList<RenderPrototypeDesc.MaterialDesc>();
            for (RenderPrototypeDesc.MaterialDesc m : messageBuilder.getMaterialsList()) {
                BuilderUtil.checkResource(this.project, resource, "material", m.getMaterial());
                newMaterialList.add(RenderPrototypeDesc.MaterialDesc.newBuilder().mergeFrom(m).setMaterial(BuilderUtil.replaceExt(m.getMaterial(), ".material", ".materialc")).build());
            }
            messageBuilder.clearMaterials();
            messageBuilder.addAllMaterials(newMaterialList);

            return messageBuilder;
        }
    }

    @ProtoParams(messageClass = SpriteDesc.class)
    @BuilderParams(name="SpriteDesc", inExts=".sprite", outExt=".spritec")
    public static class SpriteDescBuilder extends ProtoBuilder<SpriteDesc.Builder> {
        @Override
        protected SpriteDesc.Builder transform(Task<Void> task, IResource resource, SpriteDesc.Builder messageBuilder)
                throws IOException, CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "tile source", messageBuilder.getTileSet());
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "tileset", "texturesetc"));
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "tilesource", "texturesetc"));
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "atlas", "texturesetc"));
            messageBuilder.setMaterial(BuilderUtil.replaceExt(messageBuilder.getMaterial(), "material", "materialc"));
            return messageBuilder;
        }
    }

    @ProtoParams(messageClass = TileGrid.class)
    @BuilderParams(name="TileGrid", inExts={".tilegrid", ".tilemap"}, outExt=".tilegridc")
    public static class TileGridBuilder extends ProtoBuilder<TileGrid.Builder> {
        @Override
        protected TileGrid.Builder transform(Task<Void> task, IResource resource, TileGrid.Builder messageBuilder) throws IOException,
                CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "tile source", messageBuilder.getTileSet());
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "tileset", "texturesetc"));
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "tilesource", "texturesetc"));
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "atlas", "texturesetc"));
            messageBuilder.setMaterial(BuilderUtil.replaceExt(messageBuilder.getMaterial(), "material", "materialc"));
            return messageBuilder;
        }
    }

    @ProtoParams(messageClass = ParticleFX.class)
    @BuilderParams(name="ParticleFX", inExts=".particlefx", outExt=".particlefxc")
    public static class ParticleFXBuilder extends ProtoBuilder<ParticleFX.Builder> {
        @Override
        protected ParticleFX.Builder transform(Task<Void> task, IResource resource, ParticleFX.Builder messageBuilder)
                throws IOException, CompileExceptionError {
            int emitterCount = messageBuilder.getEmittersCount();
            // Move modifiers to all emitters, clear the list at the end
            List<Modifier> modifiers = messageBuilder.getModifiersList();
            for (int i = 0; i < emitterCount; ++i) {
                Emitter.Builder emitterBuilder = Emitter.newBuilder(messageBuilder.getEmitters(i));
                BuilderUtil.checkResource(this.project, resource, "tile source", emitterBuilder.getTileSource());
                BuilderUtil.checkResource(this.project, resource, "material", emitterBuilder.getMaterial());
                emitterBuilder.setTileSource(BuilderUtil.replaceExt(emitterBuilder.getTileSource(), "tileset", "texturesetc"));
                emitterBuilder.setTileSource(BuilderUtil.replaceExt(emitterBuilder.getTileSource(), "tilesource", "texturesetc"));
                emitterBuilder.setTileSource(BuilderUtil.replaceExt(emitterBuilder.getTileSource(), "atlas", "texturesetc"));
                emitterBuilder.setMaterial(BuilderUtil.replaceExt(emitterBuilder.getMaterial(), "material", "materialc"));
                Point3d ep = MathUtil.ddfToVecmath(emitterBuilder.getPosition());
                Quat4d er = MathUtil.ddfToVecmath(emitterBuilder.getRotation());
                for (Modifier modifier : modifiers) {
                    Modifier.Builder mb = Modifier.newBuilder(modifier);
                    Point3d p = MathUtil.ddfToVecmath(modifier.getPosition());
                    Quat4d r = MathUtil.ddfToVecmath(modifier.getRotation());
                    MathUtil.invTransform(ep, er, p);
                    mb.setPosition(MathUtil.vecmathToDDF(p));
                    MathUtil.invTransform(er, r);
                    mb.setRotation(MathUtil.vecmathToDDF(r));
                    emitterBuilder.addModifiers(mb.build());
                }
                messageBuilder.setEmitters(i, emitterBuilder.build());
            }
            messageBuilder.clearModifiers();
            return messageBuilder;
        }
    }

    @ProtoParams(messageClass = MaterialDesc.class)
    @BuilderParams(name="Material", inExts=".material", outExt=".materialc")
    public static class MaterialBuilder extends ProtoBuilder<MaterialDesc.Builder> {
        @Override
        protected MaterialDesc.Builder transform(Task<Void> task, IResource resource, MaterialDesc.Builder messageBuilder)
                throws IOException, CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "vertex program", messageBuilder.getVertexProgram());
            messageBuilder.setVertexProgram(BuilderUtil.replaceExt(messageBuilder.getVertexProgram(), ".vp", ".vpc"));
            BuilderUtil.checkResource(this.project, resource, "fragment program", messageBuilder.getFragmentProgram());
            messageBuilder.setFragmentProgram(BuilderUtil.replaceExt(messageBuilder.getFragmentProgram(), ".fp", ".fpc"));
            return messageBuilder;
        }
    }

    @ProtoParams(messageClass = SoundDesc.class)
    @BuilderParams(name="SoundDesc", inExts=".sound", outExt=".soundc")
    public static class SoundDescBuilder extends ProtoBuilder<SoundDesc.Builder> {
        @Override
        protected SoundDesc.Builder transform(Task<Void> task, IResource resource, SoundDesc.Builder messageBuilder)
                throws IOException, CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "sound", messageBuilder.getSound());
            messageBuilder.setSound(BuilderUtil.replaceExt(messageBuilder.getSound(), "wav", "wavc"));
            messageBuilder.setSound(BuilderUtil.replaceExt(messageBuilder.getSound(), "ogg", "oggc"));
            return messageBuilder;
        }
    }


}
