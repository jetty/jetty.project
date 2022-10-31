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

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.bindings.StandardStarter;
import org.eclipse.jetty.deploy.bindings.StandardStopper;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.util.FakeURLClassLoader;
import org.eclipse.jetty.osgi.util.TldBundleDiscoverer;
import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServerInstanceWrapper
 *
 * Configures and starts a jetty Server instance.
 */
public class ServerInstanceWrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerInstanceWrapper.class.getName());

    /**
     * The value of this property points to the parent director of the jetty.xml
     * configuration file currently executed. Everything is passed as a URL to
     * support the case where the bundle is zipped.
     */
    public static final String PROPERTY_THIS_JETTY_XML_FOLDER_URL = "this.jetty.xml.parent.folder.url";

    private static Collection<TldBundleDiscoverer> __containerTldBundleDiscoverers = new ArrayList<>();

    private final String _managedServerName;
    /**
     * The managed jetty server
     */
    private Server _server;

   /**
    * OSGi properties associated with the Server
    */
    private Dictionary<String, Object> _properties;

    /**
     * List of jetty config files to apply to the Server
     */
    List<URL> _jettyConfigurations;

    /**
     * The collection of contexts deployed to the server
     */
    private ContextHandlerCollection _ctxtCollection;

    /**
     * This is the class loader that should be the parent classloader of any
     * webapp classloader. It is in fact the _libExtClassLoader with a trick to
     * let the TldScanner find the jars where the tld files are.
     */
    private ClassLoader _commonParentClassLoaderForWebapps;

    private DeploymentManager _deploymentManager;

    public static void addContainerTldBundleDiscoverer(TldBundleDiscoverer tldBundleDiscoverer)
    {
        __containerTldBundleDiscoverers.add(tldBundleDiscoverer);
    }

    public static Collection<TldBundleDiscoverer> getContainerTldBundleDiscoverers()
    {
        return __containerTldBundleDiscoverers;
    }

    /**
     * Create and wrap a Server based on properties and jetty config files
     */
    public ServerInstanceWrapper(String managedServerName, Dictionary<String, Object> props, List<URL> jettyConfigurations)
    {
        this(managedServerName, null, props, jettyConfigurations);
    }

    /*
     * Apply some jetty config files to a pre-existing Server
     */
    public ServerInstanceWrapper(String managedServerName, Server server, Dictionary<String, Object> props, List<URL> jettyConfigurations)
    {
        _managedServerName = managedServerName;
        _properties = props;
        _jettyConfigurations = jettyConfigurations;
        _server = server;
    }

    public String getManagedServerName()
    {
        return _managedServerName;
    }

    /**
     * The classloader that should be the parent classloader for each webapp
     * deployed on this server.
     *
     * @return the classloader
     */
    public ClassLoader getParentClassLoaderForWebapps()
    {
        return _commonParentClassLoaderForWebapps;
    }

    /**
     * @return The deployment manager registered on this server.
     */
    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }

    /**
     * @return The app provider registered on this server.
     */
    public Server getServer()
    {
        return _server;
    }

    /**
     * @return The collection of context handlers
     */
    public ContextHandlerCollection getContextHandlerCollection()
    {
        return _ctxtCollection;
    }

    public void start() throws Exception
    {
        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        try
        {
            List<URL> sharedURLs = getManagedJettySharedLibFolderUrls(_properties);

            // passing this bundle's classloader as the context classloader
            // makes sure there is access to all the jetty's bundles
            ClassLoader libExtClassLoader = LibExtClassLoaderHelper.createLibExtClassLoader(null, sharedURLs, JettyBootstrapActivator.class.getClassLoader());

            if (LOG.isDebugEnabled())
                LOG.debug("LibExtClassLoader = {}", libExtClassLoader);

            Thread.currentThread().setContextClassLoader(libExtClassLoader);


            configure();

            //if support for jsp is enabled, we need to convert locations of bundles that contain tlds into urls.
            //these are tlds that we want jasper to treat as if they are on the container's classpath. Web bundles
            //can use the Require-TldBundle MANIFEST header to name other tld-containing bundles that should be regarded
            //as on the webapp classpath.
            if (!__containerTldBundleDiscoverers.isEmpty())
            {
                Set<URL> urls = new HashSet<>();
                //discover bundles with tlds that need to be on the container's classpath as URLs
                for (TldBundleDiscoverer d : __containerTldBundleDiscoverers)
                {
                    URL[] list = d.getUrlsForBundlesWithTlds(_deploymentManager, BundleFileLocatorHelperFactory.getFactory().getHelper());
                    if (list != null)
                    {
                        for (URL u : list)
                        {
                            urls.add(u);
                        }
                    }
                }
                _commonParentClassLoaderForWebapps = new FakeURLClassLoader(libExtClassLoader, urls.toArray(new URL[urls.size()]));
            }
            else
                _commonParentClassLoaderForWebapps = libExtClassLoader;

            if (LOG.isDebugEnabled())
                LOG.debug("common classloader = {}", _commonParentClassLoaderForWebapps);

            _server.start();
        }
        catch (Exception e)
        {
            if (_server != null)
            {
                try
                {
                    _server.stop();
                }
                catch (Exception x)
                {
                    LOG.trace("IGNORED", x);
                }
            }
            throw e;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(contextCl);
        }
    }

    public void stop()
    {
        try
        {
            if (_server.isRunning())
            {
                _server.stop();
            }
        }
        catch (Exception e)
        {
            LOG.warn("Failed to stop server", e);
        }
    }

    private void configure() throws Exception
    {

        applyConfigFiles();
        if (_server == null)
            throw new IllegalStateException("No server for ServerWrapper");
        configureDeployer();
    }

    private void applyConfigFiles()
    throws Exception
    {
        //config files provided as part of the osgi properties
        String jettyConfigurationUrls = (String)_properties.get(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS);
        List<URL> configs = jettyConfigurationUrls != null ? Util.fileNamesAsURLs(jettyConfigurationUrls, StringUtil.DEFAULT_DELIMS) : null;

        if (_jettyConfigurations == null)
            _jettyConfigurations = new ArrayList<URL>();

        if (configs != null)
            _jettyConfigurations.addAll(configs);

        Map<String, Object> idMap = new HashMap<>();
        if (_server != null)
        {
            //Put in a mapping for the id "Server" and the name of the server as the instance being configured
            idMap.put("Server", _server);
            idMap.put((String)_properties.get(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME), _server);
        }

        Map<String, String> properties = new HashMap<>();
        if (_properties != null)
        {
            Enumeration<String> en = _properties.keys();
            while (en.hasMoreElements())
            {
                String key = en.nextElement();
                Object value = _properties.get(key);
                properties.put(key, value.toString());
                if (_server != null)
                    _server.setAttribute(key, value);
            }
        }

        for (URL jettyConfiguration : _jettyConfigurations)
        {
            try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
            {
                // Execute a Jetty configuration file
                XmlConfiguration config = new XmlConfiguration(resourceFactory.newResource(jettyConfiguration));

                config.getIdMap().putAll(idMap);
                config.getProperties().putAll(properties);

                // #334062 compute the URL of the folder that contains the
                // conf file and set it as a property so we can compute relative paths
                // from it.
                String urlPath = jettyConfiguration.toString();
                int lastSlash = urlPath.lastIndexOf('/');
                if (lastSlash > 4)
                {
                    urlPath = urlPath.substring(0, lastSlash);
                    config.getProperties().put(PROPERTY_THIS_JETTY_XML_FOLDER_URL, urlPath);
                }

                Object o = config.configure();
                if (_server == null)
                    _server = (Server)o;

                idMap = config.getIdMap();
            }
            catch (Exception e)
            {
                LOG.warn("Configuration error in {}", jettyConfiguration);
                throw e;
            }
        }
    }

    /**
     * It is assumed the server has already been configured with the ContextHandlerCollection structure.
     */
    private void configureDeployer()
    {
        if (_server == null)
            return;

        // Get the context handler
        _ctxtCollection = _server.getDescendant(ContextHandlerCollection.class);

        if (_ctxtCollection == null)
            throw new IllegalStateException("ERROR: No ContextHandlerCollection configured in Server");

        List<String> providerClassNames = new ArrayList<>();

        // get a deployerManager and some providers
        Collection<DeploymentManager> deployers = _server.getBeans(DeploymentManager.class);
        if (deployers != null && !deployers.isEmpty())
        {
            _deploymentManager = deployers.iterator().next();
        }
        else
        {
            //add some kind of default
            _deploymentManager = new DeploymentManager();
            _deploymentManager.setContexts(_ctxtCollection);
            _server.addBean(_deploymentManager);
        }

        _deploymentManager.setUseStandardBindings(false);
        List<AppLifeCycle.Binding> deploymentLifeCycleBindings = new ArrayList<>();
        deploymentLifeCycleBindings.add(new OSGiDeployer(this.getServer()));
        deploymentLifeCycleBindings.add(new StandardStarter());
        deploymentLifeCycleBindings.add(new StandardStopper());
        deploymentLifeCycleBindings.add(new OSGiUndeployer(this.getServer()));
        _deploymentManager.setLifeCycleBindings(deploymentLifeCycleBindings);
    }

    /**
     * Get the Jetty Shared Lib Folder URLs in a form that is suitable for
     * {@link LibExtClassLoaderHelper} to use.
     *
     * @param props the properties to look for the configuration in
     * @return the list of URLs found, or null if none found
     */
    private List<URL> getManagedJettySharedLibFolderUrls(Dictionary<String, Object> props)
    {
        String sharedURLs = (String)props.get(OSGiServerConstants.MANAGED_JETTY_SHARED_LIB_FOLDER_URLS);
        if (StringUtil.isBlank(sharedURLs))
        {
            return null;
        }

        List<URL> libURLs = new ArrayList<>();

        StringTokenizer tokenizer = new StringTokenizer(sharedURLs, StringUtil.DEFAULT_DELIMS, false);
        while (tokenizer.hasMoreTokens())
        {
            String tok = tokenizer.nextToken();
            try
            {
                URL url = new URL(tok);
                url = BundleFileLocatorHelperFactory.getFactory().getHelper().getFileURL(url);
                if (url.getProtocol().equals("file"))
                {
                    libURLs.add(new URL("jar:" + url.toExternalForm() + "!/"));
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unrecognized Jetty Shared Lib URL: {}", url);
                }
            }
            catch (Throwable mfe)
            {
                LOG.warn("Unable to process legacy lib folder {}", tok, mfe);
            }
        }
        return libURLs;
    }
}
