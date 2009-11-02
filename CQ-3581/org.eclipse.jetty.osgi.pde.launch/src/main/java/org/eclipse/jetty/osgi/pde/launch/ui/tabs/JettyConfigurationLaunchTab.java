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

import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.jetty.osgi.pde.launch.JettyLauncherMessages;
import org.eclipse.jetty.osgi.pde.launch.ui.IConfigurationAreaSettingHolder;
import org.eclipse.jetty.osgi.pde.launch.ui.JettyOSGiLaunchConfigurationInitializer;
import org.eclipse.pde.ui.launcher.AbstractLauncherTab;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

/**
 * Choose a jetty.home.
 * Edit the jetty.xml file.
 * <p>
 * Similar to org.eclipse.jdt.debug.ui.launchConfigurations.JavaArgumentsTab
 * </p>
 */
public class JettyConfigurationLaunchTab extends AbstractLauncherTab
{
    protected JettyHomeBlock _jettyHomeBlock;

    public JettyConfigurationLaunchTab(IConfigurationAreaSettingHolder configAreaHolder)
    {
        _jettyHomeBlock =  new JettyHomeBlock(configAreaHolder);
    }
    
    /**
     * Currently does not do any validation.
     */
    public void validateTab()
    {
    }

    /**
     * @see org.eclipse.debug.ui.ILaunchConfigurationTab#createControl(Composite)
     */
    public void createControl(Composite parent)
    {
        Composite comp = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, true);
        comp.setLayout(layout);
        comp.setFont(parent.getFont());

        GridData gd = new GridData(GridData.FILL_BOTH);
        comp.setLayoutData(gd);
        setControl(comp);

        _jettyHomeBlock.doCreateControl(comp);
    }
    
    public String getName()
    {
        return JettyLauncherMessages.JettyConfigurationLaunchTab_JettyConfigurationTitle;
    }

    public void initializeFrom(ILaunchConfiguration configuration)
    {
        _jettyHomeBlock.initializeFrom(configuration); 
    }
    
    /**
     * 
     */
    public void performApply(ILaunchConfigurationWorkingCopy configuration)
    {
        _jettyHomeBlock.performApply(configuration);
    }

    /**
     * This is where we initialize the selected bundles by default.
     */
    public void setDefaults(ILaunchConfigurationWorkingCopy configuration)
    {
        _jettyHomeBlock.setDefaults(configuration);
        JettyOSGiLaunchConfigurationInitializer confInitializer =
            new JettyOSGiLaunchConfigurationInitializer();
        confInitializer.initialize(configuration);
    }
    
}
