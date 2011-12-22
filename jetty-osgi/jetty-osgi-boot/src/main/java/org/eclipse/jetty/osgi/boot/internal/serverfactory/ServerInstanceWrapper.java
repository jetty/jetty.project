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
// ========================================================================
package org.eclipse.jetty.osgi.boot.internal.serverfactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiAppProvider;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.internal.jsp.TldLocatableURLClassloader;
import org.eclipse.jetty.osgi.boot.internal.webapp.LibExtClassLoaderHelper;
import org.eclipse.jetty.osgi.boot.internal.webapp.WebBundleDeployerHelper;
import org.eclipse.jetty.osgi.boot.utils.WebappRegistrationCustomizer;
import org.eclipse.jetty.osgi.boot.utils.internal.DefaultFileLocatorHelper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.xml.sax.SAXParseException;


/**
 * Exposes a Jetty Server to be managed by an OSGi ManagedServiceFactory
 * Configure and start it.
 * Can also be used from the ManagedServiceFactory
 */
public class ServerInstanceWrapper {

    /** The value of this property points to the parent director of
     * the jetty.xml configuration file currently executed.
     * Everything is passed as a URL to support the
     * case where the bundle is zipped. */
    public static final String PROPERTY_THIS_JETTY_XML_FOLDER_URL = "this.jetty.xml.parent.folder.url";

    private static Logger __logger = Log.getLogger(ServerInstanceWrapper.class.getName());
    
    private final String _managedServerName;
    
    /**
     * The managed jetty server
     */
    private Server _server;
    private ContextHandlerCollection _ctxtHandler;

    /**
     * This is the class loader that should be the parent classloader of any
     * webapp classloader. It is in fact the _libExtClassLoader with a trick to
     * let the TldScanner find the jars where the tld files are.
     */
    private ClassLoader _commonParentClassLoaderForWebapps;
    private DeploymentManager _deploymentManager;
    private OSGiAppProvider _provider;
    
    private WebBundleDeployerHelper _webBundleDeployerHelper;
    
    
    public ServerInstanceWrapper(String managedServerName)
    {
        _managedServerName = managedServerName;
    }
    
    public String getManagedServerName()
    {
        return _managedServerName;
    }
    
    /**
     * The classloader that should be the parent classloader for 
     * each webapp deployed on this server.
     * @return
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
    public OSGiAppProvider getOSGiAppProvider()
    {
        return _provider;
    }
    
    
    public Server getServer()
    {
        return _server;
    }
    
    
    public WebBundleDeployerHelper getWebBundleDeployerHelp()
    {
        return _webBundleDeployerHelper;
    }
    
    /**
     * @return The collection of context handlers
     */
    public ContextHandlerCollection getContextHandlerCollection()
    {
        return _ctxtHandler;
    }

    
    public void start(Server server, Dictionary props)
    {
        _server = server;
        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        try
        {
            // passing this bundle's classloader as the context classlaoder
            // makes sure there is access to all the jetty's bundles
            ClassLoader libExtClassLoader = null;
            String sharedURLs = (String)props.get(OSGiServerConstants.MANAGED_JETTY_SHARED_LIB_FOLDER_URLS);
            try
            {
                List<File> shared = sharedURLs != null ? extractFiles(sharedURLs) : null;
                libExtClassLoader = LibExtClassLoaderHelper.createLibExtClassLoader(
                        shared, null, server, JettyBootstrapActivator.class.getClassLoader());
            }
            catch (MalformedURLException e)
            {
                e.printStackTrace();
            }

            Thread.currentThread().setContextClassLoader(libExtClassLoader);
            
            configure(server, props);

            init();

            //now that we have an app provider we can call the registration customizer.
            try
            {
                URL[] jarsWithTlds = getJarsWithTlds();
                _commonParentClassLoaderForWebapps = jarsWithTlds == null
                        ? libExtClassLoader
                        :new TldLocatableURLClassloader(libExtClassLoader,jarsWithTlds);
            }
            catch (MalformedURLException e)
            {
                e.printStackTrace();
            }

            
            server.start();
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(contextCl);
        }
        _webBundleDeployerHelper = new WebBundleDeployerHelper(this);
    }
    
    
    public void stop()
    {
        try {
            if (_server.isRunning())
            {
                _server.stop();
            }
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * TODO: right now only the jetty-jsp bundle is scanned for common taglibs.
     * Should support a way to plug more bundles that contain taglibs.
     * 
     * The jasper TldScanner expects a URLClassloader to parse a jar for the
     * /META-INF/*.tld it may contain. We place the bundles that we know contain
     * such tag-libraries. Please note that it will work if and only if the
     * bundle is a jar (!) Currently we just hardcode the bundle that contains
     * the jstl implemenation.
     * 
     * A workaround when the tld cannot be parsed with this method is to copy
     * and paste it inside the WEB-INF of the webapplication where it is used.
     * 
     * Support only 2 types of packaging for the bundle: - the bundle is a jar
     * (recommended for runtime.) - the bundle is a folder and contain jars in
     * the root and/or in the lib folder (nice for PDE developement situations)
     * Unsupported: the bundle is a jar that embeds more jars.
     * 
     * @return
     * @throws Exception
     */
    private URL[] getJarsWithTlds() throws Exception
    {
        ArrayList<URL> res = new ArrayList<URL>();
        WebBundleDeployerHelper.staticInit();//that is not looking great.
        for (WebappRegistrationCustomizer regCustomizer : WebBundleDeployerHelper.JSP_REGISTRATION_HELPERS)
        {
            URL[] urls = regCustomizer.getJarsWithTlds(_provider, WebBundleDeployerHelper.BUNDLE_FILE_LOCATOR_HELPER);
            for (URL url : urls)
            {
                if (!res.contains(url))
                    res.add(url);
            }
        }
        if (!res.isEmpty())
            return res.toArray(new URL[res.size()]);
        else
            return null;
    }
    
    private void configure(Server server, Dictionary props) throws Exception
    {
        String jettyConfigurationUrls = (String) props.get(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS);
        List<URL> jettyConfigurations = jettyConfigurationUrls != null
            ? extractResources(jettyConfigurationUrls) : null;
        if (jettyConfigurations == null || jettyConfigurations.isEmpty())
        {
            return;
        }
        Map<String,Object> id_map = new HashMap<String,Object>();
        id_map.put("Server",server);
        Map<String,String> properties = new HashMap<String,String>();
        Enumeration<Object> en = props.keys();
        while (en.hasMoreElements())
        {
            Object key = en.nextElement();
            Object value = props.get(key);
            properties.put(String.valueOf(key), String.valueOf(value));
        }

        for (URL jettyConfiguration : jettyConfigurations)
        {
            InputStream is = null;
            try
            {
                // Execute a Jetty configuration file
                Resource r = Resource.newResource(jettyConfiguration);
                is = r.getInputStream();
                XmlConfiguration config = new XmlConfiguration(is);
                config.getIdMap().putAll(id_map);
                
                //#334062 compute the URL of the folder that contains the jetty.xml conf file
                //and set it as a property so we can compute relative paths from it.
                String urlPath = jettyConfiguration.toString();
                int lastSlash = urlPath.lastIndexOf('/');
                if (lastSlash > 4)
                {
                    urlPath = urlPath.substring(0, lastSlash);
                    Map<String,String> properties2 = new HashMap<String,String>(properties);
                    properties2.put(PROPERTY_THIS_JETTY_XML_FOLDER_URL, urlPath);
                    config.getProperties().putAll(properties2);
                }
                else
                {
                    config.getProperties().putAll(properties);
                }
                config.configure();
                id_map=config.getIdMap();
            }
            catch (SAXParseException saxparse)
            {
                __logger.warn("Unable to configure the jetty/etc file " + jettyConfiguration,saxparse);
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
     * Locate the actual instance of the ContextDeployer and WebAppDeployer that
     * was created when configuring the server through jetty.xml. If there is no
     * such thing it won't be possible to deploy webapps from a context and we
     * throw IllegalStateExceptions.
     */
    private void init()
    {
        // Get the context handler
        _ctxtHandler = (ContextHandlerCollection)_server.getChildHandlerByClass(ContextHandlerCollection.class);
        
        // get a deployerManager
        List<DeploymentManager> deployers = _server.getBeans(DeploymentManager.class);
        if (deployers != null && !deployers.isEmpty())
        {
            _deploymentManager = deployers.get(0);
            
            for (AppProvider provider : _deploymentManager.getAppProviders())
            {
                if (provider instanceof OSGiAppProvider)
                {
                    _provider=(OSGiAppProvider)provider;
                    break;
                }
            }
            if (_provider == null)
            {
                //create it on the fly with reasonable default values.
                try
                {
                    _provider = new OSGiAppProvider();
                    _provider.setMonitoredDirResource(
                            Resource.newResource(getDefaultOSGiContextsHome(
                                    new File(System.getProperty("jetty.home"))).toURI()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                _deploymentManager.addAppProvider(_provider);
            }
        }

        if (_ctxtHandler == null || _provider==null)
            throw new IllegalStateException("ERROR: No ContextHandlerCollection or OSGiAppProvider configured");
        

    }
    
    /**
     * @return The default folder in which the context files of the osgi bundles
     *         are located and watched. Or null when the system property
     *         "jetty.osgi.contexts.home" is not defined.
     *         If the configuration file defines the OSGiAppProvider's context.
     *         This will not be taken into account.
     */
    File getDefaultOSGiContextsHome(File jettyHome)
    {
        String jettyContextsHome = System.getProperty("jetty.osgi.contexts.home");
        if (jettyContextsHome != null)
        {
            File contextsHome = new File(jettyContextsHome);
            if (!contextsHome.exists() || !contextsHome.isDirectory())
            {
                throw new IllegalArgumentException("the ${jetty.osgi.contexts.home} '" + jettyContextsHome + " must exist and be a folder");
            }
            return contextsHome;
        }
        return new File(jettyHome, "/contexts");
    }
    
    File getOSGiContextsHome()
    {
        return _provider.getContextXmlDirAsFile();
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
                urls.add(((DefaultFileLocatorHelper) WebBundleDeployerHelper
                        .BUNDLE_FILE_LOCATOR_HELPER).getLocalURL(new URL(tok)));
            }
            catch (Throwable mfe)
            {
                
            }
        }
        return urls;
    }
    
    /**
     * Get the folders that might contain jars for the legacy J2EE shared libraries
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
                url = ((DefaultFileLocatorHelper) WebBundleDeployerHelper
                    .BUNDLE_FILE_LOCATOR_HELPER).getFileURL(url);
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
                
            }
        }
        return files;
    }
    

}
