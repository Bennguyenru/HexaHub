// Copyright 2020-2024 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.vecmath.Point3d;
import javax.vecmath.Quat4d;

import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Project;
import com.dynamo.bob.ProtoBuilder;
import com.dynamo.bob.ProtoParams;
import com.dynamo.bob.Task;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.util.BobNLS;
import com.dynamo.bob.util.MathUtil;
import com.dynamo.bob.util.MurmurHash;
import com.dynamo.bob.util.StringUtil;
import com.dynamo.bob.pipeline.ShaderUtil.Common;
import com.dynamo.proto.DdfMath.Point3;
import com.dynamo.proto.DdfMath.Quat;
import com.dynamo.graphics.proto.Graphics.ShaderDesc;
import com.dynamo.graphics.proto.Graphics.VertexAttribute;
import com.dynamo.gamesys.proto.BufferProto.BufferDesc;
import com.dynamo.gamesys.proto.Camera.CameraDesc;
import com.dynamo.gamesys.proto.GameSystem.CollectionFactoryDesc;
import com.dynamo.gamesys.proto.GameSystem.CollectionProxyDesc;
import com.dynamo.gamesys.proto.GameSystem.FactoryDesc;
import com.dynamo.gamesys.proto.GameSystem.LightDesc;
import com.dynamo.gamesys.proto.Label.LabelDesc;
import com.dynamo.gamesys.proto.Physics.CollisionObjectDesc;
import com.dynamo.gamesys.proto.Physics.CollisionShape.Shape;
import com.dynamo.gamesys.proto.Physics.CollisionShape.ShapeOrBuilder;
import com.dynamo.gamesys.proto.Physics.CollisionShape.Type;
import com.dynamo.gamesys.proto.Physics.CollisionShape;
import com.dynamo.gamesys.proto.Physics.ConvexShape;
import com.dynamo.gamesys.proto.Sound.SoundDesc;
import com.dynamo.gamesys.proto.Sprite.SpriteTexture;
import com.dynamo.gamesys.proto.Sprite.SpriteDesc;
import com.dynamo.gamesys.proto.Tile.TileGrid;
import com.dynamo.gamesys.proto.AtlasProto.Atlas;
import com.dynamo.gamesys.proto.TextureSetProto.TextureSet;
import com.dynamo.input.proto.Input.GamepadMaps;
import com.dynamo.input.proto.Input.InputBinding;
import com.dynamo.particle.proto.Particle.Emitter;
import com.dynamo.particle.proto.Particle.Modifier;
import com.dynamo.particle.proto.Particle.ParticleFX;
import com.dynamo.render.proto.ComputeProgram.ComputeProgramDesc;
import com.dynamo.render.proto.Material.MaterialDesc;
import com.dynamo.render.proto.Render.RenderPrototypeDesc;
import com.dynamo.render.proto.Render.DisplayProfiles;

public class ProtoBuilders {

    private static String[] textureSrcExts = {".png", ".jpg", ".tga", ".cubemap"};
    private static String[][] textureSetSrcExts = {{".atlas", ".a.texturesetc"}, {".tileset", ".t.texturesetc"}, {".tilesource", ".t.texturesetc"}};

    public static String getTextureSetExt(String str) {
        for (String[] extReplacement : textureSetSrcExts) {
            if (str.endsWith(extReplacement[0])) {
                return extReplacement[1];
            }
        }
        return null;
    }

    public static String replaceTextureName(String str) {
        String out = str;
        for (String srcExt : textureSrcExts) {
            out = BuilderUtil.replaceExt(out, srcExt, ".texturec");
        }
        return out;
    }

    public static String replaceTextureSetName(String str) {
        String out = str;
        for (String[] extReplacement : textureSetSrcExts) {
            out = BuilderUtil.replaceExt(out, extReplacement[0], extReplacement[1]);
        }
        return out;
    }

    private static MaterialDesc.Builder getMaterialBuilderFromResource(IResource res) throws IOException {
        if (res.getPath().isEmpty()) {
            return null;
        }

        IResource materialBuildResource      = res.changeExt(".materialc");
        MaterialDesc.Builder materialBuilder = MaterialDesc.newBuilder();
        materialBuilder.mergeFrom(materialBuildResource.getContent());
        return materialBuilder;
    }

    // TODO: Should we move this to a build resource?
    static Set<String> materialAtlasCompatabilityCache = new HashSet<String>();

    private static void validateMaterialAtlasCompatability(Project project, IResource resource, String materialProjectPath, MaterialDesc.Builder materialBuilder, String textureSet) throws IOException, CompileExceptionError {
        if (materialProjectPath.isEmpty())
        {
            return;
        }

        if (textureSet.endsWith("atlas")) {
            String cachedValidationKey = materialProjectPath + textureSet;
            if (materialAtlasCompatabilityCache.contains(cachedValidationKey)) {
                return;
            }

            IResource textureSetResource      = project.getResource(textureSet);
            IResource textureSetBuildResource = textureSetResource.changeExt(".a.texturesetc");

            TextureSet.Builder textureSetBuilder = TextureSet.newBuilder();
            textureSetBuilder.mergeFrom(textureSetBuildResource.getContent());

            int textureSetPageCount  = textureSetBuilder.getPageCount();
            int materialMaxPageCount = materialBuilder.getMaxPageCount();

            boolean textureIsPaged = textureSetPageCount != 0;
            boolean materialIsPaged = materialMaxPageCount != 0;

            if (!textureIsPaged && !materialIsPaged) {
                return;
            }

            if (textureIsPaged && !materialIsPaged) {
                throw new CompileExceptionError(resource, 0, "The Material does not support paged Atlases, but the selected Image is paged");
            }
            else if (!textureIsPaged && materialIsPaged) {
                throw new CompileExceptionError(resource, 0, "The Material expects a paged Atlas, but the selected Image is not paged");
            }
            else if (textureSetPageCount > materialMaxPageCount) {
                throw new CompileExceptionError(resource, 0, "The Material's 'Max Page Count' is not sufficient for the number of pages in the selected Image");
            }

            materialAtlasCompatabilityCache.add(cachedValidationKey);
        }
    }

    @ProtoParams(srcClass = CollectionProxyDesc.class, messageClass = CollectionProxyDesc.class)
    @BuilderParams(name="CollectionProxy", inExts=".collectionproxy", outExt=".collectionproxyc")
    public static class CollectionProxyBuilder extends ProtoBuilder<CollectionProxyDesc.Builder> {
        @Override
        protected CollectionProxyDesc.Builder transform(Task<Void> task, IResource resource, CollectionProxyDesc.Builder messageBuilder) throws CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "collection", messageBuilder.getCollection());
            return messageBuilder.setCollection(BuilderUtil.replaceExt(messageBuilder.getCollection(), ".collection", ".collectionc"));
        }
    }

    @ProtoParams(srcClass = ConvexShape.class, messageClass = ConvexShape.class)
    @BuilderParams(name="ConvexShape", inExts=".convexshape", outExt=".convexshapec")
    public static class ConvexShapeBuilder extends ProtoBuilder<ConvexShape.Builder> {}

    @ProtoParams(srcClass = CollisionObjectDesc.class, messageClass = CollisionObjectDesc.class)
    @BuilderParams(name="CollisionObjectDesc", inExts=".collisionobject", outExt=".collisionobjectc")
    public static class CollisionObjectBuilder extends ProtoBuilder<CollisionObjectDesc.Builder> {

        private void ValidateShapeTypes(List<Shape> shapeList, IResource resource) throws IOException, CompileExceptionError {
            String physicsTypeStr = StringUtil.toUpperCase(this.project.getProjectProperties().getStringValue("physics", "type", "2D"));
            for(Shape shape : shapeList) {
                if(shape.getShapeType() == Type.TYPE_CAPSULE) {
                    if(physicsTypeStr.contains("2D")) {
                        throw new CompileExceptionError(resource, 0, BobNLS.bind(Messages.CollisionObjectBuilder_MISMATCHING_SHAPE_PHYSICS_TYPE, "Capsule", physicsTypeStr ));
                    }
                }
            }
        }

        @Override
        protected CollisionObjectDesc.Builder transform(Task<Void> task, IResource resource, CollisionObjectDesc.Builder messageBuilder) throws IOException, CompileExceptionError {
            if (messageBuilder.getEmbeddedCollisionShape().getShapesCount() == 0) {
                BuilderUtil.checkResource(this.project, resource, "collision shape", messageBuilder.getCollisionShape());
            }
            // Merge convex shape resource with collision object
            // NOTE: Special case for tilegrid resources. They are left as is
            if(messageBuilder.hasEmbeddedCollisionShape()) {
                ValidateShapeTypes(messageBuilder.getEmbeddedCollisionShape().getShapesList(), resource);
            }
            if (messageBuilder.hasCollisionShape() && !messageBuilder.getCollisionShape().isEmpty() && !(messageBuilder.getCollisionShape().endsWith(".tilegrid") || messageBuilder.getCollisionShape().endsWith(".tilemap"))) {
                IResource shapeResource = project.getResource(messageBuilder.getCollisionShape().substring(1));
                ConvexShape.Builder cb = ConvexShape.newBuilder();
                ProtoUtil.merge(shapeResource, cb);
                CollisionShape.Builder eb = CollisionShape.newBuilder().mergeFrom(messageBuilder.getEmbeddedCollisionShape());
                ValidateShapeTypes(eb.getShapesList(), shapeResource);
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

            CollisionShape.Builder embeddedShapesBuilder = messageBuilder.getEmbeddedCollisionShapeBuilder();

            for (int i=0; i < embeddedShapesBuilder.getShapesCount(); i++) {
                CollisionShape.Shape.Builder shapeBuilder = embeddedShapesBuilder.getShapesBuilder(i);
                shapeBuilder.setIdHash(MurmurHash.hash64(shapeBuilder.getId()));
            }

            messageBuilder.setCollisionShape(BuilderUtil.replaceExt(messageBuilder.getCollisionShape(), ".convexshape", ".convexshapec"));
            messageBuilder.setCollisionShape(BuilderUtil.replaceExt(messageBuilder.getCollisionShape(), ".tilegrid", ".tilemapc"));
            messageBuilder.setCollisionShape(BuilderUtil.replaceExt(messageBuilder.getCollisionShape(), ".tilemap", ".tilemapc"));
            return messageBuilder;
        }
    }

    @ProtoParams(srcClass = CameraDesc.class, messageClass = CameraDesc.class)
    @BuilderParams(name="Camera", inExts=".camera", outExt=".camerac")
    public static class CameraBuilder extends ProtoBuilder<CameraDesc.Builder> {}

    @ProtoParams(srcClass = InputBinding.class, messageClass = InputBinding.class)
    @BuilderParams(name="InputBinding", inExts=".input_binding", outExt=".input_bindingc")
    public static class InputBindingBuilder extends ProtoBuilder<InputBinding.Builder> {}

    @ProtoParams(srcClass = GamepadMaps.class, messageClass = GamepadMaps.class)
    @BuilderParams(name="GamepadMaps", inExts=".gamepads", outExt=".gamepadsc")
    public static class GamepadMapsBuilder extends ProtoBuilder<GamepadMaps.Builder> {}

    @ProtoParams(srcClass = FactoryDesc.class, messageClass = FactoryDesc.class)
    @BuilderParams(name="Factory", inExts=".factory", outExt=".factoryc")
    public static class FactoryBuilder extends ProtoBuilder<FactoryDesc.Builder> {
        @Override
        protected FactoryDesc.Builder transform(Task<Void> task, IResource resource, FactoryDesc.Builder messageBuilder) throws IOException,
                CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "prototype", messageBuilder.getPrototype());
            return messageBuilder.setPrototype(BuilderUtil.replaceExt(messageBuilder.getPrototype(), ".go", ".goc"));
        }
    }

    @ProtoParams(srcClass = CollectionFactoryDesc.class, messageClass = CollectionFactoryDesc.class)
    @BuilderParams(name="CollectionFactory", inExts=".collectionfactory", outExt=".collectionfactoryc")
    public static class CollectionFactoryBuilder extends ProtoBuilder<CollectionFactoryDesc.Builder> {
        @Override
        protected CollectionFactoryDesc.Builder transform(Task<Void> task, IResource resource, CollectionFactoryDesc.Builder messageBuilder) throws IOException,
                CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "prototype", messageBuilder.getPrototype());
            return messageBuilder.setPrototype(BuilderUtil.replaceExt(messageBuilder.getPrototype(), ".collection", ".collectionc"));
        }
    }

    @ProtoParams(srcClass = LightDesc.class, messageClass = LightDesc.class)
    @BuilderParams(name="Light", inExts=".light", outExt=".lightc")
    public static class LightBuilder extends ProtoBuilder<LightDesc.Builder> {}

    @ProtoParams(srcClass = RenderPrototypeDesc.class, messageClass = RenderPrototypeDesc.class)
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

    @ProtoParams(srcClass = SpriteDesc.class, messageClass = SpriteDesc.class)
    @BuilderParams(name="SpriteDesc", inExts=".sprite", outExt=".spritec")
    public static class SpriteDescBuilder extends ProtoBuilder<SpriteDesc.Builder>
    {
        List<String> getImageResources(SpriteDesc.Builder builder) {
            List<String> textures = new ArrayList<>();

            for (SpriteTexture texture : builder.getTexturesList()) {
                String t = texture.getTexture();
                if (!t.isEmpty())
                {
                    textures.add(t);
                }
            }

            // Deprecated
            if (textures.isEmpty()) {
                String t = builder.getTileSet();
                if (!t.isEmpty()) {
                    textures.add(t);
                }
            }
            return textures;
        }

        @Override
        public Task<Void> create(IResource input) throws IOException, CompileExceptionError {

            Task.TaskBuilder<Void> task = Task.<Void>newBuilder(this)
                .setName(params.name())
                .addInput(input)
                .addOutput(input.changeExt(params.outExt()));

            SpriteDesc.Builder spriteBuilder = SpriteDesc.newBuilder();
            ProtoUtil.merge(input, spriteBuilder);

            // Material input is optional in the protobuf description
            String material = spriteBuilder.getMaterial();
            if (!material.isEmpty())
            {
                IResource materialOutput = project.getResource(material).changeExt(".materialc");
                task.addInput(materialOutput);
            }

            List<String> images = getImageResources(spriteBuilder);

            if (images.isEmpty())
            {
                throw new CompileExceptionError(input, 0, "An atlas or tileset must be assigned.");
            }

            for (String atlas : images) {
                IResource atlasOutput = project.getResource(atlas).changeExt(getTextureSetExt(atlas));
                task.addInput(atlasOutput);
            }

            return task.build();
        }

        @Override
        protected SpriteDesc.Builder transform(Task<Void> task, IResource resource, SpriteDesc.Builder messageBuilder)
                throws IOException, CompileExceptionError {

            if (messageBuilder.hasTileSet()) {
                String texture = messageBuilder.getTileSet();

                SpriteTexture.Builder textureBuilder = SpriteTexture.newBuilder();
                textureBuilder.setTexture(texture);
                textureBuilder.setSampler("");
                messageBuilder.clearTextures();
                messageBuilder.addTextures(textureBuilder.build());
                messageBuilder.clearTileSet();
            }

            MaterialDesc.Builder materialBuilder = getMaterialBuilderFromResource(this.project.getResource(messageBuilder.getMaterial()));

            validateMaterialAtlasCompatability(this.project, resource, messageBuilder.getMaterial(), materialBuilder, messageBuilder.getTileSet());

            List<SpriteTexture> textures = new ArrayList<>();
            for (SpriteTexture texture : messageBuilder.getTexturesList()) {
                SpriteTexture.Builder textureBuilder = SpriteTexture.newBuilder();
                textureBuilder.mergeFrom(texture);

                BuilderUtil.checkResource(this.project, resource, "tile source", textureBuilder.getTexture());

                textureBuilder.setTexture(BuilderUtil.replaceExt(textureBuilder.getTexture(), "tileset", "t.texturesetc"));
                textureBuilder.setTexture(BuilderUtil.replaceExt(textureBuilder.getTexture(), "tilesource", "t.texturesetc"));
                textureBuilder.setTexture(BuilderUtil.replaceExt(textureBuilder.getTexture(), "atlas", "a.texturesetc"));

                textures.add(textureBuilder.build());
            }
            messageBuilder.clearTextures();
            messageBuilder.addAllTextures(textures);

            messageBuilder.setMaterial(BuilderUtil.replaceExt(messageBuilder.getMaterial(), "material", "materialc"));

            if (materialBuilder != null) {
                List<VertexAttribute> materialAttributes       = materialBuilder.getAttributesList();
                List<VertexAttribute> spriteAttributeOverrides = new ArrayList<VertexAttribute>();

                for (int i=0; i < messageBuilder.getAttributesCount(); i++) {
                    VertexAttribute spriteAttribute = messageBuilder.getAttributes(i);
                    VertexAttribute materialAttribute = GraphicsUtil.getAttributeByName(materialAttributes, spriteAttribute.getName());

                    if (materialAttribute != null) {
                        spriteAttributeOverrides.add(GraphicsUtil.buildVertexAttribute(spriteAttribute, materialAttribute));
                    }
                }

                messageBuilder.clearAttributes();
                messageBuilder.addAllAttributes(spriteAttributeOverrides);
            }

            return messageBuilder;
        }
    }

    @ProtoParams(srcClass = ComputeProgramDesc.class, messageClass = ComputeProgramDesc.class)
    @BuilderParams(name="ComputeProgram", inExts=".compute_program", outExt=".compute_programc")
    public static class ComputeProgramBuilder extends ProtoBuilder<ComputeProgramDesc.Builder> {
        @Override
        protected ComputeProgramDesc.Builder transform(Task<Void> task, IResource resource, ComputeProgramDesc.Builder messageBuilder)
                throws IOException, CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "compute program", messageBuilder.getProgram());
            messageBuilder.setProgram(BuilderUtil.replaceExt(messageBuilder.getProgram(), ".cp", ".cpc"));
            return messageBuilder;
        }
    }

    @ProtoParams(srcClass = LabelDesc.class, messageClass = LabelDesc.class)
    @BuilderParams(name="LabelDesc", inExts=".label", outExt=".labelc")
    public static class LabelDescBuilder extends ProtoBuilder<LabelDesc.Builder> {
        @Override
        protected LabelDesc.Builder transform(Task<Void> task, IResource resource, LabelDesc.Builder messageBuilder)
                throws IOException, CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "material", messageBuilder.getMaterial());
            BuilderUtil.checkResource(this.project, resource, "font", messageBuilder.getFont());
            messageBuilder.setMaterial(BuilderUtil.replaceExt(messageBuilder.getMaterial(), "material", "materialc"));
            messageBuilder.setFont(BuilderUtil.replaceExt(messageBuilder.getFont(), "font", "fontc"));
            return messageBuilder;
        }
    }

    @ProtoParams(srcClass = TileGrid.class, messageClass = TileGrid.class)
    @BuilderParams(name="TileGrid", inExts={".tilegrid", ".tilemap"}, outExt=".tilemapc")
    public static class TileGridBuilder extends ProtoBuilder<TileGrid.Builder> {
        @Override
        protected TileGrid.Builder transform(Task<Void> task, IResource resource, TileGrid.Builder messageBuilder) throws IOException,
                CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "tile source", messageBuilder.getTileSet());
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "tileset", "t.texturesetc"));
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "tilesource", "t.texturesetc"));
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "atlas", "a.texturesetc"));
            messageBuilder.setMaterial(BuilderUtil.replaceExt(messageBuilder.getMaterial(), "material", "materialc"));
            return messageBuilder;
        }
    }

    @ProtoParams(srcClass = ParticleFX.class, messageClass = ParticleFX.class)
    @BuilderParams(name="ParticleFX", inExts=".particlefx", outExt=".particlefxc")
    public static class ParticleFXBuilder extends ProtoBuilder<ParticleFX.Builder> {
        @Override
        public Task<Void> create(IResource input) throws IOException, CompileExceptionError {

            Task.TaskBuilder<Void> task = Task.<Void>newBuilder(this)
                .setName(params.name())
                .addInput(input)
                .addOutput(input.changeExt(params.outExt()));

            ParticleFX.Builder particleFxBuilder = ParticleFX.newBuilder();
            ProtoUtil.merge(input, particleFxBuilder);

            for (int i = 0; i < particleFxBuilder.getEmittersCount(); ++i) {
                Emitter emitter            = particleFxBuilder.getEmitters(i);
                String tileSource          = emitter.getTileSource();
                IResource tileSourceOutput = project.getResource(tileSource).changeExt(getTextureSetExt(tileSource));
                IResource materialOutput   = project.getResource(emitter.getMaterial()).changeExt(".materialc");

                task.addInput(tileSourceOutput);
                task.addInput(materialOutput);
            }

            return task.build();
        }

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

                MaterialDesc.Builder materialBuilder = getMaterialBuilderFromResource(this.project.getResource(emitterBuilder.getMaterial()));

                validateMaterialAtlasCompatability(this.project, resource, emitterBuilder.getMaterial(), materialBuilder, emitterBuilder.getTileSource());

                emitterBuilder.setTileSource(BuilderUtil.replaceExt(emitterBuilder.getTileSource(), "tileset", "t.texturesetc"));
                emitterBuilder.setTileSource(BuilderUtil.replaceExt(emitterBuilder.getTileSource(), "tilesource", "t.texturesetc"));
                emitterBuilder.setTileSource(BuilderUtil.replaceExt(emitterBuilder.getTileSource(), "atlas", "a.texturesetc"));
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

                List<VertexAttribute> materialAttributes        = materialBuilder.getAttributesList();
                List<VertexAttribute> emitterAttributeOverrides = new ArrayList<VertexAttribute>();

                for (int j=0; j < emitterBuilder.getAttributesCount(); j++) {
                    VertexAttribute emitterAttribute  = emitterBuilder.getAttributes(j);
                    VertexAttribute materialAttribute = GraphicsUtil.getAttributeByName(materialAttributes, emitterAttribute.getName());

                    if (materialAttribute != null) {
                        emitterAttributeOverrides.add(GraphicsUtil.buildVertexAttribute(emitterAttribute, materialAttribute));
                    }
                }

                emitterBuilder.clearAttributes();
                emitterBuilder.addAllAttributes(emitterAttributeOverrides);

                messageBuilder.setEmitters(i, emitterBuilder.build());
            }
            messageBuilder.clearModifiers();
            return messageBuilder;
        }
    }

    @ProtoParams(srcClass = SoundDesc.class, messageClass = SoundDesc.class)
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

    @ProtoParams(srcClass = DisplayProfiles.class, messageClass = DisplayProfiles.class)
    @BuilderParams(name="DisplayProfiles", inExts=".display_profiles", outExt=".display_profilesc")
    public static class DisplayProfilesBuilder extends ProtoBuilder<DisplayProfiles.Builder> {}


}
