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

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.util.OSGiClassLoader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
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

    /**
     * OSGiApp
     */
    public class OSGiApp extends AbstractOSGiApp
    {
        private String _contextFile;
        private ContextHandler _contextHandler;
        private boolean _configured = false;

        public OSGiApp(DeploymentManager manager, AppProvider provider, Path path, Bundle bundle, String contextFile)
        {
            super(manager, provider, bundle, path);
            _contextFile = contextFile;
        }

        public OSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle, Dictionary properties, String contextFile, Path path)
        {
            super(manager, provider, bundle, properties, path);
            _contextFile = contextFile;
        }

        public String getContextFile()
        {
            return _contextFile;
        }

        public void setHandler(ContextHandler h)
        {
            _contextHandler = h;
        }

        public ContextHandler createContextHandler()
            throws Exception
        {
            configureContextHandler();
            return _contextHandler;
        }

        public void configureContextHandler()
            throws Exception
        {
            if (_configured)
                return;

            _configured = true;

            //Override for bundle root may have been set
            String bundleOverrideLocation = (String)_properties.get(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);

            //Location on filesystem of bundle or the bundle override location
            File bundleLocation = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(_bundle);
            File root = (bundleOverrideLocation == null ? bundleLocation : new File(bundleOverrideLocation));
            Resource rootResource = Resource.newResource(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(root.toURI().toURL()));

            //try and make sure the rootResource is useable - if its a jar then make it a jar file url
            if (rootResource.exists() && !rootResource.isDirectory() && !rootResource.toString().startsWith("jar:"))
            {
                Resource jarResource = JarResource.newJarResource(rootResource);
                if (jarResource.exists() && jarResource.isDirectory())
                    rootResource = jarResource;
            }

            //Set the base resource of the ContextHandler, if not already set, can also be overridden by the context xml file
            if (_contextHandler != null && _contextHandler.getBaseResource() == null)
            {
                _contextHandler.setBaseResource(rootResource);
            }

            //Use a classloader that knows about the common jetty parent loader, and also the bundle                  
            OSGiClassLoader classLoader = new OSGiClassLoader(getServer().getParentClassLoaderForWebapps(), _bundle);

            //if there is a context file, find it and apply it
            if (_contextFile == null && _contextHandler == null)
                throw new IllegalStateException("No context file or ContextHandler");

            if (_contextFile != null)
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
            String contextPath = (String)_properties.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
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
    }
    
    public static Path findFileInBundle(String fileName, String jettyHome, String bundleOverrideLocation, Bundle containingBundle)
    {
        Path path = null;

        //try to find the context file in the filesystem
        if (fileName.startsWith("/"))
        {
            try
            {
                path = Path.of(fileName);
                if (Files.exists(path))
                    return path;
                else
                    path = null; //try something else
            }
            catch (Exception e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error finding file {} in filesystem", fileName, e);
            }
        }

        if (path == null && jettyHome != null)
        {
        //try to find it relative to jetty home
            if (jettyHome.startsWith("\"") || jettyHome.startsWith("'"))
                jettyHome = jettyHome.substring(1);
            if (jettyHome.endsWith("\"") || (jettyHome.endsWith("'")))
                jettyHome = jettyHome.substring(0, jettyHome.length() - 1);

            try
            {
                 path = Path.of(jettyHome).resolve(fileName);
                 if (Files.exists(path))
                     return path;
                 else
                     path = null;
            }
            catch (Exception e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error finding file {} relative to jettyHome={}", fileName, jettyHome, e);
            }
        }
 

        //try to find it relative to an override location that has been specified               
        if (path == null && bundleOverrideLocation != null)
        {
            try
            {
                path = Path.of(bundleOverrideLocation).resolve(fileName);
                if (Files.exists(path))
                    return path;
                else
                    path = null;
            }
            catch (Exception e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error finding file {} relative to bundleOverrideLocation={}", fileName, bundleOverrideLocation, e);
            }
        }

        //try to find it relative to the bundle in which it is being deployed
        if (path == null && containingBundle != null)
        {
            if (fileName.startsWith("./"))
                fileName = fileName.substring(1);

            if (!fileName.startsWith("/"))
                fileName = "/" + fileName;

            URL entry = containingBundle.getEntry(fileName);
            if (entry != null)
            {
                path = Path.of(entry.toURI());
                if (Files.exists(path))
                    return path;
            }
        }
        return null;
    }

    public AbstractContextProvider(Server server, ContextFactory contextFactory)
    {
        _server = server;
        _contextFactory = contextFactory;
    }

    public Server getServer()
    {
        return _server;
    }

    @Override
    public ContextHandler createContextHandler(App app) throws Exception
    {
        if (app == null)
            return null;
        if (!(app instanceof OSGiApp))
            throw new IllegalStateException(app + " is not a BundleApp");

        //Create a ContextHandler suitable to deploy in OSGi
        ContextHandler h = _contextFactory.createContextHandler(app);
        return h;
    }

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }

    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }
    


}
