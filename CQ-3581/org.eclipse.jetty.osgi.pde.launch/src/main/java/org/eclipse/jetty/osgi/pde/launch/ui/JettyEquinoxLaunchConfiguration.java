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

import java.io.File;
import java.io.IOException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jetty.osgi.pde.launch.JettyConfigurationConstants;
import org.eclipse.jetty.osgi.pde.launch.JettyOSGiPDEPlugin;
import org.eclipse.jetty.osgi.pde.launch.internal.JettyHomeHelper;
import org.eclipse.pde.internal.ui.launcher.LaunchConfigurationHelper;
import org.eclipse.pde.ui.launcher.EquinoxLaunchConfiguration;
import org.eclipse.pde.ui.launcher.IPDELauncherConstants;

/**
 * 
 */
public class JettyEquinoxLaunchConfiguration extends EquinoxLaunchConfiguration
{

    /** The configuration type as declared in the extension point's plugin.xml */
    public static final String ID = "org.eclipse.jetty.osgi.pde.launch.ui.jettyosgilaunch";

    @Override
    protected void preLaunchCheck(ILaunchConfiguration configuration, ILaunch launch, IProgressMonitor monitor) throws CoreException
    {
        if (!configuration.getAttribute(IPDELauncherConstants.DOCLEAR, false))
        {
            super.preLaunchCheck(configuration,launch,monitor);
            //make sure jettyhome exists and set it up if it does not:
            File jettyHome = resolveJettyHome(configuration);
            if (jettyHome != null && !jettyHome.exists())
            {
                try
                {
                    JettyHomeHelper.setupJettyHomeAndJettyXML(null, jettyHome.getAbsolutePath(), true);
                }
                catch (IOException e)
                {
                    throw new CoreException(new Status(IStatus.ERROR,
                            JettyOSGiPDEPlugin.PLUGIN_ID,"Unable to setup jettyhome", e));
                }
            }
            return;//nothing else to do.
        }
        //this will wipe the workspace if it was configured that way.
        //it means we must setup jettyhome and jettyxml immediately after
        //if indeed it was wiped:
        String jettyHomePath = resolveJettyHome(configuration).getAbsolutePath();
        String jettyXml = JettyHomeHelper.getCurrentJettyXml(jettyHomePath, true);
        super.preLaunchCheck(configuration,launch,monitor);
        try
        {
            JettyHomeHelper.setupJettyHomeAndJettyXML(jettyXml, jettyHomePath, true);
        }
        catch (IOException e)
        {
            throw new CoreException(new Status(IStatus.ERROR,
                    JettyOSGiPDEPlugin.PLUGIN_ID,"Unable to setup jettyhome", e));
        }
    }

    /**
     * Append -Djetty.home
     */
    @Override
    public String[] getVMArguments(ILaunchConfiguration configuration) throws CoreException
    {
        String[] args = super.getVMArguments(configuration);
        for (int i = 0; i < args.length ;i++) {
            String arg = args[i];
            if (arg.startsWith("-Djetty.home=")) {
                //overridden by the arg nothing to change (?)
                return args;
            }
        }
        String jettyHomePath = configuration.getAttribute(JettyConfigurationConstants.ATTR_JETTY_HOME, "");
        File jettyHome = resolveJettyHome(configuration);
        if (jettyHome == null || !jettyHome.exists())
        {
            //err?
            System.err.println("could not resolve jettyhome; "
                    + jettyHomePath + " -> " + jettyHome);
            return args;
        }
        String[] newArgs = new String[args.length+1];
        System.arraycopy(args, 0, newArgs, 0, args.length);
        newArgs[args.length] = "-Djetty.home=\""+jettyHome.getAbsolutePath()+"\"";
        return newArgs;
    }
    
    private File resolveJettyHome(ILaunchConfiguration configuration) throws CoreException
    {
        String jettyHomePath = configuration.getAttribute(JettyConfigurationConstants.ATTR_JETTY_HOME, "");
        if (jettyHomePath == null || jettyHomePath.length() == 0)
        {
            File configArea = LaunchConfigurationHelper.getConfigurationArea(configuration);
            return new File(configArea, "jettyhome");
        }
        else
        {
            return JettyHomeHelper.resolveJettyHome(jettyHomePath);
        }
    }
        
}
