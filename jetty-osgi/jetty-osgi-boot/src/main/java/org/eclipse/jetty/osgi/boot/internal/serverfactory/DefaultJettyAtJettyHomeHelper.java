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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.StringTokenizer;

import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.boot.utils.Util;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * DefaultJettyAtJettyHomeHelper
 * 
 * 
 * Creates a default instance of Jetty, based on the values of the
 * System properties "jetty.home" or "jetty.home.bundle", one of which
 * must be specified in order to create the default instance.
 * 
 * Called by the {@link JettyBootstrapActivator} during the starting of the
 * bundle. 
 */
public class DefaultJettyAtJettyHomeHelper
{
    private static final Logger LOG = Log.getLogger(DefaultJettyAtJettyHomeHelper.class);

    /**
     * contains a comma separated list of paths to the etc/jetty-*.xml files
     */
    public static final String JETTY_ETC_FILES = OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS;

    /**
     * Set of config files to apply to a jetty Server instance if none are supplied by SYS_PROP_JETTY_ETC_FILES
     */
    public static final String DEFAULT_JETTY_ETC_FILES = "etc/jetty.xml,etc/jetty-selector.xml,etc/jetty-deployer.xml";
    
    /**
     * Default location within bundle of a jetty home dir.
     */
    public static final String DEFAULT_JETTYHOME = "/jettyhome/";
    
    
    
    /* ------------------------------------------------------------ */
    /**
     * Called by the JettyBootStrapActivator. If the system property jetty.home
     * is defined and points to a folder, creates a corresponding jetty
     * server.
     * <p>
     * If the system property jetty.home.bundle is defined and points to a
     * bundle, look for the configuration of jetty inside that bundle.
     * </p>
     * <p>
     * In both cases reads the system property 'jetty.etc.config.urls' to locate
     * the configuration files for the deployed jetty. It is a comma separated
     * list of URLs or relative paths inside the bundle or folder to the config
     * files.
     * </p>
     * <p>
     * In both cases the system properties jetty.host, jetty.port and
     * jetty.port.ssl are passed to the configuration files that might use them
     * as part of their properties.
     * </p>
     */
    public static Server startJettyAtJettyHome(BundleContext bundleContext) throws Exception
    {
        String jettyHomeSysProp = System.getProperty(OSGiServerConstants.JETTY_HOME);
        String jettyHomeBundleSysProp = System.getProperty(OSGiServerConstants.JETTY_HOME_BUNDLE);
        File jettyHome = null;
        Bundle jettyHomeBundle = null;
        
        if (jettyHomeSysProp != null)
        {
            jettyHomeSysProp = Util.resolvePropertyValue(jettyHomeSysProp);
            // bug 329621
            if (jettyHomeSysProp.startsWith("\"") && jettyHomeSysProp.endsWith("\"") || (jettyHomeSysProp.startsWith("'") && jettyHomeSysProp.endsWith("'")))
                jettyHomeSysProp = jettyHomeSysProp.substring(1, jettyHomeSysProp.length() - 1);
         
            if (jettyHomeBundleSysProp != null)
                LOG.warn("Both jetty.home and jetty.home.bundle property defined: jetty.home.bundle ignored.");
           
            jettyHome = new File(jettyHomeSysProp);
            if (!jettyHome.exists() || !jettyHome.isDirectory())
            {
                LOG.warn("Unable to locate the jetty.home folder " + jettyHomeSysProp);
                return null;
            }
        }
        else if (jettyHomeBundleSysProp != null)
        {
            jettyHomeBundleSysProp = Util.resolvePropertyValue(jettyHomeBundleSysProp);
            for (Bundle b : bundleContext.getBundles())
            {
                if (b.getSymbolicName().equals(jettyHomeBundleSysProp))
                {
                    jettyHomeBundle = b;
                    break;
                }
            }
            if (jettyHomeBundle == null)
            {
                LOG.warn("Unable to find the jetty.home.bundle named " + jettyHomeSysProp);
                return null;
            }
        }
        
        if (jettyHome == null && jettyHomeBundle == null)
        {
            LOG.warn("No default jetty created.");
            return null;
        }

        //configure the server here rather than letting the JettyServerServiceTracker do it, because we want to be able to
        //configure the ThreadPool, which can only be done via the constructor, ie from within the xml configuration processing
        List<URL> configURLs = jettyHome != null ? getJettyConfigurationURLs(jettyHome) : getJettyConfigurationURLs(jettyHomeBundle);

        LOG.info("Configuring the default jetty server with {}",configURLs);
        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(JettyBootstrapActivator.class.getClassLoader());
            

            // these properties usually are the ones passed to this type of
            // configuration.
            Dictionary<String,String> properties = new Hashtable<String,String>();
            properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME);
            Util.setProperty(properties, OSGiServerConstants.JETTY_HOME, System.getProperty(OSGiServerConstants.JETTY_HOME));
            Util.setProperty(properties, OSGiServerConstants.JETTY_HOST, System.getProperty(OSGiServerConstants.JETTY_HOST));
            Util.setProperty(properties, OSGiServerConstants.JETTY_PORT, System.getProperty(OSGiServerConstants.JETTY_PORT));
            Util.setProperty(properties, OSGiServerConstants.JETTY_PORT_SSL, System.getProperty(OSGiServerConstants.JETTY_PORT_SSL));

            Server server = ServerInstanceWrapper.configure(null, configURLs, properties);
            
            //Register the default Server instance as an OSGi service.
            //The JettyServerServiceTracker will notice it and set it up to deploy bundles as wars etc
            bundleContext.registerService(Server.class.getName(), server, properties);
            LOG.info("Default jetty server configured");
            return server;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(contextCl);
        }
    }

    
   
    
    /* ------------------------------------------------------------ */
    /**
     * Minimum setup for the location of the configuration files given a
     * jettyhome folder. Reads the system property jetty.etc.config.urls and
     * look for the corresponding jetty configuration files that will be used to
     * setup the jetty server.
     * 
     * @param jettyhome
     * @return
     * @throws MalformedURLException 
     */
    private static List<URL> getJettyConfigurationURLs(File jettyhome) 
    throws MalformedURLException
    {
        List<URL> configURLs = new ArrayList<URL>();
        String jettyetc = System.getProperty(JETTY_ETC_FILES, DEFAULT_JETTY_ETC_FILES);
        StringTokenizer tokenizer = new StringTokenizer(jettyetc, ";,", false);
        while (tokenizer.hasMoreTokens())
        {
            String next = tokenizer.nextToken().trim();
            //etc files can either be relative to jetty.home or absolute disk locations
            if (!next.startsWith("/") && (next.indexOf(':') == -1))    
                configURLs.add(new File(jettyhome, next).toURI().toURL());
            else 
                configURLs.add(new URL(next));
        }
        return configURLs;
    }

    /* ------------------------------------------------------------ */
    /**
     * Minimum setup for the location of the configuration files given a
     * configuration embedded inside a bundle. Reads the system property
     * jetty.etc.config.urls and look for the corresponding jetty configuration
     * files that will be used to setup the jetty server.
     * 
     * @param jettyhome
     * @return
     */
    private static List<URL> getJettyConfigurationURLs(Bundle configurationBundle)
    throws MalformedURLException
    {
        List<URL> configURLs = new ArrayList<URL>();
        String files = System.getProperty(JETTY_ETC_FILES, DEFAULT_JETTY_ETC_FILES);       
        StringTokenizer tokenizer = new StringTokenizer(files, ";,", false);

        while (tokenizer.hasMoreTokens())
        {
            String etcFile = tokenizer.nextToken().trim();

            //file path is absolute
            if (etcFile.startsWith("/") || etcFile.indexOf(":") != -1)
                configURLs.add(new URL(etcFile));
            else //relative file path
            {
                Enumeration<URL> enUrls = BundleFileLocatorHelperFactory.getFactory().getHelper().findEntries(configurationBundle, etcFile);

                // default for org.eclipse.osgi.boot where we look inside
                // jettyhome/ for the default embedded configuration.
                if ((enUrls == null || !enUrls.hasMoreElements()))
                {
                    String tmp = DEFAULT_JETTYHOME+(DEFAULT_JETTYHOME.endsWith("/")?"":"/")+etcFile;
                    enUrls = BundleFileLocatorHelperFactory.getFactory().getHelper().findEntries(configurationBundle, tmp);                    
                    LOG.info("Configuring jetty from bundle: {} with {}", configurationBundle.getSymbolicName(),tmp);
                }

                if (enUrls == null || !enUrls.hasMoreElements())
                    throw new IllegalStateException ("Unable to locate a jetty configuration file for " + etcFile);

                while (enUrls.hasMoreElements())
                {
                    URL url = BundleFileLocatorHelperFactory.getFactory().getHelper().getFileURL(enUrls.nextElement());
                    configURLs.add(url);
                }
            }
        }
        return configURLs;
    }
}
