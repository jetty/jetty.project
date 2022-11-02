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
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.util.OSGiClassLoader;
import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.server.Server;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JettyBootstrapActivator
 * <p>
 * Bootstrap jetty and publish a default Server instance as an OSGi service.
 */
public class JettyBootstrapActivator implements BundleActivator
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyBootstrapActivator.class);

    private static JettyBootstrapActivator INSTANCE = null;

    /**
     * contains a comma separated list of paths to the etc/jetty-*.xml files
     */
    public static final String JETTY_ETC_FILES = OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS;

    /**
     * Set of config files to apply to a jetty Server instance if none are supplied by SYS_PROP_JETTY_ETC_FILES
     */
    public static final String DEFAULT_JETTY_ETC_FILES = "etc/jetty.xml,etc/jetty-http.xml,etc/jetty-deploy.xml";

    /**
     * Default location within bundle of a jetty home dir.
     */
    public static final String DEFAULT_JETTYHOME = "/jettyhome";

    private ServiceRegistration<?> _registeredServer;

    /**
     * Setup a new jetty Server, register it as a service. 
     *
     * @param context the bundle context
     */
    @Override
    public void start(final BundleContext context) throws Exception
    {
        ServiceReference[] references = context.getAllServiceReferences("org.eclipse.jetty.http.HttpFieldPreEncoder", null);

        if (references == null || references.length == 0)
            LOG.warn("OSGi support for java.util.ServiceLoader may not be present. You may experience runtime errors.");

        // Create a default jetty instance right now.
        startJettyAtJettyHome(context);
    }

    /**
     * Stop the activator.
     *
     * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    @Override
    public void stop(BundleContext context) throws Exception
    {
        try
        {
            if (_registeredServer != null)
            {
                try
                {
                    _registeredServer.unregister();
                }
                catch (IllegalArgumentException ill)
                {
                    // already unregistered.
                }
                finally
                {
                    _registeredServer = null;
                }
            }
        }
        finally
        {
            INSTANCE = null;
        }
    }

    /**
     * If the system property jetty.home is defined and points to a folder, 
     * creates a corresponding jetty server.
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
     * In both cases the system properties jetty.http.host, jetty.http.port and
     * jetty.ssl.port are passed to the configuration files that might use them
     * as part of their properties.
     * </p>
     *
     * @param bundleContext the bundle context
     * @throws Exception if unable to create / configure / or start the server
     */
    private void startJettyAtJettyHome(BundleContext bundleContext) throws Exception
    {
        String jettyHomeSysProp = System.getProperty(OSGiServerConstants.JETTY_HOME);
        String jettyHomeBundleSysProp = System.getProperty(OSGiServerConstants.JETTY_HOME_BUNDLE);
        File jettyHomeDir = null;
        Bundle jettyHomeBundle = null;

        Dictionary<String, Object> properties = new Hashtable<>();
        if (jettyHomeSysProp != null)
        {
            jettyHomeSysProp = Util.resolvePropertyValue(jettyHomeSysProp);
            // bug 329621
            if (jettyHomeSysProp.startsWith("\"") && jettyHomeSysProp.endsWith("\"") || (jettyHomeSysProp.startsWith("'") && jettyHomeSysProp.endsWith("'")))
                jettyHomeSysProp = jettyHomeSysProp.substring(1, jettyHomeSysProp.length() - 1);

            if (jettyHomeBundleSysProp != null)
                LOG.warn("Both jetty.home and jetty.home.bundle property defined: jetty.home.bundle ignored.");

            jettyHomeDir = new File(jettyHomeSysProp);
            if (!jettyHomeDir.exists() || !jettyHomeDir.isDirectory())
            {
                LOG.warn("Unable to locate the jetty.home folder {}", jettyHomeSysProp);
                return;
            }

            //set jetty.home
            Util.setProperty(properties, OSGiServerConstants.JETTY_HOME, jettyHomeDir.getAbsolutePath());
        }
        else if (jettyHomeBundleSysProp != null)
        {
            jettyHomeBundleSysProp = Util.resolvePropertyValue(jettyHomeBundleSysProp);
            for (Bundle b : bundleContext.getBundles())
            {
                if (b.getState() == Bundle.UNINSTALLED)
                    continue;

                if (b.getSymbolicName().equals(jettyHomeBundleSysProp))
                {
                    jettyHomeBundle = b;
                    break;
                }
            }
            if (jettyHomeBundle == null)
            {
                LOG.warn("Unable to find the jetty.home.bundle named {}", jettyHomeSysProp);
                return;
            }
        }

        if (jettyHomeDir == null && jettyHomeBundle == null)
        {
            LOG.warn("No default jetty created.");
            return;
        }
        
        //resolve the jetty xml config files
        List<URL> configURLs = jettyHomeDir != null ? getJettyConfigurationURLs(jettyHomeDir) : getJettyConfigurationURLs(jettyHomeBundle, properties);

        LOG.info("Configuring the default jetty server with {}", configURLs);
        String home = (String)properties.get(OSGiServerConstants.JETTY_HOME);
        String base = (String)properties.get(OSGiServerConstants.JETTY_BASE);
        if (base == null)
            base = home;
        LOG.info("JETTY.HOME={}  JETTY.BASE={}",  home, base);
        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        try
        {
            ClassLoader cl;
            if (jettyHomeBundle != null)
            {
                cl = new OSGiClassLoader(JettyBootstrapActivator.class.getClassLoader(), jettyHomeBundle);
            }
            else
            {
                cl = JettyBootstrapActivator.class.getClassLoader();
            }
            Thread.currentThread().setContextClassLoader(cl);

            //the default server name
            properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME);
            
            //Always set home and base
            Util.setProperty(properties, OSGiServerConstants.JETTY_HOME, home);
            Util.setProperty(properties, OSGiServerConstants.JETTY_BASE, base);
            
            // copy all system properties starting with "jetty." to service properties for the jetty server service.
            // these will be used as xml configuration properties.
            for (Map.Entry<Object, Object> prop : System.getProperties().entrySet())
            {
                if (prop.getKey() instanceof String)
                {
                    String skey = (String)prop.getKey();
                    //never copy the jetty xml config files into the properties as we pass them explicitly into
                    //the call to configure, also we set home and base explicitly
                    if (OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS.equals(skey) ||
                        OSGiServerConstants.JETTY_HOME.equals(skey) ||
                        OSGiServerConstants.JETTY_BASE.equals(skey))
                        continue;
                    
                    if (skey.startsWith("jetty."))
                    {
                        Util.setProperty(properties, skey, prop.getValue());
                    }
                }
            }

            //Create the default Server instance
            Server defaultServer = JettyServerFactory.createServer(OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME, properties, configURLs);

            //Register the default Server instance as an OSGi service.
            //The JettyServerServiceTrackers will notice it and set it up to deploy bundles as wars etc
            //for each environment eg ee9,ee10, etc
            _registeredServer = bundleContext.registerService(Server.class.getName(), defaultServer, properties);
            LOG.info("Default jetty server configured");
        }
        catch (Exception e)
        {
            LOG.warn("Failed to start Jetty at Jetty Home", e);
            throw e;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(contextCl);
        }
    }

    /**
     * Minimum setup for the location of the configuration files given a
     * jettyhome folder. Reads the system property jetty.etc.config.urls and
     * look for the corresponding jetty configuration files that will be used to
     * setup the jetty server.
     */
    private List<URL> getJettyConfigurationURLs(File jettyhome)
        throws MalformedURLException
    {
        List<URL> configURLs = new ArrayList<>();
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

    /**
     * Minimum setup for the location of the configuration files given a
     * configuration embedded inside a bundle. Reads the system property
     * jetty.etc.config.urls and look for the corresponding jetty configuration
     * files that will be used to setup the jetty server.
     */
    private List<URL> getJettyConfigurationURLs(Bundle configurationBundle, Dictionary properties)
        throws Exception
    {
        List<URL> configURLs = new ArrayList<>();
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

                String home = null;
                // default for org.eclipse.osgi.boot where we look inside
                // jettyhome/ for the default embedded configuration.
                if ((enUrls == null || !enUrls.hasMoreElements()))
                {
                    home = DEFAULT_JETTYHOME;
                    String tmp = DEFAULT_JETTYHOME + (DEFAULT_JETTYHOME.endsWith("/") ? "" : "/") + etcFile;
                    enUrls = BundleFileLocatorHelperFactory.getFactory().getHelper().findEntries(configurationBundle, tmp);
                    LOG.info("Configuring jetty from bundle: {} with {}", configurationBundle.getSymbolicName(), tmp);
                }

                //lazily ensure jetty.home value is set based on location of etc files
                if (properties.get(OSGiServerConstants.JETTY_HOME) == null)
                {
                    Path path = findDir(configurationBundle, home);
                    if (path != null)
                        properties.put(OSGiServerConstants.JETTY_HOME, path.toUri().toString());
                }

                if (enUrls == null || !enUrls.hasMoreElements())
                    throw new IllegalStateException("Unable to locate a jetty configuration file for " + etcFile);

                URL url = BundleFileLocatorHelperFactory.getFactory().getHelper().getFileURL(enUrls.nextElement());
                configURLs.add(url);
            }
        }
        return configURLs;
    }

    /**
     * Resolve a directory inside a bundle. If the dir is null,
     * return a path representing the installation location of the bundle.
     *
     * @param bundle the bundle
     * @param dir the directory
     * @return either the resolved dir inside the bundle, or the path of the bundle itself
     */
    private Path findDir(Bundle bundle, String dir)
    {
        if (bundle == null)
            return null;

        try
        {
            File f = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
            URL u = f.toURI().toURL();
            u = BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(u);
            Path p = Paths.get(u.toURI());

            if (dir != null)
                return p.resolve(dir);
            else
                return p;
        }
        catch (Exception e)
        {
            LOG.warn("Bad bundle location", e);
            return null;
        }
    }
}
