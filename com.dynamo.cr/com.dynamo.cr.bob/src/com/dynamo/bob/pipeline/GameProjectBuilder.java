package com.dynamo.bob.pipeline;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import com.dynamo.bob.Builder;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.CopyCustomResourcesBuilder;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.Task;
import com.dynamo.bob.Task.TaskBuilder;
import com.dynamo.bob.archive.ArchiveBuilder;
import com.dynamo.bob.archive.EngineVersion;
import com.dynamo.bob.archive.ManifestBuilder;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.util.BobProjectProperties;
import com.dynamo.camera.proto.Camera.CameraDesc;
import com.dynamo.gameobject.proto.GameObject.CollectionDesc;
import com.dynamo.gameobject.proto.GameObject.PrototypeDesc;
import com.dynamo.gamesystem.proto.GameSystem.CollectionFactoryDesc;
import com.dynamo.gamesystem.proto.GameSystem.CollectionProxyDesc;
import com.dynamo.gamesystem.proto.GameSystem.FactoryDesc;
import com.dynamo.gamesystem.proto.GameSystem.LightDesc;
import com.dynamo.graphics.proto.Graphics.Cubemap;
import com.dynamo.graphics.proto.Graphics.PlatformProfile;
import com.dynamo.graphics.proto.Graphics.TextureProfile;
import com.dynamo.graphics.proto.Graphics.TextureProfiles;
import com.dynamo.gui.proto.Gui;
import com.dynamo.input.proto.Input.GamepadMaps;
import com.dynamo.input.proto.Input.InputBinding;
import com.dynamo.label.proto.Label.LabelDesc;
import com.dynamo.liveupdate.proto.Manifest.HashAlgorithm;
import com.dynamo.liveupdate.proto.Manifest.SignAlgorithm;
import com.dynamo.lua.proto.Lua.LuaModule;
import com.dynamo.model.proto.Model.ModelDesc;
import com.dynamo.particle.proto.Particle.ParticleFX;
import com.dynamo.physics.proto.Physics.CollisionObjectDesc;
import com.dynamo.proto.DdfExtensions;
import com.dynamo.render.proto.Font.FontMap;
import com.dynamo.render.proto.Material.MaterialDesc;
import com.dynamo.render.proto.Render.DisplayProfiles;
import com.dynamo.render.proto.Render.RenderPrototypeDesc;
import com.dynamo.rig.proto.Rig.MeshSet;
import com.dynamo.rig.proto.Rig.RigScene;
import com.dynamo.rig.proto.Rig.Skeleton;
import com.dynamo.sound.proto.Sound.SoundDesc;
import com.dynamo.spine.proto.Spine.SpineModelDesc;
import com.dynamo.sprite.proto.Sprite.SpriteDesc;
import com.dynamo.textureset.proto.TextureSetProto.TextureSet;
import com.dynamo.tile.proto.Tile.TileGrid;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.Message;

/**
 * Game project and disk archive builder.
 * @author chmu
 *
 */
@BuilderParams(name = "GameProjectBuilder", inExts = ".project", outExt = "", createOrder = 1000)
public class GameProjectBuilder extends Builder<Void> {

    private static Map<String, Class<? extends GeneratedMessage>> extToMessageClass = new HashMap<String, Class<? extends GeneratedMessage>>();
    private static Set<String> leafResourceTypes = new HashSet<String>();

    static {
        extToMessageClass.put(".collectionc", CollectionDesc.class);
        extToMessageClass.put(".collectionproxyc", CollectionProxyDesc.class);
        extToMessageClass.put(".goc", PrototypeDesc.class);
        extToMessageClass.put(".texturesetc", TextureSet.class);
        extToMessageClass.put(".guic", Gui.SceneDesc.class);
        extToMessageClass.put(".scriptc", LuaModule.class);
        extToMessageClass.put(".gui_scriptc", LuaModule.class);
        extToMessageClass.put(".render_scriptc", LuaModule.class);
        extToMessageClass.put(".luac", LuaModule.class);
        extToMessageClass.put(".tilegridc", TileGrid.class);
        extToMessageClass.put(".collisionobjectc", CollisionObjectDesc.class);
        extToMessageClass.put(".spritec", SpriteDesc.class);
        extToMessageClass.put(".factoryc", FactoryDesc.class);
        extToMessageClass.put(".collectionfactoryc", CollectionFactoryDesc.class);
        extToMessageClass.put(".materialc", MaterialDesc.class);
        extToMessageClass.put(".fontc", FontMap.class);
        extToMessageClass.put(".soundc", SoundDesc.class);
        extToMessageClass.put(".labelc", LabelDesc.class);
        extToMessageClass.put(".modelc", ModelDesc.class);
        extToMessageClass.put(".input_bindingc", InputBinding.class);
        extToMessageClass.put(".gamepadsc", GamepadMaps.class);
        extToMessageClass.put(".renderc", RenderPrototypeDesc.class);
        extToMessageClass.put(".particlefxc", ParticleFX.class);
        extToMessageClass.put(".spinemodelc", SpineModelDesc.class);
        extToMessageClass.put(".rigscenec", RigScene.class);
        extToMessageClass.put(".skeletonc", Skeleton.class);
        extToMessageClass.put(".meshsetc", MeshSet.class);
        extToMessageClass.put(".animationsetc", MeshSet.class);
        extToMessageClass.put(".cubemapc", Cubemap.class);
        extToMessageClass.put(".camerac", CameraDesc.class);
        extToMessageClass.put(".lightc", LightDesc.class);
        extToMessageClass.put(".gamepadsc", GamepadMaps.class);
        extToMessageClass.put(".display_profilesc", DisplayProfiles.class);

        leafResourceTypes.add(".texturec");
        leafResourceTypes.add(".vpc");
        leafResourceTypes.add(".fpc");
        leafResourceTypes.add(".wavc");
        leafResourceTypes.add(".oggc");
        leafResourceTypes.add(".meshc");
    }

    private RandomAccessFile createRandomAccessFile(File handle) throws IOException {
        handle.deleteOnExit();
        RandomAccessFile file = new RandomAccessFile(handle, "rw");
        file.setLength(0);
        return file;
    }

    @Override
    public Task<Void> create(IResource input) throws IOException, CompileExceptionError {
        TaskBuilder<Void> builder = Task.<Void> newBuilder(this)
                .setName(params.name())
                .addInput(input)
                .addOutput(input.changeExt(".projectc"));
        if (project.option("archive", "false").equals("true")) {
            builder.addOutput(input.changeExt(".arci"));
            builder.addOutput(input.changeExt(".arcd"));
            builder.addOutput(input.changeExt(".dmanifest"));
            builder.addOutput(input.changeExt(".resourcepack.zip"));
            builder.addOutput(input.changeExt(".public.der"));
        }

        project.buildResource(input, CopyCustomResourcesBuilder.class);


        // Load texture profile message if supplied and enabled
        String textureProfilesPath = project.getProjectProperties().getStringValue("graphics", "texture_profiles");
        if (textureProfilesPath != null && project.option("texture-profiles", "false").equals("true")) {

            TextureProfiles.Builder texProfilesBuilder = TextureProfiles.newBuilder();
            IResource texProfilesInput = project.getResource(textureProfilesPath);
            if (!texProfilesInput.exists()) {
                throw new CompileExceptionError(input, -1, "Could not find supplied texture_profiles file: " + textureProfilesPath);
            }
            ProtoUtil.merge(texProfilesInput, texProfilesBuilder);

            // If Bob is building for a specific platform, we need to
            // filter out any platform entries not relevant to the target platform.
            // (i.e. we don't want win32 specific profiles lingering in android bundles)
            String targetPlatform = project.option("platform", "");

            List<TextureProfile> newProfiles = new LinkedList<TextureProfile>();
            for (int i = 0; i < texProfilesBuilder.getProfilesCount(); i++) {

                TextureProfile profile = texProfilesBuilder.getProfiles(i);
                TextureProfile.Builder profileBuilder = TextureProfile.newBuilder();
                profileBuilder.mergeFrom(profile);
                profileBuilder.clearPlatforms();

                // Take only the platforms that matches the target platform
                for (PlatformProfile platformProfile : profile.getPlatformsList()) {
                    if (Platform.matchPlatformAgainstOS(targetPlatform, platformProfile.getOs())) {
                        profileBuilder.addPlatforms(platformProfile);
                    }
                }

                newProfiles.add(profileBuilder.build());
            }

            // Update profiles list with new filtered one
            // Now it should only contain profiles with platform entries
            // relevant for the target platform...
            texProfilesBuilder.clearProfiles();
            texProfilesBuilder.addAllProfiles(newProfiles);


            // Add the current texture profiles to the project, since this
            // needs to be reachedable by the TextureGenerator.
            TextureProfiles textureProfiles = texProfilesBuilder.build();
            project.setTextureProfiles(textureProfiles);
        }

        for (Task<?> task : project.getTasks()) {
            for (IResource output : task.getOutputs()) {
                builder.addInput(output);
            }
        }

        return builder.build();
    }

    private void createArchive(Collection<String> resources, RandomAccessFile archiveIndex, RandomAccessFile archiveData, ManifestBuilder manifestBuilder, ZipOutputStream zipOutputStream, List<String> excludedResources) throws IOException {
        String root = FilenameUtils.concat(project.getRootDirectory(), project.getBuildDirectory());
        ArchiveBuilder archiveBuilder = new ArchiveBuilder(root, manifestBuilder);
        boolean doCompress = project.getProjectProperties().getBooleanValue("project", "compress_archive", true);
        HashMap<String, EnumSet<Project.OutputFlags>> outputs = project.getOutputs();

        for (String s : resources) {
            EnumSet<Project.OutputFlags> flags = outputs.get(s);
            boolean compress = (flags != null && flags.contains(Project.OutputFlags.UNCOMPRESSED)) ? false : doCompress;
            archiveBuilder.add(s, compress);
        }

        Path resourcePackDirectory = Files.createTempDirectory("defold.resourcepack_");
        archiveBuilder.write(archiveIndex, archiveData, resourcePackDirectory, excludedResources);
        archiveIndex.close();
        archiveData.close();

        // Populate the zip archive with the resource pack
        for (File filepath : (new File(resourcePackDirectory.toAbsolutePath().toString())).listFiles()) {
            if (filepath.isFile()) {
                ZipEntry currentEntry = new ZipEntry(filepath.getName());
                zipOutputStream.putNextEntry(currentEntry);

                FileInputStream currentInputStream = new FileInputStream(filepath);
                int currentLength = 0;
                byte[] currentBuffer = new byte[1024];
                while ((currentLength = currentInputStream.read(currentBuffer)) > 0) {
                    zipOutputStream.write(currentBuffer, 0, currentLength);
                }

                currentInputStream.close();
                zipOutputStream.closeEntry();
            }
        }
        zipOutputStream.close();

        File resourcePackDirectoryHandle = new File(resourcePackDirectory.toAbsolutePath().toString());
        if (resourcePackDirectoryHandle.exists() && resourcePackDirectoryHandle.isDirectory()) {
            FileUtils.deleteDirectory(resourcePackDirectoryHandle);
        }
    }

    private static void findResources(Project project, Message node, Collection<String> resources, ResourceNode parentNode) throws CompileExceptionError {
        List<FieldDescriptor> fields = node.getDescriptorForType().getFields();

        for (FieldDescriptor fieldDescriptor : fields) {
            FieldOptions options = fieldDescriptor.getOptions();
            FieldDescriptor resourceDesc = DdfExtensions.resource.getDescriptor();
            boolean isResource = (Boolean) options.getField(resourceDesc);
            Object value = node.getField(fieldDescriptor);
            if (value instanceof Message) {
                findResources(project, (Message) value, resources, parentNode);

            } else if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) value;
                for (Object v : list) {
                    if (v instanceof Message) {
                        findResources(project, (Message) v, resources, parentNode);
                    } else if (isResource && v instanceof String) {
                        findResources(project, project.getResource((String) v), resources, parentNode);
                    }
                }
            } else if (isResource && value instanceof String) {
                findResources(project, project.getResource((String) value), resources, parentNode);
            }
        }
    }

    private static void findResources(Project project, IResource resource, Collection<String> resources, ResourceNode parentNode) throws CompileExceptionError {
        if (resource.getPath().equals("")) {
            return;
        }

        if (resources.contains(resource.output().getAbsPath())) {
            return;
        }

        resources.add(resource.output().getAbsPath());

        ResourceNode currentNode = new ResourceNode(resource.getPath(), resource.output().getAbsPath());
        parentNode.addChild(currentNode);

        int i = resource.getPath().lastIndexOf(".");
        if (i == -1) {
            return;
        }
        String ext = resource.getPath().substring(i);

        if (leafResourceTypes.contains(ext)) {
            return;
        }

        Class<? extends GeneratedMessage> klass = extToMessageClass.get(ext);
        if (klass != null) {
            GeneratedMessage.Builder<?> builder;
            try {
                Method newBuilder = klass.getDeclaredMethod("newBuilder");
                builder = (GeneratedMessage.Builder<?>) newBuilder.invoke(null);
                final byte[] content = resource.output().getContent();
                if(content == null) {
                    throw new CompileExceptionError(resource, 0, "Unable to find resource " + resource.getPath());
                }
                builder.mergeFrom(content);
                Object message = builder.build();
                findResources(project, (Message) message, resources, currentNode);

            } catch(CompileExceptionError e) {
                throw e;
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new CompileExceptionError(resource, -1, "No mapping for " + ext);
        }
    }


    public static HashSet<String> findResources(Project project, ResourceNode rootNode) throws CompileExceptionError {
        HashSet<String> resources = new HashSet<String>();

        if (project.option("keep-unused", "false").equals("true")) {

            // All outputs of the project should be considered resources
            for (String path : project.getOutputs().keySet()) {
                resources.add(path);
            }

        } else {

            // Root nodes to follow
            for (String[] pair : new String[][] { {"bootstrap", "main_collection"}, {"bootstrap", "render"}, {"input", "game_binding"}, {"input", "gamepads"}, {"display", "display_profiles"}}) {
                String path = project.getProjectProperties().getStringValue(pair[0], pair[1]);
                if (path != null) {
                    findResources(project, project.getResource(path), resources, rootNode);
                }
            }

        }

        // Custom resources
        String[] custom_resources = project.getProjectProperties().getStringValue("project", "custom_resources", "").split(",");
        for (String s : custom_resources) {
            s = s.trim();
            if (s.length() > 0) {
                ArrayList<String> paths = new ArrayList<String>();
                project.findResourcePaths(s, paths);
                for (String path : paths) {
                    IResource r = project.getResource(path);
                    resources.add(r.output().getAbsPath());
                }
            }
        }

        return resources;
    }

    private ManifestBuilder prepareManifestBuilder(ResourceNode rootNode, List<String> excludedResourcesList) throws IOException {
        String projectIdentifier = project.getProjectProperties().getStringValue("project", "title", "<anonymous>");
        String supportedEngineVersionsString = project.getProjectProperties().getStringValue("liveupdate", "supported_versions", null);
        String privateKeyFilepath = project.getProjectProperties().getStringValue("liveupdate", "privatekey", null);
        String publicKeyFilepath = project.getProjectProperties().getStringValue("liveupdate", "publickey", null);
        String excludedResourcesString = project.getProjectProperties().getStringValue("liveupdate", "exclude", null);

        ManifestBuilder manifestBuilder = new ManifestBuilder();
        manifestBuilder.setDependencies(rootNode);
        manifestBuilder.setResourceHashAlgorithm(HashAlgorithm.HASH_SHA1);
        manifestBuilder.setSignatureHashAlgorithm(HashAlgorithm.HASH_SHA1);
        manifestBuilder.setSignatureSignAlgorithm(SignAlgorithm.SIGN_RSA);
        manifestBuilder.setProjectIdentifier(projectIdentifier);

        if (privateKeyFilepath == null || publicKeyFilepath == null) {
            File privateKeyFileHandle = File.createTempFile("defold.private_", ".der");
            privateKeyFileHandle.deleteOnExit();

            File publicKeyFileHandle = File.createTempFile("defold.public_", ".der");
            publicKeyFileHandle.deleteOnExit();

            privateKeyFilepath = privateKeyFileHandle.getAbsolutePath();
            publicKeyFilepath = publicKeyFileHandle.getAbsolutePath();
            try {
                ManifestBuilder.CryptographicOperations.generateKeyPair(SignAlgorithm.SIGN_RSA, privateKeyFilepath, publicKeyFilepath);
            } catch (NoSuchAlgorithmException exception) {
                throw new IOException("Unable to create manifest, cannot create asymmetric keypair!");
            }

        }
        manifestBuilder.setPrivateKeyFilepath(privateKeyFilepath);
        manifestBuilder.setPublicKeyFilepath(publicKeyFilepath);

        manifestBuilder.addSupportedEngineVersion(EngineVersion.sha1);
        if (supportedEngineVersionsString != null) {
            String[] supportedEngineVersions = supportedEngineVersionsString.split("\\s*,\\s*");
            for (String supportedEngineVersion : supportedEngineVersions) {
                manifestBuilder.addSupportedEngineVersion(supportedEngineVersion.trim());
            }
        }

        if (excludedResourcesString != null) {
            String[] excludedResources = excludedResourcesString.split("\\s*,\\s*");
            for (String excludedResource : excludedResources) {
                excludedResourcesList.add(excludedResource);
            }
        }

        return manifestBuilder;
    }

    @Override
    public void build(Task<Void> task) throws CompileExceptionError, IOException {
        FileInputStream archiveIndexInputStream = null;
        FileInputStream archiveDataInputStream = null;
        FileInputStream resourcePackInputStream = null;
        FileInputStream publicKeyInputStream = null;

        BobProjectProperties properties = new BobProjectProperties();
        IResource input = task.input(0);
        try {
            properties.load(new ByteArrayInputStream(input.getContent()));
        } catch (Exception e) {
            throw new CompileExceptionError(input, -1, "Failed to parse game.project", e);
        }

        try {
            if (project.option("archive", "false").equals("true")) {
                ResourceNode rootNode = new ResourceNode("<AnonymousRoot>", "<AnonymousRoot>");
                HashSet<String> resources = findResources(project, rootNode);
                List<String> excludedResources = new ArrayList<String>();
                ManifestBuilder manifestBuilder = this.prepareManifestBuilder(rootNode, excludedResources);

                // Make sure we don't try to archive the .darc, .projectc, .dmanifest, .resourcepack.zip, .public.der
                for (IResource resource : task.getOutputs()) {
                    resources.remove(resource.getAbsPath());
                }

                // Create zip archive to store resource pack
                File resourcePackZip = File.createTempFile("defold.resourcepack_", ".zip");
                resourcePackZip.deleteOnExit();
                FileOutputStream resourcePackOutputStream = new FileOutputStream(resourcePackZip);
                ZipOutputStream zipOutputStream = new ZipOutputStream(resourcePackOutputStream);

                // Create output for the data archive
                File archiveIndexHandle = File.createTempFile("defold.index_", ".arci");
                RandomAccessFile archiveIndex = createRandomAccessFile(archiveIndexHandle);
                File archiveDataHandle = File.createTempFile("defold.data_", ".arcd");
                RandomAccessFile archiveData = createRandomAccessFile(archiveDataHandle);
                createArchive(resources, archiveIndex, archiveData, manifestBuilder, zipOutputStream, excludedResources);

                // Create manifest
                byte[] manifestFile = manifestBuilder.buildManifest();

                // Write outputs to the build system
                archiveIndexInputStream = new FileInputStream(archiveIndexHandle);
                task.getOutputs().get(1).setContent(archiveIndexInputStream);
                
                archiveDataInputStream = new FileInputStream(archiveDataHandle);
                task.getOutputs().get(2).setContent(archiveDataInputStream);
                
                task.getOutputs().get(3).setContent(manifestFile);

                resourcePackInputStream = new FileInputStream(resourcePackZip);
                task.getOutputs().get(4).setContent(resourcePackInputStream);
                
                publicKeyInputStream = new FileInputStream(manifestBuilder.getPublicKeyFilepath());
                task.getOutputs().get(5).setContent(publicKeyInputStream);
            }

            task.getOutputs().get(0).setContent(task.getInputs().get(0).getContent());
        } finally {
            IOUtils.closeQuietly(archiveIndexInputStream);
            IOUtils.closeQuietly(archiveDataInputStream);
            IOUtils.closeQuietly(resourcePackInputStream);
            IOUtils.closeQuietly(publicKeyInputStream);
        }
    }
}
