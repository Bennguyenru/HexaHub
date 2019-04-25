package com.dynamo.bob.pipeline;

import static org.apache.commons.io.FilenameUtils.normalize;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderResource;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.util.BobProjectProperties;

public class ExtenderUtil {

    public static final String appManifestPath = "_app/" + ExtenderClient.appManifestFilename;

    private static class FSExtenderResource implements ExtenderResource {

        private IResource resource;
        FSExtenderResource(IResource resource) {
            this.resource = resource;
        }

        public IResource getResource() {
            return resource;
        }

        @Override
        public byte[] sha1() throws IOException {
            return resource.sha1();
        }

        @Override
        public String getAbsPath() {
            return resource.getAbsPath().replace('\\', '/');
        }

        @Override
        public String getPath() {
            return resource.getPath().replace('\\', '/');
        }

        @Override
        public byte[] getContent() throws IOException {
            return resource.getContent();
        }

        @Override
        public long getLastModified() {
            return resource.getLastModified();
        }

        @Override
        public String toString() {
            return resource.getPath().replace('\\', '/');
        }
    }

    public static class JavaRExtenderResource implements ExtenderResource {

        private File javaFile;
        private String path;
        public JavaRExtenderResource(File javaFile, String path) {
            this.javaFile = javaFile;
            this.path = path;
        }

        @Override
        public byte[] sha1() throws IOException {
            byte[] content = getContent();
            if (content == null) {
                throw new IllegalArgumentException(String.format("Resource '%s' is not created", getPath()));
            }
            MessageDigest sha1;
            try {
                sha1 = MessageDigest.getInstance("SHA1");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            sha1.update(content);
            return sha1.digest();
        }

        @Override
        public String getAbsPath() {
            return path;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public byte[] getContent() throws IOException {
            File f = javaFile;
            if (!f.exists())
                return null;

            byte[] buf = new byte[(int) f.length()];
            BufferedInputStream is = new BufferedInputStream(new FileInputStream(f));
            try {
                is.read(buf);
                return buf;
            } finally {
                is.close();
            }
        }

        @Override
        public long getLastModified() {
            return javaFile.lastModified();
        }

        @Override
        public String toString() {
            return getPath();
        }
    }

    private static class EmptyResource implements IResource {
    	private String rootDir;
    	private String path;

    	public EmptyResource(String rootDir, String path) {
            this.rootDir = rootDir;
            this.path = path;
        }

    	@Override
		public IResource changeExt(String ext) {
			return null;
		}

		@Override
		public byte[] getContent() throws IOException {
			return new byte[0];
		}

		@Override
		public void setContent(byte[] content) throws IOException {
		}

		@Override
		public byte[] sha1() throws IOException {
            byte[] content = getContent();
            if (content == null) {
                throw new IllegalArgumentException(String.format("Resource '%s' is not created", getPath()));
            }
            MessageDigest sha1;
            try {
                sha1 = MessageDigest.getInstance("SHA1");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            sha1.update(content);
            return sha1.digest();
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public boolean isFile() {
			return false;
		}

		@Override
		public String getAbsPath() {
            return rootDir + "/" + path;
		}

		@Override
		public String getPath() {
			return path;
		}

		@Override
		public void remove() {
		}

		@Override
		public IResource getResource(String name) {
			return null;
		}

		@Override
		public IResource output() {
			return null;
		}

		@Override
		public boolean isOutput() {
			return false;
		}

		@Override
		public void setContent(InputStream stream) throws IOException {
		}

		@Override
		public long getLastModified() {
	        return new File(rootDir).lastModified();
		}

    }

    // Used to rename a resource in the multipart request and prefix the content with a base variant
    public static class FSAppManifestResource extends FSExtenderResource {

        private IResource resource;
        private String alias;
        private String rootDir;
        private Map<String, String> options;

        FSAppManifestResource(IResource resource, String rootDir, String alias, Map<String, String> options) {
            super(resource);
            this.resource = resource;
            this.rootDir = rootDir;
            this.alias = alias;
            this.options = options;
        }

        public IResource getResource() {
            return resource;
        }

        @Override
        public byte[] sha1() throws IOException {
            byte[] content = getContent();
            if (content == null) {
                throw new IllegalArgumentException(String.format("Resource '%s' is not created", getPath()));
            }
            MessageDigest sha1;
            try {
                sha1 = MessageDigest.getInstance("SHA1");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            sha1.update(content);
            return sha1.digest();
        }

        @Override
        public String getAbsPath() {
            return rootDir + "/" + alias;
        }

        @Override
        public String getPath() {
            return alias;
        }

        @Override
        public byte[] getContent() throws IOException {
            String prefix = "";
            if (options != null) {
                prefix += "context:" + System.getProperty("line.separator");
                for (String key : options.keySet()) {
                    String value = options.get(key);
                    prefix += String.format("    %s: %s", key, value) + System.getProperty("line.separator");
                }
            }

            byte[] prefixBytes = prefix.getBytes();
            byte[] content = resource.getContent();
            byte[] c = new byte[prefixBytes.length + content.length];
            System.arraycopy(prefixBytes, 0, c, 0, prefixBytes.length);
            System.arraycopy(content, 0, c, prefixBytes.length, content.length);
            return c;
        }

        @Override
        public long getLastModified() {
            return resource.getLastModified();
        }

        @Override
        public String toString() {
            return getPath();
        }
    }


    private static List<ExtenderResource> listFilesRecursive(Project project, String path) {
        List<ExtenderResource> resources = new ArrayList<ExtenderResource>();
        ArrayList<String> paths = new ArrayList<>();
        project.findResourcePaths(path, paths);
        for (String p : paths) {
            IResource r = project.getResource(p);
            // Note: findResourcePaths will return the supplied path even if it's not a file.
            // We need to check if the resource is not a directory before adding it to the list of paths found.
            if (r.isFile()) {
                resources.add(new FSExtenderResource(r));
            }
        }

        return resources;
    }

    private static List<String> trimExcludePaths(List<String> excludes) {
        List<String> trimmedExcludes = new ArrayList<String>();
        for (String path : excludes) {
            trimmedExcludes.add(path.trim());
        }
        return trimmedExcludes;
    }

    private static void mergeBundleMap(Map<String, IResource> into, Map<String, IResource> from) throws CompileExceptionError{

        Iterator<Map.Entry<String, IResource>> it = from.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, IResource> entry = (Map.Entry<String, IResource>)it.next();
            String outputPath = entry.getKey();
            if (into.containsKey(outputPath)) {
                IResource inputA = into.get(outputPath);
                IResource inputB = entry.getValue();

                String errMsg = "Conflicting output bundle resource '" + outputPath + "‘ generated by the following input files: " + inputA.toString() + " <-> " + inputB.toString();
                throw new CompileExceptionError(inputB, 0, errMsg);
            } else {
                into.put(outputPath, entry.getValue());
            }
        }
    }

    /**
     * Get a list of paths to extension directories in the project.
     * @param project
     * @return A list of paths to extension directories
     */
    public static List<String> getExtensionFolders(Project project) {
        ArrayList<String> paths = new ArrayList<>();
        project.findResourcePaths("", paths);

        List<String> folders = new ArrayList<>();
        for (String p : paths) {
            File f = new File(p);
            if (f.getName().equals(ExtenderClient.extensionFilename)) {
                folders.add( f.getParent() );
            }
        }
        return folders;
    }

    /**
     * Returns true if the project should build remotely
     * @param project
     * @return True if it contains native extension code
     */
    public static boolean hasNativeExtensions(Project project) {
        BobProjectProperties projectProperties = project.getProjectProperties();
        String appManifest = projectProperties.getStringValue("native_extension", "app_manifest", "");
        if (!appManifest.isEmpty()) {
            IResource resource = project.getResource(appManifest);
            if (resource.exists()) {
                return true;
            }
        }

        ArrayList<String> paths = new ArrayList<>();
        project.findResourcePaths("", paths);
        for (String p : paths) {
            File f = new File(p);
            if (f.getName().equals(ExtenderClient.extensionFilename)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get a list of all extension sources and libraries from a project for a specific platform.
     * @param project
     * @return A list of IExtenderResources that can be supplied to ExtenderClient
     */
    public static List<ExtenderResource> getExtensionSources(Project project, Platform platform, Map<String, String> appmanifestOptions) throws CompileExceptionError {
        List<ExtenderResource> sources = new ArrayList<>();

        List<String> platformFolderAlternatives = new ArrayList<String>();
        platformFolderAlternatives.addAll(Arrays.asList(platform.getExtenderPaths()));
        platformFolderAlternatives.add("common");

        // Find app manifest if there is one
        BobProjectProperties projectProperties = project.getProjectProperties();
        String appManifest = projectProperties.getStringValue("native_extension", "app_manifest", "");
        if (!appManifest.isEmpty()) {
            IResource resource = project.getResource(appManifest);
            if (resource.exists()) {
                // We use an alias so the the app manifest has a predefined name
                sources.add( new FSAppManifestResource( resource, project.getRootDirectory(), appManifestPath, appmanifestOptions ) );
            } else {
                IResource projectResource = project.getResource("game.project");
                throw new CompileExceptionError(projectResource, 0, String.format("No such resource: %s", resource.getAbsPath()));
            }
        }
        else if (appmanifestOptions != null)
        {
            // Make up appmanifest
        	IResource resource = new EmptyResource(project.getRootDirectory(), appManifestPath);
        	sources.add( new FSAppManifestResource( resource, project.getRootDirectory(), appManifestPath, appmanifestOptions ) );
        }

        // Find extension folders
        List<String> extensionFolders = getExtensionFolders(project);
        for (String extension : extensionFolders) {
            IResource resource = project.getResource(extension + "/" + ExtenderClient.extensionFilename);
            if (!resource.exists()) {
                throw new CompileExceptionError(resource, 1, "Resource doesn't exist!");
            }

            sources.add( new FSExtenderResource( resource ) );
            sources.addAll( listFilesRecursive( project, extension + "/include/" ) );
            sources.addAll( listFilesRecursive( project, extension + "/src/") );

            // Get "lib" folder; branches of into sub folders such as "common" and platform specifics
            for (String platformAlt : platformFolderAlternatives) {
                sources.addAll( listFilesRecursive( project, extension + "/lib/" + platformAlt + "/") );
            }
        }

        return sources;
    }

    /** Makes sure the project doesn't have duplicates wrt relative paths.
     * This is important since we use both files on disc and in memory. (DEF-3868, )
     * @return Doesn't return anything. It throws CompileExceptionError if the check fails.
     */
    public static void checkProjectForDuplicates(Project project) throws CompileExceptionError {
        Map<String, IResource> files = new HashMap<String, IResource>();

        ArrayList<String> paths = new ArrayList<>();
        project.findResourcePaths("", paths);
        for (String p : paths) {
            IResource r = project.getResource(p);
            if (!r.isFile()) // Skip directories
                continue;

            if (files.containsKey(r.getPath())) {
                IResource previous = files.get(r.getPath());
                throw new CompileExceptionError(r, 0, String.format("The files' relative path conflict:\n'%s' and\n'%s", r.getAbsPath(), r.getAbsPath()));
            }
            files.put(r.getPath(), r);
        }
    }

    /** Get the platform manifests from the extensions
     */
    public static List<IResource> getExtensionManifests(Project project, Platform platform, String name) throws CompileExceptionError {
        List<IResource> out = new ArrayList<>();

        List<String> platformFolderAlternatives = new ArrayList<String>();
        platformFolderAlternatives.addAll(Arrays.asList(platform.getExtenderPaths())); // we skip "common" here since it makes little sense

        // Find extension folders
        List<String> extensionFolders = getExtensionFolders(project);
        for (String extension : extensionFolders) {
            for (String platformAlt : platformFolderAlternatives) {
                List<ExtenderResource> files = listFilesRecursive( project, extension + "/manifests/" + platformAlt + "/");
                for (ExtenderResource r : files) {
                    if (!(r instanceof FSExtenderResource))
                        continue;
                    File f = new File(r.getAbsPath());
                    if (f.getName().equals(name)) {
                        out.add( ((FSExtenderResource)r).getResource() );
                    }
                }
            }
        }
        return out;
    }

    /**
     * Collect bundle resources from a specific project path and a list of exclude paths.
     * @param project
     * @param platform String representing the target platform.
     * @param excludes A list of project relative paths for resources to exclude.
     * @return Returns a map with output paths as keys and the corresponding IResource that should be used as value.
     * @throws CompileExceptionError if a output conflict occurs.
     */
    public static Map<String, IResource> collectResources(Project project, String path, List<String> excludes) throws CompileExceptionError {
        if (excludes == null) {
            excludes = new ArrayList<>();
        }

        HashMap<String, IResource> resources = new HashMap<String, IResource>();
        ArrayList<String> paths = new ArrayList<>();
        project.findResourcePaths(path.substring(1), paths);
        for (String p : paths) {
            String pathProjectAbsolute = "/" + p;
            if (!excludes.contains(pathProjectAbsolute)) {
                IResource r = project.getResource(p);
                // Note: findResourcePaths will return the supplied path even if it's not a file.
                // We need to check if the resource is not a directory before adding it to the list of paths found.
                if (r.isFile()) {
                    String bundleRelativePath = pathProjectAbsolute.substring(path.length());
                    resources.put(bundleRelativePath, r);
                }
            }
        }

        return resources;
    }

    /**
     * Collect bundle resources based on a Project and a target platform string used to collect correct platform specific resources.
     * @param project
     * @param platform String representing the target platform.
     * @return Returns a map with output paths as keys and the corresponding IResource that should be used as value.
     * @throws CompileExceptionError if a output conflict occurs.
     */
    public static Map<String, IResource> collectResources(Project project, Platform platform) throws CompileExceptionError {

        Map<String, IResource> bundleResources = new HashMap<String, IResource>();
        List<String> bundleExcludeList = trimExcludePaths(Arrays.asList(project.getProjectProperties().getStringValue("project", "bundle_exclude_resources", "").split(",")));
        List<String> platformFolderAlternatives = new ArrayList<String>();
        platformFolderAlternatives.addAll(Arrays.asList(platform.getExtenderPaths()));
        platformFolderAlternatives.add("common");

        // Project specific bundle resources
        String bundleResourcesPath = project.getProjectProperties().getStringValue("project", "bundle_resources", "").trim();
        if (bundleResourcesPath.length() > 0) {
            for (String platformAlt : platformFolderAlternatives) {
                Map<String, IResource> projectBundleResources = ExtenderUtil.collectResources(project, FilenameUtils.concat(bundleResourcesPath, platformAlt + "/"), bundleExcludeList);
                mergeBundleMap(bundleResources, projectBundleResources);
            }
        }

        // Get bundle resources from extensions
        List<String> extensionFolders = getExtensionFolders(project);
        for (String extension : extensionFolders) {
            for (String platformAlt : platformFolderAlternatives) {
                Map<String, IResource> extensionBundleResources = ExtenderUtil.collectResources(project, FilenameUtils.concat("/" + extension, "res/" + platformAlt + "/"), bundleExcludeList);
                mergeBundleMap(bundleResources, extensionBundleResources);
            }
        }

        return bundleResources;
    }

    /** Gets a list of all android specific folders (/res) from all project and extension resource folders
     * E.g. "res/android/res" but not "res/android/foo"
     */
    public static List<String> getAndroidResourcePaths(Project project, Platform platform) throws CompileExceptionError {

        List<String> platformFolderAlternatives = new ArrayList<String>();
        platformFolderAlternatives.addAll(Arrays.asList(platform.getExtenderPaths()));

        List<String> out = new ArrayList<String>();
        String rootDir = project.getRootDirectory();

        // Project specific bundle resources
        String bundleResourcesPath = rootDir + "/" + project.getProjectProperties().getStringValue("project", "bundle_resources", "").trim();
        if (bundleResourcesPath.length() > 0) {
            for (String platformAlt : platformFolderAlternatives) {
                File dir = new File(FilenameUtils.concat(bundleResourcesPath, platformAlt + "/res"));
                if (dir.exists() && dir.isDirectory() )
                {
                    out.add(dir.getAbsolutePath());
                }
            }
        }

        // Get bundle resources from extensions
        List<String> extensionFolders = getExtensionFolders(project);
        for (String extension : extensionFolders) {
            for (String platformAlt : platformFolderAlternatives) {
                File dir = new File(FilenameUtils.concat(rootDir +"/" + extension, "res/" + platformAlt + "/res"));
                if (dir.exists() && dir.isDirectory() )
                {
                    out.add(dir.getAbsolutePath());
                }
            }
        }

        return out;
    }

    // Collects all resources (even those inside the zip packages) and stores them into one single folder
    public static void storeAndroidResources(File targetDirectory, Map<String, IResource> resources) throws IOException, CompileExceptionError {
        for (String relativePath : resources.keySet()) {
            IResource r = resources.get(relativePath);
            File outputFile = new File(targetDirectory, relativePath);
            if (!outputFile.getParentFile().exists()) {
                outputFile.getParentFile().mkdirs();
            } else if (outputFile.exists()) {
                throw new CompileExceptionError(r, 0, "The resource already exists in another extension: " + relativePath);
            }
            byte[] data = r.getContent();
            FileUtils.writeByteArrayToFile(outputFile, data);
        }
    }

    public static Map<String, IResource> getAndroidResources(Project project) throws CompileExceptionError {

        Map<String, IResource> androidResources = new HashMap<String, IResource>();
        List<String> bundleExcludeList = trimExcludePaths(Arrays.asList(project.getProjectProperties().getStringValue("project", "bundle_exclude_resources", "").split(",")));
        List<String> platformFolderAlternatives = new ArrayList<String>();

        List<String> armv7ExtenderPaths = new ArrayList<String>(Arrays.asList(Platform.Armv7Android.getExtenderPaths()));
        List<String> arm64ExtenderPaths = new ArrayList<String>(Arrays.asList(Platform.Arm64Android.getExtenderPaths()));
        Set<String> set = new LinkedHashSet<>(armv7ExtenderPaths);
        set.addAll(arm64ExtenderPaths);
        platformFolderAlternatives = new ArrayList<>(set);

        // Project specific bundle resources
        String bundleResourcesPath = project.getProjectProperties().getStringValue("project", "bundle_resources", "").trim();
        if (bundleResourcesPath.length() > 0) {
            for (String platformAlt : platformFolderAlternatives) {
                Map<String, IResource> projectBundleResources = ExtenderUtil.collectResources(project, FilenameUtils.concat(bundleResourcesPath, platformAlt + "/res/"), bundleExcludeList);
                mergeBundleMap(androidResources, projectBundleResources);
            }
        }

        // Get bundle resources from extensions
        List<String> extensionFolders = getExtensionFolders(project);
        for (String extension : extensionFolders) {
            for (String platformAlt : platformFolderAlternatives) {
                Map<String, IResource> extensionBundleResources = ExtenderUtil.collectResources(project, FilenameUtils.concat("/" + extension, "res/" + platformAlt + "/res/"), bundleExcludeList);
                mergeBundleMap(androidResources, extensionBundleResources);
            }
        }

        return androidResources;
    }

    /**
     * Collect bundle resources based on a Project, will automatically retrieve the target platform to collect correct platform specific resources.
     * @param project
     * @return Returns a map with output paths as keys and the corresponding IResource that should be used as value.
     * @throws CompileExceptionError if a output conflict occurs.
     */
    public static Map<String, IResource> collectResources(Project project) throws CompileExceptionError {
        return collectResources(project, Platform.getHostPlatform());
    }

    /**
     * Write a map of bundle resources to a specific disk directory.
     * @param resources Map of resources to write to disk.
     * @param directory File object pointing to a output directory.
     * @throws IOException
     */
    public static void writeResourcesToDirectory(Map<String, IResource> resources, File directory) throws IOException {
        Iterator<Map.Entry<String, IResource>> it = resources.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, IResource> entry = (Map.Entry<String, IResource>)it.next();
            File outputFile = new File(directory, entry.getKey());
            outputFile.getParentFile().mkdirs();
            FileUtils.writeByteArrayToFile(outputFile, entry.getValue().getContent());
        }
    }

    /**
     * Write a map of bundle resources to a Zip output stream.
     * @param resources Map of resources to write to disk.
     * @param zipOutputStream A ZipOutputStream where bundle resources should be written as Zip entries.
     * @throws IOException
     */
    public static void writeResourcesToZip(Map<String, IResource> resources, ZipOutputStream zipOutputStream) throws IOException {
        Iterator<Map.Entry<String, IResource>> it = resources.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, IResource> entry = (Map.Entry<String, IResource>)it.next();
            ZipEntry ze = new ZipEntry(normalize(entry.getKey(), true));
            zipOutputStream.putNextEntry(ze);
            zipOutputStream.write(entry.getValue().getContent());
        }
    }

    /** Finds a resource given a relative path
     * @param path  The relative path to the resource
     * @param source A list of all source files
     * @return The resource, or null if not found
     */
    public static IResource getResource(String path, List<ExtenderResource> source) {
        for (ExtenderResource r : source) {
            if (r.getPath().equals(path)) {
                if (r instanceof ExtenderUtil.FSExtenderResource) {
                    ExtenderUtil.FSExtenderResource fsr = (ExtenderUtil.FSExtenderResource)r;
                    return fsr.getResource();
                } else {
                    // It was a generated file (e.g. R.java) which doesn't exist in the project
                    break;
                }
            }
        }
        return null;
    }
}
