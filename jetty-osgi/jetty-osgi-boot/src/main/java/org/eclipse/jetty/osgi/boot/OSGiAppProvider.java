// ========================================================================
// Copyright (c) 2009-2010 Mortbay, Inc.
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
//    Greg Wilkins - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.providers.ContextProvider;
import org.eclipse.jetty.deploy.providers.ScanningAppProvider;
import org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * AppProvider for OSGi. Supports the configuration of ContextHandlers and
 * WebApps. Extends the AbstractAppProvider to support the scanning of context
 * files located outside of the bundles.
 * <p>
 * This provider must not be called outside of jetty.boot: it should always be
 * called via the OSGi service listener.
 * </p>
 * <p>
 * This provider supports the same set of parameters than the WebAppProvider as
 * it supports the deployment of WebAppContexts. Except for the scanning of the
 * webapps directory.
 * </p>
 * <p>
 * When the parameter autoInstallOSGiBundles is set to true, OSGi bundles that
 * are located in the monitored directory are installed and started after the
 * framework as finished auto-starting all the other bundles.
 * Warning: only use this for development.
 * </p>
 */
public class OSGiAppProvider extends ScanningAppProvider implements AppProvider
{
    private static final Logger LOG = Log.getLogger(OSGiAppProvider.class);


    private boolean _extractWars = true;
    private boolean _parentLoaderPriority = false;
    private String _defaultsDescriptor;
    private String _tldBundles;
    private String[] _configurationClasses;
    
    private boolean _autoInstallOSGiBundles = true;
    
    //Keep track of the bundles that were installed and that are waiting for the
    //framework to complete its initialization.
    Set<Bundle> _pendingBundlesToStart = null;
        
    /**
     * When a context file corresponds to a deployed bundle and is changed we
     * reload the corresponding bundle.
     */
    private static class Filter implements FilenameFilter
    {
        OSGiAppProvider _enclosedInstance;
        
        public boolean accept(File dir, String name)
        {
            File file = new File(dir,name);
            if (fileMightBeAnOSGiBundle(file))
            {
                return true;
            }
            if (!file.isDirectory())
            {
                String contextName = getDeployedAppName(name);
                if (contextName != null)
                {
                    App app = _enclosedInstance.getDeployedApps().get(contextName);
                    return app != null;
                }
            }
            return false;
        }
    }

    /**
     * @param contextFileName
     *            for example myContext.xml
     * @return The context, for example: myContext; null if this was not a
     *         suitable contextFileName.
     */
    private static String getDeployedAppName(String contextFileName)
    {
        String lowername = contextFileName.toLowerCase();
        if (lowername.endsWith(".xml"))
        {
            String contextName = contextFileName.substring(0,lowername.length() - ".xml".length());
            return contextName;
        }
        return null;
    }

    /**
     * Reading the display name of a webapp is really not sufficient for indexing the various
     * deployed ContextHandlers.
     * 
     * @param context
     * @return
     */
    private String getContextHandlerAppName(ContextHandler context) {
        String appName = context.getDisplayName();
        if (appName == null || appName.length() == 0  || getDeployedApps().containsKey(appName)) {
        	if (context instanceof WebAppContext)
        	{
        		appName = ((WebAppContext)context).getContextPath();
        		if (getDeployedApps().containsKey(appName)) {
            		appName = "noDisplayName"+context.getClass().getSimpleName()+context.hashCode();
            	}
        	} else {
        		appName = "noDisplayName"+context.getClass().getSimpleName()+context.hashCode();
        	}
        }
        return appName;
    }
    
    /**
     * Default OSGiAppProvider consutructed when none are defined in the
     * jetty.xml configuration.
     */
    public OSGiAppProvider()
    {
        super(new Filter());
        ((Filter)super._filenameFilter)._enclosedInstance = this;
    }

    /**
     * Default OSGiAppProvider consutructed when none are defined in the
     * jetty.xml configuration.
     * 
     * @param contextsDir
     */
    public OSGiAppProvider(File contextsDir) throws IOException
    {
        this();
        setMonitoredDirResource(Resource.newResource(contextsDir.toURI()));
    }
    
    /**
     * Returns the ContextHandler that was created by WebappRegistractionHelper
     * 
     * @see AppProvider
     */
    public ContextHandler createContextHandler(App app) throws Exception
    {
        // return pre-created Context
        ContextHandler wah = app.getContextHandler();
        if (wah == null)
        {
            // for some reason it was not defined when the App was constructed.
            // we don't support this situation at this point.
            // once the WebAppRegistrationHelper is refactored, the code
            // that creates the ContextHandler will actually be here.
            throw new IllegalStateException("The App must be passed the " + "instance of the ContextHandler when it is construsted");
        }
        if (_configurationClasses != null && wah instanceof WebAppContext) 
        {
            ((WebAppContext)wah).setConfigurationClasses(_configurationClasses);
        }
        
        if (_defaultsDescriptor != null)
            ((WebAppContext)wah).setDefaultsDescriptor(_defaultsDescriptor);
        return app.getContextHandler();
    }

    /**
     * @see AppProvider
     */
    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        // _manager=deploymentManager;
        super.setDeploymentManager(deploymentManager);
    }

    private static String getOriginId(Bundle contributor, String pathInBundle)
    {
    	return contributor.getSymbolicName() + "-" + contributor.getVersion().toString() +
    		(pathInBundle.startsWith("/") ? pathInBundle : "/" + pathInBundle);
    }
    
    /**
     * @param context
     * @throws Exception
     */
    public void addContext(Bundle contributor, String pathInBundle, ContextHandler context) throws Exception
    {
    	addContext(getOriginId(contributor, pathInBundle), context);
    }
    /**
     * @param context
     * @throws Exception
     */
    public void addContext(String originId, ContextHandler context) throws Exception
    {
        // TODO apply configuration specific to this provider
    	if (context instanceof WebAppContext)
    	{
           ((WebAppContext)context).setExtractWAR(isExtract());
    	}

        // wrap context as an App
        App app = new App(getDeploymentManager(),this,originId,context);
        String appName = getContextHandlerAppName(context);
        getDeployedApps().put(appName,app);
        getDeploymentManager().addApp(app);
    }
    
    

    /**
     * Called by the scanner of the context files directory. If we find the
     * corresponding deployed App we reload it by returning the App. Otherwise
     * we return null and nothing happens: presumably the corresponding OSGi
     * webapp is not ready yet.
     * 
     * @return the corresponding already deployed App so that it will be
     *         reloaded. Otherwise returns null.
     */
    @Override
    protected App createApp(String filename)
    {
        // find the corresponding bundle and ContextHandler or WebAppContext
        // and reload the corresponding App.
        // see the 2 pass of the refactoring of the WebAppRegistrationHelper.
        String name = getDeployedAppName(filename);
        if (name != null)
        {
            return getDeployedApps().get(name);
        }
        return null;
    }

    public void removeContext(ContextHandler context) throws Exception
    {
    	String appName = getContextHandlerAppName(context);
        App app = getDeployedApps().remove(context.getDisplayName());
        if (app == null) {
        	//try harder to undeploy this context handler.
        	//see bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=330098
        	appName = null;
        	for (Entry<String,App> deployedApp : getDeployedApps().entrySet()) {
        		if (deployedApp.getValue().getContextHandler() == context) {
        			app = deployedApp.getValue();
        			appName = deployedApp.getKey();
        			break;
        		}
        	}
        	if (appName != null) {
        		getDeployedApps().remove(appName);
        	}
        }
        if (app != null)
        {
            getDeploymentManager().removeApp(app);
        }
    }

    // //copied from WebAppProvider as the parameters are identical.
    // //only removed the parameer related to extractWars.
    /* ------------------------------------------------------------ */
    /**
     * Get the parentLoaderPriority.
     * 
     * @return the parentLoaderPriority
     */
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the parentLoaderPriority.
     * 
     * @param parentLoaderPriority
     *            the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _parentLoaderPriority = parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the defaultsDescriptor.
     * 
     * @return the defaultsDescriptor
     */
    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the defaultsDescriptor.
     * 
     * @param defaultsDescriptor
     *            the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }

    /**
     * The context xml directory. In fact it is the directory watched by the
     * scanner.
     */
    public File getContextXmlDirAsFile()
    {
        try
        {
            Resource monitoredDir = getMonitoredDirResource();
            if (monitoredDir == null)
                return null;
            return monitoredDir.getFile();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * The context xml directory. In fact it is the directory watched by the
     * scanner.
     */
    public String getContextXmlDir()
    {
        try
        {
            Resource monitoredDir = getMonitoredDirResource();
            if (monitoredDir == null)
                return null;
            return monitoredDir.getFile().toURI().toString();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }
    
    public boolean isExtract()
    {
        return _extractWars;
    }

    public void setExtract(boolean extract)
    {
        _extractWars=extract;
    }

    /**
     * @return true when this app provider locates osgi bundles and features in
     * its monitored directory and installs them. By default true if there is a folder to monitor.
     */
    public boolean isAutoInstallOSGiBundles()
    {
    	return _autoInstallOSGiBundles;
    }

    /**
     * &lt;autoInstallOSGiBundles&gt;true&lt;/autoInstallOSGiBundles&gt;
     * @param installingOSGiBundles
     */
    public void setAutoInstallOSGiBundles(boolean installingOSGiBundles)
    {
        _autoInstallOSGiBundles=installingOSGiBundles;
    }


    /* ------------------------------------------------------------ */
    /**
     * Set the directory in which to look for context XML files.
     * <p>
     * If a webapp call "foo/" or "foo.war" is discovered in the monitored
     * directory, then the ContextXmlDir is examined to see if a foo.xml file
     * exists. If it does, then this deployer will not deploy the webapp and the
     * ContextProvider should be used to act on the foo.xml file.
     * </p>
     * <p>
     * Also if this directory contains some osgi bundles, it will install them.
     * </p>
     * 
     * @see ContextProvider
     * @param contextsDir
     */
    public void setContextXmlDir(String contextsDir)
    {
        setMonitoredDirName(contextsDir);
    }
    
    /**
     * @param tldBundles Comma separated list of bundles that contain tld jars
     * that should be setup on the jetty instances created here.
     */
    public void setTldBundles(String tldBundles)
    {
    	_tldBundles = tldBundles;
    }
    
    /**
     * @return The list of bundles that contain tld jars that should be setup
     * on the jetty instances created here.
     */
    public String getTldBundles()
    {
    	return _tldBundles;
    }
    
    /**
     * @param configurations The configuration class names.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        _configurationClasses = configurations==null?null:(String[])configurations.clone();
    }  
    
    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public String[] getConfigurationClasses()
    {
        return _configurationClasses;
    }

    /**
     * Overridden to install the OSGi bundles found in the monitored folder.
     */
    @Override
    protected void doStart() throws Exception
    {
        if (isAutoInstallOSGiBundles())
        {
        	if (getMonitoredDirResource()  == null)
        	{
        		setAutoInstallOSGiBundles(false);
        		LOG.info("Disable autoInstallOSGiBundles as there is not contexts folder to monitor.");
        	}
	    	else
        	{
	    		File scandir = null;
	    		try
	    		{
	                scandir = getMonitoredDirResource().getFile();
	                if (!scandir.exists() || !scandir.isDirectory())
	                {
	                	setAutoInstallOSGiBundles(false);
	            		LOG.warn("Disable autoInstallOSGiBundles as the contexts folder '" + scandir.getAbsolutePath() + " does not exist.");
	            		scandir = null;
	                }
	    		}
	    		catch (IOException ioe)
	    		{
                	setAutoInstallOSGiBundles(false);
            		LOG.warn("Disable autoInstallOSGiBundles as the contexts folder '" + getMonitoredDirResource().getURI() + " does not exist.");
            		scandir = null;
	    		}
	    		if (scandir != null)
	    		{
		            for (File file : scandir.listFiles())
		            {
		                if (fileMightBeAnOSGiBundle(file))
		                {
		                    installBundle(file, false);
		                }
		            }
	    		}
        	}
        }
        super.doStart();
        if (isAutoInstallOSGiBundles())
        {
            Scanner.ScanCycleListener scanCycleListner = new AutoStartWhenFrameworkHasCompleted(this);
            super.addScannerListener(scanCycleListner);
        }
    }
    
    /**
     * When the file is a jar or a folder, we look if it looks like an OSGi bundle.
     * In that case we install it and start it.
     * <p>
     * Really a simple trick to get going quickly with development.
     * </p>
     */
    @Override
    protected void fileAdded(String filename) throws Exception
    {
        File file = new File(filename);
        if (isAutoInstallOSGiBundles() && file.exists() && fileMightBeAnOSGiBundle(file))
        {
            installBundle(file, true);
        }
        else
        {
            super.fileAdded(filename);
        }
    }
    
    /**
     * @param file
     * @return
     */
    private static boolean fileMightBeAnOSGiBundle(File file)
    {
        if (file.isDirectory())
        {
            if (new File(file,"META-INF/MANIFEST.MF").exists())
            {
                return true;
            }
        }
        else if (file.getName().endsWith(".jar"))
        {
            return true;
        }
        return false;
    }

    @Override
    protected void fileChanged(String filename) throws Exception
    {
        File file = new File(filename);
        if (isAutoInstallOSGiBundles() && fileMightBeAnOSGiBundle(file))
        {
            updateBundle(file);
        }
        else
        {
            super.fileChanged(filename);
        }
    }

    @Override
    protected void fileRemoved(String filename) throws Exception
    {
        File file = new File(filename);
        if (isAutoInstallOSGiBundles() && fileMightBeAnOSGiBundle(file))
        {
            uninstallBundle(file);
        }
        else
        {
            super.fileRemoved(filename);
        }
    }
    
    /**
     * Returns a bundle according to its location.
     * In the version 1.6 of org.osgi.framework, BundleContext.getBundle(String) is what we want.
     * However to support older versions of OSGi. We use our own local refrence mechanism.
     * @param location
     * @return
     */
    protected Bundle getBundle(BundleContext bc, String location)
    {
    	//not available in older versions of OSGi:
    	//return bc.getBundle(location);
    	for (Bundle b : bc.getBundles())
    	{
    		if (b.getLocation().equals(location))
    		{
    			return b;
    		}
    	}
    	return null;
    }

    protected synchronized Bundle installBundle(File file, boolean start)
    {
    	
        try
        {
            BundleContext bc = JettyBootstrapActivator.getBundleContext();
            String location = file.toURI().toString();
            Bundle b = getBundle(bc, location);
            if (b == null) 
            {
                b = bc.installBundle(location);
            }
            if (b == null)
            {
            	//not sure we will ever be here,
            	//most likely a BundleException was thrown
            	LOG.warn("The file " + location + " is not an OSGi bundle.");
            	return null;
            }
            if (start && b.getHeaders().get(Constants.FRAGMENT_HOST) == null)
            {//not a fragment, try to start it. if the framework has finished auto-starting.
            	if (!PackageAdminServiceTracker.INSTANCE.frameworkHasCompletedAutostarts())
            	{
            		if (_pendingBundlesToStart == null)
            		{
            			_pendingBundlesToStart = new HashSet<Bundle>();
            		}
            		_pendingBundlesToStart.add(b);
            		return null;
            	}
            	else
            	{
            		b.start();
            	}
            }
            return b;
        }
        catch (BundleException e)
        {
            LOG.warn("Unable to " + (start? "start":"install") + " the bundle " + file.getAbsolutePath(), e);
        }
        return null;
    }
    
    protected void uninstallBundle(File file)
    {
        try
        {
            Bundle b = getBundle(JettyBootstrapActivator.getBundleContext(), file.toURI().toString());
            b.stop();
            b.uninstall();
        }
        catch (BundleException e)
        {
            LOG.warn("Unable to uninstall the bundle " + file.getAbsolutePath(), e);
        }
    }
    
    protected void updateBundle(File file)
    {
        try
        {
            Bundle b = getBundle(JettyBootstrapActivator.getBundleContext(), file.toURI().toString());
            if (b == null)
            {
                installBundle(file, true);
            }
            else if (b.getState() == Bundle.ACTIVE)
            {
                b.update();
            }
            else
            {
                b.start();
            }
        }
        catch (BundleException e)
        {
            LOG.warn("Unable to update the bundle " + file.getAbsolutePath(), e);
        }
    }
    

}
/**
 * At the end of each scan, if there are some bundles to be started,
 * look if the framework has completed its autostart. In that case start those bundles.
 */
class AutoStartWhenFrameworkHasCompleted implements Scanner.ScanCycleListener
{
        private static final Logger LOG = Log.getLogger(AutoStartWhenFrameworkHasCompleted.class);
    
	private final OSGiAppProvider _appProvider;
	
	AutoStartWhenFrameworkHasCompleted(OSGiAppProvider appProvider)
	{
		_appProvider = appProvider;
	}
	
	public void scanStarted(int cycle) throws Exception
	{
	}
	
	public void scanEnded(int cycle) throws Exception
	{
		if (_appProvider._pendingBundlesToStart != null && PackageAdminServiceTracker.INSTANCE.frameworkHasCompletedAutostarts())
		{
			Iterator<Bundle> it = _appProvider._pendingBundlesToStart.iterator();
			while (it.hasNext())
			{
				Bundle b = it.next();
				if (b.getHeaders().get(Constants.FRAGMENT_HOST) != null)
				{
					continue;
				}
				try
				{
					b.start();
				}
		        catch (BundleException e)
		        {
		            LOG.warn("Unable to start the bundle " + b.getLocation(), e);
		        }

			}
			_appProvider._pendingBundlesToStart = null;
		}
	}

}

