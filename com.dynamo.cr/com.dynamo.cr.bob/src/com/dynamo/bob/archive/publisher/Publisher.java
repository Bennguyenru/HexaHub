// Copyright 2020 The Defold Foundation
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob.archive.publisher;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.fs.IResource;

public abstract class Publisher {

    private final PublisherSettings settings;
    private final Map<String, File> entries = new HashMap<String, File>();
    protected String platform = "";

    public Publisher(PublisherSettings settings) {
        this.settings = settings;
    }

    public String getManifestPublicKey() {
        return this.settings.getManifestPublicKey();
    }
    
    public String getManifestPrivateKey() {
        return this.settings.getManifestPrivateKey();
    }

    public String getSupportedVersions() {
        return this.settings.getSupportedVersions();
    }

    protected final PublisherSettings getPublisherSettings() {
        return this.settings;
    }

    protected final Map<String, File> getEntries() {
        return this.entries;
    }
    
    public String getPlatform() {
        return this.platform;
    }
    
    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public abstract void Publish() throws CompileExceptionError;

    public List<IResource> getOutputs(IResource input) {
        List<IResource> outputs = new ArrayList<IResource>();
        return outputs;
    }

    public List<InputStream> getOutputResults() {
        List<InputStream> outputs = new ArrayList<InputStream>();
        return outputs;
    }

    public final void AddEntry(String hexDigest, File fhandle) {
        this.entries.put(hexDigest, fhandle);
    }

}
