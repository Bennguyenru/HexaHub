# Copyright 2020-2022 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
# 
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
# 
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.

# Copyright 2020 The Defold Foundation
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
# 
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
# 
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.

#ALL="com.ibm.icu javax.activation javax.annotation javax.mail javax.persistence javax.servlet javax.xml org.apache.batik.css org.apache.batik.util.gui org.apache.batik.util org.eclipse.ant.core org.eclipse.compare.core org.eclipse.compare org.eclipse.core.commands org.eclipse.core.contenttype org.eclipse.core.databinding.observable org.eclipse.core.databinding.property org.eclipse.core.databinding org.eclipse.core.expressions org.eclipse.core.filebuffers org.eclipse.core.filesystem.java7 org.eclipse.core.filesystem.macosx org.eclipse.core.filesystem org.eclipse.core.jobs org.eclipse.core.net org.eclipse.core.resources org.eclipse.core.runtime.compatibility.registry org.eclipse.core.runtime.compatibility org.eclipse.core.runtime org.eclipse.core.variables org.eclipse.debug.core org.eclipse.e4.core.commands org.eclipse.e4.core.contexts org.eclipse.e4.core.di.extensions org.eclipse.e4.core.di org.eclipse.e4.core.services org.eclipse.e4.tools.emf.liveeditor org.eclipse.e4.tools.emf.ui.script.js org.eclipse.e4.tools.emf.ui org.eclipse.e4.tools.services org.eclipse.e4.tools.spy org.eclipse.e4.ui.bindings org.eclipse.e4.ui.css.core org.eclipse.e4.ui.css.swt.theme org.eclipse.e4.ui.css.swt org.eclipse.e4.ui.di org.eclipse.e4.ui.model.workbench org.eclipse.e4.ui.services org.eclipse.e4.ui.widgets org.eclipse.e4.ui.workbench.addons.swt org.eclipse.e4.ui.workbench.renderers.swt.cocoa org.eclipse.e4.ui.workbench.renderers.swt org.eclipse.e4.ui.workbench.swt org.eclipse.e4.ui.workbench3 org.eclipse.e4.ui.workbench org.eclipse.ecf.filetransfer org.eclipse.ecf.identity org.eclipse.ecf.provider.filetransfer.ssl org.eclipse.ecf.provider.filetransfer org.eclipse.ecf.ssl org.eclipse.ecf org.eclipse.emf.common org.eclipse.emf.databinding.edit org.eclipse.emf.databinding org.eclipse.emf.ecore.change org.eclipse.emf.ecore.xmi org.eclipse.emf.ecore org.eclipse.emf.edit org.eclipse.equinox.app org.eclipse.equinox.bidi org.eclipse.equinox.common org.eclipse.equinox.concurrent org.eclipse.equinox.ds org.eclipse.equinox.event org.eclipse.equinox.frameworkadmin.equinox org.eclipse.equinox.frameworkadmin org.eclipse.equinox.p2.artifact.repository org.eclipse.equinox.p2.core org.eclipse.equinox.p2.director.app org.eclipse.equinox.p2.director org.eclipse.equinox.p2.engine org.eclipse.equinox.p2.garbagecollector org.eclipse.equinox.p2.jarprocessor org.eclipse.equinox.p2.metadata.repository org.eclipse.equinox.p2.metadata org.eclipse.equinox.p2.operations org.eclipse.equinox.p2.publisher.eclipse org.eclipse.equinox.p2.publisher org.eclipse.equinox.p2.repository.tools org.eclipse.equinox.p2.repository org.eclipse.equinox.p2.touchpoint.eclipse org.eclipse.equinox.p2.touchpoint.natives org.eclipse.equinox.p2.transport.ecf org.eclipse.equinox.p2.ui.sdk org.eclipse.equinox.p2.ui org.eclipse.equinox.p2.updatesite org.eclipse.equinox.preferences org.eclipse.equinox.registry org.eclipse.equinox.security.macosx org.eclipse.equinox.security.ui org.eclipse.equinox.security org.eclipse.equinox.simpleconfigurator.manipulator org.eclipse.equinox.simpleconfigurator org.eclipse.equinox.util org.eclipse.help org.eclipse.jdt.compiler.apt org.eclipse.jdt.compiler.tool org.eclipse.jdt.core org.eclipse.jdt.debug org.eclipse.jdt.launching org.eclipse.jetty.continuation org.eclipse.jetty.http org.eclipse.jetty.io org.eclipse.jetty.server org.eclipse.jetty.util org.eclipse.jface.databinding org.eclipse.jface.text org.eclipse.jface org.eclipse.ltk.core.refactoring org.eclipse.ltk.ui.refactoring org.eclipse.osgi.compatibility.state org.eclipse.osgi.services org.eclipse.osgi org.eclipse.pde.build org.eclipse.pde.core org.eclipse.search org.eclipse.swt.cocoa.macosx.x86_64 org.eclipse.swt org.eclipse.team.core org.eclipse.team.ui org.eclipse.text org.eclipse.ui.cocoa org.eclipse.ui.console org.eclipse.ui.editors org.eclipse.ui.forms org.eclipse.ui.ide org.eclipse.ui.navigator.resources org.eclipse.ui.navigator org.eclipse.ui.net org.eclipse.ui.trace org.eclipse.ui.views.log org.eclipse.ui.views.properties.tabbed org.eclipse.ui.views org.eclipse.ui.workbench.texteditor org.eclipse.ui.workbench org.eclipse.ui org.eclipse.update.configurator org.hamcrest.core org.junit org.mozilla.javascript org.sat4j.core org.sat4j.pb org.slf4j.api org.w3c.css.sac org.w3c.dom.events org.w3c.dom.smil org.w3c.dom.svg"

for F in $ALL
do
    for REAL in `ls ~/eclipse/plugins/${F}_*.jar`
    do
        VERS=`echo $REAL | sed -ne 's/.*_//;s/\.v[0-9\-]*\.jar//;p'`
        LOC=`echo $REAL | sed -ne 's/\/[^/]*\/[^/]*/~/;p'`
        #        echo "install_file $LOC org.eclipse $F $VERS"
        echo "[org.eclipse/$F \"$VERS\"]"
    done
done
