package com.dynamo.cr.server.resources.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.StringReader;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.net.URI;
import java.net.URL;

import javax.persistence.EntityManager;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.plist.XMLPropertyListConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.dynamo.cr.protocol.proto.Protocol.ProjectInfo;
import com.dynamo.cr.protocol.proto.Protocol.ProjectInfoList;
import com.dynamo.cr.protocol.proto.Protocol.UserInfo;
import com.dynamo.cr.server.model.ModelUtil;
import com.dynamo.cr.server.model.Project;
import com.dynamo.cr.server.model.User;
import com.dynamo.cr.server.providers.ProtobufProviders;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;

public class ProjectResourceTest extends AbstractResourceTest {

    private int port = 6500;

    private String ownerEmail = "owner@foo.com";
    private String ownerPassword = "secret";
    private User owner;
    private UserInfo ownerInfo;

    private Project proj1;

    private String memberEmail = "member@foo.com";
    private String memberPassword = "secret";
    private User member;
    private UserInfo memberInfo;
    private WebResource memberProjectsWebResource;

    private String nonMemberEmail = "nonmember@foo.com";
    private String nonMemberPassword = "secret";
    private User nonMember;
    private UserInfo nonMemberInfo;

    private WebResource nonMemberProjectsWebResource;
    private WebResource usersResource;
    private WebResource ownerProjectsResource;
    private WebResource ownerProjectResource;

    private EntityManager em;

    private <T> T get(WebResource resource, String path, Class<T> klass)  {
        ClientResponse resp = resource.path(path).accept(ProtobufProviders.APPLICATION_XPROTOBUF).get(ClientResponse.class);
        if (!(resp.getStatus() == 200 || resp.getStatus() == 204 || resp.getStatus() == 400)) {
            throw new RuntimeException(String.format("Expected 200, 204 or 400. Got %d", resp.getStatus()));
        }
        return resp.getEntity(klass);
    }

    private <T> void put(WebResource resource, String path, T data) {
        ClientResponse resp = resource.path(path).accept(ProtobufProviders.APPLICATION_XPROTOBUF).put(ClientResponse.class, data);
        if (!(resp.getStatus() == 200 || resp.getStatus() == 204 || resp.getStatus() == 400)) {
            throw new RuntimeException(String.format("Expected 200, 204 or 400. Got %d", resp.getStatus()));
        }
    }

    @Before
    public void setUp() throws Exception {
        setupUpTest();

        em = emf.createEntityManager();
        em.getTransaction().begin();
        owner = new User();
        owner.setEmail(ownerEmail);
        owner.setFirstName("undefined");
        owner.setLastName("undefined");
        owner.setPassword(ownerPassword);
        em.persist(owner);

        member = new User();
        member.setEmail(memberEmail);
        member.setFirstName("undefined");
        member.setLastName("undefined");
        member.setPassword(memberPassword);
        em.persist(member);

        nonMember = new User();
        nonMember.setEmail(nonMemberEmail);
        nonMember.setFirstName("undefined");
        nonMember.setLastName("undefined");
        nonMember.setPassword(nonMemberPassword);
        em.persist(nonMember);

        proj1 = ModelUtil.newProject(em, owner, "proj1", "proj1 description");
        em.getTransaction().commit();

        ClientConfig cc = new DefaultClientConfig();
        cc.getClasses().add(ProtobufProviders.ProtobufMessageBodyReader.class);
        cc.getClasses().add(ProtobufProviders.ProtobufMessageBodyWriter.class);

        URI uri;
        Client client;

        uri = UriBuilder.fromUri("http://localhost/users").port(port).build();

        client = Client.create(cc);
        client.addFilter(new HTTPBasicAuthFilter(ownerEmail, ownerPassword));
        usersResource = client.resource(uri);
        ownerInfo = get(usersResource, String.format("/%s", ownerEmail), UserInfo.class);

        uri = UriBuilder.fromUri(String.format("http://localhost/projects/%d", ownerInfo.getId())).port(port).build();
        ownerProjectsResource = client.resource(uri);
        uri = UriBuilder.fromUri(String.format("http://localhost/projects/%d/%d", ownerInfo.getId(), proj1.getId())).port(port).build();
        ownerProjectResource = client.resource(uri);

        client = Client.create(cc);
        client.addFilter(new HTTPBasicAuthFilter(memberEmail, memberPassword));
        memberInfo = get(usersResource, String.format("/%s", memberEmail), UserInfo.class);
        uri = UriBuilder.fromUri(String.format("http://localhost/projects/%d/%d", memberInfo.getId(), proj1.getId())).port(port).build();
        memberProjectsWebResource = client.resource(uri);

        // Add member
        ownerProjectResource.path("/members").post(memberEmail);

        client = Client.create(cc);
        client.addFilter(new HTTPBasicAuthFilter(nonMemberEmail, nonMemberPassword));
        nonMemberInfo = get(usersResource, String.format("/%s", nonMemberEmail), UserInfo.class);

        uri = UriBuilder.fromUri(String.format("http://localhost/projects/%d/%d", nonMemberInfo.getId(), proj1.getId())).port(port).build();
        nonMemberProjectsWebResource = client.resource(uri);

        execCommand("scripts/setup_testdata.sh", Long.toString(proj1.getId()));
    }

    @After
    public void tearDown() throws Exception {
    }

    /*
     * Basic tests
     */

    @Test
    public void addMember() throws Exception {
        ClientResponse response = nonMemberProjectsWebResource.path("/members").post(ClientResponse.class, nonMemberEmail);
        assertEquals(403, response.getStatus());

        response = memberProjectsWebResource.path("/members").post(ClientResponse.class, nonMemberEmail);
        assertEquals(204, response.getStatus());

        // Add again, verify the list is not increased
        int membersCount = get(ownerProjectResource, "/project_info", ProjectInfo.class).getMembersCount();
        assertEquals(3, membersCount);
        memberProjectsWebResource.path("/members").post(nonMemberEmail);
        assertEquals(membersCount, get(ownerProjectResource, "/project_info", ProjectInfo.class).getMembersCount());

        response = memberProjectsWebResource.path("/members").post(ClientResponse.class, "nonexisting@foo.com");
        assertEquals(404, response.getStatus());
    }

    @Test
    public void deleteMember() throws Exception {
        ClientResponse response = nonMemberProjectsWebResource.path(String.format("/members/%d", ownerInfo.getId())).delete(ClientResponse.class);
        assertEquals(403, response.getStatus());

        response = memberProjectsWebResource.path(String.format("/members/%d", ownerInfo.getId())).delete(ClientResponse.class);
        assertEquals(403, response.getStatus());
        int b = response.getEntityInputStream().read();
        assertTrue(b >= 0);

        assertEquals(2, get(ownerProjectResource, "/project_info", ProjectInfo.class).getMembersCount());
        response = ownerProjectResource.path(String.format("/members/%d", memberInfo.getId())).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        assertEquals(1, get(ownerProjectResource, "/project_info", ProjectInfo.class).getMembersCount());

        response = ownerProjectResource.path(String.format("/members/%d", memberInfo.getId())).delete(ClientResponse.class);
        assertEquals(404, response.getStatus());

        response = ownerProjectResource.path("/members/9999").delete(ClientResponse.class);
        assertEquals(404, response.getStatus());
    }

    @Test
    public void projectInfo() throws Exception {
        ProjectInfo projectInfo = get(ownerProjectResource, "/project_info", ProjectInfo.class);
        assertEquals("proj1", projectInfo.getName());

        ClientResponse response = memberProjectsWebResource.path("/project_info").get(ClientResponse.class);
        assertEquals(200, response.getStatus());

        response = memberProjectsWebResource.path("/project_info").put(ClientResponse.class, projectInfo);
        assertEquals(403, response.getStatus());

        response = nonMemberProjectsWebResource.path("/project_info").get(ClientResponse.class);
        assertEquals(403, response.getStatus());

        response = nonMemberProjectsWebResource.path("/project_info").put(ClientResponse.class, projectInfo);
        assertEquals(403, response.getStatus());

        ProjectInfo newProjectInfo = ProjectInfo.newBuilder()
            .mergeFrom(projectInfo)
            .setName("new name")
            .setDescription("new desc")
            .build();
        put(ownerProjectResource, "/project_info", newProjectInfo);

        projectInfo = get(ownerProjectResource, "/project_info", ProjectInfo.class);
        assertEquals("new name", projectInfo.getName());
        assertEquals("new desc", projectInfo.getDescription());
    }

    @Test
    public void changeProjectOwner() throws Exception {
        ownerProjectResource
                .path("change_owner")
                .queryParam("newOwnerId", Long.toString(memberInfo.getId()))
                .post();
        ProjectInfo projectInfo = get(ownerProjectResource, "/project_info", ProjectInfo.class);
        assertEquals(memberInfo.getId(), projectInfo.getOwner().getId());
    }

    @Test
    public void changeProjectOwnerToNonMember() throws Exception {
        ProjectInfo projectInfo = get(ownerProjectResource, "/project_info", ProjectInfo.class);
        UserInfo projectOwner = projectInfo.getOwner();

        ClientResponse response = ownerProjectResource
                .path("change_owner")
                .queryParam("newOwnerId", Long.toString(nonMemberInfo.getId()))
                .post(ClientResponse.class, null);

        assertEquals(403, response.getStatus());
        projectInfo = get(ownerProjectResource, "/project_info", ProjectInfo.class);
        assertEquals(projectOwner.getId(), projectInfo.getOwner().getId());
    }

    @Test
    public void changeProjectOwnerCapReached() throws Exception {
        ProjectInfo projectInfo = get(ownerProjectResource, "/project_info", ProjectInfo.class);
        UserInfo projectOwner = projectInfo.getOwner();

        // Create maximum amount of projects
        int maxProjectCount = server.getConfiguration().getMaxProjectCount();
        em.getTransaction().begin();
        for (int i = 0; i < maxProjectCount; ++i) {
            ModelUtil.newProject(em, member, "member_proj" + i, String.format("member_proj%d description", i));
        }
        em.getTransaction().commit();

        ClientResponse response = ownerProjectResource
                .path("change_owner")
                .queryParam("newOwnerId", Long.toString(memberInfo.getId()))
                .post(ClientResponse.class, null);

        assertEquals(403, response.getStatus());
        projectInfo = get(ownerProjectResource, "/project_info", ProjectInfo.class);
        assertEquals(projectOwner.getId(), projectInfo.getOwner().getId());
    }

    @Test
    public void deleteProject() throws Exception {
        assertEquals(1, get(ownerProjectsResource, "/", ProjectInfoList.class).getProjectsCount());

        ClientResponse response = memberProjectsWebResource.path("").delete(ClientResponse.class);
        assertEquals(403, response.getStatus());

        response = nonMemberProjectsWebResource.path("").delete(ClientResponse.class);
        assertEquals(403, response.getStatus());

        response = ownerProjectResource.path("").delete(ClientResponse.class);
        assertEquals(204, response.getStatus());

        assertEquals(0, get(ownerProjectsResource, "/", ProjectInfoList.class).getProjectsCount());
    }

    private XMLPropertyListConfiguration readPlistFromBundle(final String path) throws IOException {
        try (ZipFile file = new ZipFile(path)) {
            final Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final String entryName = entry.getName();
                if (!entryName.endsWith("Info.plist"))
                    continue;

                try {
                    XMLPropertyListConfiguration plist = new XMLPropertyListConfiguration();
                    plist.load(file.getInputStream(entry));
                    return plist;
                } catch (ConfigurationException e) {
                    throw new IOException("Failed to read Info.plist", e);
                }
            }

            // Info.plist not found
            throw new FileNotFoundException(String.format("Bundle %s didn't contain Info.plist", path));
        }
    }

    @Test
    public void uploadEngine() throws Exception {
        File f = File.createTempFile("test", ".suff");
        f.deleteOnExit();
        FileOutputStream out = new FileOutputStream(f);

        byte[] originalbundle = FileUtils.readFileToByteArray(new File("test_data/test.ipa"));
        out.write(originalbundle);
        out.close();
        ClientResponse resp = ownerProjectResource
                .path("/engine")
                .path("ios")
                .type(MediaType.APPLICATION_OCTET_STREAM_TYPE)
                .post(ClientResponse.class, new FileInputStream(f));
        assertEquals(200, resp.getStatus());

        String downloadpath = String.format("tmp/engine_root/%d/ios/Defold.ipa", proj1.getId());
        byte[] uploaded = FileUtils.readFileToByteArray(new File(downloadpath));
        assertArrayEquals(originalbundle, uploaded);

        ProjectInfo projectInfo = get(ownerProjectResource, "/project_info", ProjectInfo.class);
        String iOSUrl = projectInfo.getIOSExecutableUrl();
        String[] tmp = iOSUrl.split("/");
        String key = tmp[tmp.length-1];

        byte[] downloaded = ownerProjectResource
                .path("/engine")
                .path("ios")
                .path(key+".ipa")
                .accept(MediaType.APPLICATION_OCTET_STREAM_TYPE)
                .get(byte[].class);

        assertArrayEquals(originalbundle, downloaded);

        ClientResponse response = ownerProjectResource
                .path("/engine")
                .path("ios")
                .path("dummy_key.ipa")
                .accept(MediaType.APPLICATION_OCTET_STREAM_TYPE)
                .get(ClientResponse.class);
        assertEquals(403, response.getStatus());

        response = ownerProjectResource
                .path("/engine_manifest")
                .path("ios")
                .path("dummy_key.ipa")
                .accept("text/xml")
                .get(ClientResponse.class);
        assertEquals(403, response.getStatus());

        String manifestString = ownerProjectResource
                .path("/engine_manifest")
                .path("ios")
                .path(key)
                .accept("text/xml")
                .get(String.class);

        XMLPropertyListConfiguration downloadedmanifest = new XMLPropertyListConfiguration();
        downloadedmanifest.load(new StringReader(manifestString));

        XMLPropertyListConfiguration downloadedmanifest_item = (XMLPropertyListConfiguration) downloadedmanifest.getList("items").get(0);
        XMLPropertyListConfiguration downloadedmanifest_asset = (XMLPropertyListConfiguration) downloadedmanifest_item.getList("assets").get(0);

        URL url = new URL(downloadedmanifest_asset.getString("url"));
        byte[] downloadedFromManifest = IOUtils.toByteArray(url.openStream());
        assertArrayEquals(originalbundle, downloadedFromManifest);

        // Check that the bundle id from the downloaded bundle and the downloaded manifest match
        XMLPropertyListConfiguration downloadedbundleplist = readPlistFromBundle(downloadpath);
        String downloadedbundleid = downloadedbundleplist.getString("CFBundleIdentifier");
        String downloadedmanifest_bundleid = downloadedmanifest_item.getString("metadata.bundle-identifier");

        assertEquals(downloadedmanifest_bundleid, downloadedbundleid);
    }
}
