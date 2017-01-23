package com.dynamo.bob.bundle;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import org.apache.commons.io.FileUtils;

import com.dynamo.bob.Bob;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.pipeline.BundleResourceUtil;
import com.dynamo.bob.util.BobProjectProperties;

public class LinuxBundler implements IBundler {

    @Override
    public void bundleApplication(Project project, File bundleDir)
            throws IOException, CompileExceptionError {

        // Collect bundle/package resources to be included in bundle directory
        Map<String, IResource> bundleResources = BundleResourceUtil.collectResources(project);

        BobProjectProperties projectProperties = project.getProjectProperties();
        String binaryX86 = Bob.getDmengineExe(Platform.X86Linux, project.hasOption("debug"));
        String binaryX64 = Bob.getDmengineExe(Platform.X86_64Linux, project.hasOption("debug"));
        String title = projectProperties.getStringValue("project", "title", "Unnamed");

        File buildDir = new File(project.getRootDirectory(), project.getBuildDirectory());
        File appDir = new File(bundleDir, title);

        FileUtils.deleteDirectory(appDir);
        appDir.mkdirs();

        // Copy archive and game.projectc
        for (String name : Arrays.asList("game.projectc", "game.arci", "game.arcd", "game.dmanifest", "game.public.der")) {
            FileUtils.copyFile(new File(buildDir, name), new File(appDir, name));
        }

        // Copy bundle resources into bundle directory
        BundleResourceUtil.writeResourcesToDirectory(bundleResources, appDir);

        // Copy Executable
        File x86Out = new File(appDir, title + ".x86");
        File x64Out = new File(appDir, title + ".x86_64");

        FileUtils.copyFile(new File(binaryX86), x86Out);
        FileUtils.copyFile(new File(binaryX64), x64Out);

        x86Out.setExecutable(true);
        x64Out.setExecutable(true);
    }
}
