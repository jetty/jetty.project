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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.osgi.service.resolver.ExportPackageDescription;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.internal.ui.launcher.BundleLauncherHelper;
import org.eclipse.pde.internal.ui.launcher.EquinoxInitializer;
import org.eclipse.pde.ui.launcher.IPDELauncherConstants;

/**
 * In charge of selecting the proper default bundles to load and to make sure
 * that the jetty.osgi.boot bundle will in fact start.
 * <p>
 * The default behavior of OSGi run configuration is to select everything.
 * This is overwhelming for the web-app developer.
 * This run configuration initializer will select the minimum set of plugins to run jetty.
 * </p>
 */
public class JettyOSGiLaunchConfigurationInitializer extends EquinoxInitializer
{
    
    /** well known bundles that jetty depends on, often optional dependency. */
    private static final Set<String> BUNDLE_DEPENDENCIES = new HashSet<String>();
    /** well known bundles required to support headles jdt */
    private static final Set<String> JDT_HEADLESS_DEPENDENCIES = new HashSet<String>();
    
    private static final Set<String> ALL_BUNDLE_DEPS = new HashSet<String>();
    
    
    /** we don't know the bundle but if we find a bundle that exports this package */
    private static final Set<String> PACKAGES_DEPENDENCIES = new HashSet<String>();
    
    private static final String JETTY_BUNDLES_PREFIX = "org.eclipse.jetty.";
    private static final String JETTY_JSP_BUNDLES_PREFIX = "org.mortbay.jetty.jsp-";
    static {
        BUNDLE_DEPENDENCIES.add("javax.servlet");
        BUNDLE_DEPENDENCIES.add("org.eclipse.osgi");
        BUNDLE_DEPENDENCIES.add("org.eclipse.osgi.services");
        BUNDLE_DEPENDENCIES.add("org.objectweb.asm");
        
        PACKAGES_DEPENDENCIES.add("javax.mail");
        PACKAGES_DEPENDENCIES.add("javax.transaction");
        PACKAGES_DEPENDENCIES.add("javax.activation");
        PACKAGES_DEPENDENCIES.add("javax.annotation");
        
        //now add the headless jdt:
        //no more ecj: we use jdt.core instead that contains ecj.
//        BUNDLE_DEPENDENCIES.add("org.eclipse.jdt.core.compiler.batch");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.core.commands");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.core.contenttype");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.core.expressions");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.core.filessytem");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.core.jobs");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.core.resources");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.core.runtime");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.core.variables");

        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.debug.core");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.equinox.app");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.equinox.common");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.equinox.preferences");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.equinox.registry");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.equinox.app");

        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.jdt.core");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.jdt.debug");
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.jdt.launching");
        
        JDT_HEADLESS_DEPENDENCIES.add("org.eclipse.text");
        
        
        ALL_BUNDLE_DEPS.addAll(JDT_HEADLESS_DEPENDENCIES);
        ALL_BUNDLE_DEPS.addAll(BUNDLE_DEPENDENCIES);
    }
    
    /**
     * Override the default behavior which consists of selecting every bundle.
     * Here we select only the jetty and jdt-headless bundles from the platform
     * By default we continue to select all the bundles located in the workspace.
     * 
     * @param configuration the launch configuration
     */
    @Override
    protected void initializeBundleState(ILaunchConfigurationWorkingCopy configuration)
    {
        List<IPluginModelBase> bundlesInPlatform = new ArrayList<IPluginModelBase>();
        List<IPluginModelBase> bundlesInWorkspace = new ArrayList<IPluginModelBase>();
        selectBundles(bundlesInPlatform, bundlesInWorkspace);
        
        configuration.setAttribute(IPDELauncherConstants.WORKSPACE_BUNDLES, createBundleList(bundlesInWorkspace));
        configuration.setAttribute(IPDELauncherConstants.TARGET_BUNDLES, createBundleList(bundlesInPlatform));
        configuration.setAttribute(IPDELauncherConstants.AUTOMATIC_ADD, true);
    }
    
    /**
     * @return The list of bundles to install along with their start level in the format expected by
     * equinox for the config.ini file. For example:
     * <code>osgi.bundles=org.eclipse.equinox.common@2:start, org.eclipse.update.configurator@3:start</code>
     * 
     * (As documented here: http://www.eclipse.org/equinox/documents/quickstart.php)
     */
    private String createBundleList(List<IPluginModelBase> bundlesInPlatform)
    {
        StringBuilder bundlesInPlatformString = new StringBuilder();
        String sep = "";
        for (IPluginModelBase bundleModel : bundlesInPlatform)
        {
        	String id = bundleModel.getPluginBase().getId();
        	bundlesInPlatformString.append(sep
        			+ BundleLauncherHelper.writeBundleEntry(bundleModel,
        					getStartLevel(id), getAutoStart(id)));
        	sep = ",";
        }
        return bundlesInPlatformString.toString();
    }
    
    /**
     * This is where we are doing something else than the default OSGi launch configuration initializer.
     * We select only the jetty plugins, their dependencies and the jdt-headless plugins
     * if we want to launch the ability to debug the java projects too.
     * 
     * @param bundlesInPlatformCollector Where the bundles that belong to the platform are collected.
     * @param bundlesInWorkspaceCollector Where the bundles that belong to the workspace are collected.
     */
    private void selectBundles(List<IPluginModelBase> bundlesInPlatformCollector,
    		List<IPluginModelBase> bundlesInWorkspaceCollector)
    {
    	for (IPluginModelBase pluginModel : PluginRegistry.getActiveModels())
        {
            String symbName = pluginModel.getBundleDescription().getSymbolicName();
            if (pluginModel.getUnderlyingResource() != null)
            {
            	//all bundles in the workspace are added by default.
            	bundlesInWorkspaceCollector.add(pluginModel);
            	continue;
            }
            
            if (symbName.startsWith(JETTY_BUNDLES_PREFIX))
            {
                if (symbName.startsWith("org.eclipse.jetty.osgi.pde"))
                {
                    // don't select the SDK PDE plugins for running jetty!
                    continue;
                }
            }
            else if (symbName.startsWith(JETTY_JSP_BUNDLES_PREFIX))
            {
            	//let's add them.
            	bundlesInPlatformCollector.add(pluginModel);
            }
            else if (!ALL_BUNDLE_DEPS.contains(symbName))
            {
                ExportPackageDescription[] exPacks =
                	pluginModel.getBundleDescription().getExportPackages();
                for (int j = 0; j < exPacks.length; j++)
                {
                    ExportPackageDescription xp = exPacks[j];
                    if (PACKAGES_DEPENDENCIES.contains(xp.getName()))
                    {
                    	bundlesInPlatformCollector.add(pluginModel);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Override the default behavior: we need to make sure that
     * org.eclipse.jetty.osgi.boot has its autostart level set to 'true'
     */
    @Override
    protected String getAutoStart(String bundleID)
    {
        if (bundleID.equals("org.eclipse.jetty.osgi.boot"))
        {
            return Boolean.TRUE.toString();
        }
        return super.getAutoStart(bundleID);
    }
    
    
}
