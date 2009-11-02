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
import java.lang.reflect.Field;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.WorkingDirectoryBlock;
import org.eclipse.jetty.osgi.pde.launch.JettyConfigurationConstants;
import org.eclipse.jetty.osgi.pde.launch.internal.JettyHomeHelper;
import org.eclipse.jetty.osgi.pde.launch.ui.IConfigurationAreaSettingHolder;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;

/**
 * Choose the jettyhome folder. A bit of a stretch to reuse the WorkingDirectoryBlock
 * but it does save us a lot of UI development.
 */
public class JettyHomeBlock extends WorkingDirectoryBlock
{
	private Button _editJettyXml;
    private String _editedJettyXml;
    private IConfigurationAreaSettingHolder _configAreaHolder;
    private boolean _initializing = true;
    
    /**
     * Constructs a new working directory block.
     */
    public JettyHomeBlock(IConfigurationAreaSettingHolder configAreaHolder)
    {
        super(JettyConfigurationConstants.ATTR_JETTY_HOME);
        _configAreaHolder = configAreaHolder;
        setDirty(false);
    }

    /**
     * @return null We don't use this in the context of a java project.
     */
    protected IProject getProject(ILaunchConfiguration configuration)
            throws CoreException
    {
        return null;
    }

    /**
     * For now just dumpt the stack trace on the console
     */
    protected void log(CoreException e)
    {
        e.printStackTrace();
    }
    
    /**
     * Calls create control of the super (which is final)
     * then go an tweak the created control so that its purpose is for jetty home
     * instead of a working directory selection.
     */
    protected void doCreateControl(Composite parent)
    {
        super.createControl(parent);
        Group grp = (Group)super.getControl();
        grp.setText("Jetty &Home:");
        
        //ok now a bit painful: get the composite inside which the buttons are
        //add a column to the grid layout and place a button.
        Button fSysButton = getfFileSystemButton();
        Composite compButtons = fSysButton.getParent();
        GridLayout gLayout = (GridLayout) compButtons.getLayout();
        gLayout.numColumns = gLayout.numColumns+1;
        _editJettyXml = createPushButton(compButtons, "Edit jetty.xml", null); 
        _editJettyXml.addSelectionListener(new SelectionListener()
        {
            public void widgetSelected(SelectionEvent e)
            {
                //open a text editor for jetty.xml inside a popup window.
                //we are using a cheap popup.
                JettyXmlEditDialog diag = new JettyXmlEditDialog(_editJettyXml.getShell(),
                        getCurrentJettyXml());
                if (diag.open() == Window.OK) {
                    _editedJettyXml = diag.getNewJettyXml();
                    setDirty(true);
                }
            }
            
            public void widgetDefaultSelected(SelectionEvent e)
            {
                
            }
        });
    }
    
    /**
     * Sets the default value for this field: not related to the working directory.
     */
    @Override
    protected void setDefaultWorkingDir()
    {
        //the problem is that at this point, the settings tab is not configured yet...
        //so we delay the configuration of this one:
        getShell().getDisplay().asyncExec(new Runnable()
        {
            public void run()
            {
                setDefaultWorkingDirectoryText(
                        _configAreaHolder.getConfigurationAreaLocation()
                            + File.separator + "jettyhome"); //$NON-NLS-1$
                _initializing = false;
            }
        });
    }

    /**
     * The current content of jettyXml
     */
    protected String getCurrentJettyXml()
    {
        return JettyHomeHelper.getCurrentJettyXml(getWorkingDirectoryText(), false);
    }
    
    
    
    @Override
    public void performApply(ILaunchConfigurationWorkingCopy configuration)
    {
        super.performApply(configuration);
        String jettyHome = getWorkingDirectoryText();
        if (_initializing || jettyHome == null || jettyHome.length() == 0) {
            //we are only interested in the case where the user really did press on
            //Apply or "Run". furthermore before the conf area is set
            //we will not be able to locate the default jettyhome.
            _initializing = false;
            return;
        }
        try
        {
            JettyHomeHelper.setupJettyHomeAndJettyXML(
                    _editedJettyXml, getWorkingDirectoryText(), false);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }



    //introspection trick to be able to insert a button.
    private static Field fFileSystemButton_field;
    private Button getfFileSystemButton()
    {
        try
        {
            if (fFileSystemButton_field == null)
            {
                fFileSystemButton_field = WorkingDirectoryBlock.class.getDeclaredField("fFileSystemButton");
                fFileSystemButton_field.setAccessible(true);
            }
            return (Button)fFileSystemButton_field.get(this);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        return null;
    }
    
    
}
