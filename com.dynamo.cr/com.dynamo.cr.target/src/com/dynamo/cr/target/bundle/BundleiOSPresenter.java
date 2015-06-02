package com.dynamo.cr.target.bundle;

import java.io.File;
import java.io.IOException;

import javax.inject.Inject;

import com.dynamo.cr.target.sign.IIdentityLister;

public class BundleiOSPresenter implements BundleiOSDialog.IPresenter {

    private IBundleiOSView view;
    private IIdentityLister lister;
    private String profile = "";
    private String identity = "";
    private String[] identities = new String[0];
    private boolean releaseMode;

    @Inject
    public BundleiOSPresenter(IBundleiOSView view, IIdentityLister lister) {
        this.view = view;
        this.lister = lister;
    }

    @Override
    public void start() {
        try {
            identities = lister.listIdentities();
            view.setIdentities(identities);
            validate();
        } catch (IOException e) {
            view.setErrorMessage(e.getMessage());
        }
    }

    private void validate() {
        // Set defaults
        view.setEnabled(false);
        view.setMessage(Messages.BundleiOSPresenter_DIALOG_MESSAGE);

        if (identities.length == 0) {
            view.setErrorMessage(Messages.BundleiOSPresenter_NO_IDENTITY_FOUND);
            return;
        }

        if (identity.equals("") || profile.equals("")) {
            // All values not set yet. Just return
            return;
        }

        if (!new File(profile).isFile()) {
            view.setErrorMessage(Messages.BundleiOSPresenter_PROFILE_NOT_FOUND);
            return;
        }

        // Only warnings after this point
        view.setEnabled(true);
    }

    @Override
    public void setIdentity(String identity) {
        this.identity = identity;
        validate();
    }

    public String getIdentity() {
        return identity;
    }

    @Override
    public void setProvisioningProfile(String profile) {
        this.profile = profile;
        validate();
    }

    public String getProvisioningProfile() {
        return profile;
    }

    public boolean isReleaseMode() {
    	return releaseMode;
    }

    @Override
    public void releaseModeSelected(boolean selection) {
        this.releaseMode = selection;
        validate();
    }

}
