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

package com.dynamo.bob.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.Arrays;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.junit.Test;
import org.junit.Assert;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import com.dynamo.gamesys.proto.Gui;
import com.dynamo.gamesys.proto.Gui.SceneDesc.LayoutDesc;
import com.dynamo.gamesys.proto.Gui.NodeDesc;
import com.google.protobuf.Message;
import com.dynamo.bob.Project;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.ProtoParams;
import com.dynamo.bob.ProtoBuilder;
import com.dynamo.bob.ClassLoaderScanner;

public class GuiBuilderTest extends AbstractProtoBuilderTest {

    private ClassLoaderScanner scanner = null;


    public GuiBuilderTest() throws IOException {
        this.scanner = Project.createClassLoaderScanner();
        registerProtoBuilderNames();
    }

    private boolean nodeExists(Gui.SceneDesc scene, String nodeId)
    {
        for (int n = 0; n < scene.getNodesCount(); n++) {
            Gui.NodeDesc node = scene.getNodes(n);
            if (node.getId().equals(nodeId)) {
                return true;
            }
        }

        return false;
    }

    private void registerProtoBuilderNames() {
        Set<String> classNames = this.scanner.scan("com.dynamo.bob.pipeline");

        for (String className : classNames) {
            // Ignore TexcLibrary to avoid it being loaded and initialized
            boolean skip = className.startsWith("com.dynamo.bob.TexcLibrary");
            if (!skip) {
                try {
                    Class<?> klass = Class.forName(className, true, this.scanner.getClassLoader());
                    BuilderParams builderParams = klass.getAnnotation(BuilderParams.class);
                    if (builderParams != null) {
                        ProtoParams protoParams = klass.getAnnotation(ProtoParams.class);
                        if (protoParams != null) {
                            ProtoBuilder.addMessageClass(builderParams.outExt(), protoParams.messageClass());

                            for (String ext : builderParams.inExts()) {
                                Class<?> inputKlass = protoParams.srcClass();
                                if (inputKlass != null) {
                                    ProtoBuilder.addMessageClass(ext, protoParams.srcClass());
                                }
                            }
                        }
                    }

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private StringBuilder createGui() {
        StringBuilder src = new StringBuilder();
        return src;
    }

    private Gui.SceneDesc buildGui(StringBuilder src, String path) throws Exception {
        return (Gui.SceneDesc)build(path, src.toString()).get(0);
    }

    private void addBoxNode(StringBuilder src, String id, String parent) {
        src.append("nodes {\n");
        src.append("  type: TYPE_BOX\n");
        src.append("  id: \""+id+"\"\n");
        src.append("  parent: \""+parent+"\"\n");
        src.append("}\n");
    }

    private void addTextNode(StringBuilder src, String id, String parent, String text) {
        src.append("nodes {\n");
        src.append("  type: TYPE_TEXT\n");
        src.append("  id: \""+id+"\"\n");
        src.append("  parent: \""+parent+"\"\n");
        src.append("  text: \""+text+"\"\n");
        src.append("}\n");
    }

    private void addTemplateNode(StringBuilder src, String id, String parent, String template) {
        src.append("nodes {\n");
        src.append("  type: TYPE_TEMPLATE\n");
        src.append("  id: \""+id+"\"\n");
        src.append("  parent: \""+parent+"\"\n");
        src.append("  template: \""+template+"\"\n");
        src.append("}\n");
    }

    private void startOverridedNode(StringBuilder src, String type, String id, String parent, List<Integer> overrides) {
        src.append("nodes {\n");
        src.append("  type: "+type+"\n");
        src.append("  id: \""+id+"\"\n");
        src.append("  parent: \""+parent+"\"\n");
        for(int num : overrides) {
            src.append("  overridden_fields: "+num+"\n");
        }
    }

    private void finishOverridedNode(StringBuilder src) {
        src.append("}\n");
    }

    private void startLayout(StringBuilder src, String name) {
        src.append("layouts {\n");
        src.append("  name: \""+name+"\"\n");
    }

    private void finishLayout(StringBuilder src) {
        src.append("}\n");
    }

    @Test
    public void test() throws Exception {
        // Kept empty as a future working template
    }

    // https://github.com/defold/defold/issues/6151
    @Test
    public void testDefaultLayoutOverridesPriorityOverTemplateValues() throws Exception {
        StringBuilder src = createGui();
        addBoxNode(src, "box", "");
        addTextNode(src, "text", "box", "templateText");
        addFile("/template.gui", src.toString());

        src = createGui();
        addBoxNode(src, "box", "");
        addTemplateNode(src, "template", "box", "/template.gui");

        // override text in default layout
        startOverridedNode(src, "TYPE_TEXT", "template/text", "template/box", Arrays.asList(8));
        src.append("  text: \"defaultText\"\n");
        finishOverridedNode(src);

        startLayout(src, "Landscape");
        addBoxNode(src, "template/box", "");

        // override clipping_visible in Landscape layout
        startOverridedNode(src, "TYPE_TEXT", "template/text", "template/box", Arrays.asList(28));
        src.append("  clipping_visible: false\n");
        src.append("  text: \"defaultText\"\n");
        finishOverridedNode(src);

        finishLayout(src);
        Gui.SceneDesc gui = buildGui(src, "/test.gui");
        for(LayoutDesc layout : gui.getLayoutsList()) {
            if (layout.getName().equals("Landscape")) {
                for(NodeDesc node : layout.getNodesList()) {
                    if (node.getId().equals("template/text")) {
                        Assert.assertFalse(node.getClippingVisible());
                        Assert.assertEquals(node.getText(), "defaultText");
                        return;
                    }
                }
            }
        }
        Assert.assertFalse("Can't find node!", true);
    }
}
