package com.dynamo.bob.pipeline;

import static org.apache.commons.io.FilenameUtils.normalize;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderResource;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;

public class ExtenderUtil {

    private static class FSExtenderResource implements ExtenderResource {

        private IResource resource;
        FSExtenderResource(IResource resource) {
            this.resource = resource;
        }

        @Override
        public byte[] sha1() throws IOException {
            return resource.sha1();
        }

        @Override
        public String getAbsPath() {
            return resource.getAbsPath();
        }

        @Override
        public String getPath() {
            return resource.getPath();
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
            return resource.getPath();
        }

    }

    private static List<ExtenderResource> listFilesRecursive(Project project, String path) {
        List<ExtenderResource> resources = new ArrayList<ExtenderResource>();
        ArrayList<String> paths = new ArrayList<>();
        project.findResourcePaths(path, paths);
        for (String p : paths) {
            IResource r = project.getResource(p);
            resources.add(new FSExtenderResource(r));
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
     * Get a list of all extension sources and libraries from a project for a specific platform.
     * @param project
     * @return A list of IExtenderResources that can be supplied to ExtenderClient
     */
    public static List<ExtenderResource> getExtensionSources(Project project, Platform platform) {
        List<ExtenderResource> sources = new ArrayList<>();

        List<String> platformFolderAlternatives = new ArrayList<String>();
        platformFolderAlternatives.addAll(Arrays.asList(platform.getExtenderPaths()));
        platformFolderAlternatives.add("common");

        // Find extension folders
        List<String> extensionFolders = getExtensionFolders(project);
        for (String extension : extensionFolders) {

            sources.add( new FSExtenderResource( project.getResource(extension + "/" + ExtenderClient.extensionFilename)) );
            sources.addAll( listFilesRecursive( project, extension + "/include" ) );
            sources.addAll( listFilesRecursive( project, extension + "/src") );

            // Get "lib" folder; branches of into sub folders such as "common" and platform specifics
            for (String platformAlt : platformFolderAlternatives) {
                sources.addAll( listFilesRecursive( project, extension + "/lib/" + platformAlt) );
            }
        }

        return sources;
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
                String bundleRelativePath = pathProjectAbsolute.substring(path.length());
                resources.put(bundleRelativePath, r);
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

}
