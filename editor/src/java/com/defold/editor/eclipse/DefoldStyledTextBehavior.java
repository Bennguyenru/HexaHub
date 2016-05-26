package com.defold.editor.eclipse;

import java.util.List;

import org.eclipse.fx.core.Util;
import org.eclipse.fx.ui.controls.styledtext.ActionEvent;
import org.eclipse.fx.ui.controls.styledtext.ActionEvent.ActionType;
import org.eclipse.fx.ui.controls.styledtext.StyledTextArea;
import org.eclipse.fx.ui.controls.styledtext.StyledTextLayoutContainer;
import org.eclipse.fx.ui.controls.styledtext.TextSelection;
import org.eclipse.fx.ui.controls.styledtext.VerifyEvent;
import org.eclipse.fx.ui.controls.styledtext.skin.StyledTextSkin.LineCell;
import org.eclipse.fx.ui.controls.styledtext.behavior.StyledTextBehavior;

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;

/**
 * Behavior for styled text
  Overrides for key event handlers until we can move to version 2.3.0
 */
public class DefoldStyledTextBehavior extends StyledTextBehavior{

	/**
	 * Create a new behavior
	 *
	 * @param styledText
	 *            the styled text control
	 */
	public DefoldStyledTextBehavior(StyledTextArea styledText) {
                super(styledText);
	}

	/**
	 * Handle key event
	 *
	 * @param arg0
	 *            the event
	 */
	protected void callActionForEvent(KeyEvent arg0) {
		if (arg0.getEventType() == KeyEvent.KEY_PRESSED) {
			defold_keyPressed(arg0);
		} else if (arg0.getEventType() == KeyEvent.KEY_TYPED) {
			defold_keyTyped(arg0);
		}
	}



	@SuppressWarnings("deprecation")
	private void defold_keyPressed(KeyEvent event) {
		VerifyEvent evt = new VerifyEvent(getControl(), getControl(), event);
		Event.fireEvent(getControl(), evt);

		// Bug in JavaFX who enables the menu when ALT is pressed
		if (Util.isMacOS()) {
			if (event.getCode() == KeyCode.ALT || event.isAltDown()) {
				event.consume();
			}
		}

		if (evt.isConsumed()) {
			event.consume();
			return;
		}

		int currentRowIndex = getControl().getContent().getLineAtOffset(getControl().getCaretOffset());

		final int offset = getControl().getCaretOffset();

		switch (event.getCode()) {
		case ENTER:
			if (getControl().getEditable()) {
				int line = getControl().getContent().getLineAtOffset(getControl().getCaretOffset());
				String lineContent = getControl().getContent().getLine(line);

				// FIXME Temp hack
				char[] chars = lineContent.toCharArray();
				String prefix = ""; //$NON-NLS-1$
				for (int i = 0; i < chars.length; i++) {
					if (chars[i] == ' ') {
						prefix += " "; //$NON-NLS-1$
					} else {
						break;
					}
				}

				String newLine = System.getProperty("line.separator"); //$NON-NLS-1$

				getControl().getContent().replaceTextRange(getControl().getCaretOffset(), 0, newLine + prefix);
				// listView.getSelectionModel().select(listView.getSelectionModel().getSelectedIndex()+1);
				getControl().setCaretOffset(offset + newLine.length() + prefix.length());
			}
			break;
		default:
			break;
		}
	}

        private void defold_keyTyped(KeyEvent event) {
            //we are handling this ourselves
	}

}
