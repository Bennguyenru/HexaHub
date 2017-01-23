package com.dynamo.bob.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.dynamo.bob.ClassLoaderScanner;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.test.util.MockFileSystem;


public class BundleResourcesTest {

    private MockFileSystem fileSystem;
    private Project project;

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @After
    public void tearDown() {
        this.project.dispose();
    }

    public BundleResourcesTest() {
        // Avoid hang when running unit-test on Mac OSX
        // Related to SWT and threads?
        if (System.getProperty("os.name").toLowerCase().indexOf("mac") != -1) {
            Display.getDefault();
        }

        this.fileSystem = new MockFileSystem();
        this.fileSystem.setBuildDirectory("");
        this.project = new Project(this.fileSystem);
        this.project.clearProjectProperties();

        ClassLoaderScanner scanner = new TestClassLoaderScanner();
        project.scan(scanner, "com.dynamo.bob");
        project.scan(scanner, "com.dynamo.bob.pipeline");

        addResourceDirectory("/test");
    }

    private void addFile(String file, String source) {
        addFile(file, source.getBytes());
    }

    private void addFile(String file, byte[] content) {
        this.fileSystem.addFile(file, content);
    }

    private void addResourceDirectory(String dir) {
        Bundle bundle = FrameworkUtil.getBundle(getClass());
        Enumeration<URL> entries = bundle.findEntries(dir, "*", true);
        if (entries != null) {
            while (entries.hasMoreElements()) {
                final URL url = entries.nextElement();
                IPath path = new Path(url.getPath()).removeFirstSegments(1);

                // Make sure to only add files and not directory entries.
                if (path.toString().lastIndexOf('/') != path.toString().length() - 1) {
                    InputStream is = null;
                    try {
                        is = url.openStream();
                        ByteArrayOutputStream os = new ByteArrayOutputStream();
                        IOUtils.copy(is, os);
                        String p = "/" + path.toString();
                        addFile(p, os.toByteArray());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        IOUtils.closeQuietly(is);
                    }
                }
            }
        }
    }

    @Test
    public void testFindExtensionFolders() throws Exception {

        List<String> folders = BundleResourceUtil.getExtensionFolders(project);
        assertEquals(1, folders.size());
        assertEquals("/extension1", folders.get(0));

        // Add one more extension folder at root
        addFile("extension2/ext.manifest", "name: \"extension2\"");
        folders = BundleResourceUtil.getExtensionFolders(project);
        assertEquals(2, folders.size());
        assertEquals("/extension1", folders.get(0));
        assertEquals("/extension2", folders.get(1));

        // Add one more extension folder in a nested subfolder
        addFile("subfolder/extension3/ext.manifest", "name: \"extension3\"");
        folders = BundleResourceUtil.getExtensionFolders(project);
        assertEquals(3, folders.size());
        assertEquals("/extension1", folders.get(0));
        assertEquals("/extension2", folders.get(1));
        assertEquals("/subfolder/extension3", folders.get(2));

    }

    @Test
    public void testCollectResources() throws Exception {

        Map<String, IResource> resourceMap = BundleResourceUtil.collectResources(project, "/restest1/common/", null);
        assertEquals(2, resourceMap.size());
        assertTrue(resourceMap.containsKey("collision.txt"));
        assertTrue(resourceMap.containsKey("test.txt"));

        resourceMap = BundleResourceUtil.collectResources(project, "/restest2/common/", null);
        assertEquals(1, resourceMap.size());
    }

    @Test
    public void testExclude() throws Exception {

        Map<String, IResource> resourceMap = BundleResourceUtil.collectResources(project, "/restest1/common/", Arrays.asList(new String[] { "/restest1/common/collision.txt" }));
        assertTrue(!resourceMap.containsKey("collision.txt"));
        assertTrue(resourceMap.containsKey("test.txt"));

    }

    @Test
    public void testExtensionResources() throws Exception {

        // Should find bundle resources inside the extension1 folder
        Map<String, IResource> resourceMap = BundleResourceUtil.collectResources(project);
        assertEquals(2, resourceMap.size());
        assertTrue(resourceMap.containsKey("collision.txt"));
    }

    @Test(expected=CompileExceptionError.class)
    public void testConflict() throws Exception {

        // Add project property for bundle resources
        project.getProjectProperties().putStringValue("project", "bundle_resources", "/restest1/");
        Map<String, IResource> resourceMap = BundleResourceUtil.collectResources(project);
        assertEquals(3, resourceMap.size());
        assertTrue(resourceMap.containsKey("collision.txt")); // Will throw a CompileExceptionError due to a conflict in output resources
    }

    @Test
    public void testCollisionExcludes() throws Exception {

        // Add project property for bundle resources
        project.getProjectProperties().putStringValue("project", "bundle_resources", "/restest1/");

        // Exclude the conflicting file from bundle_resources
        project.getProjectProperties().putStringValue("project", "bundle_exclude_resources", "/restest1/common/collision.txt");
        Map<String, IResource> resourceMap = BundleResourceUtil.collectResources(project);
        assertEquals(3, resourceMap.size());
        assertTrue(resourceMap.containsKey("collision.txt"));

        // Exclude the conflicting file from extension
        project.getProjectProperties().putStringValue("project", "bundle_exclude_resources", "/extension1/res/common/collision.txt");
        resourceMap = BundleResourceUtil.collectResources(project);
        assertEquals(3, resourceMap.size());
        assertTrue(resourceMap.containsKey("collision.txt"));
    }

    @Test
    public void testInvalidProjectSetting() throws Exception {

        // Add project property for bundle resources
        project.getProjectProperties().putStringValue("project", "bundle_exclude_resources", "/extension1/res/common/collision.txt,/extension1/res/common/subdir/subdirtest.txt");

        // Test "old" way of specifying custom resources without leading slash (ie non absolute)
        project.getProjectProperties().putStringValue("project", "bundle_resources", "restest1/");
        Map<String, IResource> resourceMap = BundleResourceUtil.collectResources(project);
        assertEquals(0, resourceMap.size());

        // Test non existing project path
        project.getProjectProperties().putStringValue("project", "bundle_resources", "/not_valid/");
        resourceMap = BundleResourceUtil.collectResources(project);
        assertEquals(0, resourceMap.size());
    }

    @Test
    public void testWriteToDisk() throws Exception {

        Map<String, IResource> resourceMap = BundleResourceUtil.collectResources(project);
        File folder = tmpFolder.newFolder();

        BundleResourceUtil.writeResourcesToDirectory(resourceMap, folder);

        // Output folder should contain: collision.txt and subdir/subdirtest.txt
        Collection<File> outputFiles = FileUtils.listFiles(folder, null, true);
        assertEquals(2, outputFiles.size());
        assertTrue(outputFiles.contains(new File(FilenameUtils.concat(folder.getAbsolutePath(), "collision.txt"))));
        assertTrue(outputFiles.contains(new File(FilenameUtils.concat(folder.getAbsolutePath(), "subdir/subdirtest.txt"))));
    }

    @Test
    public void testWriteToZip() throws Exception {

        Map<String, IResource> resourceMap = BundleResourceUtil.collectResources(project);

        // Write entries to temp zip file
        File tmpZipFile = tmpFolder.newFile();
        ZipOutputStream zipOut = null;
        try {
            zipOut = new ZipOutputStream(new FileOutputStream(tmpZipFile));
            BundleResourceUtil.writeResourcesToZip(resourceMap, zipOut);
        } finally {
            IOUtils.closeQuietly(zipOut);
        }

        // Read temp zip file and assert
        ZipFile zipFile = new ZipFile(tmpZipFile);
        assertEquals(2, zipFile.size());
        assertTrue(zipFile.getEntry("collision.txt") != null);
        assertTrue(zipFile.getEntry("subdir/subdirtest.txt") != null);
        zipFile.close();
    }

    // Platform specific tests
    @Test
    public void testPlatform() throws Exception {

        // Test data
        Map<String, String[]> expected = new HashMap<String, String[]>();
        expected.put("x86-darwin", new String[] { "osx.txt", "x86-osx.txt" });
        expected.put("x86_64-darwin", new String[] { "osx.txt", "x86_64-osx.txt" });
        expected.put("x86-linux", new String[] { "linux.txt", "x86-linux.txt" });
        expected.put("x86_64-linux", new String[] { "linux.txt", "x86_64-linux.txt" });
        expected.put("x86-win32", new String[] { "windows.txt", "x86-windows.txt" });
        expected.put("x86_64-win32", new String[] { "windows.txt", "x86_64-windows.txt" });
        expected.put("armv7-android", new String[] { "android.txt" });
        expected.put("armv7-darwin", new String[] { "ios.txt", "armv7-ios.txt" });
        expected.put("arm64-darwin", new String[] { "ios.txt", "arm64-ios.txt" });
        expected.put("js-web", new String[] { "web.txt" });

        // Should find bundle resources inside the extension1 folder
        project.getProjectProperties().putStringValue("project", "bundle_resources", "/restest2/");

        Iterator<Map.Entry<String, String[]>> it = expected.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String[]> entry = (Map.Entry<String, String[]>)it.next();
            String expectedPlatform = entry.getKey();
            String[] expectedFiles = entry.getValue();

            project.setOption("platform", expectedPlatform);
            Map<String, IResource> resourceMap = BundleResourceUtil.collectResources(project);

            // +3 size since collision.txt, common.txt subdir/subdirtest.txt always included.
            assertEquals(expectedFiles.length + 3, resourceMap.size());
            assertTrue(resourceMap.containsKey("collision.txt"));
            assertTrue(resourceMap.containsKey("common.txt"));

            for (int i = 0; i < expectedFiles.length; ++i) {
                String expectedFile = expectedFiles[i];
                assertTrue(resourceMap.containsKey(expectedFile));
            }
        }

    }

}
