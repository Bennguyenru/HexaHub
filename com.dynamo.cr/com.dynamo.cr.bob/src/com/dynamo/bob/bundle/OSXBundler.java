package com.dynamo.bob.bundle;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.IExtenderResource;
import com.dynamo.bob.Bob;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.pipeline.BundleResourceUtil;
import com.dynamo.bob.util.BobProjectProperties;

public class OSXBundler implements IBundler {
    public static final String ICON_NAME = "icon.icns";

    private void copyIcon(BobProjectProperties projectProperties, File projectRoot, File resourcesDir) throws IOException {
        String name = projectProperties.getStringValue("osx", "app_icon");
        if (name != null) {
            File inFile = new File(projectRoot, name);
            File outFile = new File(resourcesDir, ICON_NAME);
            FileUtils.copyFile(inFile, outFile);
        }
    }

    @Override
    public void bundleApplication(Project project, File bundleDir)
            throws IOException, CompileExceptionError {

        // Collect bundle/package resources to be included in .App directory
        Map<String, IResource> bundleResources = BundleResourceUtil.collectResources(project, Platform.X86Darwin);

        BobProjectProperties projectProperties = project.getProjectProperties();
        String title = projectProperties.getStringValue("project", "title", "Unnamed");

        File buildDir = new File(project.getRootDirectory(), project.getBuildDirectory());
        File appDir = new File(bundleDir, title + ".app");
        File contentsDir = new File(appDir, "Contents");
        File resourcesDir = new File(contentsDir, "Resources");
        File macosDir = new File(contentsDir, "MacOS");

        boolean debug = project.hasOption("debug");

        boolean nativeExtEnabled = project.hasOption("native-ext");
        List<String> extensionPaths = BundleResourceUtil.getExtensionFolders(project);
        boolean hasNativeExtensions = nativeExtEnabled && extensionPaths.size() > 0;

        File exe = null;

        if (hasNativeExtensions) {
            String platform64 = "x86_64-osx";

            File cacheDir = new File(project.getBuildCachePath());
            cacheDir.mkdirs();
            String sdkVersion = project.option("defoldsdk", "");
            String buildServer = project.option("build-server", "");
            ExtenderClient extender = new ExtenderClient(buildServer, cacheDir);
            File logFile = File.createTempFile("build_" + sdkVersion + "_", ".txt");
            logFile.deleteOnExit();

            exe = File.createTempFile("engine_" + sdkVersion + "_" + platform64, "");
            exe.deleteOnExit();

            List<IExtenderResource> allSource = BundleResourceUtil.getExtensionSources(project, Platform.X86Darwin);
            BundleHelper.buildEngineRemote(extender, platform64, sdkVersion, allSource, logFile, "/dmengine", exe);
        } else {
            exe = new File(Bob.getDmengineExe(Platform.X86Darwin, debug));
        }

        FileUtils.deleteDirectory(appDir);
        appDir.mkdirs();
        contentsDir.mkdirs();
        resourcesDir.mkdirs();
        macosDir.mkdirs();

        BundleHelper helper = new BundleHelper(project, Platform.X86Darwin, bundleDir, ".app");

        // Copy bundle resources into .app folder
        BundleResourceUtil.writeResourcesToDirectory(bundleResources, appDir);

        // Copy archive and game.projectc
        for (String name : Arrays.asList("game.projectc", "game.arci", "game.arcd", "game.dmanifest", "game.public.der")) {
            FileUtils.copyFile(new File(buildDir, name), new File(resourcesDir, name));
        }

        helper.format("osx", "infoplist", "resources/osx/Info.plist", new File(contentsDir, "Info.plist"));

        // Copy icon
        copyIcon(projectProperties, new File(project.getRootDirectory()), resourcesDir);

        // Copy Executable
        File exeOut = new File(macosDir, title);
        FileUtils.copyFile(exe, exeOut);
        exeOut.setExecutable(true);
    }

}
