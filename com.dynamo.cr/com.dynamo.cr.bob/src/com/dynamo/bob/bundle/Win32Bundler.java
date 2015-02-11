package com.dynamo.bob.bundle;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import com.dynamo.bob.Bob;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.util.BobProjectProperties;

public class Win32Bundler implements IBundler {

    @Override
    public void bundleApplication(Project project, File bundleDir)
            throws IOException, CompileExceptionError {

        BobProjectProperties projectProperties = project.getProjectProperties();
        String exe = Bob.getDmengineExe(Platform.X86Win32, project.hasOption("release"));
        String title = projectProperties.getStringValue("project", "title", "Unnamed");

        File buildDir = new File(project.getRootDirectory(), project.getBuildDirectory());
        File appDir = new File(bundleDir, projectProperties.getStringValue("project", "title", "Unnamed"));

        FileUtils.deleteDirectory(appDir);
        appDir.mkdirs();

        // Copy archive and game.projectc
        for (String name : Arrays.asList("game.projectc", "game.darc")) {
            FileUtils.copyFile(new File(buildDir, name), new File(appDir, name));
        }

        // Copy Executable and DLL:s
        File exeOut = new File(appDir, String.format("%s.exe", title));
        FileUtils.copyFile(new File(exe), exeOut);
        Collection<File> dlls = FileUtils.listFiles(new File(FilenameUtils.getFullPath(exe)), new String[] {"dll"}, false);
        for (File file : dlls) {
            FileUtils.copyFileToDirectory(file, appDir);
        }

        String icon = projectProperties.getStringValue("windows", "app_icon");
        if (icon != null) {
            File iconFile = new File(project.getRootDirectory(), icon);
            if (iconFile.exists()) {
                String[] args = new String[] { exe, iconFile.getAbsolutePath() };
                try {
                    IconExe.main(args);
                } catch (Exception e) {
                    throw new IOException("Failed to set icon for executable", e);
                }
            }
        }
    }
}
