package com.dynamo.bob.bundle.test;

import static org.apache.commons.io.FilenameUtils.concat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.NullProgress;
import com.dynamo.bob.OsgiScanner;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.bundle.OSXBundler;
import com.dynamo.bob.fs.DefaultFileSystem;

public class OSXBundlerTest {

    private String contentRoot;
    private String outputDir;

    @Before
    public void setUp() throws Exception {
        contentRoot = Files.createTempDirectory(null).toFile().getAbsolutePath();
        outputDir = Files.createTempDirectory(null).toFile().getAbsolutePath();
    }

    @After
    public void tearDown() throws IOException {
        FileUtils.deleteDirectory(new File(contentRoot));
        FileUtils.deleteDirectory(new File(outputDir));
    }

    private void assertPList() {
        assertTrue(new File(concat(outputDir, "Unnamed.app/Contents/Info.plist")).isFile());
    }

    private void assertExe() throws IOException {
        assertTrue(new File(concat(outputDir, "Unnamed.app/Contents/MacOS/Unnamed")).isFile());
    }

    void build() throws IOException, CompileExceptionError {
        Project project = new Project(new DefaultFileSystem(), contentRoot, "build");

        OsgiScanner scanner = new OsgiScanner(FrameworkUtil.getBundle(Project.class));
        project.scan(scanner, "com.dynamo.bob");
        project.scan(scanner, "com.dynamo.bob.pipeline");

        project.setOption("platform", Platform.X86Darwin.getPair());
        project.setOption("archive", "true");
        project.setOption("bundle-output", outputDir);
        project.findSources(contentRoot, new HashSet<String>());
        project.build(new NullProgress(), "clean", "build", "bundle");
    }

    @Test
    public void testBundle() throws IOException, ConfigurationException, CompileExceptionError {
        createFile(contentRoot, "test.icns", "test_icon");
        createFile(contentRoot, "game.project", "[osx]\napp_icon=test.icns\n");
        build();
        assertEquals("test_icon", readFile(concat(outputDir, "Unnamed.app/Contents/Resources"), OSXBundler.ICON_NAME));
        assertExe();
        assertPList();
    }

    private String readFile(String dir, String name) throws IOException {
        return FileUtils.readFileToString(new File(dir, name));
    }

    private String createFile(String root, String name, String content) throws IOException {
        File file = new File(root, name);
        file.deleteOnExit();
        FileUtils.copyInputStreamToFile(new ByteArrayInputStream(content.getBytes()), file);
        return file.getAbsolutePath();
    }

}
