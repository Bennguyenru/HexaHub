// Copyright 2020-2023 The Defold Foundation
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
import java.util.List;
import java.util.Collections;

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
import com.dynamo.bob.pipeline.ShaderUtil.Common;
import com.dynamo.proto.DdfMath.Point3;
import com.dynamo.proto.DdfMath.Quat;
import com.dynamo.graphics.proto.Graphics.ShaderDesc;
import com.dynamo.gamesys.proto.BufferProto.BufferDesc;
import com.dynamo.gamesys.proto.Camera.CameraDesc;
import com.dynamo.gamesys.proto.GameSystem.CollectionFactoryDesc;
import com.dynamo.gamesys.proto.GameSystem.CollectionProxyDesc;
import com.dynamo.gamesys.proto.GameSystem.FactoryDesc;
import com.dynamo.gamesys.proto.GameSystem.LightDesc;
import com.dynamo.gamesys.proto.Label.LabelDesc;
import com.dynamo.gamesys.proto.Physics.CollisionObjectDesc;
import com.dynamo.gamesys.proto.Physics.CollisionShape.Shape;
import com.dynamo.gamesys.proto.Physics.CollisionShape.Type;
import com.dynamo.gamesys.proto.Physics.CollisionShape;
import com.dynamo.gamesys.proto.Physics.ConvexShape;
import com.dynamo.gamesys.proto.Sound.SoundDesc;
import com.dynamo.gamesys.proto.Sprite.SpriteDesc;
import com.dynamo.gamesys.proto.Tile.TileGrid;
import com.dynamo.gamesys.proto.AtlasProto.Atlas;
import com.dynamo.input.proto.Input.GamepadMaps;
import com.dynamo.input.proto.Input.InputBinding;
import com.dynamo.particle.proto.Particle.Emitter;
import com.dynamo.particle.proto.Particle.Modifier;
import com.dynamo.particle.proto.Particle.ParticleFX;
import com.dynamo.render.proto.Material.MaterialDesc;
import com.dynamo.render.proto.Render.RenderPrototypeDesc;
import com.dynamo.render.proto.Render.DisplayProfiles;

public class ProtoBuilders {

    private static String[] textureSrcExts = {".png", ".jpg", ".tga", ".cubemap"};
    private static String[][] textureSetSrcExts = {{".atlas", ".a.texturesetc"}, {".tileset", ".t.texturesetc"}, {".tilesource", ".t.texturesetc"}};

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

    private static void validateMaterialAtlasCompatability(Project project, IResource resource, String material, String textureSet) throws IOException, CompileExceptionError {
        if (material == null || textureSet == null) {
            return;
        }

        // Note: For the texture array feature, we need to determine if the shaders of a material has any array samplers.
        //       This could be slow since it needs to be run on _every_ sprite & pfx in the project.
        if (textureSet.endsWith("atlas")) {
            IResource atlasResource    = project.getResource(textureSet);
            IResource materialResource = project.getResource(material);

            if (atlasResource.getContent() == null || materialResource.getContent() == null) {
                return;
            }

            Atlas.Builder atlasBuilder = Atlas.newBuilder();
            ProtoUtil.merge(atlasResource, atlasBuilder);

            Point3 atlasMaxPageSize           = atlasBuilder.getMaxPageSize();
            boolean textureSetHasArrayTexture = atlasMaxPageSize.getX() > 0 && atlasMaxPageSize.getY() > 0;

            MaterialDesc.Builder materialBuilder = MaterialDesc.newBuilder();
            ProtoUtil.merge(materialResource, materialBuilder);

            IResource vsResource = project.getResource(materialBuilder.getVertexProgram());
            IResource fsResource = project.getResource(materialBuilder.getFragmentProgram());

            if (textureSetHasArrayTexture)
            {
                boolean fsHasArraySampler = Common.hasUniformType(new String(fsResource.getContent()), ShaderDesc.ShaderDataType.SHADER_TYPE_SAMPLER2D_ARRAY);
                boolean vsHasArraySampler = Common.hasUniformType(new String(vsResource.getContent()), ShaderDesc.ShaderDataType.SHADER_TYPE_SAMPLER2D_ARRAY);

                if (!(fsHasArraySampler || vsHasArraySampler))
                {
                    throw new CompileExceptionError(resource, 0,
                        String.format("Texture %s is not compatible with material %s, texture is array but none of the shader has any 'sampler2DArray' samplers.", textureSet, material));
                }
            }
            else
            {
                boolean fsHas2DSampler = Common.hasUniformType(new String(fsResource.getContent()), ShaderDesc.ShaderDataType.SHADER_TYPE_SAMPLER2D);
                boolean vsHas2DSampler = Common.hasUniformType(new String(vsResource.getContent()), ShaderDesc.ShaderDataType.SHADER_TYPE_SAMPLER2D);
                if (!(fsHas2DSampler || vsHas2DSampler))
                {
                    throw new CompileExceptionError(resource, 0,
                        String.format("Texture %s is not compatible with material %s, material has no 'sampler2D' samplers.", textureSet, material));
                }
            }
        }
    }

    @ProtoParams(srcClass = CollectionProxyDesc.class, messageClass = CollectionProxyDesc.class)
    @BuilderParams(name="CollectionProxy", inExts=".collectionproxy", outExt=".collectionproxyc")
    public static class CollectionProxyBuilder extends ProtoBuilder<CollectionProxyDesc.Builder> {
        @Override
        protected CollectionProxyDesc.Builder transform(Task<Void> task, IResource resource, CollectionProxyDesc.Builder messageBuilder) throws CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "collection", messageBuilder.getCollection());

            if (messageBuilder.getExclude()) {
            	if (project.getBuildDirectory() != null && resource.output() != null && resource.output().getPath() != null) {
            		if (resource.output().getPath().startsWith(project.getBuildDirectory())) {
            			String excludePath = resource.output().getPath().substring(project.getBuildDirectory().length());
            			excludePath = BuilderUtil.replaceExt(excludePath, ".collectionproxy", ".collectionproxyc");
            			this.project.excludeCollectionProxy(excludePath);
            		}
            	}
            }

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
            String physicsTypeStr = this.project.getProjectProperties().getStringValue("physics", "type", "2D").toUpperCase();
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
    public static class SpriteDescBuilder extends ProtoBuilder<SpriteDesc.Builder> {
        @Override
        protected SpriteDesc.Builder transform(Task<Void> task, IResource resource, SpriteDesc.Builder messageBuilder)
                throws IOException, CompileExceptionError {
            BuilderUtil.checkResource(this.project, resource, "tile source", messageBuilder.getTileSet());

            validateMaterialAtlasCompatability(this.project, resource, messageBuilder.getMaterial(), messageBuilder.getTileSet());

            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "tileset", "t.texturesetc"));
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "tilesource", "t.texturesetc"));
            messageBuilder.setTileSet(BuilderUtil.replaceExt(messageBuilder.getTileSet(), "atlas", "a.texturesetc"));
            messageBuilder.setMaterial(BuilderUtil.replaceExt(messageBuilder.getMaterial(), "material", "materialc"));
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
        protected ParticleFX.Builder transform(Task<Void> task, IResource resource, ParticleFX.Builder messageBuilder)
                throws IOException, CompileExceptionError {
            int emitterCount = messageBuilder.getEmittersCount();
            // Move modifiers to all emitters, clear the list at the end
            List<Modifier> modifiers = messageBuilder.getModifiersList();
            for (int i = 0; i < emitterCount; ++i) {
                Emitter.Builder emitterBuilder = Emitter.newBuilder(messageBuilder.getEmitters(i));
                BuilderUtil.checkResource(this.project, resource, "tile source", emitterBuilder.getTileSource());
                BuilderUtil.checkResource(this.project, resource, "material", emitterBuilder.getMaterial());

                validateMaterialAtlasCompatability(this.project, resource, emitterBuilder.getMaterial(), emitterBuilder.getTileSource());

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
