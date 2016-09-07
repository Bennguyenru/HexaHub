package com.dynamo.bob.pipeline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.vecmath.Point2d;
import javax.vecmath.Point3d;
import javax.vecmath.Quat4d;
import javax.vecmath.Vector2d;
import javax.vecmath.Vector3d;

import org.apache.commons.io.FilenameUtils;

import com.dynamo.bob.Builder;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.ProtoParams;
import com.dynamo.bob.Task;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.textureset.TextureSetGenerator.TextureSetResult;
import com.dynamo.bob.textureset.TextureSetGenerator.UVTransform;
import com.dynamo.bob.util.BezierUtil;
import com.dynamo.bob.util.MathUtil;
import com.dynamo.bob.util.MurmurHash;
import com.dynamo.bob.util.RigScene;
import com.dynamo.bob.util.RigScene.LoadException;
import com.dynamo.bob.util.RigScene.UVTransformProvider;
import com.dynamo.spine.proto.Spine;
import com.dynamo.spine.proto.Spine.SpineSceneDesc;
import com.dynamo.rig.proto.Rig;
import com.dynamo.rig.proto.Rig.AnimationSet;
import com.dynamo.rig.proto.Rig.AnimationTrack;
import com.dynamo.rig.proto.Rig.IKAnimationTrack;
import com.dynamo.rig.proto.Rig.Bone;
import com.dynamo.rig.proto.Rig.EventKey;
import com.dynamo.rig.proto.Rig.EventTrack;
import com.dynamo.rig.proto.Rig.IK;
import com.dynamo.rig.proto.Rig.Mesh;
import com.dynamo.rig.proto.Rig.MeshAnimationTrack;
import com.dynamo.rig.proto.Rig.MeshEntry;
import com.dynamo.rig.proto.Rig.MeshSet;
import com.dynamo.rig.proto.Rig.Skeleton;
import com.dynamo.rig.proto.Rig.RigAnimation;
import com.dynamo.textureset.proto.TextureSetProto.TextureSet;
import com.dynamo.textureset.proto.TextureSetProto.TextureSetAnimation;
import com.google.protobuf.Message;

@ProtoParams(messageClass = SpineSceneDesc.class)
@BuilderParams(name="SpineScene", inExts=".spinescene", outExt=".rigscenec")
public class SpineSceneBuilder extends Builder<Void> {

    private static Quat4d toQuat(double angle) {
        double halfRad = 0.5 * angle * Math.PI / 180.0;
        double c = Math.cos(halfRad);
        double s = Math.sin(halfRad);
        return new Quat4d(0.0, 0.0, s, c);
    }

    private interface PropertyBuilder<T, Key extends RigScene.AnimationKey> {
        void addComposite(T v);
        void add(double v);
        T toComposite(Key key);
    }

    private static abstract class AbstractPropertyBuilder<T> implements PropertyBuilder<T, RigScene.AnimationKey> {
        protected AnimationTrack.Builder builder;

        public AbstractPropertyBuilder(AnimationTrack.Builder builder) {
            this.builder = builder;
        }
    }

    private static abstract class AbstractIKPropertyBuilder<T> implements PropertyBuilder<T, RigScene.IKAnimationKey> {
        protected IKAnimationTrack.Builder builder;

        public AbstractIKPropertyBuilder(IKAnimationTrack.Builder builder) {
            this.builder = builder;
        }
    }

    private static abstract class AbstractMeshPropertyBuilder<T> implements PropertyBuilder<T, RigScene.SlotAnimationKey> {
        protected MeshAnimationTrack.Builder builder;

        public AbstractMeshPropertyBuilder(MeshAnimationTrack.Builder builder) {
            this.builder = builder;
        }
    }

    private static class PositionBuilder extends AbstractPropertyBuilder<Point3d> {
        public PositionBuilder(AnimationTrack.Builder builder) {
            super(builder);
        }

        @Override
        public void addComposite(Point3d v) {
            builder.addPositions((float)v.x).addPositions((float)v.y).addPositions((float)v.z);

        }

        @Override
        public void add(double v) {
            builder.addPositions((float)v);
        }

        @Override
        public Point3d toComposite(RigScene.AnimationKey key) {
            float[] v = key.value;
            return new Point3d(v[0], v[1], 0.0);
        }
    }

    private static class RotationBuilder extends AbstractPropertyBuilder<Quat4d> {
        public RotationBuilder(AnimationTrack.Builder builder) {
            super(builder);
        }

        @Override
        public void addComposite(Quat4d v) {
            this.builder.addRotations((float)v.x).addRotations((float)v.y).addRotations((float)v.z).addRotations((float)v.w);
        }

        @Override
        public void add(double v) {
            addComposite(toQuat(v));
        }

        @Override
        public Quat4d toComposite(RigScene.AnimationKey key) {
            float[] v = key.value;
            return toQuat(v[0]);
        }
    }

    private static class ScaleBuilder extends AbstractPropertyBuilder<Vector3d> {
        public ScaleBuilder(AnimationTrack.Builder builder) {
            super(builder);
        }

        @Override
        public void addComposite(Vector3d v) {
            builder.addScale((float)v.x).addScale((float)v.y).addScale((float)v.z);
        }

        @Override
        public void add(double v) {
            builder.addScale((float)v);
        }

        @Override
        public Vector3d toComposite(RigScene.AnimationKey key) {
            float[] v = key.value;
            return new Vector3d(v[0], v[1], 1.0);
        }
    }

    private static class IKMixBuilder extends AbstractIKPropertyBuilder<Float> {
        public IKMixBuilder(IKAnimationTrack.Builder builder) {
            super(builder);
        }

        @Override
        public void addComposite(Float value) {
            builder.addMix(value);
        }

        @Override
        public void add(double v) {
            builder.addMix((float)v);
        }

        @Override
        public Float toComposite(RigScene.IKAnimationKey key) {
            return new Float(key.mix);
        }
    }

    private static class IKPositiveBuilder extends AbstractIKPropertyBuilder<Boolean> {
        public IKPositiveBuilder(IKAnimationTrack.Builder builder) {
            super(builder);
        }

        @Override
        public void addComposite(Boolean value) {
            builder.addPositive(value);
        }

        @Override
        public void add(double v) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public Boolean toComposite(RigScene.IKAnimationKey key) {
            return new Boolean(key.positive);
        }
    }

    private static class ColorBuilder extends AbstractMeshPropertyBuilder<float[]> {
        public ColorBuilder(MeshAnimationTrack.Builder builder) {
            super(builder);
        }

        @Override
        public void addComposite(float[] c) {
            builder.addColors(c[0]).addColors(c[1]).addColors(c[2]).addColors(c[3]);
        }

        @Override
        public void add(double v) {
            builder.addColors((float)v);
        }

        @Override
        public float[] toComposite(RigScene.SlotAnimationKey key) {
            return key.value;
        }
    }

    private static class VisibilityBuilder extends AbstractMeshPropertyBuilder<Boolean> {
        private String meshName;

        public VisibilityBuilder(MeshAnimationTrack.Builder builder, String meshName) {
            super(builder);
            this.meshName = meshName;
        }

        @Override
        public void addComposite(Boolean value) {
            builder.addVisible(value);
        }

        @Override
        public void add(double v) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public Boolean toComposite(RigScene.SlotAnimationKey key) {
            return this.meshName.equals(key.attachment);
        }
    }

    private static class DrawOrderBuilder extends AbstractMeshPropertyBuilder<Integer> {
        public DrawOrderBuilder(MeshAnimationTrack.Builder builder) {
            super(builder);
        }

        @Override
        public void addComposite(Integer value) {
            builder.addOrderOffset(value);
        }

        @Override
        public void add(double v) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public Integer toComposite(RigScene.SlotAnimationKey key) {
            return key.orderOffset;
        }
    }

    @Override
    public Task<Void> create(IResource input) throws IOException, CompileExceptionError {
        Task.TaskBuilder<Void> taskBuilder = Task.<Void>newBuilder(this)
                .setName(params.name())
                .addInput(input)
                .addOutput(input.changeExt(params.outExt()));

        SpineSceneDesc.Builder builder = SpineSceneDesc.newBuilder();
        ProtoUtil.merge(input, builder);
        BuilderUtil.checkResource(this.project, input, "spine_json", builder.getSpineJson());
        BuilderUtil.checkResource(this.project, input, "atlas", builder.getAtlas());
        
        taskBuilder.addOutput(input.changeExt(".skeletonc"));
        taskBuilder.addOutput(input.changeExt(".meshsetc"));
        taskBuilder.addOutput(input.changeExt(".animationsetc"));

        taskBuilder.addInput(input.getResource(builder.getSpineJson()));
        taskBuilder.addInput(project.getResource(project.getBuildDirectory() + BuilderUtil.replaceExt( builder.getAtlas(), "atlas", "texturesetc")));
        return taskBuilder.build();
    }

    private static int reindexNodesDepthFirst(RigScene.Bone bone, Map<RigScene.Bone, List<RigScene.Bone>> children, int index) {
        List<RigScene.Bone> c = children.get(bone);
        if (c != null) {
            for (RigScene.Bone child : c) {
                child.index = index++;
                index = reindexNodesDepthFirst(child, children, index);
            }
        }
        return index;
    }

    private static List<Integer> toDDF(List<RigScene.Bone> bones, List<RigScene.IK> iks, Skeleton.Builder skeletonBuilder) {
        // Order bones strictly breadth-first
        Map<RigScene.Bone, List<RigScene.Bone>> children = new HashMap<RigScene.Bone, List<RigScene.Bone>>();
        for (RigScene.Bone bone : bones) {
            if (bone.parent != null) {
                List<RigScene.Bone> c = children.get(bone.parent);
                if (c == null) {
                    c = new ArrayList<RigScene.Bone>();
                    children.put(bone.parent, c);
                }
                c.add(bone);
            }
        }
        reindexNodesDepthFirst(bones.get(0), children, 1);
        List<Integer> indexRemap = new ArrayList<Integer>(bones.size());
        for (int i = 0; i < bones.size(); ++i) {
            indexRemap.add(bones.get(i).index);
        }
        Collections.sort(bones, new Comparator<RigScene.Bone>() {
            @Override
            public int compare(RigScene.Bone o1, RigScene.Bone o2) {
                return o1.index - o2.index;
            }
        });
        for (RigScene.Bone bone : bones) {
            Bone.Builder boneBuilder = Bone.newBuilder();
            boneBuilder.setId(MurmurHash.hash64(bone.name));
            int parentIndex = 0xffff;
            if (bone.parent != null) {
                parentIndex = bone.parent.index;
            }
            boneBuilder.setParent(parentIndex);
            boneBuilder.setPosition(MathUtil.vecmathToDDF(bone.localT.position));
            boneBuilder.setRotation(MathUtil.vecmathToDDF(bone.localT.rotation));
            boneBuilder.setScale(MathUtil.vecmathToDDF(bone.localT.scale));
            boneBuilder.setInheritScale(bone.inheritScale);
            boneBuilder.setLength((float)bone.length);
            skeletonBuilder.addBones(boneBuilder);
        }
        for (RigScene.IK ik : iks) {
            IK.Builder ikBuilder = IK.newBuilder();
            ikBuilder.setId(MurmurHash.hash64(ik.name));
            if (ik.parent != null) {
                ikBuilder.setParent(ik.parent.index);
            } else {
                ikBuilder.setParent(ik.child.index);
            }
            ikBuilder.setChild(ik.child.index);
            ikBuilder.setTarget(ik.target.index);
            ikBuilder.setPositive(ik.positive);
            ikBuilder.setMix(ik.mix);
            skeletonBuilder.addIks(ikBuilder);
        }
        return indexRemap;
    }

    private static void toDDF(RigScene.Mesh mesh, Mesh.Builder meshBuilder, List<Integer> boneIndexRemap, int drawOrder) {
        float[] v = mesh.vertices;
        int vertexCount = v.length / 5;
        for (int i = 0; i < vertexCount; ++i) {
            int vi = i * 5;
            for (int pi = 0; pi < 3; ++pi) {
                meshBuilder.addPositions(v[vi+pi]);
            }
            for (int ti = 3; ti < 5; ++ti) {
                meshBuilder.addTexcoord0(v[vi+ti]);
            }
        }
        for (int ci = 0; ci < 4; ++ci) {
            meshBuilder.addColor(mesh.slot.color[ci]);
        }
        if (mesh.boneIndices != null) {
            for (int boneIndex : mesh.boneIndices) {
                meshBuilder.addBoneIndices(boneIndexRemap.get(boneIndex));
            }
            for (float boneWeight : mesh.boneWeights) {
                meshBuilder.addWeights(boneWeight);
            }
        }
        for (int index : mesh.triangles) {
            meshBuilder.addIndices(index);
        }
        meshBuilder.setVisible(mesh.visible);
        meshBuilder.setDrawOrder(drawOrder);
    }

    private static List<MeshIndex> getIndexList(Map<String, List<MeshIndex>> slotIndices, String slot) {
        List<MeshIndex> indexList = slotIndices.get(slot);
        if (indexList == null) {
            indexList = new ArrayList<MeshIndex>();
            slotIndices.put(slot, indexList);
        }
        return indexList;
    }

    private static Map<String, List<MeshIndex>> toDDF(String skinName, List<RigScene.Mesh> generics, List<RigScene.Mesh> specifics, MeshSet.Builder meshSetBuilder, List<Integer> boneIndexRemap) {
        Map<String, List<MeshIndex>> slotIndices = new HashMap<String, List<MeshIndex>>();
        MeshEntry.Builder meshEntryBuilder = MeshEntry.newBuilder();
        meshEntryBuilder.setId(MurmurHash.hash64(skinName));
        List<RigScene.Mesh> meshes = new ArrayList<RigScene.Mesh>(generics.size() + specifics.size());
        meshes.addAll(generics);
        meshes.addAll(specifics);
        Collections.sort(meshes, new Comparator<RigScene.Mesh>() {
            @Override
            public int compare(com.dynamo.bob.util.RigScene.Mesh arg0,
                    com.dynamo.bob.util.RigScene.Mesh arg1) {
                return arg0.slot.index - arg1.slot.index;
            }
        });
        int meshIndex = 0;
        for (RigScene.Mesh mesh : meshes) {
            Mesh.Builder meshBuilder = Mesh.newBuilder();
            toDDF(mesh, meshBuilder, boneIndexRemap, meshIndex);
            meshEntryBuilder.addMeshes(meshBuilder);
            List<MeshIndex> indexList = getIndexList(slotIndices, mesh.slot.name);
            indexList.add(new MeshIndex(meshIndex, mesh.attachment));
            ++meshIndex;
        }
        meshSetBuilder.addMeshEntries(meshEntryBuilder);
        return slotIndices;
    }

    private static double evalCurve(RigScene.AnimationCurve curve, double x) {
        if (curve == null) {
            return x;
        }
        double t = BezierUtil.findT(x, 0.0, curve.x0, curve.x1, 1.0);
        return BezierUtil.curve(t, 0.0, curve.y0, curve.y1, 1.0);
    }

    private static void sampleCurve(RigScene.AnimationCurve curve, PropertyBuilder<?,?> builder, double cursor, double t0, float[] v0, double t1, float[] v1, double spf) {
        double length = t1 - t0;
        double t = (cursor - t0) / length;
        for (int i = 0; i < v0.length; ++i) {
            double y = evalCurve(curve, t);
            double v = (1.0 - y) * v0[i] + y * v1[i];
            builder.add(v);
        }
    }

    private static <T,Key extends RigScene.AnimationKey> void sampleTrack(RigScene.AbstractAnimationTrack<Key> track, PropertyBuilder<T, Key> propertyBuilder, T defaultValue, double duration, double sampleRate, double spf, boolean interpolate) {
        if (track.keys.isEmpty()) {
            return;
        }
        int sampleCount = (int)Math.ceil(duration * sampleRate) + 1;
        int keyIndex = 0;
        int keyCount = track.keys.size();
        Key key = null;
        Key next = track.keys.get(keyIndex);
        T endValue = propertyBuilder.toComposite(track.keys.get(keyCount-1));
        for (int i = 0; i < sampleCount; ++i) {
            double cursor = i * spf;
            // Skip passed keys
            while (next != null && next.t <= cursor) {
                key = next;
                ++keyIndex;
                if (keyIndex < keyCount) {
                    next = track.keys.get(keyIndex);
                } else {
                    next = null;
                }
            }
            if (key != null) {
                if (next != null) {
                    if (key.stepped || !interpolate) {
                        // Stepped sampling only uses current key
                        propertyBuilder.addComposite(propertyBuilder.toComposite(key));
                    } else {
                        // Normal sampling
                        sampleCurve(key.curve, propertyBuilder, cursor, key.t, key.value, next.t, next.value, spf);
                    }
                } else {
                    // Last key reached, use its value for remaining samples
                    propertyBuilder.addComposite(endValue);
                }
            } else {
                // No valid key yet, use default value
                propertyBuilder.addComposite(defaultValue);
            }
        }
    }

    private static void toDDF(RigScene.AnimationTrack track, AnimationTrack.Builder animTrackBuilder, double duration, double sampleRate, double spf) {
        switch (track.property) {
        case POSITION:
            PositionBuilder posBuilder = new PositionBuilder(animTrackBuilder);
            sampleTrack(track, posBuilder, new Point3d(0.0, 0.0, 0.0), duration, sampleRate, spf, true);
            break;
        case ROTATION:
            RotationBuilder rotBuilder = new RotationBuilder(animTrackBuilder);
            sampleTrack(track, rotBuilder, new Quat4d(0.0, 0.0, 0.0, 1.0), duration, sampleRate, spf, true);
            break;
        case SCALE:
            ScaleBuilder scaleBuilder = new ScaleBuilder(animTrackBuilder);
            sampleTrack(track, scaleBuilder, new Vector3d(1.0, 1.0, 1.0), duration, sampleRate, spf, true);
            break;
        }
    }

    private static void toDDF(RigScene.IKAnimationTrack track, IKAnimationTrack.Builder iKanimTrackBuilder, double duration, double sampleRate, double spf) {
        IKMixBuilder mixBuilder = new IKMixBuilder(iKanimTrackBuilder);
        sampleTrack(track, mixBuilder, track.ik.mix, duration, sampleRate, spf, false);
        IKPositiveBuilder positiveBuilder = new IKPositiveBuilder(iKanimTrackBuilder);
        sampleTrack(track, positiveBuilder, track.ik.positive, duration, sampleRate, spf, false);
    }

    private static void toDDF(RigScene.Slot slot, RigScene.SlotAnimationTrack track, MeshAnimationTrack.Builder animTrackBuilder, double duration, double sampleRate, double spf, String meshName) {
        switch (track.property) {
        case ATTACHMENT:
            VisibilityBuilder visibilityBuilder = new VisibilityBuilder(animTrackBuilder, meshName);
            sampleTrack(track, visibilityBuilder, new Boolean(meshName.equals(slot.attachment)), duration, sampleRate, spf, false);
            break;
        case COLOR:
            ColorBuilder colorBuilder = new ColorBuilder(animTrackBuilder);
            sampleTrack(track, colorBuilder, slot.color, duration, sampleRate, spf, true);
            break;
        case DRAW_ORDER:
            DrawOrderBuilder drawOrderBuilder = new DrawOrderBuilder(animTrackBuilder);
            sampleTrack(track, drawOrderBuilder, new Integer(0), duration, sampleRate, spf, false);
            break;
        }
    }

    private static void toDDF(RigScene.EventTrack track, EventTrack.Builder builder) {
        builder.setEventId(MurmurHash.hash64(track.name));
        for (RigScene.EventKey key : track.keys) {
            EventKey.Builder keyBuilder = EventKey.newBuilder();
            keyBuilder.setT(key.t).setInteger(key.intPayload).setFloat(key.floatPayload).setString(MurmurHash.hash64(key.stringPayload));
            builder.addKeys(keyBuilder);
        }
    }

    private static void toDDF(RigScene scene, String id, RigScene.Animation animation, AnimationSet.Builder animSetBuilder, double sampleRate, Map<Long, Map<String, List<MeshIndex>>> slotIndices) {
        RigAnimation.Builder animBuilder = RigAnimation.newBuilder();
        animBuilder.setId(MurmurHash.hash64(id));
        animBuilder.setDuration(animation.duration);
        animBuilder.setSampleRate((float)sampleRate);
        double spf = 1.0 / sampleRate;
        if (!animation.tracks.isEmpty()) {
            List<AnimationTrack.Builder> builders = new ArrayList<AnimationTrack.Builder>();
            AnimationTrack.Builder animTrackBuilder = AnimationTrack.newBuilder();
            RigScene.AnimationTrack firstTrack = animation.tracks.get(0);
            animTrackBuilder.setBoneIndex(firstTrack.bone.index);
            for (RigScene.AnimationTrack track : animation.tracks) {
                if (animTrackBuilder.getBoneIndex() != track.bone.index) {
                    builders.add(animTrackBuilder);
                    animTrackBuilder = AnimationTrack.newBuilder();
                    animTrackBuilder.setBoneIndex(track.bone.index);
                }
                toDDF(track, animTrackBuilder, animation.duration, sampleRate, spf);
            }
            builders.add(animTrackBuilder);
            // Compiled anim tracks must be in bone order
            Collections.sort(builders, new Comparator<AnimationTrack.Builder>() {
                @Override
                public int compare(AnimationTrack.Builder o1, AnimationTrack.Builder o2) {
                    return o1.getBoneIndex() - o2.getBoneIndex();
                }
            });
            for (AnimationTrack.Builder builder : builders) {
                animBuilder.addTracks(builder);
            }
        }

        if (!animation.iKTracks.isEmpty()) {
            List<IKAnimationTrack.Builder> builders = new ArrayList<IKAnimationTrack.Builder>();
            IKAnimationTrack.Builder animTrackBuilder = IKAnimationTrack.newBuilder();
            RigScene.IKAnimationTrack firstTrack = animation.iKTracks.get(0);
            animTrackBuilder.setIkIndex(firstTrack.ik.index);
            for (RigScene.IKAnimationTrack track : animation.iKTracks) {
                if (animTrackBuilder.getIkIndex() != track.ik.index) {
                    builders.add(animTrackBuilder);
                    animTrackBuilder = IKAnimationTrack.newBuilder();
                    animTrackBuilder.setIkIndex(track.ik.index);
                }
                toDDF(track, animTrackBuilder, animation.duration, sampleRate, spf);
            }
            builders.add(animTrackBuilder);
            // Compiled ik tracks must be in ik index order
            Collections.sort(builders, new Comparator<IKAnimationTrack.Builder>() {
                @Override
                public int compare(IKAnimationTrack.Builder o1, IKAnimationTrack.Builder o2) {
                    return o1.getIkIndex() - o2.getIkIndex();
                }
            });
            for (IKAnimationTrack.Builder builder : builders) {
                animBuilder.addIkTracks(builder);
            }
        }

        if (!animation.slotTracks.isEmpty()) {
            for (Map.Entry<Long, Map<String, List<MeshIndex>>> skinEntry : slotIndices.entrySet()) {
                long skinId = skinEntry.getKey();
                Map<String, List<MeshIndex>> slotToMeshIndex = skinEntry.getValue();
                for (RigScene.SlotAnimationTrack track : animation.slotTracks) {
                    List<MeshIndex> meshIndices = slotToMeshIndex.get(track.slot.name);
                    RigScene.Slot slot = scene.getSlot(track.slot.name);
                    if (meshIndices != null) {
                        for (MeshIndex meshIndex : meshIndices) {
                            MeshAnimationTrack.Builder trackBuilder = MeshAnimationTrack.newBuilder();
                            trackBuilder.setMeshId(skinId);
                            trackBuilder.setMeshIndex(meshIndex.index);
                            toDDF(slot, track, trackBuilder, animation.duration, sampleRate, spf, meshIndex.name);
                            animBuilder.addMeshTracks(trackBuilder.build());
                        }
                    }
                }
            }
        }
        for (RigScene.EventTrack track : animation.eventTracks) {
            EventTrack.Builder builder = EventTrack.newBuilder();
            toDDF(track, builder);
            animBuilder.addEventTracks(builder);
        }

        animSetBuilder.addAnimations(animBuilder);
    }

    private static class MeshIndex {
        public MeshIndex(int index, String name) {
            this.index = index;
            this.name = name;
        }

        public int index;
        public String name;
    }

    @Override
    public void build(Task<Void> task) throws CompileExceptionError,
            IOException {

        SpineSceneDesc.Builder builder = SpineSceneDesc.newBuilder();
        ProtoUtil.merge(task.input(0), builder);

        // Load previously created atlas textureset
        TextureSet.Builder resultBuilder = TextureSet.newBuilder();
        resultBuilder.mergeFrom(task.input(2).getContent());

        TextureSet result = resultBuilder.build();
        FloatBuffer uv = ByteBuffer.wrap(result.getTexCoords().toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();

        final Map<String, UVTransform> animToTransform = new HashMap<String, UVTransform>();
        for (TextureSetAnimation animation : result.getAnimationsList()) {
            int i = animation.getStart() * 8;

            // We base the rotation on what order minU and maxV comes, see texture_set_ddf.proto
            // If it's rotated the value of minU should be at position 0 and 6.
            animToTransform.put(animation.getId(),
                new UVTransform(new Point2d(uv.get(i), uv.get(i+3)),
                                new Vector2d(uv.get(i+4) - uv.get(i), uv.get(i+7) - uv.get(i+3)),
                                uv.get(i) == uv.get(i+6)) );
        }

        Rig.RigScene.Builder b = Rig.RigScene.newBuilder();
        try {
            RigScene scene = RigScene.loadJson(new ByteArrayInputStream(task.input(1).getContent()), new UVTransformProvider() {
                @Override
                public UVTransform getUVTransform(String animId) {
                    return animToTransform.get(animId);
                }
            });
            ByteArrayOutputStream out;

            // Skeleton
            Skeleton.Builder skeletonBuilder = Skeleton.newBuilder();
            List<Integer> boneIndexRemap = toDDF(scene.bones, scene.iks, skeletonBuilder);
            out = new ByteArrayOutputStream(64 * 1024);
            skeletonBuilder.build().writeTo(out);
            out.close();
            task.output(1).setContent(out.toByteArray());

            // MeshSet
            MeshSet.Builder meshSetBuilder = MeshSet.newBuilder();
            Map<Long, Map<String, List<MeshIndex>>> slotIndices = new HashMap<Long, Map<String, List<MeshIndex>>>();
            slotIndices.put(MurmurHash.hash64(""), toDDF("", scene.meshes, Collections.<RigScene.Mesh>emptyList(), meshSetBuilder, boneIndexRemap));
            for (Map.Entry<String, List<RigScene.Mesh>> entry : scene.skins.entrySet()) {
                slotIndices.put(MurmurHash.hash64(entry.getKey()), toDDF(entry.getKey(), scene.meshes, entry.getValue(), meshSetBuilder, boneIndexRemap));
            }
            out = new ByteArrayOutputStream(64 * 1024);
            meshSetBuilder.build().writeTo(out);
            out.close();
            task.output(2).setContent(out.toByteArray());

            // AnimationSet
            AnimationSet.Builder animSetBuilder = AnimationSet.newBuilder();
            for (Map.Entry<String, RigScene.Animation> entry : scene.animations.entrySet()) {
                toDDF(scene, entry.getKey(), entry.getValue(), animSetBuilder, builder.getSampleRate(), slotIndices);
            }
            out = new ByteArrayOutputStream(64 * 1024);
            animSetBuilder.build().writeTo(out);
            out.close();
            task.output(3).setContent(out.toByteArray());

        } catch (LoadException e) {
            throw new CompileExceptionError(task.input(1), -1, e.getMessage());
        }

        int buildDirLen = project.getBuildDirectory().length();

        b.setSkeleton(task.output(1).getPath().substring(buildDirLen));
        b.setMeshSet(task.output(2).getPath().substring(buildDirLen));
        b.setAnimationSet(task.output(3).getPath().substring(buildDirLen));
        b.setTextureSet(BuilderUtil.replaceExt(builder.getAtlas(), "atlas", "texturesetc"));

        Message msg = b.build();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        msg.writeTo(out);
        out.close();
        task.output(0).setContent(out.toByteArray());
    }
}
