//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.osgi;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee.Deployable;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.util.OSGiClassLoader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.service.packageadmin.PackageAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractContextProvider
 *
 * Base class for DeploymentManager Providers that can deploy ContextHandlers into
 * Jetty that have been discovered via OSGI either as bundles or services.
 */
public abstract class AbstractContextProvider extends AbstractLifeCycle implements AppProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractContextProvider.class);

    private DeploymentManager _deploymentManager;
    private Server _server;
    private ContextFactory _contextFactory;
    private String _environment;
    private final Map<String, String> _properties = new HashMap<>();

    public AbstractContextProvider(String environment, Server server, ContextFactory contextFactory)
    {
        _environment = environment;
        _server = server;
        _contextFactory = contextFactory;
    }

    public Server getServer()
    {
        return _server;
    }

    public Map<String, String> getProperties()
    {
        return _properties;
    }
    
    @Override
    public ContextHandler createContextHandler(App app) throws Exception
    {
        if (app == null)
            return null;

        //Create a ContextHandler suitable to deploy in OSGi
        ContextHandler h = _contextFactory.createContextHandler(this, app);
        
        //Apply defaults from the deployer providers
        if (h instanceof Deployable deployable)
            deployable.initializeDefaults(_properties);
        
        //Finish configuring the ContextHandler
        _contextFactory.configureContextHandler(_server, this, app, h);
        
        applyContextXmlFiles(h)

        return h;
    }
    
    

    protected void applyMetaInfContextXml(Resource rootResource, String overrideBundleInstallLocation)
        throws Exception
    {
        if (_bundle == null)
            return;
        if (_webApp == null)
            return;

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (LOG.isDebugEnabled())
            LOG.debug("Context classloader = {}", cl);
        try
        {

            Thread.currentThread().setContextClassLoader(_webApp.getClassLoader());

            URI contextXmlUri = null;

            //TODO replace this with getting the InputStream so we don't cache in URL
            //Try looking for a context xml file in META-INF with a specific name
            URL url = _bundle.getEntry("/META-INF/jetty-webapp-context.xml");
            if (url != null)
            {
                contextXmlUri = url.toURI();
            }

            if (contextXmlUri == null)
            {
                //Didn't find specially named file, try looking for a property that names a context xml file to use
                if (_properties != null)
                {
                    String tmp = (String)_properties.get(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);
                    if (tmp != null)
                    {
                        String[] filenames = tmp.split("[,;]");
                        if (filenames != null && filenames.length > 0)
                        {
                            String filename = filenames[0]; //should only be 1 filename in this usage
                            String jettyHome = (String)getServer().getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
                            if (jettyHome == null)
                                jettyHome = System.getProperty(OSGiServerConstants.JETTY_HOME);
                            Resource res = findFile(filename, jettyHome, overrideBundleInstallLocation, _bundle);
                            if (res != null)
                            {
                                contextXmlUri = res.getURI();
                            }
                        }
                    }
                }
            }
            if (contextXmlUri == null)
                return;

            // Apply it just as the standard jetty ContextProvider would do
            LOG.info("Applying {} to {}", contextXmlUri, _webApp);

            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.newResource(contextXmlUri));
            WebAppClassLoader.runWithServerClassAccess(() ->
            {
                HashMap<String, String> properties = new HashMap<>();
                xmlConfiguration.getIdMap().put("Server", getDeploymentManager().getServer());
                properties.put(OSGiWebappConstants.JETTY_BUNDLE_ROOT, rootResource.toString());
                properties.put(OSGiServerConstants.JETTY_HOME, (String)getDeploymentManager().getServer().getAttribute(OSGiServerConstants.JETTY_HOME));
                xmlConfiguration.getProperties().putAll(properties);
                xmlConfiguration.configure(_webApp);
                return null;
            });
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }
    
    

    
    //TODO
    private void configureContextHandler(ContextHandler contextHandler)
        throws Exception
    {
        //Set the base resource of the ContextHandler, if not already set, can also be overridden by the context xml file
        if (contextHandler != null && contextHandler.getBaseResource() == null)
        {
            contextHandler.setBaseResource(rootResource);
        }

        //Use a classloader that knows about the common jetty parent loader, and also the bundle                  
        OSGiClassLoader classLoader = new OSGiClassLoader(getServer().getParentClassLoaderForWebapps(), _bundle);

        //if there is a context file, find it and apply it
        if (contextFile == null && contextHandler == null)
            throw new IllegalStateException("No context file or ContextHandler");

        if (contextFile != null)
        {
            //apply the contextFile, creating the ContextHandler, the DeploymentManager will register it in the ContextHandlerCollection
            Resource res = null;

            String jettyHome = (String)getServer().getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
            if (jettyHome == null)
                jettyHome = System.getProperty(OSGiServerConstants.JETTY_HOME);

            res = findFile(_contextFile, jettyHome, bundleOverrideLocation, _bundle);

            //apply the context xml file, either to an existing ContextHandler, or letting the
            //it create the ContextHandler as necessary
            if (res != null)
            {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();

                if (LOG.isDebugEnabled())
                    LOG.debug("Context classloader = {}", cl);
                try
                {
                    Thread.currentThread().setContextClassLoader(classLoader);

                    XmlConfiguration xmlConfiguration = new XmlConfiguration(res);
                    HashMap properties = new HashMap();
                    //put the server instance in
                    properties.put("Server", getServer().getServer());
                    //put in the location of the bundle root
                    properties.put(OSGiWebappConstants.JETTY_BUNDLE_ROOT, rootResource.toString());

                    // insert the bundle's location as a property.
                    xmlConfiguration.getProperties().putAll(properties);

                    if (_contextHandler == null)
                        _contextHandler = (ContextHandler)xmlConfiguration.configure();
                    else
                        xmlConfiguration.configure(_contextHandler);
                }
                finally
                {
                    Thread.currentThread().setContextClassLoader(cl);
                }
            }
        }

        //Set up the class loader we created
        _contextHandler.setClassLoader(classLoader);

        //If a bundle/service property specifies context path, let it override the context xml
        String contextPath = (String)_osgiProperties.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
        if (contextPath != null)
            _contextHandler.setContextPath(contextPath);

        //osgi Enterprise Spec r4 p.427
        _contextHandler.setAttribute(OSGiWebappConstants.OSGI_BUNDLECONTEXT, _bundle.getBundleContext());

        //make sure we protect also the osgi dirs specified by OSGi Enterprise spec
        String[] targets = _contextHandler.getProtectedTargets();
        int length = (targets == null ? 0 : targets.length);

        String[] updatedTargets = null;
        if (targets != null)
        {
            updatedTargets = new String[length + OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
            System.arraycopy(targets, 0, updatedTargets, 0, length);
        }
        else
            updatedTargets = new String[OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
        System.arraycopy(OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS, 0, updatedTargets, length, OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length);
        _contextHandler.setProtectedTargets(updatedTargets);
    }

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }
    
    @Override
    public String getEnvironmentName()
    {
        return _environment;
    }

    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }
    
    /**
     * Get the extractWars.
     * This is equivalent to getting the {@link Deployable#EXTRACT_WARS} property.
     *
     * @return the extractWars
     */
    public boolean isExtractWars()
    {
        return Boolean.parseBoolean(_properties.get(Deployable.EXTRACT_WARS));
    }

    /**
     * Set the extractWars.
     * This is equivalent to setting the {@link Deployable#EXTRACT_WARS} property.
     *
     * @param extractWars the extractWars to set
     */
    public void setExtractWars(boolean extractWars)
    {
        _properties.put(Deployable.EXTRACT_WARS, Boolean.toString(extractWars));
    }

    /**
     * Get the parentLoaderPriority.
     * This is equivalent to getting the {@link Deployable#PARENT_LOADER_PRIORITY} property.
     *
     * @return the parentLoaderPriority
     */
    public boolean isParentLoaderPriority()
    {
        return Boolean.parseBoolean(_properties.get(Deployable.PARENT_LOADER_PRIORITY));
    }

    /**
     * Set the parentLoaderPriority.
     * This is equivalent to setting the {@link Deployable#PARENT_LOADER_PRIORITY} property.
     *
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _properties.put(Deployable.PARENT_LOADER_PRIORITY, Boolean.toString(parentLoaderPriority));
    }

    /**
     * Get the defaultsDescriptor.
     * This is equivalent to getting the {@link Deployable#DEFAULTS_DESCRIPTOR} property.
     *
     * @return the defaultsDescriptor
     */
    public String getDefaultsDescriptor()
    {
        return _properties.get(Deployable.DEFAULTS_DESCRIPTOR);
    }

    /**
     * Set the defaultsDescriptor.
     * This is equivalent to setting the {@link Deployable#DEFAULTS_DESCRIPTOR} property.
     *
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _properties.put(Deployable.DEFAULTS_DESCRIPTOR, defaultsDescriptor);
    }

    /**
     * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} property.
     * @param configurations The configuration class names as a comma separated list
     */
    public void setConfigurationClasses(String configurations)
    {
        setConfigurationClasses(StringUtil.isBlank(configurations) ? null : configurations.split(","));
    }

    /**
     * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} property.
     * @param configurations The configuration class names.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        _properties.put(Deployable.CONFIGURATION_CLASSES, (configurations == null)
            ? null
            : String.join(",", configurations));
    }

    /**
     *
     * This is equivalent to getting the {@link Deployable#CONFIGURATION_CLASSES} property.
     * @return The configuration class names.
     */
    public String[] getConfigurationClasses()
    {
        String cc = _properties.get(Deployable.CONFIGURATION_CLASSES);
        return cc == null ? new String[0] : cc.split(",");
    }

    /**
     * Set the temporary directory for deployment.
     * <p>
     * This is equivalent to setting the {@link Deployable#BASE_TEMP_DIR} property.
     * If not set, then the <code>java.io.tmpdir</code> System Property is used.
     *
     * @param directory the new work directory
     */
    public void setTempDir(String directory)
    {
        _properties.put(Deployable.BASE_TEMP_DIR, directory);
    }

    /**
     * Set the temporary directory for deployment.
     * <p>
     * This is equivalent to setting the {@link Deployable#BASE_TEMP_DIR} property.
     * If not set, then the <code>java.io.tmpdir</code> System Property is used.
     *
     * @param directory the new work directory
     */
    public void setTempDir(File directory)
    {
        _properties.put(Deployable.BASE_TEMP_DIR, directory.getAbsolutePath());
    }

    /**
     * Get the temporary directory for deployment.
     * <p>
     * This is equivalent to getting the {@link Deployable#BASE_TEMP_DIR} property.
     *
     * @return the user supplied work directory (null if user has not set Temp Directory yet)
     */
    public File getTempDir()
    {
        String tmpDir = _properties.get(Deployable.BASE_TEMP_DIR);
        return tmpDir == null ? null : new File(tmpDir);
    }
    
    /**
     * @param tldBundles Comma separated list of bundles that contain tld jars
     * that should be setup on the context instances created here.
     */
    public void setTldBundles(String tldBundles)
    {
       _properties.put(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, tldBundles);
    }

    /**
     * @return The list of bundles that contain tld jars that should be setup on
     * the contexts create here.
     */
    public String getTldBundles()
    {
        return _properties.get(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
    }
}
