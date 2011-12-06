package com.dynamo.cr.integrationtest;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.operations.DefaultOperationHistory;
import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.IUndoContext;
import org.eclipse.core.commands.operations.UndoContext;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.junit.Before;
import org.osgi.framework.Bundle;

import com.dynamo.cr.editor.core.EditorUtil;
import com.dynamo.cr.editor.core.ILogger;
import com.dynamo.cr.properties.IPropertyModel;
import com.dynamo.cr.sceneed.Activator;
import com.dynamo.cr.sceneed.core.IImageProvider;
import com.dynamo.cr.sceneed.core.IModelListener;
import com.dynamo.cr.sceneed.core.INodeTypeRegistry;
import com.dynamo.cr.sceneed.core.ISceneModel;
import com.dynamo.cr.sceneed.core.ISceneView;
import com.dynamo.cr.sceneed.core.ISceneView.ILoaderContext;
import com.dynamo.cr.sceneed.core.ISceneView.IPresenterContext;
import com.dynamo.cr.sceneed.core.Node;
import com.dynamo.cr.sceneed.core.SceneModel;
import com.dynamo.cr.sceneed.core.ScenePresenter;
import com.dynamo.cr.sceneed.ui.LoaderContext;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Singleton;

public abstract class AbstractSceneTest {
    private ISceneModel model;
    private ISceneView view;
    private ISceneView.IPresenter presenter;
    private IOperationHistory history;
    private IUndoContext undoContext;
    private IContainer contentRoot;
    private IProject project;
    private INodeTypeRegistry nodeTypeRegistry;
    private ILogger logger;
    private IImageProvider imageProvider;
    private IPresenterContext presenterContext;
    private ILoaderContext loaderContext;

    private Map<Node, Integer> updateCounts;
    private int selectCount;
    private int dirtyCount;
    private int cleanCount;

    protected class TestModule extends AbstractModule {
        @Override
        protected void configure() {
            bind(ISceneModel.class).to(SceneModel.class).in(Singleton.class);
            bind(ISceneView.class).toInstance(view);
            bind(ISceneView.IPresenter.class).to(ScenePresenter.class).in(Singleton.class);
            bind(IModelListener.class).to(ScenePresenter.class).in(Singleton.class);
            bind(ISceneView.ILoaderContext.class).to(LoaderContext.class).in(Singleton.class);
            bind(ISceneView.IPresenterContext.class).toInstance(presenterContext);
            bind(IOperationHistory.class).to(DefaultOperationHistory.class).in(Singleton.class);
            bind(IUndoContext.class).to(UndoContext.class).in(Singleton.class);
            bind(IContainer.class).toInstance(contentRoot);
            bind(INodeTypeRegistry.class).toInstance(nodeTypeRegistry);
            bind(ILogger.class).toInstance(logger);
            bind(IImageProvider.class).toInstance(imageProvider);
        }
    }

    @Before
    public void setup() throws CoreException, IOException {
        System.setProperty("java.awt.headless", "true");

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        this.project = workspace.getRoot().getProject("test");
        if (this.project.exists()) {
            this.project.delete(true, null);
        }
        this.project.create(null);
        this.project.open(null);

        IProjectDescription pd = this.project.getDescription();
        pd.setNatureIds(new String[] { "com.dynamo.cr.editor.core.crnature" });
        this.project.setDescription(pd, null);

        Bundle bundle = Platform.getBundle("com.dynamo.cr.integrationtest");
        Enumeration<URL> entries = bundle.findEntries("/test", "*", true);
        while (entries.hasMoreElements()) {
            URL url = entries.nextElement();
            IPath path = new Path(url.getPath()).removeFirstSegments(1);
            // Create path of url-path and remove first element, ie /test/sounds/ -> /sounds
            if (url.getFile().endsWith("/")) {
                this.project.getFolder(path).create(true, true, null);
            } else {
                InputStream is = url.openStream();
                IFile file = this.project.getFile(path);
                file.create(is, true, null);
                is.close();
            }
        }
        this.contentRoot = EditorUtil.findContentRoot(this.project.getFile("game.project"));

        this.view = mock(ISceneView.class);
        this.logger = mock(ILogger.class);
        doThrow(new RuntimeException()).when(this.logger).logException(any(Throwable.class));

        this.presenterContext = mock(IPresenterContext.class);
        this.imageProvider = mock(IImageProvider.class);

        this.nodeTypeRegistry = Activator.getDefault().getNodeTypeRegistry();

        Injector injector = Guice.createInjector(new TestModule());
        this.model = injector.getInstance(ISceneModel.class);
        this.presenter = injector.getInstance(ISceneView.IPresenter.class);
        this.history = injector.getInstance(IOperationHistory.class);
        this.undoContext = injector.getInstance(IUndoContext.class);
        this.loaderContext = injector.getInstance(ILoaderContext.class);

        ResourcesPlugin.getWorkspace().addResourceChangeListener(new IResourceChangeListener() {
            @Override
            public void resourceChanged(IResourceChangeEvent event) {
                try {
                    presenter.onResourceChanged(event);
                } catch (CoreException e) {
                    throw new RuntimeException(e);
                }
            }
        }, IResourceChangeEvent.POST_CHANGE);

        this.updateCounts = new HashMap<Node, Integer>();
        this.selectCount = 0;
        this.dirtyCount = 0;
        this.cleanCount = 0;
    }

    // Accessors

    protected ISceneModel getModel() {
        return this.model;
    }

    protected ISceneView getView() {
        return this.view;
    }

    protected ISceneView.IPresenter getPresenter() {
        return this.presenter;
    }

    protected IPresenterContext getPresenterContext() {
        return this.presenterContext;
    }

    protected ILoaderContext getLoaderContext() {
        return this.loaderContext;
    }

    protected IContainer getContentRoot() {
        return this.contentRoot;
    }

    protected INodeTypeRegistry getNodeTypeRegistry() {
        return this.nodeTypeRegistry;
    }

    // Helpers

    protected void undo() throws ExecutionException {
        this.history.undo(this.undoContext, null, null);
    }

    protected void redo() throws ExecutionException {
        this.history.redo(this.undoContext, null, null);
    }

    protected void verifyUpdate(Node node) {
        verifyUpdate(node, 1);
    }

    protected void verifyUpdate(Node node, int times) {
        Integer count = this.updateCounts.get(node);
        if (count == null) {
            count = times;
        } else {
            count = count + times;
        }
        this.updateCounts.put(node, count);
        verify(this.view, times(count.intValue())).updateNode(node);
    }

    protected void verifySelection() {
        ++this.selectCount;
        verify(this.view, times(this.selectCount)).updateSelection(any(IStructuredSelection.class));
    }

    protected void verifyNoSelection() {
        verify(this.view, times(this.selectCount)).updateSelection(any(IStructuredSelection.class));
    }

    protected void verifyDirty() {
        ++this.dirtyCount;
        verify(this.view, times(this.dirtyCount)).setDirty(true);
    }

    protected void verifyNoDirty() {
        verify(this.view, times(this.dirtyCount)).setDirty(true);
    }

    protected void verifyClean() {
        ++this.cleanCount;
        verify(this.view, times(this.cleanCount)).setDirty(false);
    }

    protected void verifyNoClean() {
        verify(this.view, times(this.cleanCount)).setDirty(false);
    }

    @SuppressWarnings("unchecked")
    protected void setNodeProperty(Node node, Object id, Object value) {
        IPropertyModel<? extends Node, ISceneModel> propertyModel = (IPropertyModel<? extends Node, ISceneModel>)node.getAdapter(IPropertyModel.class);
        this.model.executeOperation(propertyModel.setPropertyValue(id, value));
    }

    @SuppressWarnings("unchecked")
    protected IStatus getNodePropertyStatus(Node node, Object id) {
        IPropertyModel<? extends Node, ISceneModel> propertyModel = (IPropertyModel<? extends Node, ISceneModel>)node.getAdapter(IPropertyModel.class);
        return propertyModel.getPropertyStatus(id);
    }

    protected void assertNodePropertyStatus(Node node, Object id, int severity, String message) {
        IStatus status = getNodePropertyStatus(node, id);
        assertTrue(testStatus(status, severity, message));
    }

    private boolean testStatus(IStatus status, int severity, String message) {
        if (status.isMultiStatus()) {
            for (IStatus child : status.getChildren()) {
                if (testStatus(child, severity, message)) {
                    return true;
                }
            }
            return false;
        } else {
            return status.getSeverity() == severity && (message == null || message.equals(status.getMessage()));
        }
    }

}
