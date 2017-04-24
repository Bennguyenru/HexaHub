package com.dynamo.cr.server.resources.v2;

import com.dynamo.cr.protocol.proto.Protocol;
import com.dynamo.cr.server.ServerException;
import com.dynamo.cr.server.clients.magazine.MagazineClient;
import com.dynamo.cr.server.model.AppStoreReference;
import com.dynamo.cr.server.model.Project;
import com.dynamo.cr.server.model.Screenshot;
import com.dynamo.cr.server.model.SocialMediaReference;
import com.dynamo.cr.server.resources.BaseResource;
import com.dynamo.cr.server.resources.mappers.ProjectSiteMapper;
import com.dynamo.cr.server.services.ProjectService;
import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.FormDataParam;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;

@Path("/v2/projects/{project}/site/")
public class ProjectSitesResource extends BaseResource {

    @Inject
    private ProjectService projectService;

    @Inject
    private MagazineClient magazineClient;

    @GET
    public Protocol.ProjectSite getProjectSite(@PathParam("project") Long projectId) throws Exception {
        Project project = projectService.find(projectId)
                .orElseThrow(() -> new ServerException(String.format("No such project %s", projectId)));

        if (project.getProjectSite() == null || !project.getProjectSite().isPublicSite()) {
            if (!projectService.isProjectMember(getUser(), project)) {
                throw new ServerException("No access to project site");
            }
        }

        return ProjectSiteMapper.map(getUser(), project, magazineClient);
    }

    @PUT
    @RolesAllowed(value = {"member"})
    public void updateProjectSite(@PathParam("project") Long projectId, Protocol.ProjectSite projectSite) {
        projectService.updateProjectSite(projectId, projectSite);
    }

    @POST
    @RolesAllowed(value = {"member"})
    @Path("app_store_references")
    public void addAppStoreReference(@PathParam("project") Long projectId,
                                     Protocol.NewAppStoreReference newAppStoreReference) {
        projectService.addAppStoreReference(
                projectId,
                new AppStoreReference(newAppStoreReference.getLabel(), newAppStoreReference.getUrl()));
    }

    @DELETE
    @RolesAllowed(value = {"member"})
    @Path("app_store_references/{appStoreReferenceId}")
    public void deleteAppStoreReference(@PathParam("project") Long projectId, @PathParam("appStoreReferenceId") Long id) {
        projectService.deleteAppStoreReference(projectId, id);
    }

    @POST
    @RolesAllowed(value = {"member"})
    @Path("screenshots")
    public void addScreenshot(@PathParam("project") Long projectId, Protocol.NewScreenshot newScreenshot) {
        projectService.addScreenshot(
                projectId,
                new Screenshot(newScreenshot.getUrl(), Screenshot.MediaType.valueOf(newScreenshot.getMediaType().name()))
        );
    }

    @POST
    @RolesAllowed(value = {"member"})
    @Path("screenshots/images")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public void addScreenshotImage(@PathParam("project") Long projectId,
                                   @FormDataParam("file") InputStream file,
                                   @FormDataParam("file") FormDataContentDisposition fileInfo) throws Exception {
        projectService.addScreenshotImage(getUser().getEmail(), projectId, fileInfo.getFileName(), file);
    }

    @DELETE
    @RolesAllowed(value = {"member"})
    @Path("screenshots/{screenshotId}")
    public void deleteScreenshot(@PathParam("project") Long projectId, @PathParam("screenshotId") Long id) throws Exception {
        projectService.deleteScreenshot(getUser(), projectId, id);
    }

    @PUT
    @RolesAllowed(value = {"member"})
    @Path("screenshots/order")
    public void orderScreenshots(@PathParam("project") Long projectId, Protocol.ScreenshotSortOrderRequest screenshotSortOrderRequest) throws Exception {
        projectService.orderScreenshots(projectId, screenshotSortOrderRequest.getScreenshotIdsList());
    }

    @PUT
    @RolesAllowed(value = {"member"})
    @Path("playable")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public void putPlayable(@PathParam("project") Long projectId,
                            @FormDataParam("file") InputStream file,
                            @FormDataParam("file") FormDataContentDisposition fileInfo) throws Exception {
        projectService.uploadPlayableFiles(getUser().getEmail(), projectId, file);
    }

    @POST
    @RolesAllowed(value = {"member"})
    @Path("cover_image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public void addCoverImage(@PathParam("project") Long projectId,
                              @FormDataParam("file") InputStream file,
                              @FormDataParam("file") FormDataContentDisposition fileInfo) throws Exception {
        projectService.addCoverImage(getUser().getEmail(), projectId, fileInfo.getFileName(), file);
    }

    @POST
    @RolesAllowed(value = {"member"})
    @Path("store_front_image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public void addStoreFrontImage(@PathParam("project") Long projectId,
                                   @FormDataParam("file") InputStream file,
                                   @FormDataParam("file") FormDataContentDisposition fileInfo) throws Exception {
        projectService.addStoreFrontImage(getUser().getEmail(), projectId, fileInfo.getFileName(), file);
    }

    @POST
    @RolesAllowed(value = {"member"})
    @Path("playable_image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public void addPlayableImage(@PathParam("project") Long projectId,
                                 @FormDataParam("file") InputStream file,
                                 @FormDataParam("file") FormDataContentDisposition fileInfo) throws Exception {
        projectService.addPlayableImage(getUser().getEmail(), projectId, fileInfo.getFileName(), file);
    }

    @POST
    @RolesAllowed(value = {"member"})
    @Path("attachment")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public void addAttachment(@PathParam("project") Long projectId,
                              @FormDataParam("file") InputStream file,
                              @FormDataParam("file") FormDataContentDisposition fileInfo) throws Exception {
        projectService.addAttachment(getUser().getEmail(), projectId, fileInfo.getFileName(), file);
    }

    @POST
    @RolesAllowed(value = {"member"})
    @Path("social_media_references")
    public void addSocialMediaReference(@PathParam("project") Long projectId,
                                        Protocol.NewSocialMediaReference newSocialMediaReference) {
        projectService.addSocialMediaReference(
                projectId,
                new SocialMediaReference(newSocialMediaReference.getLabel(), newSocialMediaReference.getUrl()));
    }

    @DELETE
    @RolesAllowed(value = {"member"})
    @Path("social_media_references/{socialMediaReferenceId}")
    public void deleteSocialMediaReference(@PathParam("project") Long projectId,
                                           @PathParam("socialMediaReferenceId") Long id) {
        projectService.deleteSocialMediaReference(projectId, id);
    }

    @PUT
    @RolesAllowed(value = {"user"})
    @Path("like")
    public Protocol.ProjectLikeResponse like(@PathParam("project") Long projectId) {
        int numberOfLikes = projectService.likeProjectSite(projectId, getUser());
        return Protocol.ProjectLikeResponse.newBuilder().setNumberOfLikes(numberOfLikes).build();
    }

    @PUT
    @RolesAllowed(value = {"user"})
    @Path("unlike")
    public Protocol.ProjectLikeResponse unlike(@PathParam("project") Long projectId) {
        int numberOfLikes = projectService.unlikeProjectSite(projectId, getUser());
        return Protocol.ProjectLikeResponse.newBuilder().setNumberOfLikes(numberOfLikes).build();
    }
}
