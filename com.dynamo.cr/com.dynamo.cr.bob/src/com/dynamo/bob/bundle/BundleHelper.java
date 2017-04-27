package com.dynamo.bob.bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderClientException;
import com.defold.extender.client.ExtenderResource;
import com.dynamo.bob.Bob;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.MultipleCompileException;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.pipeline.ExtenderUtil;
import com.dynamo.bob.util.BobProjectProperties;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;


public class BundleHelper {
    private Project project;
    private String title;
    private File buildDir;
    private File appDir;
    private Map<String, Map<String, Object>> propertiesMap;

    public BundleHelper(Project project, Platform platform, File bundleDir, String appDirSuffix) throws IOException {
        BobProjectProperties projectProperties = project.getProjectProperties();

        this.project = project;
        this.title = projectProperties.getStringValue("project", "title", "Unnamed");

        this.buildDir = new File(project.getRootDirectory(), project.getBuildDirectory());
        this.appDir = new File(bundleDir, title + appDirSuffix);

        this.propertiesMap = createPropertiesMap(project.getProjectProperties());
    }

    static Object convert(String value, String type) {
        if (type != null && type.equals("bool")) {
            if (value != null) {
                return value.equals("1");
            } else {
                return false;
            }
        }
        return value;
    }

    public static Map<String, Map<String, Object>> createPropertiesMap(BobProjectProperties projectProperties) throws IOException {
        BobProjectProperties meta = new BobProjectProperties();
        InputStream is = Bob.class.getResourceAsStream("meta.properties");
        try {
            meta.load(is);
        } catch (ParseException e) {
            throw new RuntimeException("Failed to parse meta.properties", e);
        } finally {
            IOUtils.closeQuietly(is);
        }

        Map<String, Map<String, Object>> map = new HashMap<>();

        for (String c : meta.getCategoryNames()) {
            map.put(c, new HashMap<String, Object>());

            for (String k : meta.getKeys(c)) {
                if (k.endsWith(".default")) {
                    String k2 = k.split("\\.")[0];
                    String v = meta.getStringValue(c, k);
                    Object v2 = convert(v, meta.getStringValue(c, k2 + ".type"));
                    map.get(c).put(k2, v2);
                }
            }
        }

        for (String c : projectProperties.getCategoryNames()) {
            if (!map.containsKey(c)) {
                map.put(c, new HashMap<String, Object>());
            }

            for (String k : projectProperties.getKeys(c)) {
                String def = meta.getStringValue(c, k + ".default");
                map.get(c).put(k, def);
                String v = projectProperties.getStringValue(c, k);
                Object v2 = convert(v, meta.getStringValue(c, k + ".type"));
                map.get(c).put(k, v2);
            }
        }

        return map;
    }

    URL getResource(String category, String key, String defaultValue) {
        BobProjectProperties projectProperties = project.getProjectProperties();
        File projectRoot = new File(project.getRootDirectory());
        String s = projectProperties.getStringValue(category, key);
        if (s != null && s.trim().length() > 0) {
            try {
                return new File(projectRoot, s).toURI().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        } else {
            return getClass().getResource(defaultValue);
        }
    }

    public BundleHelper format(Map<String, Object> properties, String templateCategory, String templateKey, String defaultTemplate, File toFile) throws IOException {
        URL templateURL = getResource(templateCategory, templateKey, defaultTemplate);
        Template template = Mustache.compiler().compile(IOUtils.toString(templateURL));
        StringWriter sw = new StringWriter();
        template.execute(this.propertiesMap, properties, sw);
        sw.flush();
        FileUtils.write(toFile, sw.toString());
        return this;
    }

    public BundleHelper format(String templateCategory, String templateKey, String defaultTemplate, File toFile) throws IOException {
        return format(new HashMap<String, Object>(), templateCategory, templateKey, defaultTemplate, toFile);
    }


    public BundleHelper copyBuilt(String name) throws IOException {
        FileUtils.copyFile(new File(buildDir, name), new File(appDir, name));
        return this;
    }

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

    public void createAndroidManifest(BobProjectProperties projectProperties, String projectRoot, File manifestFile, File resOutput, String exeName) throws IOException {
     // Copy icons
        int iconCount = 0;
        // copy old 32x32 icon first, the correct size is actually 36x36
        if (copyIcon(projectProperties, projectRoot, resOutput, "app_icon_32x32", "drawable-ldpi/icon.png")
            || copyIcon(projectProperties, projectRoot, resOutput, "app_icon_36x36", "drawable-ldpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resOutput, "app_icon_48x48", "drawable-mdpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resOutput, "app_icon_72x72", "drawable-hdpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resOutput, "app_icon_96x96", "drawable-xhdpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resOutput, "app_icon_144x144", "drawable-xxhdpi/icon.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resOutput, "app_icon_192x192", "drawable-xxxhdpi/icon.png"))
            iconCount++;

        // Copy push notification icons
        if (copyIcon(projectProperties, projectRoot, resOutput, "push_icon_small", "drawable/push_icon_small.png"))
            iconCount++;
        if (copyIcon(projectProperties, projectRoot, resOutput, "push_icon_large", "drawable/push_icon_large.png"))
            iconCount++;

        String[] dpis = new String[] { "ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" };
        for (String dpi : dpis) {
            if (copyIconDPI(projectProperties, projectRoot, resOutput, "push_icon_small", "push_icon_small.png", dpi))
                iconCount++;
            if (copyIconDPI(projectProperties, projectRoot, resOutput, "push_icon_large", "push_icon_large.png", dpi))
                iconCount++;
        }

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
        format(properties, "android", "manifest", "resources/android/AndroidManifest.xml", manifestFile);
    }

    static class ResourceInfo
    {
        public String resource;
        public String message;
        public int lineNumber;

        public ResourceInfo(String r, int l, String msg) {
            resource = r;
            lineNumber = l;
            message = msg;
        }
    };

    // Error regexp works for both cpp and javac errors
    private static Pattern errorRe = Pattern.compile("^\\/tmp\\/upload[0-9]+\\/([^:]+):([0-9]+):?([0-9]*):\\s*(.+)");
    // Warning regexp is more simple, catches anything starting with "warning:"
    private static Pattern warningRe = Pattern.compile("warning:(.+)");

    private static void parseLog(String log, List<ResourceInfo> errors, List<ResourceInfo> warnings) {
        String[] lines = log.split("\n");

        for (String line : lines) {

            Matcher m = BundleHelper.errorRe.matcher(line);
            if (m.matches()) {
                String resource = m.group(1);
                int lineNumber = Integer.parseInt(m.group(2)); // column is group 3
                String msg = m.group(4);
                errors.add( new BundleHelper.ResourceInfo(resource, lineNumber, msg) );
            }

            m = BundleHelper.warningRe.matcher(line);
            if (m.matches()) {
                String msg = m.group(0);
                warnings.add( new BundleHelper.ResourceInfo(null, 0, msg) );
            }
        }
    }

    public static void buildEngineRemote(ExtenderClient extender, String platform, String sdkVersion, List<ExtenderResource> allSource, File logFile, String srcName, File outputEngine, File outputClassesDex) throws CompileExceptionError, MultipleCompileException {
        File zipFile = null;

        try {
            zipFile = File.createTempFile("build_" + sdkVersion, ".zip");
            zipFile.deleteOnExit();
        } catch (IOException e) {
            throw new CompileExceptionError("Failed to create temp zip file", e.getCause());
        }

        try {
            extender.build(platform, sdkVersion, allSource, zipFile, logFile);
        } catch (ExtenderClientException e) {
            String buildError = "<no log file>";
            if (logFile != null) {
                try {
                    buildError = FileUtils.readFileToString(logFile);

                    List<ResourceInfo> errors = new ArrayList<>();
                    List<ResourceInfo> warnings = new ArrayList<>();
                    parseLog(buildError, errors, warnings);

                    MultipleCompileException exception = new MultipleCompileException("Build error", e);

                    IResource firstresource = null;
                    if (errors.size() > 0) {
                        for (ResourceInfo info : errors) {
                            IResource resource = ExtenderUtil.getResource(info.resource, allSource);
                            if (resource != null) {
                                exception.addError(resource, info.message, info.lineNumber);

                                if (firstresource == null) {
                                    firstresource = resource;
                                }
                            }
                        }
                    }

                    if (warnings.size() > 0) {
                        for (ResourceInfo info : warnings) {
                            IResource resource = ExtenderUtil.getResource(allSource.get(0).getPath(), allSource);
                            if (resource != null) {
                                exception.addWarning(resource, info.message, info.lineNumber);
                            }
                        }
                    }

                    // Trick to always supply the full log
                    // 1. We pick a resource, the first one generating errors should be related.
                    //    Otherwise we fall back on an ext.manifest (possibly the wrong one!)
                    // 2. We put it first, because the list is reversed later when presented
                    if (firstresource == null) {
                        firstresource = ExtenderUtil.getResource(allSource.get(0).getPath(), allSource);
                    }
                    exception.addError(firstresource, "Build server output: " + buildError, 1);

                    throw exception;
                } catch (IOException ioe) {
                    buildError = "<failed reading log>";
                }
            }
            buildError = String.format("'%s' could not be built. Sdk version: '%s'\nLog: '%s'", platform, sdkVersion, buildError);
            throw new CompileExceptionError(buildError, e.getCause());
        }

        FileSystem zip = null;
        try {
            zip = FileSystems.newFileSystem(zipFile.toPath(), null);
        } catch (IOException e) {
            throw new CompileExceptionError(String.format("Failed to mount temp zip file %s", zipFile.getAbsolutePath()), e.getCause());
        }

        // If we expect a classes.dex file, try to extract it from the zip
        if (outputClassesDex != null) {
            try {
                Path source = zip.getPath("classes.dex");
                Files.copy(source, new FileOutputStream(outputClassesDex));
            } catch (IOException e) {
                throw new CompileExceptionError(String.format("Failed to copy classes.dex to %s", outputClassesDex.getAbsolutePath()), e.getCause());
            }
        }

        try {
            Path source = zip.getPath(srcName);
            Files.copy(source, new FileOutputStream(outputEngine));
            outputEngine.setExecutable(true);
        } catch (IOException e) {
            throw new CompileExceptionError(String.format("Failed to copy %s to %s", srcName, outputEngine.getAbsolutePath()), e.getCause());
        }
    }

}
