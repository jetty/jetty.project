// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.pde.launch.ui.tabs;

import java.io.File;

import org.eclipse.jetty.osgi.pde.launch.JettyLauncherMessages;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * A dialog that displays the content of jetty.xml
 * <p>
 * If someone knows how to open a standard editor provided by the IDE
 * inside a popup from a modal dialog please let us know:
 * that would be so much better than the simple SWT Text widget!
 * </p>
 */
public class JettyXmlEditDialog extends Dialog
{
    
    /** The text area to edit the contents of jetty.xml file */
    protected Text _textField;
    private Button _okButton;
    /** Status label. */
    private Label _statusLabel;
    
    private boolean _isDirty = false;;
    private String _lastValidatedString;
    private String _jettyHome;
    
    /**
     * 
     * @param scope The scope in which the variable is created/edited
     * @param parentShell The parent shell
     * @param initValue The initial name of the variable
     * before the edit. Or null if it is a bran new variable.
     */
    public JettyXmlEditDialog(Shell parentShell, String currentJettyXml)
    {
        super(parentShell);
        setShellStyle(SWT.SHELL_TRIM
                | getDefaultOrientation() | SWT.RESIZE);
        setBlockOnOpen(true);
        _lastValidatedString = currentJettyXml;
    }

    /**
     * @return the "OK" button
     */
    public Button getGoForItButton() {
        return _okButton;
    }
    
    /**
     * @return The new jetty.xml contents or null if there was no modification.
     */
    protected String getNewJettyXml() {
        return _isDirty ? _lastValidatedString : null;
    }

    /**
     * Invoked to update UI based on user input.
     *
     */
    protected void validateInput() {
        String text = _textField.getText();
        if (text.equals(_lastValidatedString)) {
            return;
        }
        _isDirty = true;
        _lastValidatedString = text;
        Button ok = getGoForItButton();
        ok.setEnabled(true);
    }

    /**
     * {@inheritDoc}
     */
    protected void configureShell(Shell newShell, String jettyHome) {
        super.configureShell(newShell);
        _jettyHome = jettyHome;
        newShell.setText(JettyLauncherMessages.JettyXmlEditDialog_Edit_jetty_xml_title);
        newShell.setSize(600, 400);
    }
    
    /**
     * {@inheritDoc}
     */
    protected Control createDialogArea(Composite parent) {
        Composite composite = (Composite)super.createDialogArea(parent);
        GridLayout gridLayout = new GridLayout();
        gridLayout.numColumns = 1;
        composite.setLayout(gridLayout);
        Label label = new Label(composite, SWT.NONE);
        String msg = JettyLauncherMessages.bind(
                JettyLauncherMessages.JettyXmlEditDialog_Edit_jetty_xml,
                _jettyHome + File.separatorChar + "etc" + File.separatorChar + "jetty.xml");
        label.setText(msg);
        
        _textField = new Text(composite, SWT.MULTI | SWT.BORDER /*| SWT.WRAP*/ | SWT.H_SCROLL | SWT.V_SCROLL);
        _textField.addKeyListener(new KeyListener() {
            public void keyReleased(KeyEvent e) {
                validateInput();
            }
            public void keyPressed(KeyEvent e) {
            }
        });
        if (_lastValidatedString != null) {
            _textField.setText(_lastValidatedString);
        }
        GridData gridData = new GridData();
        gridData.horizontalAlignment = GridData.FILL;
        gridData.verticalAlignment = GridData.FILL;
        gridData.grabExcessHorizontalSpace = true;
        gridData.grabExcessVerticalSpace = true;
        _textField.setLayoutData(gridData);
//        _textField.setSize(500, 500);
        // status label
        _statusLabel = new Label(composite, SWT.NONE);
        _statusLabel.setText(" "); //$NON-NLS-1$
        gridData = new GridData();
        gridData.horizontalAlignment = GridData.FILL;
        gridData.grabExcessHorizontalSpace = true;
        _statusLabel.setLayoutData(gridData);
        
        
        return composite;
    }
        
    /**
     * {@inheritDoc}
     */
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CANCEL_ID, JettyLauncherMessages.JettyXmlEditDialog_Cancel, false);
        _okButton = createButton(parent, IDialogConstants.OK_ID,
                JettyLauncherMessages.JettyXmlEditDialog_OK, true);
        _okButton.setEnabled(false);
        
        //call validate input before the user has to type something.
        //it will decide on the state of the create button.
        validateInput();
    }

//    @Override
//    public boolean close() {
//        _text = getText();
//        return super.close();
//    }


}
