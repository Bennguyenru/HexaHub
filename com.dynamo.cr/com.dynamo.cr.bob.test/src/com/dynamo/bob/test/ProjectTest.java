package com.dynamo.bob.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.osgi.framework.Bundle;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.NullProgress;
import com.dynamo.bob.OsgiResourceScanner;
import com.dynamo.bob.OsgiScanner;
import com.dynamo.bob.Project;
import com.dynamo.bob.TaskResult;
import com.dynamo.bob.test.util.MockFileSystem;

public class ProjectTest {

    private final static int SERVER_PORT = 8081;
    private final static String EMAIL = "test@king.com";
    private final static String AUTH = "secret-auth";

    private MockFileSystem fileSystem;
    private Project project;
    private Bundle bundle;
    private Server httpServer;

    private AtomicInteger _304Count = new AtomicInteger();

    @Rule
    public TestLibrariesRule testLibs = new TestLibrariesRule();

    private void initHttpServer(String serverLocation) throws IOException {
        httpServer = new Server();

        SocketConnector connector = new SocketConnector();
        connector.setPort(SERVER_PORT);
        httpServer.addConnector(connector);
        HandlerList handlerList = new HandlerList();
        FileHandler fileHandler = new FileHandler();
        fileHandler.setResourceBase(serverLocation);
        handlerList.addHandler(fileHandler);
        httpServer.setHandler(handlerList);

        try {
            httpServer.start();
        } catch (Exception e) {
            throw new IOException("Unable to start http server", e);
        }
    }

    @Before
    public void setUp() throws Exception {
        bundle = Platform.getBundle("com.dynamo.cr.bob");
        fileSystem = new MockFileSystem();
        project = new Project(fileSystem, FileUtils.getTempDirectoryPath(), "build/default");
        project.setOption("email", EMAIL);
        project.setOption("auth", AUTH);
        project.scan(new OsgiScanner(bundle), "com.dynamo.bob.test");
        project.setLibUrls(Arrays.asList(new URL("http://localhost:8081/test_lib1.zip"), new URL("http://localhost:8081/test_lib2.zip")));

        initHttpServer(testLibs.getServerLocation());
    }

    @After
    public void tearDown() throws Exception {
        httpServer.stop();
        project.dispose();
    }

    List<TaskResult> build(String... commands) throws IOException, CompileExceptionError {
        return project.build(new NullProgress(), commands);
    }

    private boolean libExists(String lib) {
        return new File(FilenameUtils.concat(project.getLibPath(), lib)).exists();
    }

    @Test
    public void testResolve() throws Exception {
        assertEquals(0, _304Count.get());
        File lib = new File(project.getLibPath());
        if (lib.exists()) {
            FileUtils.cleanDirectory(new File(project.getLibPath()));
        }
        String[] filenames = new String[] {
                "http___localhost_8081_test_lib1_zip.zip",
                "http___localhost_8081_test_lib2_zip.zip",
        };
        for (String filename : filenames) {
            assertFalse(libExists(filename));
        }
        this.project.resolveLibUrls(new NullProgress());
        for (String filename : filenames) {
            assertTrue(libExists(filename));
        }
        assertEquals(0, _304Count.get());

        this.project.resolveLibUrls(new NullProgress());
        for (String filename : filenames) {
            assertTrue(libExists(filename));
        }
        assertEquals(filenames.length, _304Count.get());
    }

    @Test
    public void testMountPoints() throws Exception {
        project.resolveLibUrls(new NullProgress());
        project.mount(new OsgiResourceScanner(Platform.getBundle("com.dynamo.cr.bob")));
        project.setInputs(Arrays.asList("test_lib1/file1.in", "test_lib2/file2.in", "builtins/cp_test.in"));
        List<TaskResult> results = build("resolve", "build");
        assertEquals(3, results.size());
        for (TaskResult result : results) {
            assertTrue(result.isOk());
        }
    }

    @Test
    public void testMountPointFindSources() throws Exception {
        project.resolveLibUrls(new NullProgress());
        project.mount(new OsgiResourceScanner(Platform.getBundle("com.dynamo.cr.bob")));
        project.findSources(".", null);
        List<TaskResult> results = build("build");
        assertFalse(results.isEmpty());
        for (TaskResult result : results) {
            assertTrue(result.isOk());
        }
    }

    private class FileHandler extends ResourceHandler {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException ,javax.servlet.ServletException {
            if (EMAIL.equals(request.getHeader("X-Email")) && AUTH.equals(request.getHeader("X-Auth"))) {

                String sha1 = null;
                Resource resource = getResource(request);
                File file = resource.getFile();
                if (file.exists()) {
                    ZipFile zip = new ZipFile(file);
                    sha1 = zip.getComment();
                    zip.close();
                }

                String etag = request.getHeader("If-None-Match");
                if (sha1 != null) {
                    response.setHeader("ETag", sha1);
                }
                if (etag != null && etag.equals(sha1)) {
                    _304Count.incrementAndGet();
                    response.setStatus(304);
                    baseRequest.setHandled(true);
                } else {
                    super.handle(target, baseRequest, request, response);
                }

            } else {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
        }
    }
}

