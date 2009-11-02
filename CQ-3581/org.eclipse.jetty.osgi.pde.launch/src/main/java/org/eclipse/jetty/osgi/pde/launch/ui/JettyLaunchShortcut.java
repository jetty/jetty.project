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
package org.eclipse.jetty.osgi.pde.launch.ui;

import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.pde.ui.launcher.AbstractLaunchShortcut;
import org.eclipse.pde.ui.launcher.IPDELauncherConstants;
import org.eclipse.ui.IEditorPart;

/**
 * Shortcut to launch Jetty on osgi.
 */
public class JettyLaunchShortcut extends AbstractLaunchShortcut
{

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.debug.ui.ILaunchShortcut#launch(org.eclipse.jface.viewers.ISelection, java.lang.String)
     */
    public void launch(ISelection selection, String mode)
    {
        launch(mode);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.debug.ui.ILaunchShortcut#launch(org.eclipse.ui.IEditorPart, java.lang.String)
     */
    public void launch(IEditorPart editor, String mode)
    {
        launch(mode);
    }

    /**
     * Returns the launch configuration type name.
     * 
     * @return the launch configuration type name
     */
    protected String getLaunchConfigurationTypeName()
    {
        return JettyEquinoxLaunchConfiguration.ID;
    }

    /**
     * Initialize launch attributes on the new launch configuration. Must be overridden by subclasses.
     * 
     * @param wc
     *            the launch configuration working copy to be initialize
     * 
     * @see IPDELauncherConstants
     */
    protected void initializeConfiguration(ILaunchConfigurationWorkingCopy wc)
    {
        JettyOSGiLaunchConfigurationInitializer confInitializer = new JettyOSGiLaunchConfigurationInitializer();
        confInitializer.initialize(wc);
    }
    
    /**
     * Determines whether a given launch configuration is a good match given the
     * current application or framework being launched. This method must be overridden
     * by subclasses. Its purpose is to add criteria on what makes a good match or not.
     * 
     * @param configuration
     *            the launch configuration being evaluated
     * @return <code>true</code> if the launch configuration is a good match
     *            for the application or framework being launched,
     *          <code>false</code> otherwise.
     */
    protected boolean isGoodMatch(ILaunchConfiguration configuration)
    {
        return true;
    }

}
