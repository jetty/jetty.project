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
package org.eclipse.jetty.osgi.pde.launch.ui;

import java.lang.reflect.Field;

import org.eclipse.debug.ui.AbstractLaunchConfigurationTabGroup;
import org.eclipse.debug.ui.ILaunchConfigurationDialog;
import org.eclipse.debug.ui.ILaunchConfigurationTab;
import org.eclipse.jetty.osgi.pde.launch.ui.tabs.JettyConfigurationLaunchTab;
import org.eclipse.pde.ui.launcher.OSGiLauncherTabGroup;
import org.eclipse.pde.ui.launcher.OSGiSettingsTab;

/**
 * Customizes the OSGi configuration UI.
 * <p>
 * Make sure that new default configurations select all the jetty bundles
 * and deselect the rest of the platform.
 * </p>
 * <p>
 * Insert a new tab for the configuration of jetty home and the ability to edit
 * jetty.xml
 * </p>
 */
public class JettyOSGiLauncherTabGroup extends AbstractLaunchConfigurationTabGroup
implements IConfigurationAreaSettingHolder
{
    
    /**
     * Use the same tabs than the OSGi lauch.
     * Add one first tab to give access to the jetty configuration.
     * For now we will simply display the jetty.xml file in a text editor.
     */
    public void createTabs(ILaunchConfigurationDialog dialog, String mode)
    {	
        ILaunchConfigurationTab[] tabs = createOSGiLaunchTabs(dialog, mode);
        ILaunchConfigurationTab[] newtabs = new ILaunchConfigurationTab[tabs.length+1];
        newtabs[0] = new JettyConfigurationLaunchTab(this);
        System.arraycopy(tabs, 0, newtabs, 1, tabs.length);
        super.setTabs(newtabs);
    }
    
    /**
     * @param dialog
     * @param mode
     * @return The tabs created for an OSGi Launch
     */
    private ILaunchConfigurationTab[] createOSGiLaunchTabs(ILaunchConfigurationDialog dialog, String mode)
    {
    	OSGiLauncherTabGroup osgiTabsFactory = new OSGiLauncherTabGroup();
    	osgiTabsFactory.createTabs(dialog, mode);
    	return osgiTabsFactory.getTabs();
    }
    
    /**
     * Helper method to read the setting "Configuration Area" in the Settings tab
     * that will become the folder pointed by the system property osgi.config.area
     * when the app is run.
     * <p>
     * Painful to access it.
     * </p>
     */
    public String getConfigurationAreaLocation()
    {
        OSGiSettingsTab settingsTab = null;
        for (ILaunchConfigurationTab t : getTabs())
        {
            if (t instanceof OSGiSettingsTab) {
                settingsTab = (OSGiSettingsTab)t;
                break;
            }
        }
        if (settingsTab == null)
        {
            return null;
        }
        //OSGisettingsTab contains org.eclipse.pde.internal.ui.launcher.ConfigurationAreaBlock
        //it is a private field called "fConfigurationBlock"
        //that object contains the private string "fLastEnteredConfigArea"
        //which is what we want.
        //we must access it from there as we need the value "live"
        return getfLastEnteredConfigArea(getfConfigurationBlock(settingsTab));
    }
    
    //introspection tricks..
    private static Field fConfigurationBlock;
    private static Field fLastEnteredConfigArea;

    private static synchronized Object getfConfigurationBlock(OSGiSettingsTab settingsTab)
    {
        try
        {
            if (fConfigurationBlock == null)
            {
                fConfigurationBlock = OSGiSettingsTab.class.getDeclaredField("fConfigurationBlock");
                fConfigurationBlock.setAccessible(true);
            }
            return fConfigurationBlock.get(settingsTab);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        return null;
    }
    
    private static synchronized String getfLastEnteredConfigArea(Object configurationBlock)
    {
        try
        {
            if (fLastEnteredConfigArea == null)
            {
                fLastEnteredConfigArea = configurationBlock.getClass().getDeclaredField("fLastEnteredConfigArea");
                fLastEnteredConfigArea.setAccessible(true);
            }
            return (String)fLastEnteredConfigArea.get(configurationBlock);
        }
        catch (Throwable t)
        {
        	t.printStackTrace();
        }
        return null;
    }
    
}
