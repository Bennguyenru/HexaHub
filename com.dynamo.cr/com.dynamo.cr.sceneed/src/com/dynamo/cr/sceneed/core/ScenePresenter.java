package com.dynamo.cr.sceneed.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;

import com.dynamo.cr.editor.core.ProjectProperties;
import com.dynamo.cr.sceneed.Activator;
import com.dynamo.cr.sceneed.core.ISceneView.IPresenter;
import com.dynamo.cr.sceneed.core.ISceneView.IPresenterContext;
import com.dynamo.cr.sceneed.core.operations.AddChildrenOperation;
import com.dynamo.cr.sceneed.core.operations.MoveChildrenOperation;
import com.dynamo.cr.sceneed.core.operations.RemoveChildrenOperation;
import com.dynamo.cr.sceneed.core.operations.SelectOperation;
import com.dynamo.cr.sceneed.core.util.NodeListTransfer;
import com.google.inject.Inject;
import com.google.protobuf.Message;

public class ScenePresenter implements IPresenter, IModelListener {

    @Inject private ISceneModel model;
    @Inject private ISceneView view;
    @Inject private INodeTypeRegistry nodeTypeRegistry;
    @Inject private ILoaderContext loaderContext;
    @Inject private IClipboard clipboard;

    private boolean simulating = false;
    private final Animator animator = new Animator();

    private class Animator implements Runnable {
        @Override
        public void run() {
            if (simulating) {
                view.refreshRenderView();
                // NOTE: This value is related to timerExec in
                // RenderView#requestPaint
                Display.getCurrent().timerExec(5, this);
            }
        }
    }

    private IStructuredSelection currentSelection;

    private void setSelection(IPresenterContext presenterContext, IStructuredSelection selection) {
        if (!sameSelection(this.currentSelection, selection)) {
            presenterContext.executeOperation(new SelectOperation(this.currentSelection, selection, presenterContext));
            this.currentSelection = selection;
        }
    }

    @Override
    public void onSelect(IPresenterContext presenterContext, IStructuredSelection selection) {
        setSelection(presenterContext, selection);
    }

    @Override
    public void onSelectAll(IPresenterContext presenterContext) {
        IStructuredSelection selection = new StructuredSelection(this.model.getRoot().getChildren());
        setSelection(presenterContext, selection);
    }

    @Override
    public void onRefresh() {
        this.view.setRoot(this.model.getRoot());
        this.view.refresh(this.model.getSelection(), this.model.isDirty());
    }

    @Override
    public final void onLoad(String type, InputStream contents) throws IOException, CoreException {
        INodeType nodeType = this.nodeTypeRegistry.getNodeTypeFromExtension(type);
        INodeLoader<Node> loader = nodeType.getLoader();
        try {
            Node node = loader.load(this.loaderContext, contents);
            this.model.setRoot(node);
        } catch (Exception e) {
            // Should never happen in production
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, type + " node could not be loaded: " + e.getMessage(), e));
        }
    }

    @Override
    public void onSave(OutputStream contents, IProgressMonitor monitor) throws IOException, CoreException {
        Node node = this.model.getRoot();
        INodeType nodeType = this.nodeTypeRegistry.getNodeTypeClass(node.getClass());
        INodeLoader<Node> loader = nodeType.getLoader();
        Message message = loader.buildMessage(this.loaderContext, node, monitor);
        SceneUtil.saveMessage(message, contents, monitor);
        this.model.clearDirty();
    }

    @Override
    public void onResourceChanged(IResourceChangeEvent event) throws CoreException {
        this.model.handleResourceChanged(event);
    }

    @Override
    public void onProjectPropertiesChanged(ProjectProperties properties) throws CoreException {

    }

    @Override
    public void rootChanged(Node root) {
        this.currentSelection = this.model.getSelection();
        this.view.setRoot(root);
    }

    @Override
    public void stateChanged(IStructuredSelection selection, boolean dirty) {
        this.currentSelection = this.model.getSelection();
        this.view.refresh(selection, dirty);
    }

    @Override
    public void onDeleteSelection(IPresenterContext presenterContext) {
        List<Node> nodes = selectionToNodeList(presenterContext);
        if (nodes.size() > 0) {
            presenterContext.executeOperation(new RemoveChildrenOperation(nodes, presenterContext));
        }
    }

    @Override
    public void onCopySelection(IPresenterContext presenterContext, ILoaderContext loaderContext, IProgressMonitor monitor) throws IOException, CoreException {
        List<Node> nodes = selectionToNodeList(presenterContext);
        if (nodes.size() > 0) {
            NodeListTransfer transfer = NodeListTransfer.getInstance();
            this.clipboard.setContents(new Object[] {nodes}, new Transfer[] {transfer});
        }
    }

    @Override
    public void onCutSelection(IPresenterContext presenterContext, ILoaderContext loaderContext, IProgressMonitor monitor) throws IOException, CoreException {
        List<Node> nodes = selectionToNodeList(presenterContext);
        if (nodes.size() > 0) {
            NodeListTransfer transfer = NodeListTransfer.getInstance();
            this.clipboard.setContents(new Object[] {nodes}, new Transfer[] {transfer});
            presenterContext.executeOperation(new RemoveChildrenOperation(nodes, presenterContext));
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onPasteIntoSelection(IPresenterContext context) throws IOException, CoreException {
        List<Node> originals = selectionToNodeList(context);
        if (originals.isEmpty()) {
            return;
        }

        NodeListTransfer transfer = NodeListTransfer.getInstance();
        List<Node> nodes = (List<Node>)this.clipboard.getContents(transfer);

        if (nodes != null && nodes.size() > 0) {
            // Always check for targets starting at the parent level
            Node target = originals.get(0).getParent();
            if (target == null && originals.size() == 1) {
                target = originals.get(0);
            }
            if (target != null) {
                target = NodeUtil.findAcceptingParent(target, nodes, context);
                // If a target could not be found, check at the selection level
                if (target == null && originals.size() == 1) {
                    target = NodeUtil.findAcceptingParent(originals.get(0), nodes, context);
                }
                if (target != null) {
                    context.executeOperation(new AddChildrenOperation("Paste", target, nodes, context));
                }
            }
        }
    }

    @Override
    public void onDNDMoveSelection(IPresenterContext presenterContext, List<Node> copies, Node targetParent) {
        List<Node> originals = selectionToNodeList(presenterContext);
        presenterContext.executeOperation(new MoveChildrenOperation(originals, targetParent, copies, presenterContext));
    }

    @Override
    public void onDNDDuplicateSelection(IPresenterContext presenterContext, List<Node> copies, Node targetParent) {
        presenterContext.executeOperation(new AddChildrenOperation("Duplicate", targetParent, copies, presenterContext));
    }

    private List<Node> selectionToNodeList(IPresenterContext presenterContext) {
        IStructuredSelection selection = presenterContext.getSelection();
        Object[] objects = selection.toArray();
        List<Node> nodes = new ArrayList<Node>(objects.length);
        for (Object object : objects) {
            nodes.add((Node)object);
        }
        return nodes;
    }

    @Override
    public void toogleSimulation() {
        simulating = !simulating;
        if (simulating) {
            Display.getCurrent().asyncExec(animator);
        } else {
            view.refreshRenderView();
        }
        view.setSimulating(simulating);
    }

    private boolean sameSelection(ISelection selectionA, ISelection selectionB) {
        if (selectionA == selectionB) {
            return true;
        }
        if (selectionA instanceof IStructuredSelection && selectionB instanceof IStructuredSelection) {
            IStructuredSelection a = (IStructuredSelection) selectionA;
            IStructuredSelection b = (IStructuredSelection) selectionB;
            int sA = a.size();
            int sB = b.size();
            if (sA != sB) {
                return false;
            }
            if (a.isEmpty() && b.isEmpty()) {
                return true;
            }
            @SuppressWarnings("unchecked") List<Object> lA = a.toList();
            @SuppressWarnings("unchecked") List<Object> lB = b.toList();
            Comparator<Object> comp = new Comparator<Object>() {
                @Override
                public int compare(Object o1, Object o2) {
                    return o2.hashCode() - o1.hashCode();
                }
            };
            Collections.sort(lA, comp);
            Collections.sort(lB, comp);
            Iterator<Object> itA = lA.iterator();
            Iterator<Object> itB = lB.iterator();
            while (itA.hasNext()) {
                Object oA = itA.next();
                Object oB = itB.next();
                if (oA != oB) {
                    return false;
                }
            }
            // Same objects in both selections
            return true;
        }
        // No idea what type of selection, assume inequality
        return false;
    }
}
