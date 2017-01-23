package com.dynamo.cr.editor.handlers;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.internal.ide.actions.BuildUtilities;

import com.defold.extender.client.ExtenderClient;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.bundle.BundleHelper;
import com.dynamo.cr.client.IBranchClient;
import com.dynamo.cr.common.util.Exec;
import com.dynamo.cr.editor.Activator;
import com.dynamo.cr.editor.BobUtil;
import com.dynamo.cr.editor.core.EditorCorePlugin;
import com.dynamo.cr.editor.core.EditorUtil;
import com.dynamo.cr.editor.preferences.PreferenceConstants;
import com.dynamo.cr.engine.Engine;
import com.dynamo.cr.target.core.ITargetService;
import com.dynamo.cr.target.core.TargetPlugin;

// this suppression is for the usage of BuildUtilities in the bottom of this class
@SuppressWarnings("restriction")
public class LaunchHandler extends AbstractHandler {

    private static final String PARM_MSG = "com.dynamo.crepo.commands.launch.type";

    @Override
    public boolean isEnabled() {
        return Activator.getDefault().getBranchClient() != null;
    }

    IProject getActiveProject(ExecutionEvent event) {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor != null) {
            IEditorInput input = editor.getEditorInput();
            if (input instanceof IFileEditorInput) {
                IFileEditorInput file_input = (IFileEditorInput) input;
                final IProject project = file_input.getFile().getProject();
                return project;
            }
        }
        // else return first project
        return ResourcesPlugin.getWorkspace().getRoot().getProjects()[0];
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        String msg = event.getParameter(PARM_MSG);
        final boolean rebuild = msg != null && msg.equals("rebuild");

        final IPreferenceStore store = Activator.getDefault().getPreferenceStore();

        String buildError = null;
        boolean hasNativeExtensions = false;
        String exeName = "";

        if (store.getBoolean(PreferenceConstants.P_CUSTOM_APPLICATION)) {
            exeName = store.getString(PreferenceConstants.P_APPLICATION);
        } else {
            IBranchClient branchClient = Activator.getDefault().getBranchClient();
            String location = branchClient.getNativeLocation();
            File root = new File(location);
            boolean nativeExtEnabled = EditorUtil.isDev();
            hasNativeExtensions = nativeExtEnabled && ExtenderClient.hasExtensions(root);

            String platform = EditorCorePlugin.getPlatform();
            if (hasNativeExtensions) {
                File logFile = null;

                String buildPlatform = null;
                String sdkVersion = "";
                if (platform == "darwin" )
                    buildPlatform = "x86_64-osx";

                try {
                    EditorCorePlugin corePlugin = EditorCorePlugin.getDefault();
                    sdkVersion = corePlugin.getSha1();
                    if (sdkVersion == "NO SHA1") {
                        sdkVersion = "";
                    }
                    String serverURL = store.getString(PreferenceConstants.P_NATIVE_EXT_SERVER_URI);

                    ExtenderClient extender = new ExtenderClient(serverURL);
                    logFile = File.createTempFile("build_" + sdkVersion + "_", ".txt");
                    logFile.deleteOnExit();

                    // Store the engine one level above the content build since that folder gets removed during a distclean
                    File cacheDir = new File(location + File.separator + ".internal" + File.separator + "cache");
                    File buildDir = new File(location + File.separator + "build" + File.separator + buildPlatform);
                    buildDir.mkdirs();
                    File exe = new File(buildDir.getAbsolutePath() + File.separator + "dmengine");

                    String defaultName = FilenameUtils.getName(Engine.getDefault().getEnginePath());
                    List<File> allSource = ExtenderClient.getExtensionSource(root, buildPlatform);
                    BundleHelper.buildEngineRemote(extender, buildPlatform, sdkVersion, root, allSource, logFile, cacheDir, defaultName, exe);
                    exeName = exe.getAbsolutePath();
                } catch (IOException e) {
                    buildError = e.getMessage();
                } catch (CompileExceptionError e) {
                    buildError = e.getMessage();
                }
            }
            else {
                exeName = Engine.getDefault().getEnginePath();
            }

            if (!platform.contains("win32")) {
                try {
                    Exec.exec("chmod", "+x", exeName);
                } catch (IOException e) {
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID, String.format("'%s' could not be made executable.", exeName));
                }
            }
        }
        final File exe = new File(exeName);
        final String buildErrorLog = buildError;

        final IProject project = getActiveProject(event);
        final boolean remoteBuiltEngine = hasNativeExtensions;

        // save all editors depending on user preferences (this is set to true by default in plugin_customization.ini)
        BuildUtilities.saveEditors(null);

        Job job = new Job("Building") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {

                if (buildErrorLog != null) {
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID, buildErrorLog);
                }

                if (!exe.exists()) {
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID, String.format("Executable '%s' could not be found.", exe.getAbsolutePath()));
                }

                Map<String, String> args = new HashMap<String, String>();
                final boolean localBranch = store.getBoolean(PreferenceConstants.P_USE_LOCAL_BRANCHES);
                if (localBranch)
                    args.put("location", "local");
                else
                    args.put("location", "remote");

                HashMap<String, String> bobArgs = new HashMap<String, String>();
                boolean enableTextureProfiles = store.getBoolean(PreferenceConstants.P_TEXTURE_PROFILES);
                if (enableTextureProfiles) {
                    bobArgs.put("texture-profiles", "true");
                }

                BobUtil.putBobArgs(bobArgs, args);

                try {
                    project.build(rebuild ? IncrementalProjectBuilder.FULL_BUILD : IncrementalProjectBuilder.INCREMENTAL_BUILD,  "com.dynamo.cr.editor.builders.contentbuilder", args, monitor);
                    int severity = project.findMaxProblemSeverity(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
                    if (severity < IMarker.SEVERITY_ERROR) {
                        ITargetService targetService = TargetPlugin.getDefault().getTargetsService();
                        IBranchClient branchClient = Activator.getDefault().getBranchClient();
                        String location = branchClient.getNativeLocation();
                        String socksProxy = store.getString(PreferenceConstants.P_SOCKS_PROXY);
                        int socksProxyPort = store.getInt(PreferenceConstants.P_SOCKS_PROXY_PORT);
                        boolean runInDebugger = store.getBoolean(PreferenceConstants.P_RUN_IN_DEBUGGER);
                        boolean autoRunDebugger = store.getBoolean(PreferenceConstants.P_AUTO_RUN_DEBUGGER);
                        boolean quitOnEsc = store.getBoolean(PreferenceConstants.P_QUIT_ON_ESC);

                        String customApplication = null;
                        if (store.getBoolean(PreferenceConstants.P_CUSTOM_APPLICATION)) {
                            customApplication = store.getString(PreferenceConstants.P_APPLICATION);
                        } else if (remoteBuiltEngine) {
                            customApplication = exe.getAbsolutePath();
                        }

                        targetService.launch(customApplication, location, runInDebugger, autoRunDebugger, socksProxy,
                                socksProxyPort, Activator.SERVER_PORT, quitOnEsc);
                        return Status.OK_STATUS;
                    } else {
                        return Status.OK_STATUS;
                    }
                } catch (CoreException e) {
                    return e.getStatus();
                }
            }
        };
        job.setProperty(new QualifiedName("com.dynamo.cr.editor", "build"), true);
        job.schedule();
        return null;
    }
}
