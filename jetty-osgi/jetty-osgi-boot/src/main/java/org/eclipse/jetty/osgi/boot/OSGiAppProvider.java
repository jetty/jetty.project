// ========================================================================
// Copyright (c) 2009 Mortbay, Inc.
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

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.providers.ContextProvider;
import org.eclipse.jetty.deploy.providers.ScanningAppProvider;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;

/**
 * AppProvider for OSGi.
 * Supports the configuration of ContextHandlers and WebApps.
 * Extends the AbstractAppProvider to support the scanning of context files located outside
 * of the bundles.
 * <p>
 * This provider must not be called outside of jetty.boot:
 * it should always be called via the OSGi service listener.
 * </p>
 * <p>
 * This provider supports the same set of parameters than the WebAppProvider
 * as it supports the deployment of WebAppContexts.
 * Except for the scanning of the webapps directory.
 * </p>
 */
public class OSGiAppProvider extends ScanningAppProvider implements AppProvider
{
    
    private boolean _extractWars = false;
    private boolean _parentLoaderPriority = false;
    private String _defaultsDescriptor;
	
	/**
	 * When a context file corresponds to a deployed bundle and is changed we
	 * reload the corresponding bundle.
	 */
    private static class Filter implements FilenameFilter
    {
    	OSGiAppProvider _enclosedInstance;
    	public boolean accept(File dir, String name)
        {
        	if (!new File(dir,name).isDirectory()) {
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
     * @param contextFileName for example myContext.xml
     * @return The context, for example: myContext; null if this was not a suitable contextFileName.
     */
	private static String getDeployedAppName(String contextFileName)
	{
		String lowername = contextFileName.toLowerCase();
        if (lowername.endsWith(".xml")) {
        	String contextName = contextFileName.substring(0,lowername.length()-".xml".length());
        	return contextName;
        }
        return null;
	}
	
	/**
	 * Default OSGiAppProvider consutructed when none are defined in the jetty.xml
	 * configuration.
	 * @param contextsDir
	 */
	public OSGiAppProvider() {
		super(new Filter());
		((Filter)super._filenameFilter)._enclosedInstance = this;
	}

	
	/**
	 * Default OSGiAppProvider consutructed when none are defined in the jetty.xml
	 * configuration.
	 * @param contextsDir
	 */
	public OSGiAppProvider(File contextsDir) throws IOException {
		this();
		setMonitoredDir(Resource.newResource(contextsDir.toURI()));
	}
	
    /**
     * Returns the ContextHandler that was created by WebappRegistractionHelper
     * @see AppProvider
     */
    public ContextHandler createContextHandler(App app) throws Exception
    {
        // return pre-created Context
        if (app.getContextId() != null)
        {
        	return app.getContextHandler();
        }
        //for some reason it was not defined when the App was constructed.
        //we don't support this situation at this point.
        //once the WebAppRegistrationHelper is refactored, the code
        //that creates the ContextHandler will actually be here.
        throw new IllegalStateException("The App must be passed the " +
        		"instance of the ContextHandler when it is construsted");
    }

    /**
     * @see AppProvider
     */
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        //_manager=deploymentManager;
    	super.setDeploymentManager(deploymentManager);
    }

    /**
     * @param context
     * @throws Exception
     */
    public void addContext(ContextHandler context) throws Exception
    {
        // TODO apply configuration specific to this provider
        
        // wrap context as an App
        App app = new App(getDeploymentManager(),this,context.getDisplayName(),context);
        getDeployedApps().put(context.getDisplayName(),app);
        getDeploymentManager().addApp(app);
    }
    
    
//TODO: refactor the WebAppRegistrationHelper to add the creation of the context directly from here.
//unless the client code took care of the creation of the ContextHandler.
//    public void addContext(String contextxml) throws Exception
//    {
//        // TODO apply configuration specific to this provider
//        // TODO construct ContextHandler
//        ContextHandler context=null;
//        // wrap context as an App
//        OSGiApp app = new OSGiApp(_manager,this,context.getDisplayName(),context);
//        _apps.put(context,app);
//        _manager.addApp(app);
//    }
    
    /**
     * Called by the scanner of the context files directory.
     * If we find the corresponding deployed App we reload it by returning the App.
     * Otherwise we return null and nothing happens: presumably the corresponding OSGi webapp
     * is not ready yet.
     * @return the corresponding already deployed App so that it will be reloaded.
     * Otherwise returns null.
     */
    @Override
	protected App createApp(String filename) {
		//find the corresponding bundle and ContextHandler or WebAppContext
    	//and reload the corresponding App.
    	//see the 2 pass of the refactoring of the WebAppRegistrationHelper.
		String name = getDeployedAppName(filename);
    	if (name != null)
    	{
    		return getDeployedApps().get(name);
    	}
    	return null;
	}

	public void removeContext(ContextHandler context) throws Exception
    {
        App app = getDeployedApps().remove(context.getDisplayName());
        if (app != null)
        {
        	getDeploymentManager().removeApp(app);
        }
    }
    
	////copied from WebAppProvider as the parameters are identical.
    /* ------------------------------------------------------------ */
    /** Get the extractWars.
     * @return the extractWars
     */
    public boolean isExtractWars()
    {
        return _extractWars;
    }

    /* ------------------------------------------------------------ */
    /** Set the extractWars.
     * @param extractWars the extractWars to set
     */
    public void setExtractWars(boolean extractWars)
    {
        _extractWars = extractWars;
    }

    /* ------------------------------------------------------------ */
    /** Get the parentLoaderPriority.
     * @return the parentLoaderPriority
     */
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    /** Set the parentLoaderPriority.
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _parentLoaderPriority = parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    /** Get the defaultsDescriptor.
     * @return the defaultsDescriptor
     */
    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /* ------------------------------------------------------------ */
    /** Set the defaultsDescriptor.
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }

    /**
     * The context xml directory.
     * In fact it is the directory watched by the scanner.
     */
    public File getContextXmlDirAsFile()
    {
        try {
        	Resource monitoredDir = getMonitoredDir();
        	if (monitoredDir == null)
        		return null;
			return monitoredDir.getFile();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
    }

    
    /* ------------------------------------------------------------ */
    /**
     * The context xml directory.
     * In fact it is the directory watched by the scanner.
     */
    public String getContextXmlDir()
    {
        try {
        	Resource monitoredDir = getMonitoredDir();
        	if (monitoredDir == null)
        		return null;
			return monitoredDir.getFile().toURI().toString();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the directory in which to look for context XML files.
     * <p>
     * If a webapp call "foo/" or "foo.war" is discovered in the monitored
     * directory, then the ContextXmlDir is examined to see if a foo.xml
     * file exists.  If it does, then this deployer will not deploy the webapp
     * and the ContextProvider should be used to act on the foo.xml file.
     * @see ContextProvider
     * @param contextsDir
     */
    public void setContextXmlDir(String contextsDir)
    {
    	setMonitoredDir(contextsDir);
    }

	
    
}
