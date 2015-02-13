package com.dynamo.bob.bundle;

import static org.apache.commons.io.FilenameUtils.normalize;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import com.dynamo.bob.Bob;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.pipeline.GameProjectBuilder;
import com.dynamo.bob.util.BobProjectProperties;
import com.dynamo.bob.util.Exec;
import com.dynamo.bob.util.Exec.Result;

public class AndroidBundler implements IBundler {
    private boolean copyIcon(BobProjectProperties projectProperties, String projectRoot, File resDir, String name, String outName)
            throws IOException {
        String resource = projectProperties.getStringValue("android", name);
        if (resource != null && resource.length() > 0) {
            File inFile = new File(projectRoot, resource);
            File outFile = new File(resDir, outName);
            FileUtils.copyFile(inFile, outFile);
            return true;
        }
        return false;
    }

    @Override
    public void bundleApplication(Project project, File bundleDir)
            throws IOException, CompileExceptionError {

        BobProjectProperties projectProperties = project.getProjectProperties();
        final boolean release = project.hasOption("release");
        String exeName = release ? "dmengine_release" : "dmengine";
        String exe = Bob.getDmengineExe(Platform.Armv7Android, release);
        String title = projectProperties.getStringValue("project", "title", "Unnamed");

        String certificate = project.option("certificate", "");
        String key = project.option("private-key", "");

        File appDir = new File(bundleDir, title);
        File resDir = new File(appDir, "res");

        String contentRoot = project.getBuildDirectory();
        String projectRoot = project.getRootDirectory();

        FileUtils.deleteDirectory(appDir);
        appDir.mkdirs();
        resDir.mkdirs();
        FileUtils.forceMkdir(new File(resDir, "drawable"));
        FileUtils.forceMkdir(new File(resDir, "drawable-ldpi"));
        FileUtils.forceMkdir(new File(resDir, "drawable-mdpi"));
        FileUtils.forceMkdir(new File(resDir, "drawable-hdpi"));
        FileUtils.forceMkdir(new File(resDir, "drawable-xhdpi"));
        FileUtils.forceMkdir(new File(resDir, "drawable-xxhdpi"));
        FileUtils.forceMkdir(new File(resDir, "drawable-xxxhdpi"));
        FileUtils.forceMkdir(new File(appDir, "libs/armeabi-v7a"));

        BundleHelper helper = new BundleHelper(project, Platform.Armv7Android, bundleDir, "");

        // Copy icons
        int iconCount = 0;
        // copy old 32x32 icon first, the correct size is actually 36x36
        if (copyIcon(projectProperties, projectRoot, resDir, "app_icon_32x32", "drawable-ldpi/icon.png")
    		|| copyIcon(projectProperties, projectRoot, resDir, "app_icon_36x36", "drawable-ldpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resDir, "app_icon_48x48", "drawable-mdpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resDir, "app_icon_72x72", "drawable-hdpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resDir, "app_icon_96x96", "drawable-xhdpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resDir, "app_icon_144x144", "drawable-xxhdpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resDir, "app_icon_192x192", "drawable-xxxhdpi/icon.png"))
            iconCount++;

        File manifestFile = new File(appDir, "AndroidManifest.xml");

        Map<String, Object> properties = new HashMap<>();
        if (iconCount > 0) {
            properties.put("has-icons?", true);
        } else {
            properties.put("has-icons?", false);
        }
        properties.put("exe-name", exeName);

        helper.format(properties, "android", "manifest", "resources/android/AndroidManifest.xml", manifestFile);

        // Create APK
        File ap1 = new File(appDir, title + ".ap1");

        Result res = Exec.execResult(Bob.getExe(Platform.getHostPlatform(), "aapt"),
                "package",
                "--no-crunch",
                "-f",
                "--extra-packages",
                "com.facebook.android:com.google.android.gms",
                "-m",
                //"--debug-mode",
                "--auto-add-overlay",
                "-S", resDir.getAbsolutePath(),
                "-S", Bob.getPath("res/facebook"),
                "-S", Bob.getPath("res/google-play-services"),
                "-M", manifestFile.getAbsolutePath(),
                "-I", Bob.getPath("lib/android.jar"),
                "-F", ap1.getAbsolutePath());

        if (res.ret != 0) {
            throw new IOException(new String(res.stdOutErr));
        }

        File tmpClassesDex = new File("classes.dex");
        FileUtils.copyFile(new File(Bob.getPath("lib/classes.dex")), tmpClassesDex);

        res = Exec.execResult(Bob.getExe(Platform.getHostPlatform(), "aapt"),
                "add",
                ap1.getAbsolutePath(),
                tmpClassesDex.getPath());

        if (res.ret != 0) {
            throw new IOException(new String(res.stdOutErr));
        }

        tmpClassesDex.delete();


        File ap2 = File.createTempFile(title, ".ap2");
        ap2.deleteOnExit();
        ZipInputStream zipIn = null;
        ZipOutputStream zipOut = null;
        try {
            zipIn = new ZipInputStream(new FileInputStream(ap1));
            zipOut = new ZipOutputStream(new FileOutputStream(ap2));

            ZipEntry inE = zipIn.getNextEntry();
            while (inE != null) {
                zipOut.putNextEntry(new ZipEntry(inE.getName()));
                IOUtils.copy(zipIn, zipOut);
                inE = zipIn.getNextEntry();
            }

            HashSet<String> resources = GameProjectBuilder.findResources(project);
            resources.add(new File(new File(projectRoot, contentRoot), "game.projectc").getAbsolutePath());

            int prefixLen = normalize(new File(projectRoot, contentRoot).getPath(), true).length();
            for (String path : resources) {
                File r = new File(path);
                String p = normalize(r.getPath(), true).substring(prefixLen);
                if (!(p.startsWith("/builtins") || p.equals("/game.darc"))) {
                    String ap = normalize("assets" + p, true);
                    zipOut.putNextEntry(new ZipEntry(ap));
                    FileUtils.copyFile(r, zipOut);
                }
            }

            // Copy executable
            String filename = FilenameUtils.concat("lib/armeabi-v7a", FilenameUtils.getName(exe));
            filename = FilenameUtils.normalize(filename, true);
            zipOut.putNextEntry(new ZipEntry(filename));
            FileUtils.copyFile(new File(exe), zipOut);

        } finally {
            IOUtils.closeQuietly(zipIn);
            IOUtils.closeQuietly(zipOut);
        }

        // Sign
        File apk = new File(appDir, title + ".apk");
        if (certificate.length() > 0 && key.length() > 0) {
            Result r = Exec.execResult(Bob.getExe(Platform.getHostPlatform(), "apkc"),
                    "--in=" + ap2.getAbsolutePath(),
                    "--out=" + apk.getAbsolutePath(),
                    "-cert=" + certificate,
                    "-key=" + key);
            if (r.ret != 0 ) {
                throw new IOException(new String(r.stdOutErr));
            }
        } else {
            Result r = Exec.execResult(Bob.getExe(Platform.getHostPlatform(), "apkc"),
                    "--in=" + ap2.getAbsolutePath(),
                    "--out=" + apk.getAbsolutePath());
            if (r.ret != 0) {
                if (r.ret != 0 ) {
                    throw new IOException(new String(r.stdOutErr));
                }
            }
        }

        ap1.delete();
        ap2.delete();
    }
}
