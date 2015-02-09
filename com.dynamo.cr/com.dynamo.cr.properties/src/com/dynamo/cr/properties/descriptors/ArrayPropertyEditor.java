package com.dynamo.cr.properties.descriptors;

import java.util.Arrays;

import org.eclipse.core.commands.operations.IUndoableOperation;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import com.dynamo.cr.editor.core.operations.IMergeableOperation;
import com.dynamo.cr.editor.core.operations.IMergeableOperation.Type;
import com.dynamo.cr.properties.IPropertyEditor;
import com.dynamo.cr.properties.IPropertyModel;
import com.dynamo.cr.properties.IPropertyObjectWorld;
import com.dynamo.cr.properties.PropertyUtil;
import com.dynamo.cr.properties.util.NumberUtil;

public class ArrayPropertyEditor<V, T, U extends IPropertyObjectWorld> implements IPropertyEditor<T, U>, Listener {

    private SpinnerText[] spinnerFields;
    private Composite composite;
    private IPropertyModel<T, U>[] models;
    private String[] oldValue;
    private int count;
    private ArrayPropertyDesc<V, T, U> propertyDesc;
    public ArrayPropertyEditor(Composite parent, int count, ArrayPropertyDesc<V, T, U> propertyDesc ) {
        this.count = count;
        this.propertyDesc = propertyDesc;
        composite = new Composite(parent, SWT.NONE);
        composite.setBackground(parent.getBackground());
        GridLayout layout = new GridLayout(count, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        GridData gd = new GridData();
        gd.widthHint = 54;

        spinnerFields = new SpinnerText[count];
        for (int i = 0; i < count; i++) {
            SpinnerText t = createText(composite);
            t.setLayoutData(gd);
            spinnerFields[i] = t;
        }
        oldValue = new String[count];
    }

    @Override
    public void dispose() {
    }

    SpinnerText createText(Composite parent) {
        SpinnerText text = new SpinnerText(parent, SWT.BORDER, false);
        text.getText().addListener(SWT.KeyDown, this);
        text.getText().addListener(SWT.FocusOut, this);
        text.getText().addListener(SWT.DefaultSelection, this);
        text.getText().addFocusListener(new com.dynamo.cr.properties.util.SelectAllOnFocus());
        return text;
    }

    @Override
    public Control getControl() {
        return composite;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void refresh() {
        boolean editable = models[0].isPropertyEditable(propertyDesc.getId());
        getControl().setEnabled(editable);

        boolean[] equal = new boolean[count];
        for (int i = 0; i < count; ++i) {
            equal[i] = true;
        }

        V firstValue = (V) models[0].getPropertyValue(propertyDesc.getId());
        for (int i = 1; i < models.length; ++i) {
            V value = (V) models[i].getPropertyValue(propertyDesc.getId());
            for (int j = 0; j < count; ++j) {
                if (!propertyDesc.isComponentEqual(firstValue, value, j)) {
                    equal[j] = false;
                }
            }
        }

        double[] array = propertyDesc.valueToArray(firstValue);
        for (int i = 0; i < count; ++i) {
            String s;
            if (equal[i]) {
                s = NumberUtil.formatDouble(array[i]);
            } else {
                s = "";
            }

            // Avoid setText unless necessary since it messes with selections.
            if (!spinnerFields[i].getText().getText().equals(s)) {
                String old = spinnerFields[i].getText().getText();
                spinnerFields[i].getText().setText(s);
            }
            oldValue[i] = s;
        }
    }

    @Override
    public void setModels(IPropertyModel<T, U>[] models) {
        this.models = models;
    }

    @Override
    public void handleEvent(Event event) {

        double[] newValue = new double[count];
        String[] newStringValue = new String[count];
        try {
            for (int i = 0; i < newValue.length; i++) {
                String s = this.spinnerFields[i].getText().getText();
                newStringValue[i] = s;
                // NOTE: Treat "" as 0
                if (s.length() != 0) {
                    newValue[i] = NumberUtil.parseDouble(s);
                }
            }
        } catch (NumberFormatException e) {
            return;
        }

        boolean releaseFocus = false;
        boolean updateValue = false;
        IMergeableOperation.Type type = Type.OPEN;
        if (event.type == SWT.KeyDown && (event.character == '\r' || event.character == '\n')) {
            updateValue = true;
            releaseFocus = true;
        } else if (event.type == SWT.FocusOut && !Arrays.equals(newStringValue, oldValue)) {
            updateValue = true;
        } else if (event.type == SWT.DefaultSelection) {
            updateValue = true;
            type = Type.INTERMEDIATE;
        }

        if (updateValue) {
            Double[] diff = new Double[count];

            for (int i = 0; i < diff.length; i++) {
                if (event.widget == this.spinnerFields[i].getText()) {
                    diff[i] = newValue[i];
                }
            }

            IUndoableOperation combinedOperation = PropertyUtil.setProperty(models, propertyDesc.getId(), diff, type);
            if (combinedOperation != null)
                models[0].getCommandFactory().execute(combinedOperation, models[0].getWorld());
        }

        oldValue = newStringValue;

        // When pressing the return key, give up focus. (DEF-923). Unfortunately there seems to be no way of
        // removing focus; it has to be moved elsewhere. So just force it to the parent to avoid users entering text
        // elsewhere.
        if (releaseFocus) {
            composite.forceFocus();
        }
    }
}
