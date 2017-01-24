package com.dynamo.cr.target.bundle;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

public class BundleGenericDialog extends TitleAreaDialog implements
        IBundleGenericView {

    public interface IPresenter {
        public void start();
        public void releaseModeSelected(boolean selection);
        public void releaseModeSelected(boolean selection, boolean validate);
        public void generateReportSelected(boolean selection);
        public void generateReportSelected(boolean selection, boolean validate);
        public void publishLiveUpdateSelected(boolean selection);
    }

    private Button packageApplication;
    
    private IPresenter presenter;
    private Button releaseMode;
    private Button generateReport;
    private Button publishLiveUpdate;
    private String title = "";

    private static boolean persistentReleaseMode = false;
    private static boolean persistentGenerateReport = false;

    public BundleGenericDialog(Shell parentShell) {
        super(parentShell);
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE);
    }

    @Override
    public void setPresenter(IPresenter presenter) {
        this.presenter = presenter;
    }

    @Override
    public void setWarningMessage(String msg) {
        setMessage(msg, IMessageProvider.WARNING);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        getShell().setText(this.title); //$NON-NLS-1$
        super.setTitle(title); //$NON-NLS-1$
        setMessage(Messages.BundleGenericPresenter_DIALOG_MESSAGE);
        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        GridLayout containerLayout = new GridLayout(3, false);
        containerLayout.marginRight = 6;
        containerLayout.marginLeft = 6;
        containerLayout.marginTop = 10;
        container.setLayout(containerLayout);
        container.setLayoutData(new GridData(GridData.FILL_BOTH));

        releaseMode = new Button(container, SWT.CHECK);
        releaseMode.setText("Release mode");
        releaseMode.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        
        if (persistentReleaseMode == true) {
            releaseMode.setSelection(persistentReleaseMode);
            presenter.releaseModeSelected(persistentReleaseMode, false);
        }
        releaseMode.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                persistentReleaseMode = releaseMode.getSelection();
                presenter.releaseModeSelected(persistentReleaseMode);
            }
        });

        generateReport = new Button(container, SWT.CHECK);
        generateReport.setText("Generate build report");
        generateReport.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        if (persistentGenerateReport == true) {
            generateReport.setSelection(persistentGenerateReport);
            presenter.generateReportSelected(persistentGenerateReport, false);
        }
        generateReport.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                persistentGenerateReport = generateReport.getSelection();
                presenter.generateReportSelected(persistentGenerateReport);
            }
        });
        
        publishLiveUpdate = new Button(container, SWT.CHECK);
        publishLiveUpdate.setText("Publish LiveUpdate content");
        publishLiveUpdate.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        publishLiveUpdate.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                presenter.publishLiveUpdateSelected(publishLiveUpdate.getSelection());
            }
        });
        
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        packageApplication = createButton(parent, IDialogConstants.OK_ID, "Package", true); //$NON-NLS-1$
    }

    @Override
    protected Point getInitialSize() {
        return new Point(500, 270);
    }

    @Override
    public void create() {
        super.create();
        presenter.start();
    }

    @Override
    public void setEnabled(boolean enabled) {
        packageApplication.setEnabled(enabled);
    }

    @Override
    public void setTitle(String title) {
        this.title = title;
        super.setTitle(title);
    }

    public static void main(String[] args) {
        Display display = Display.getDefault();
        BundleGenericDialog dialog = new BundleGenericDialog(new Shell(display));
        dialog.setPresenter(new BundleGenericPresenter(dialog));
        dialog.open();
    }

}
