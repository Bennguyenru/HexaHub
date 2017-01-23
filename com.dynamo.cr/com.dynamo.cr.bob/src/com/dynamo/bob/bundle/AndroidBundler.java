package com.dynamo.bob.bundle;

import static org.apache.commons.io.FilenameUtils.normalize;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import com.defold.extender.client.ExtenderClient;
import com.dynamo.bob.Bob;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.pipeline.BundleResourceUtil;
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

    private boolean copyIconDPI(BobProjectProperties projectProperties, String projectRoot, File resDir, String name, String outName, String dpi)
            throws IOException {
            return copyIcon(projectProperties, projectRoot, resDir, name + "_" + dpi, "drawable-" + dpi + "/" + outName);
    }

    @Override
    public void bundleApplication(Project project, File bundleDir)
            throws IOException, CompileExceptionError {

        // Collect bundle/package resources to be included in APK zip
        Map<String, IResource> bundleResources = BundleResourceUtil.collectResources(project);

        BobProjectProperties projectProperties = project.getProjectProperties();
        final boolean debug = project.hasOption("debug");

        String title = projectProperties.getStringValue("project", "title", "Unnamed");
        String exeName = title.replace(' ', '_');

        String certificate = project.option("certificate", "");
        String key = project.option("private-key", "");

        File root = new File(project.getRootDirectory());
        boolean nativeExtEnabled = project.hasOption("native-ext");
        boolean hasNativeExtensions = nativeExtEnabled && ExtenderClient.hasExtensions(root);
        File exe = null;

        if (hasNativeExtensions) {
            String platform = "armv7-android";

            String sdkVersion = project.option("defoldsdk", "");
            String buildServer = project.option("build-server", "");
            ExtenderClient extender = new ExtenderClient(buildServer);
            File logFile = File.createTempFile("build_" + sdkVersion + "_", ".txt");
            logFile.deleteOnExit();

            exe = File.createTempFile("engine_" + sdkVersion + "_" + platform, "");
            exe.deleteOnExit();

            List<File> allSource = ExtenderClient.getExtensionSource(root, platform);
            BundleHelper.buildEngineRemote(extender, platform, sdkVersion, root, allSource, logFile, "/libdmengine.so", exe);
        } else {
            exe = new File(Bob.getDmengineExe(Platform.Armv7Android, debug));
        }

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

        // Copy push notification icons
        if (copyIcon(projectProperties, projectRoot, resDir, "push_icon_small", "drawable/push_icon_small.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resDir, "push_icon_large", "drawable/push_icon_large.png"))
            iconCount++;

        String[] dpis = new String[] { "ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" };
        for (String dpi : dpis) {
            if (copyIconDPI(projectProperties, projectRoot, resDir, "push_icon_small", "push_icon_small.png", dpi))
                iconCount++;
            if (copyIconDPI(projectProperties, projectRoot, resDir, "push_icon_large", "push_icon_large.png", dpi))
                iconCount++;
        }

        File manifestFile = new File(appDir, "AndroidManifest.xml");

        Map<String, Object> properties = new HashMap<>();
        if (iconCount > 0) {
            properties.put("has-icons?", true);
        } else {
            properties.put("has-icons?", false);
        }
        properties.put("exe-name", exeName);

        if(projectProperties.getBooleanValue("display", "dynamic_orientation", false)==false) {
            Integer displayWidth = projectProperties.getIntValue("display", "width");
            Integer displayHeight = projectProperties.getIntValue("display", "height");
            if((displayWidth != null & displayHeight != null) && (displayWidth > displayHeight)) {
                properties.put("orientation-support", "landscape");
            } else {
                properties.put("orientation-support", "portrait");
            }
        } else {
            properties.put("orientation-support", "sensor");
        }

        helper.format(properties, "android", "manifest", "resources/android/AndroidManifest.xml", manifestFile);

        // Create APK
        File ap1 = new File(appDir, title + ".ap1");

        Map<String, String> aaptEnv = new HashMap<String, String>();
        if (Platform.getHostPlatform() == Platform.X86_64Linux || Platform.getHostPlatform() == Platform.X86Linux) {
            aaptEnv.put("LD_LIBRARY_PATH", Bob.getPath(String.format("%s/lib", Platform.getHostPlatform().getPair())));
        }

        Result res = Exec.execResultWithEnvironment(aaptEnv, Bob.getExe(Platform.getHostPlatform(), "aapt"),
                "package",
                "--no-crunch",
                "-f",
                "--extra-packages",
                "com.facebook:com.google.android.gms",
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

        res = Exec.execResultWithEnvironment(aaptEnv, Bob.getExe(Platform.getHostPlatform(), "aapt"),
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

            for (String name : Arrays.asList("game.projectc", "game.arci", "game.arcd", "game.dmanifest", "game.public.der")) {
                File source = new File(new File(projectRoot, contentRoot), name);
                ZipEntry ze = new ZipEntry(normalize("assets/" + name, true));
                zipOut.putNextEntry(ze);
                FileUtils.copyFile(source, zipOut);
            }

            // Copy bundle resources into .apk zip (actually .ap2 in this case)
            BundleResourceUtil.writeResourcesToZip(bundleResources, zipOut);

            // Strip executable
            String strippedpath = exe.getAbsolutePath();
            if( !debug )
            {
                File tmp = File.createTempFile(title, "." + exe.getName() + ".stripped");
                tmp.deleteOnExit();
                strippedpath = tmp.getAbsolutePath();
                FileUtils.copyFile(exe, tmp);

                res = Exec.execResult(Bob.getExe(Platform.getHostPlatform(), "strip_android"), strippedpath);
                if (res.ret != 0) {
                    throw new IOException(new String(res.stdOutErr));
                }
            }

            // Copy executable
            String filename = FilenameUtils.concat("lib/armeabi-v7a", "lib" + exeName + ".so");
            filename = FilenameUtils.normalize(filename, true);
            zipOut.putNextEntry(new ZipEntry(filename));
            FileUtils.copyFile(new File(strippedpath), zipOut);
        } finally {
            IOUtils.closeQuietly(zipIn);
            IOUtils.closeQuietly(zipOut);
        }

        File ap3 = new File(appDir, title + ".ap3");

        // Sign
        if (certificate.length() > 0 && key.length() > 0) {
            Result r = Exec.execResult(Bob.getExe(Platform.getHostPlatform(), "apkc"),
                    "--in=" + ap2.getAbsolutePath(),
                    "--out=" + ap3.getAbsolutePath(),
                    "-cert=" + certificate,
                    "-key=" + key);
            if (r.ret != 0 ) {
                throw new IOException(new String(r.stdOutErr));
            }
        } else {
            Result r = Exec.execResult(Bob.getExe(Platform.getHostPlatform(), "apkc"),
                    "--in=" + ap2.getAbsolutePath(),
                    "--out=" + ap3.getAbsolutePath());
            if (r.ret != 0) {
                if (r.ret != 0 ) {
                    throw new IOException(new String(r.stdOutErr));
                }
            }
        }

        // Rezip with some files as STORED
        File ap4 = File.createTempFile(title, ".ap4");
        ap4.deleteOnExit();
        ZipFile zipFileIn = null;
        zipOut = null;
        try {
            zipFileIn = new ZipFile(ap3);
            zipOut = new ZipOutputStream(new FileOutputStream(ap4));

            Enumeration<? extends ZipEntry> entries = zipFileIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry inE = entries.nextElement();

                ZipEntry ze = new ZipEntry(inE.getName());
                ze.setSize(inE.getSize());
                byte[] entryData = null;
                CRC32 crc = null;

                // Some files need to be STORED instead of DEFLATED to
                // get "correct" memory mapping at runtime.
                int zipMethod = ZipEntry.DEFLATED;
                if (Arrays.asList("assets/game.projectc", "assets/game.arci", "assets/game.arcd", "assets/game.dmanifest", "assets/game.public.der").contains(inE.getName())) {
                    // Set up uncompresed file, unfortunately need to calculate crc32 and other data for this to work.
                    // https://blogs.oracle.com/CoreJavaTechTips/entry/creating_zip_and_jar_files
                    crc = new CRC32();
                    zipMethod = ZipEntry.STORED;
                    ze.setCompressedSize(inE.getSize());
                }

                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                try {
                	if (inE.getSize() > 0) {
	                    int count;
	                    entryData = new byte[(int) inE.getSize()];
	                    InputStream stream = zipFileIn.getInputStream(inE);
	                    while((count = stream.read(entryData, 0, (int)inE.getSize())) != -1) {
	                        byteOut.write(entryData, 0, count);
	                        if (zipMethod == ZipEntry.STORED) {
	                            crc.update(entryData, 0, count);
	                        }
	                    }
                	}
                } finally {
                    if(null != byteOut) {
                        byteOut.close();
                        entryData = byteOut.toByteArray();
                    }
                }

                if (zipMethod == ZipEntry.STORED) {
                    ze.setCrc(crc.getValue());
                    ze.setMethod(zipMethod);
                }

                zipOut.putNextEntry(ze);
                zipOut.write(entryData);
                zipOut.closeEntry();
            }

        } finally {
            IOUtils.closeQuietly(zipFileIn);
            IOUtils.closeQuietly(zipOut);
        }

        File apk = new File(appDir, title + ".apk");
        Result r = Exec.execResult(Bob.getExe(Platform.getHostPlatform(), "zipalign"),
                "-v", "4",
                ap4.getAbsolutePath(),
                apk.getAbsolutePath());

        if (r.ret != 0) {
            if (r.ret != 0 ) {
                throw new IOException(new String(r.stdOutErr));
            }
        }

        ap1.delete();
        ap2.delete();
        ap3.delete();
        ap4.delete();
    }
}
