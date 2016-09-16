package com.dynamo.bob.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.jackson.JsonNode;
import org.jagatoo.loaders.models.collada.Rotations;
import org.jagatoo.loaders.models.collada.datastructs.animation.Bone;

import javax.vecmath.Point3d;
import javax.vecmath.Vector3d;
import javax.vecmath.Quat4d;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jagatoo.loaders.models.collada.stax.XMLAnimation;
import org.jagatoo.loaders.models.collada.stax.XMLCOLLADA;
import org.jagatoo.loaders.models.collada.stax.XMLGeometry;
import org.jagatoo.loaders.models.collada.stax.XMLInput;
import org.jagatoo.loaders.models.collada.stax.XMLLibraryAnimations;
import org.jagatoo.loaders.models.collada.stax.XMLLibraryGeometries;
import org.jagatoo.loaders.models.collada.stax.XMLMesh;
import org.jagatoo.loaders.models.collada.stax.XMLParam;
import org.jagatoo.loaders.models.collada.stax.XMLSampler;
import org.jagatoo.loaders.models.collada.stax.XMLNode;
import org.jagatoo.loaders.models.collada.stax.XMLSource;
import org.jagatoo.loaders.models.collada.stax.XMLVisualScene;
import org.openmali.FastMath;
import org.openmali.vecmath2.Matrix4f;
import org.openmali.vecmath2.Point3f;
import org.openmali.vecmath2.Quaternion4f;
import org.openmali.vecmath2.Tuple3f;
import org.openmali.vecmath2.Vector3f;


import com.dynamo.bob.util.MathUtil;
import com.dynamo.bob.util.RigUtil;

import com.dynamo.bob.util.MurmurHash;
import com.dynamo.bob.util.RigUtil.AbstractAnimationTrack;
import com.dynamo.bob.util.RigUtil.AnimationCurve;
import com.dynamo.bob.util.RigUtil.AnimationCurve.CurveIntepolation;
import com.dynamo.bob.util.RigUtil.AnimationKey;
import com.dynamo.bob.util.RigUtil.AnimationTrack.Property;
import com.dynamo.bob.util.SpineSceneUtil.JsonUtil;
import com.dynamo.proto.DdfMath.Point3;
import com.dynamo.proto.DdfMath.Quat;
import com.dynamo.proto.DdfMath.Vector3;

import com.dynamo.rig.proto.Rig.AnimationSet;
import com.dynamo.rig.proto.Rig.AnimationTrack;
import com.dynamo.rig.proto.Rig.Mesh;
import com.dynamo.rig.proto.Rig.RigAnimation;
import com.dynamo.rig.proto.Rig.RigScene;
import com.dynamo.rig.proto.Rig.Skeleton;


public class ColladaUtil {

    private static XMLInput findInput(List<XMLInput> inputs, String semantic, boolean required)
            throws LoaderException {
        for (XMLInput i : inputs) {
            if (i.semantic.equals(semantic))
                return i;
        }

        if (required)
            throw new LoaderException(String.format("Input '%s' not found", semantic));
        return null;
    }

    private static HashMap<String, XMLSource> getSourcesMap(XMLMesh mesh,
            List<XMLSource> sources) {
        HashMap<String, XMLSource> sourcesMap;
        sourcesMap = new HashMap<String, XMLSource>();
        for (int i = 0; i < sources.size(); i++) {
            XMLSource source = sources.get(i);

            sourcesMap.put(source.id, source);
        }

        return sourcesMap;
    }

    public static XMLCOLLADA loadDAE(InputStream is) throws IOException, XMLStreamException, LoaderException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLStreamReader stream_reader = factory.createXMLStreamReader(is);

        XMLCOLLADA collada = new XMLCOLLADA();
        collada.parse(stream_reader);

        return collada;
    }

    public static boolean load(InputStream is, Mesh.Builder meshBuilder, AnimationSet.Builder animationSetBuilder, Skeleton.Builder skeletonBuilder) throws IOException, XMLStreamException, LoaderException {
        XMLCOLLADA collada = loadDAE(is);
        meshBuilder.mergeFrom(loadMesh(collada));
        Skeleton skeleton = loadSkeleton(collada);
        skeletonBuilder.mergeFrom(skeleton);
        animationSetBuilder.mergeFrom(loadAnimations(collada, skeleton, 30.0f)); // TODO pick the sample rate from a parameter
        return true;
    }

    private static HashMap<String, XMLSource> getSamplersLUT(XMLAnimation animation) {
        XMLSampler sampler = animation.samplers.get(0);
        HashMap<String, XMLSource> samplersLUT = new HashMap<String, XMLSource>();
        for (int i = 0; i < sampler.inputs.size(); i++) {
            XMLInput input = sampler.inputs.get(i);

            // Find source for sampler
            XMLSource source = null;
            for (int j = 0; j < animation.sources.size(); j++) {
                if (animation.sources.get(j).id.equals(input.source)) {
                    source = animation.sources.get(j);
                    break;
                }
            }

            samplersLUT.put(input.semantic, source);
        }
        return samplersLUT;
    }

    private static void loadTrack(XMLAnimation animation, RigUtil.AnimationTrack track) {
        float[] t = animation.getInput();
        float[] outputs = animation.getOutput();

        boolean hasCurve = false;
        float[] inTangents = animation.getInTangents();
        float[] outTangents = animation.getOutTangents();
        if (inTangents != null && outTangents != null) {
            hasCurve = true;
        }

        String[] interpolations = animation.getInterpolations();

        Float prevAngles = null;
        for (int i = 0; i < t.length; i++) {
            AnimationKey key = new AnimationKey();
            key.t = t[i];
            switch (track.property) {
            case POSITION_COMPONENT:
                key.value = new float[] {outputs[i]};
                break;
            case ROTATION_COMPONENT:
              // See the comment above why this is done for rotations
              float angles = outputs[i];
              if (prevAngles != null) {
                  float diff = angles - prevAngles;
                  if (Math.abs(diff) > 180.0f) {
                      angles += 360.0f * -Math.signum(diff);
                  }
              }
              prevAngles = angles;
              key.value = new float[] {angles};
                break;
            default:
                System.out.println("loadTrack for " + track.property.toString() + " not implemented yet!");
                break;
            }
            if (hasCurve) {
                AnimationCurve curve = new AnimationCurve();
                if (interpolations[i].equals("BEZIER")) {
                    curve.interpolation = CurveIntepolation.BEZIER;
                } else {
                    curve.interpolation = CurveIntepolation.LINEAR;
                }
                // TMP: Force linear curve interpolation!
                curve.interpolation = CurveIntepolation.LINEAR;
                curve.x0 = (float)inTangents[i*2];
                curve.y0 = (float)inTangents[i*2+1];
                curve.x1 = (float)outTangents[i*2];
                curve.y1 = (float)outTangents[i*2+1];
                key.curve = curve;
            } else {
                key.stepped = true;
            }
            track.keys.add(key);
        }
    }

    private static void toDDF(RigAnimation.Builder animBuilder, com.dynamo.rig.proto.Rig.Skeleton skeleton, String animationId, long meshBoneId, HashMap<Long, HashMap<String, XMLAnimation>> boneToAnimations, float duration, float sampleRate) {
        animBuilder.setId(MurmurHash.hash64(animationId));
        animBuilder.setDuration(duration);
        animBuilder.setSampleRate(sampleRate);

        double spf = 1.0 / sampleRate;

        for (int bi = 0; bi < skeleton.getBonesCount(); bi++) {
            com.dynamo.rig.proto.Rig.Bone bone = skeleton.getBones(bi);

            // Get animations for bone:
            if (boneToAnimations.containsKey(bone.getId())) {
                HashMap<String, XMLAnimation> propAnimations = boneToAnimations.get(bone.getId());

                AnimationTrack.Builder animTrackBuilder = AnimationTrack.newBuilder();
                animTrackBuilder.setBoneIndex(bi);

                // Positions
                if (propAnimations.containsKey("location.X") || propAnimations.containsKey("location.Y") || propAnimations.containsKey("location.Z")) {

                    AnimationTrack.Builder animTrackBuilderX = AnimationTrack.newBuilder();
                    RigUtil.PositionComponentBuilder posXBuilder = new RigUtil.PositionComponentBuilder(animTrackBuilderX);

                    AnimationTrack.Builder animTrackBuilderY = AnimationTrack.newBuilder();
                    RigUtil.PositionComponentBuilder posYBuilder = new RigUtil.PositionComponentBuilder(animTrackBuilderY);

                    AnimationTrack.Builder animTrackBuilderZ = AnimationTrack.newBuilder();
                    RigUtil.PositionComponentBuilder posZBuilder = new RigUtil.PositionComponentBuilder(animTrackBuilderZ);

                    if (propAnimations.containsKey("location.X")) {
                        XMLAnimation locX = propAnimations.get("location.X");
                        RigUtil.AnimationTrack track = new RigUtil.AnimationTrack();
                        track.property = RigUtil.AnimationTrack.Property.POSITION_COMPONENT;
                        loadTrack(locX, track);
                        RigUtil.sampleTrack(track, posXBuilder, new Double(0.0), duration, sampleRate, spf, true);
                    }

                    if (propAnimations.containsKey("location.Y")) {
                        XMLAnimation locY = propAnimations.get("location.Y");
                        RigUtil.AnimationTrack track = new RigUtil.AnimationTrack();
                        track.property = RigUtil.AnimationTrack.Property.POSITION_COMPONENT;
                        loadTrack(locY, track);
                        RigUtil.sampleTrack(track, posYBuilder, new Double(0.0), duration, sampleRate, spf, true);
                    }

                    if (propAnimations.containsKey("location.Z")) {
                        XMLAnimation locZ = propAnimations.get("location.Z");
                        RigUtil.AnimationTrack track = new RigUtil.AnimationTrack();
                        track.property = RigUtil.AnimationTrack.Property.POSITION_COMPONENT;
                        loadTrack(locZ, track);
                        RigUtil.sampleTrack(track, posZBuilder, new Double(0.0), duration, sampleRate, spf, true);
                    }

                    assert(animTrackBuilderX.getPositionsCount() == animTrackBuilderY.getPositionsCount());
                    assert(animTrackBuilderY.getPositionsCount() == animTrackBuilderZ.getPositionsCount());

                    for (int i = 0; i < animTrackBuilderX.getPositionsCount(); i++) {
                        animTrackBuilder.addPositions(animTrackBuilderY.getPositions(i));
                        animTrackBuilder.addPositions(animTrackBuilderZ.getPositions(i));
                        animTrackBuilder.addPositions(animTrackBuilderX.getPositions(i));

                        animTrackBuilder.addScale(1.0f);
                        animTrackBuilder.addScale(1.0f);
                        animTrackBuilder.addScale(1.0f);
                    }

                }

                if (propAnimations.containsKey("rotationX.ANGLE") || propAnimations.containsKey("rotationY.ANGLE") || propAnimations.containsKey("rotationZ.ANGLE")) {

                    AnimationTrack.Builder animTrackBuilderX = AnimationTrack.newBuilder();
                    RigUtil.PositionComponentBuilder posXBuilder = new RigUtil.PositionComponentBuilder(animTrackBuilderX);

                    AnimationTrack.Builder animTrackBuilderY = AnimationTrack.newBuilder();
                    RigUtil.PositionComponentBuilder posYBuilder = new RigUtil.PositionComponentBuilder(animTrackBuilderY);

                    AnimationTrack.Builder animTrackBuilderZ = AnimationTrack.newBuilder();
                    RigUtil.PositionComponentBuilder posZBuilder = new RigUtil.PositionComponentBuilder(animTrackBuilderZ);

                    if (propAnimations.containsKey("rotationX.ANGLE")) {
                        XMLAnimation locX = propAnimations.get("rotationX.ANGLE");
                        RigUtil.AnimationTrack track = new RigUtil.AnimationTrack();
                        track.property = RigUtil.AnimationTrack.Property.ROTATION_COMPONENT;
                        loadTrack(locX, track);
                        RigUtil.sampleTrack(track, posXBuilder, new Double(0.0), duration, sampleRate, spf, true);
                    }

                    if (propAnimations.containsKey("rotationY.ANGLE")) {
                        XMLAnimation locY = propAnimations.get("rotationY.ANGLE");
                        RigUtil.AnimationTrack track = new RigUtil.AnimationTrack();
                        track.property = RigUtil.AnimationTrack.Property.ROTATION_COMPONENT;
                        loadTrack(locY, track);
                        RigUtil.sampleTrack(track, posYBuilder, new Double(0.0), duration, sampleRate, spf, true);
                    }

                    if (propAnimations.containsKey("rotationZ.ANGLE")) {
                        XMLAnimation locZ = propAnimations.get("rotationZ.ANGLE");
                        RigUtil.AnimationTrack track = new RigUtil.AnimationTrack();
                        track.property = RigUtil.AnimationTrack.Property.ROTATION_COMPONENT;
                        loadTrack(locZ, track);
                        RigUtil.sampleTrack(track, posZBuilder, new Double(0.0), duration, sampleRate, spf, true);
                    }

                    assert(animTrackBuilderX.getPositionsCount() == animTrackBuilderY.getPositionsCount());
                    assert(animTrackBuilderY.getPositionsCount() == animTrackBuilderZ.getPositionsCount());

                    for (int i = 0; i < animTrackBuilderX.getPositionsCount(); i++) {

                        float toAngle = (float)Math.PI / 180.0f;
                        Tuple3f euler = new Tuple3f(animTrackBuilderY.getPositions(i)*toAngle, animTrackBuilderZ.getPositions(i)*toAngle, animTrackBuilderX.getPositions(i)*toAngle);
                        Quaternion4f quat = Rotations.toQuaternion( euler );

                        animTrackBuilder.addRotations(quat.getA());
                        animTrackBuilder.addRotations(quat.getB());
                        animTrackBuilder.addRotations(quat.getC());
                        animTrackBuilder.addRotations(quat.getD());
                    }
                }

                animBuilder.addTracks(animTrackBuilder.build());
            }

            // TODO scale
        }

    }

    public static AnimationSet loadAnimations(XMLCOLLADA collada, com.dynamo.rig.proto.Rig.Skeleton skeleton, float sampleRate) throws IOException, XMLStreamException, LoaderException {

        AnimationSet.Builder animationSetBuilder = AnimationSet.newBuilder();
        if (collada.libraryAnimations.size() != 1) {
            return animationSetBuilder.build();
        }

        // We only support one model per scene for now, get first geo entry.
        ArrayList<XMLLibraryGeometries> geometries = collada.libraryGeometries;
        String geometryName = "";
        for (XMLGeometry geometry : geometries.get(0).geometries.values()) {
            geometryName = geometry.name;
            break;
        }

        float maxAnimationLength = 0.0f;
        //for (int i = 0; i < collada.libraryAnimations.size(); i++) {
        {
            HashMap<Long, HashMap<String, XMLAnimation>> boneToAnimations = new HashMap<Long, HashMap<String, XMLAnimation>>();

            // Loop through all animations (where an animation is keyframed animation on different properties)
            //XMLLibraryAnimations libraryAnimation = collada.libraryAnimations.get(i);
            XMLLibraryAnimations libraryAnimation = collada.libraryAnimations.get(0);
            Iterator<Entry<String, XMLAnimation>> it = libraryAnimation.animations.entrySet().iterator();
            while (it.hasNext()) {
                HashMap.Entry pair = (HashMap.Entry)it.next();

                String animationId = (String)pair.getKey();
                XMLAnimation animation = (XMLAnimation)pair.getValue();
                System.out.println("animationId: " + animationId);

                // Get animations for root node (ie model)
                String targetPath = animation.channels.get(0).target;
                String[] targetParts = targetPath.split("/");
                String boneTarget = targetParts[0];
                String propertyTarget = targetParts[1];

                if (!boneToAnimations.containsKey(MurmurHash.hash64(boneTarget))) {
                    boneToAnimations.put(MurmurHash.hash64(boneTarget), new HashMap<String, XMLAnimation>());
                }
                boneToAnimations.get(MurmurHash.hash64(boneTarget)).put(propertyTarget, animation);

                // Figure out the total duration of the animation.
                HashMap<String, XMLSource> samplersLUT = getSamplersLUT(animation);
                if (samplersLUT == null || samplersLUT.isEmpty()) {
                    throw new LoaderException("Animation lacks samplers.");
                }

                // Find time input
                if (!samplersLUT.containsKey("INPUT")) {
                    throw new LoaderException("Animation lacks INPUT sampler.");
                }
                XMLSource inputSampler = samplersLUT.get("INPUT");
                if (inputSampler.techniqueCommon.accessor.params.get(0).name != XMLParam.Name.TIME) {
                    throw new LoaderException("Animation input is not a time channel: '" + inputSampler.techniqueCommon.accessor.params.get(0).name + "'.");
                }
                float animLength = inputSampler.floatArray.floats[inputSampler.floatArray.count-1];
                maxAnimationLength = Math.max(maxAnimationLength, animLength);

                assert(maxAnimationLength > 0.0f);
            }

            RigAnimation.Builder animBuilder = RigAnimation.newBuilder();
            toDDF(animBuilder, skeleton, "default", MurmurHash.hash64(geometryName), boneToAnimations, maxAnimationLength, sampleRate);
            animationSetBuilder.addAnimations(animBuilder.build());

        }

        return animationSetBuilder.build();
    }

    public static Mesh loadMesh(XMLCOLLADA collada) throws IOException,
            XMLStreamException, LoaderException {


        if (collada.libraryGeometries.size() != 1) {
            throw new LoaderException("Only a single geometry is supported");
        }

        XMLGeometry geom = collada.libraryGeometries.get(0).geometries.values()
                .iterator().next();

        XMLMesh mesh = geom.mesh;

        List<XMLSource> sources = mesh.sources;
        HashMap<String, XMLSource> sourcesMap = getSourcesMap(mesh, sources);

        XMLInput vpos_input = findInput(mesh.vertices.inputs, "POSITION", true);
        XMLInput vertex_input = findInput(mesh.triangles.inputs, "VERTEX", true);
        XMLInput normal_input = findInput(mesh.triangles.inputs, "NORMAL", false);

        XMLInput texcoord_input = findInput(mesh.triangles.inputs, "TEXCOORD", false);

        // NOTE: Normals could be part of the vertex of specified directly in triangles...
        int normalOffset;
        if (normal_input  != null) {
            normalOffset = normal_input.offset;
        } else {
            normalOffset = 0;
            normal_input = findInput(mesh.vertices.inputs, "NORMAL", false);
        }

        if (mesh.triangles.inputs.size() == 0)
            throw new LoaderException("No inputs in triangles");

        int stride = 0;
        for (XMLInput i : mesh.triangles.inputs) {
            stride = Math.max(stride, i.offset);
        }
        stride += 1;

        XMLSource positions = sourcesMap.get(vpos_input.source);
        XMLSource normals = null;
        if (normal_input != null) {
            normals = sourcesMap.get(normal_input.source);
        }

        XMLSource texcoords = null;
        if (texcoord_input != null) {
            texcoords = sourcesMap.get(texcoord_input.source);
        }

        List<Float> position_list = new ArrayList<Float>();
        List<Float> normal_list = new ArrayList<Float>();
        List<Float> texcoord_list = new ArrayList<Float>();
        List<Integer> indices_list = new ArrayList<Integer>();
        List<Integer> bone_indices_list= new ArrayList<Integer>();
        List<Float> bone_weights_list= new ArrayList<Float>();

        float meter = collada.asset.unit.meter;
        for (int i = 0; i < mesh.triangles.count; ++i) {
            int idx = i * stride * 3 + vertex_input.offset;

            // TMP since rig needs indexed rendering for now...
            indices_list.add(i*3);
            indices_list.add(i*3+1);
            indices_list.add(i*3+2);

            for (int v = 0; v < 3; v++) {
                bone_indices_list.add(0);
                bone_indices_list.add(0);
                bone_indices_list.add(0);
                bone_indices_list.add(0);
                bone_weights_list.add(1.0f);
                bone_weights_list.add(0.0f);
                bone_weights_list.add(0.0f);
                bone_weights_list.add(0.0f);
            }



            for (int j = 0; j < 3; ++j) {
                int tri_ind = mesh.triangles.p[idx + stride * j];

                float px = positions.floatArray.floats[3 * tri_ind + 0];
                float py = positions.floatArray.floats[3 * tri_ind + 1];
                float pz = positions.floatArray.floats[3 * tri_ind + 2];

                position_list.add(px * meter);
                position_list.add(py * meter);
                position_list.add(pz * meter);
            }

            for (int j = 0; j < 3; ++j) {

                float px, py, pz;

                if (normals == null) {
                    px = py = 0;
                    pz = 1;
                } else {
                    idx = i * stride * 3 + normalOffset;
                    int tri_ind = mesh.triangles.p[idx + stride * j];
                    px = normals.floatArray.floats[3 * tri_ind + 0];
                    py = normals.floatArray.floats[3 * tri_ind + 1];
                    pz = normals.floatArray.floats[3 * tri_ind + 2];
                }

                normal_list.add(px);
                normal_list.add(py);
                normal_list.add(pz);
            }


            for (int j = 0; j < 3; ++j) {

                float u, v;

                if (normals == null || texcoords == null) {
                    u  = v  = 0;
                } else {
                    idx = i * stride * 3 + texcoord_input.offset;
                    int tri_ind = mesh.triangles.p[idx + stride * j];
                    u = texcoords.floatArray.floats[2 * tri_ind + 0];
                    v = texcoords.floatArray.floats[2 * tri_ind + 1];
                }

                texcoord_list.add(u);
                texcoord_list.add(v);
            }

        }

        Mesh.Builder b = Mesh.newBuilder();
        b.addAllPositions(position_list);
        b.addAllNormals(normal_list);
        b.addAllTexcoord0(texcoord_list);
        b.addAllIndices(indices_list);
        b.addAllBoneIndices(bone_indices_list);
        b.addAllWeights(bone_weights_list);
        return b.build();
    }


    public static com.dynamo.rig.proto.Rig.Skeleton loadSkeleton(XMLCOLLADA collada) throws IOException, XMLStreamException, LoaderException {
        com.dynamo.rig.proto.Rig.Skeleton.Builder skeletonBuilder = com.dynamo.rig.proto.Rig.Skeleton.newBuilder();

        if (collada.libraryVisualScenes.size() != 1) {
            return skeletonBuilder.build();
        }

        // We only support one model per scene for now, get first geo entry.
        ArrayList<XMLLibraryGeometries> geometries = collada.libraryGeometries;
        String geometryName = "";
        for (XMLGeometry geometry : geometries.get(0).geometries.values()) {
            geometryName = geometry.name;
            break;
        }

        // Add a fake root bone that mesh animation is performed upon
        ArrayList<com.dynamo.rig.proto.Rig.Bone> bones = new ArrayList<com.dynamo.rig.proto.Rig.Bone>();
        Bone fakeRootBone = new Bone(geometryName, geometryName, Matrix4f.IDENTITY, Quaternion4f.IDENTITY);

        XMLNode rootNode = null;
        for ( XMLVisualScene scene : collada.libraryVisualScenes.get(0).scenes.values() ) {
            for ( XMLNode node : scene.nodes.values() ) {
                rootNode = findSkeleton(node);
                if(rootNode != null) {
                    break;
                }
            }
        }

        if(rootNode != null) {
            Vector3f upVector = new Vector3f(0.0f, 1.0f, 0.0f);
            Point3f skeletonPos;
            Matrix4f localToWorld;
            localToWorld = rootNode.matrix.matrix4f;
            skeletonPos = new Point3f( localToWorld.m03(), localToWorld.m13(), localToWorld.m23() );

            Bone rootBone = new Bone( rootNode.sid, rootNode.name, localToWorld, new Quaternion4f( 0f, 0f, 0f, 1f ) );
            ArrayList<Bone> boneList = new ArrayList<Bone>();
            loadJoint( localToWorld, rootNode, upVector, Point3f.ZERO, Point3f.ZERO, rootBone, boneList );

            fakeRootBone.addChild(rootBone);
        }

        toDDF(bones, fakeRootBone, 0xffff);

        skeletonBuilder.addAllBones(bones);
        return skeletonBuilder.build();
    }

    private static XMLNode findSkeleton(XMLNode node) {
        if(node.type == XMLNode.Type.JOINT) {
            return node;
        }
        XMLNode rootNode = null;
        if(!node.childrenList.isEmpty()) {
            for(XMLNode childNode : node.childrenList) {
                rootNode = findSkeleton(childNode);
                if(rootNode != null) {
                    break;
                }
            }
        }
        return rootNode;
    }

    private static void loadJoint( Matrix4f localToWorld, XMLNode node, Vector3f upVector, Point3f parentRoot, Point3f parentTip, Bone bone, ArrayList<Bone> boneList )
    {
        boneList.add( bone );
        if ( node.childrenList == null || ( node.childrenList != null && node.childrenList.isEmpty() ) ) {
            return;
        }

        // Get the node tip
        Matrix4f colMatrix = node.childrenList.get( 0 ).matrix.matrix4f;
        Point3f nodeTip = new Point3f(
                parentTip.getX() + colMatrix.get( 0, 3 ),
                parentTip.getY() + colMatrix.get( 1, 3 ),
                parentTip.getZ() + colMatrix.get( 2, 3 )
        );

        // Transform all into world coordinates
        Point3f parentRootW = new Point3f( parentRoot );
        localToWorld.transform( parentRootW );
        Point3f parentTipW = new Point3f( parentTip );
        localToWorld.transform( parentTipW );
        Point3f nodeTipW = new Point3f( nodeTip );
        localToWorld.transform( nodeTipW );

        // Compute the vectors
        Vector3f nodeVecW = new Vector3f();
        nodeVecW.sub( nodeTipW, parentTipW );
        Vector3f parentVecW = new Vector3f();
        parentVecW.sub( parentTipW, parentRootW );
        float length = nodeVecW.length();
        parentVecW.normalize();
        nodeVecW.normalize();

        // Compute the angle
        float angle = FastMath.acos( parentVecW.dot( nodeVecW ) );
        if ( Float.isNaN( angle ) )
        {
            // Singularity : if a vector is the 0-vector, the angle will be NaN so set it to 0.
            angle = 0f;
        }
        Vector3f axis = new Vector3f();
        axis.cross( parentVecW, nodeVecW );
        if ( Float.isNaN( axis.getX() ) | Float.isNaN( axis.getY() ) | Float.isNaN( axis.getZ() ) )
        {
            // Singularity : Angle = 0. The axis found is (NaN, NaN, NaN)
            // In this case we reset it to UP.
            axis.set( upVector );
        }
        if ( axis.lengthSquared() == 0f )
        {
            // Singularity : Angle = 180, there is no single axis. In this case, we take an axis which is perpendicular
            // to one of the two vectors. This avoid NaNs.
            axis.set( parentVecW.getZ(), parentVecW.getX(), parentVecW.getY() );
            axis.normalize();
            axis.negate();
        }
        else
        {
            // For quaternion conversion
            axis.normalize();
            axis.negate();
        }
        Quaternion4f quat = Rotations.toQuaternion( axis, angle );
        bone.setBindRotation( quat );
        bone.setLength( length );

        for ( XMLNode child : node.childrenList )
        {
            Bone newBone = new Bone( child.sid, child.name, child.matrix.matrix4f, new Quaternion4f( 0f, 0f, 0f, 1f ) );
            bone.addChild( newBone );
            loadJoint( localToWorld, child, upVector, parentTip, nodeTip, newBone, boneList );
        }
    }

    private static void toDDF(ArrayList<com.dynamo.rig.proto.Rig.Bone> builderList, Bone bone, int parentIndex) {

        int boneIndex = builderList.size();
        com.dynamo.rig.proto.Rig.Bone.Builder b = com.dynamo.rig.proto.Rig.Bone.newBuilder();

        b.setParent(parentIndex);
        b.setId(MurmurHash.hash64(bone.getName()));

        Vector3f v = bone.getAbsoluteTranslation();
        Point3 position = MathUtil.vecmathToDDF(new Point3d(v.getX(), v.getY(), v.getZ()));
        b.setPosition(position);

        Quaternion4f q = bone.getBindRotation();
        Quat rotation = MathUtil.vecmathToDDF(new Quat4d(q.getA(), q.getB(), q.getC(), q.getD()));
        b.setRotation(rotation);

        Tuple3f t = bone.getAbsoluteScaling();
        Vector3 scale = MathUtil.vecmathToDDF(new Vector3d(t.getX(), t.getY(), t.getZ()));
        b.setScale(scale);

        b.setLength(bone.getLength());

        builderList.add(b.build());

        for(int i = 0; i < bone.numChildren(); i++) {
            Bone childBone = bone.getChild(i);
            toDDF(builderList, childBone, boneIndex);
        }
    }


}
