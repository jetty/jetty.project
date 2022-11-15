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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;

import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.bindings.StandardStarter;
import org.eclipse.jetty.deploy.bindings.StandardStopper;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JettyServerFactory
 *
 * Configures a jetty Server instance.
 */
public class JettyServerFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyServerFactory.class.getName());

    /**
     * The value of this property points to the parent director of the jetty.xml
     * configuration file currently executed. Everything is passed as a URL to
     * support the case where the bundle is zipped.
     */
    public static final String PROPERTY_THIS_JETTY_XML_FOLDER_URL = "this.jetty.xml.parent.folder.url";

    /*
     * Create a Server that is suitable for using in OSGi
     */
    public static Server createServer(String name, Dictionary<String, Object> props, List<URL> jettyConfigurations)
        throws Exception
    {
        Objects.requireNonNull(name);
        
        Server server = null;
        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        try
        {
            List<URL> sharedURLs = getManagedJettySharedLibFolderUrls(props);

            // Ensure we have a classloader that will have access to all jetty classes
            ClassLoader libExtClassLoader = LibExtClassLoaderHelper.createLibExtClassLoader(null, sharedURLs, contextCl/*JettyServerFactory.class.getClassLoader()*/);

            ClassLoader serverClassLoader = libExtClassLoader;
            
            if (LOG.isDebugEnabled())
                LOG.debug("LibExtClassLoader = {}", libExtClassLoader);

            Thread.currentThread().setContextClassLoader(libExtClassLoader);

            //Get ready to apply jetty configuration files, both those as explicit argument,
            //as well as those provided by property
            List<URL> jettyConfigs = new ArrayList<>();
            if (jettyConfigurations != null)
                jettyConfigs.addAll(jettyConfigurations);

            //config files provided as part of the osgi properties
            String jettyConfigFilenames = (String)props.get(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS);
            if (jettyConfigFilenames != null)
            {
                jettyConfigs.addAll(Util.fileNamesAsURLs(jettyConfigFilenames, StringUtil.DEFAULT_DELIMS));
            }

            Map<String, Object> idMap = new HashMap<>();

            Map<String, String> properties = new HashMap<>();
            if (props != null)
            {
                Enumeration<String> en = props.keys();
                while (en.hasMoreElements())
                {
                    String key = en.nextElement();
                    Object value = props.get(key);
                    properties.put(key, value.toString());
                }
            }

            try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
            {
                //create the server via config files
                for (URL jettyConfiguration : jettyConfigurations)
                {
                    try
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
                        server = (Server)o;

                        idMap = config.getIdMap();
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Configuration error in {}", jettyConfiguration);
                        throw e;
                    }
                }
            }

            //if no config files, create the server
            if (server == null)
                server = new Server();

            //ensure ContextHandlerCollection
            ContextHandlerCollection contextHandlerCollection = getContextHandlerCollection(server);
            if (contextHandlerCollection == null)
            {
                contextHandlerCollection = new ContextHandlerCollection();
                server.setHandler(contextHandlerCollection);
            }

            //ensure DeploymentManager
            DeploymentManager deploymentManager = ensureDeploymentManager(server);
            deploymentManager.setUseStandardBindings(false);
            List<AppLifeCycle.Binding> deploymentLifeCycleBindings = new ArrayList<>();
            deploymentLifeCycleBindings.add(new OSGiDeployer(server));
            deploymentLifeCycleBindings.add(new StandardStarter());
            deploymentLifeCycleBindings.add(new StandardStopper());
            deploymentLifeCycleBindings.add(new OSGiUndeployer(server));
            deploymentManager.setLifeCycleBindings(deploymentLifeCycleBindings);
            
            server.setAttribute(OSGiServerConstants.JETTY_HOME, properties.get(OSGiServerConstants.JETTY_HOME));
            server.setAttribute(OSGiServerConstants.JETTY_BASE, properties.get(OSGiServerConstants.JETTY_BASE));
            server.setAttribute(OSGiServerConstants.SERVER_CLASSLOADER, serverClassLoader);
            server.setAttribute(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, name);

            return server;
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

   private static DeploymentManager ensureDeploymentManager(Server server)
   {
       Collection<DeploymentManager> deployers = server.getBeans(DeploymentManager.class);
       DeploymentManager deploymentManager;

       if (deployers != null)
       {
           deploymentManager = deployers.stream().findFirst().get();
       }
       else
       {
           deploymentManager = new DeploymentManager();
           deploymentManager.setContexts(getContextHandlerCollection(server));
           server.addBean(deploymentManager);
       }
       
       return deploymentManager;
   }
   
   private static ContextHandlerCollection getContextHandlerCollection(Server server)
   {
       return (ContextHandlerCollection)server.getDescendant(ContextHandlerCollection.class);
   }
       
    /**
     * Get the Jetty Shared Lib Folder URLs in a form that is suitable for
     * {@link LibExtClassLoaderHelper} to use.
     *
     * @param props the properties to look for the configuration in
     * @return the list of URLs found, or null if none found
     */
    private static List<URL> getManagedJettySharedLibFolderUrls(Dictionary<String, Object> props)
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
