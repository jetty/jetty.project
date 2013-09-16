//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.osgi.boot.internal.serverfactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.bindings.StandardStarter;
import org.eclipse.jetty.deploy.bindings.StandardStopper;
import org.eclipse.jetty.osgi.boot.BundleContextProvider;
import org.eclipse.jetty.osgi.boot.BundleWebAppProvider;
import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiDeployer;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.OSGiUndeployer;
import org.eclipse.jetty.osgi.boot.ServiceContextProvider;
import org.eclipse.jetty.osgi.boot.ServiceWebAppProvider;
import org.eclipse.jetty.osgi.boot.internal.jsp.TldLocatableURLClassloader;
import org.eclipse.jetty.osgi.boot.internal.webapp.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.boot.internal.webapp.LibExtClassLoaderHelper;
import org.eclipse.jetty.osgi.boot.internal.webapp.WebBundleTrackerCustomizer;
import org.eclipse.jetty.osgi.boot.utils.WebappRegistrationCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.xml.sax.SAXParseException;

/**
 * ServerInstanceWrapper
 * 
 *  Configures and starts a jetty Server instance. 
 */
public class ServerInstanceWrapper
{

    /**
     * The value of this property points to the parent director of the jetty.xml
     * configuration file currently executed. Everything is passed as a URL to
     * support the case where the bundle is zipped.
     */
    public static final String PROPERTY_THIS_JETTY_XML_FOLDER_URL = "this.jetty.xml.parent.folder.url";

    private static Logger LOG = Log.getLogger(ServerInstanceWrapper.class.getName());
    
 

    private final String _managedServerName;

    /**
     * The managed jetty server
     */
    private Server _server;

    private ContextHandlerCollection _ctxtCollection;

    /**
     * This is the class loader that should be the parent classloader of any
     * webapp classloader. It is in fact the _libExtClassLoader with a trick to
     * let the TldScanner find the jars where the tld files are.
     */
    private ClassLoader _commonParentClassLoaderForWebapps;

    private DeploymentManager _deploymentManager;
    
    
    /* ------------------------------------------------------------ */
    public ServerInstanceWrapper(String managedServerName)
    {
        _managedServerName = managedServerName;
    }

    /* ------------------------------------------------------------ */ 
    public String getManagedServerName()
    {
        return _managedServerName;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * The classloader that should be the parent classloader for each webapp
     * deployed on this server.
     * 
     * @return
     */
    public ClassLoader getParentClassLoaderForWebapps()
    {
        return _commonParentClassLoaderForWebapps;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return The deployment manager registered on this server.
     */
    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return The app provider registered on this server.
     */
    public Server getServer()
    {
        return _server;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The collection of context handlers
     */
    public ContextHandlerCollection getContextHandlerCollection()
    {
        return _ctxtCollection;
    }
    
    
    /* ------------------------------------------------------------ */
    public void start(Server server, Dictionary props) throws Exception
    {
        _server = server;
        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        try
        {
            // passing this bundle's classloader as the context classloader
            // makes sure there is access to all the jetty's bundles
            ClassLoader libExtClassLoader = null;
            String sharedURLs = (String) props.get(OSGiServerConstants.MANAGED_JETTY_SHARED_LIB_FOLDER_URLS);

            List<File> shared = sharedURLs != null ? extractFiles(sharedURLs) : null;
            libExtClassLoader = LibExtClassLoaderHelper.createLibExtClassLoader(shared, null, server, JettyBootstrapActivator.class.getClassLoader());

            if (LOG.isDebugEnabled()) LOG.debug("LibExtClassLoader = "+libExtClassLoader);
            
            Thread.currentThread().setContextClassLoader(libExtClassLoader);

            configure(server, props);

            init();

            URL[] jarsWithTlds = getJarsWithTlds();
            _commonParentClassLoaderForWebapps = jarsWithTlds == null ? libExtClassLoader : new TldLocatableURLClassloader(libExtClassLoader, jarsWithTlds);
            
            if (LOG.isDebugEnabled()) LOG.debug("common classloader = "+_commonParentClassLoaderForWebapps);

            server.start();
        }
        catch (Exception e)
        {
            if (server != null)
            {
                try
                {
                    server.stop();
                }
                catch (Exception x)
                {
                    LOG.ignore(x);
                }
            }
            throw e;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(contextCl);
        }
    }
    
    /* ------------------------------------------------------------ */
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
            LOG.warn(e);
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * TODO: right now only the jetty-jsp bundle is scanned for common taglibs.
     * Should support a way to plug more bundles that contain taglibs.
     * 
     * The jasper TldScanner expects a URLClassloader to parse a jar for the
     * /META-INF/*.tld it may contain. We place the bundles that we know contain
     * such tag-libraries. Please note that it will work if and only if the
     * bundle is a jar (!) Currently we just hardcode the bundle that contains
     * the jstl implementation.
     * 
     * A workaround when the tld cannot be parsed with this method is to copy
     * and paste it inside the WEB-INF of the webapplication where it is used.
     * 
     * Support only 2 types of packaging for the bundle: - the bundle is a jar
     * (recommended for runtime.) - the bundle is a folder and contain jars in
     * the root and/or in the lib folder (nice for PDE development situations)
     * Unsupported: the bundle is a jar that embeds more jars.
     * 
     * @return
     * @throws Exception
     */
    private URL[] getJarsWithTlds() throws Exception
    {
        
        //Jars that are added onto the equivalent of the container classpath are:
        // jstl jars: identified by the class WhenTag (and the boot-bundle manifest imports the jstl packages
        // bundles identified by System property org.eclipse.jetty.osgi.tldbundles
        // bundle symbolic name patterns defined in the DeploymentManager
        //
        // Any bundles mentioned in the Require-TldBundle manifest header of the webapp bundle MUST ALSO HAVE Import-Bundle
        // in order to get them onto the classpath of the webapp.
        
        ArrayList<URL> res = new ArrayList<URL>();
        for (WebappRegistrationCustomizer regCustomizer : WebBundleTrackerCustomizer.JSP_REGISTRATION_HELPERS)
        {
            URL[] urls = regCustomizer.getJarsWithTlds(_deploymentManager, BundleFileLocatorHelperFactory.getFactory().getHelper());
            for (URL url : urls)
            {
                if (!res.contains(url)) res.add(url);
            }
        }
        if (!res.isEmpty())
            return res.toArray(new URL[res.size()]);
        else
            return null;
    }
    
    
    /* ------------------------------------------------------------ */
    private void configure(Server server, Dictionary props) throws Exception
    {
        String jettyConfigurationUrls = (String) props.get(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS);
        List<URL> jettyConfigurations = jettyConfigurationUrls != null ? extractResources(jettyConfigurationUrls) : null;
        if (jettyConfigurations == null || jettyConfigurations.isEmpty()) { return; }
        Map<String, Object> id_map = new HashMap<String, Object>();
        
        //Put in a mapping for the id "Server" and the name of the server as the instance being configured
        id_map.put("Server", server);
        id_map.put((String)props.get(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME), server);
        
        Map<String, String> properties = new HashMap<String, String>();
        Enumeration<Object> en = props.keys();
        while (en.hasMoreElements())
        {
            Object key = en.nextElement();
            Object value = props.get(key);
            String keyStr = String.valueOf(key);
            String valStr = String.valueOf(value);
            properties.put(keyStr, valStr);
            server.setAttribute(keyStr, valStr);
        }

        for (URL jettyConfiguration : jettyConfigurations)
        {
            InputStream is = null;
            try
            {
                // Execute a Jetty configuration file
                Resource r = Resource.newResource(jettyConfiguration);
                if (!r.exists())
                {
                    LOG.warn("File does not exist "+r);
                    continue;
                }
                is = r.getInputStream();
                XmlConfiguration config = new XmlConfiguration(is);
                config.getIdMap().putAll(id_map);

                // #334062 compute the URL of the folder that contains the
                // jetty.xml conf file
                // and set it as a property so we can compute relative paths
                // from it.
                String urlPath = jettyConfiguration.toString();
                int lastSlash = urlPath.lastIndexOf('/');
                if (lastSlash > 4)
                {
                    urlPath = urlPath.substring(0, lastSlash);
                    Map<String, String> properties2 = new HashMap<String, String>(properties);
                    properties2.put(PROPERTY_THIS_JETTY_XML_FOLDER_URL, urlPath);
                    config.getProperties().putAll(properties2);
                }
                else
                {
                    config.getProperties().putAll(properties);
                }
                config.configure();
                id_map = config.getIdMap();
            }
            catch (SAXParseException saxparse)
            {
                LOG.warn("Unable to configure the jetty/etc file " + jettyConfiguration, saxparse);
                throw saxparse;
            }
            finally
            {
                IO.close(is);
            }
        }

    }

    /**
     * Must be called after the server is configured. 
     * 
     * It is assumed the server has already been configured with the ContextHandlerCollection structure.
     * 
     */
    private void init()
    {
        // Get the context handler
        _ctxtCollection = (ContextHandlerCollection) _server.getChildHandlerByClass(ContextHandlerCollection.class);

        if (_ctxtCollection == null) 
            throw new IllegalStateException("ERROR: No ContextHandlerCollection configured in Server");
        
        List<String> providerClassNames = new ArrayList<String>();
        
        // get a deployerManager and some providers
        List<DeploymentManager> deployers = _server.getBeans(DeploymentManager.class);
        if (deployers != null && !deployers.isEmpty())
        {
            _deploymentManager = deployers.get(0);
            
            for (AppProvider provider : _deploymentManager.getAppProviders())
            {
               providerClassNames.add(provider.getClass().getName());
            }
        }
        else
        {
            //add some kind of default
            _deploymentManager = new DeploymentManager();
            _deploymentManager.setContexts(_ctxtCollection);
            _server.addBean(_deploymentManager);
        }

        _deploymentManager.setUseStandardBindings(false);
        List<AppLifeCycle.Binding> deploymentLifeCycleBindings = new ArrayList<AppLifeCycle.Binding>();
        deploymentLifeCycleBindings.add(new OSGiDeployer());
        deploymentLifeCycleBindings.add(new StandardStarter());
        deploymentLifeCycleBindings.add(new StandardStopper());
        deploymentLifeCycleBindings.add(new OSGiUndeployer());
        _deploymentManager.setLifeCycleBindings(deploymentLifeCycleBindings);
        
        if (!providerClassNames.contains(BundleWebAppProvider.class.getName()))
        {
            // create it on the fly with reasonable default values.
            try
            {
                BundleWebAppProvider webAppProvider = new BundleWebAppProvider(this);
                _deploymentManager.addAppProvider(webAppProvider);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }

        if (!providerClassNames.contains(ServiceWebAppProvider.class.getName()))
        {
            // create it on the fly with reasonable default values.
            try
            {
                ServiceWebAppProvider webAppProvider = new ServiceWebAppProvider(this);
                _deploymentManager.addAppProvider(webAppProvider);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }

        if (!providerClassNames.contains(BundleContextProvider.class.getName()))
        {
            try
            {
                BundleContextProvider contextProvider = new BundleContextProvider(this);
                _deploymentManager.addAppProvider(contextProvider);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }

        if (!providerClassNames.contains(ServiceContextProvider.class.getName()))
        {
            try
            {
                ServiceContextProvider contextProvider = new ServiceContextProvider(this);
                _deploymentManager.addAppProvider(contextProvider);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
    }

    /**
     * @return The default folder in which the context files of the osgi bundles
     *         are located and watched. Or null when the system property
     *         "jetty.osgi.contexts.home" is not defined. If the configuration
     *         file defines the OSGiAppProvider's context. This will not be
     *         taken into account.
     */
    File getDefaultOSGiContextsHome(File jettyHome)
    {
        String jettyContextsHome = System.getProperty("jetty.osgi.contexts.home");
        if (jettyContextsHome != null)
        {
            File contextsHome = new File(jettyContextsHome);
            if (!contextsHome.exists() || !contextsHome.isDirectory())
            { 
                throw new IllegalArgumentException("the ${jetty.osgi.contexts.home} '" 
                                                   + jettyContextsHome
                                                   + " must exist and be a folder"); 
            }
            return contextsHome;
        }
        return new File(jettyHome, "/contexts");
    }


    /**
     * @return the urls in this string.
     */
    private List<URL> extractResources(String propertyValue)
    {
        StringTokenizer tokenizer = new StringTokenizer(propertyValue, ",;", false);
        List<URL> urls = new ArrayList<URL>();
        while (tokenizer.hasMoreTokens())
        {
            String tok = tokenizer.nextToken();
            try
            {
                urls.add(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(new URL(tok)));
            }
            catch (Throwable mfe)
            {
                LOG.warn(mfe);
            }
        }
        return urls;
    }

    /**
     * Get the folders that might contain jars for the legacy J2EE shared
     * libraries
     */
    private List<File> extractFiles(String propertyValue)
    {
        StringTokenizer tokenizer = new StringTokenizer(propertyValue, ",;", false);
        List<File> files = new ArrayList<File>();
        while (tokenizer.hasMoreTokens())
        {
            String tok = tokenizer.nextToken();
            try
            {
                URL url = new URL(tok);
                url = BundleFileLocatorHelperFactory.getFactory().getHelper().getFileURL(url);
                if (url.getProtocol().equals("file"))
                {
                    Resource res = Resource.newResource(url);
                    File folder = res.getFile();
                    if (folder != null)
                    {
                        files.add(folder);
                    }
                }
            }
            catch (Throwable mfe)
            {
                LOG.warn(mfe);
            }
        }
        return files;
    }

}
