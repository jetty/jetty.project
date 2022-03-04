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

package org.eclipse.jetty.ee10.osgi.boot.internal.serverfactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.eclipse.jetty.ee10.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.ee10.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.ee10.osgi.boot.utils.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.ee10.osgi.boot.utils.OSGiClassLoader;
import org.eclipse.jetty.ee10.osgi.boot.utils.Util;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DefaultJettyAtJettyHomeHelper
 * <p>
 * Creates a default instance of Jetty, based on the values of the
 * System properties "jetty.home" or "jetty.home.bundle", one of which
 * must be specified in order to create the default instance.
 * <p>
 * Called by the {@link JettyBootstrapActivator} during the starting of the
 * bundle.
 */
public class DefaultJettyAtJettyHomeHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultJettyAtJettyHomeHelper.class);

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
     * In both cases the system properties jetty.http.host, jetty.http.port and
     * jetty.ssl.port are passed to the configuration files that might use them
     * as part of their properties.
     * </p>
     *
     * @param bundleContext the bundle context
     * @return the configured server
     * @throws Exception if unable to create / configure / or start the server
     */
    public static Server startJettyAtJettyHome(BundleContext bundleContext) throws Exception
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
                return null;
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
                return null;
            }
        }

        if (jettyHomeDir == null && jettyHomeBundle == null)
        {
            LOG.warn("No default jetty created.");
            return null;
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

            //configure the server here rather than letting the JettyServerServiceTracker do it, because we want to be able to
            //configure the ThreadPool, which can only be done via the constructor, ie from within the xml configuration processing
            Server server = ServerInstanceWrapper.configure(null, configURLs, properties);

            //Register the default Server instance as an OSGi service.
            //The JettyServerServiceTracker will notice it and set it up to deploy bundles as wars etc
            bundleContext.registerService(Server.class.getName(), server, properties);
            LOG.info("Default jetty server configured");
            return server;
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
    private static List<URL> getJettyConfigurationURLs(File jettyhome)
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
    private static List<URL> getJettyConfigurationURLs(Bundle configurationBundle, Dictionary properties)
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
                    Resource res = findDir(configurationBundle, home);
                    if (res != null)
                        properties.put(OSGiServerConstants.JETTY_HOME, res.toString());
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
     * Get a resource representing a directory inside a bundle. If the dir is null,
     * return a resource representing the installation location of the bundle.
     *
     * @param bundle the bundle
     * @param dir the directory
     * @return the resource found
     */
    public static Resource findDir(Bundle bundle, String dir)
    {
        if (bundle == null)
            return null;

        try
        {
            File f = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
            URL u = f.toURI().toURL();
            u = BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(u);
            Resource res = Resource.newResource(u);
            String s = res.toString();

            //check if it is an unarchived bundle
            if (s.endsWith(".jar") && s.startsWith("file:"))
                res = JarResource.newJarResource(res);

            //if looking for a directory 
            if (dir != null)
                res = res.addPath(dir);

            return res;
        }
        catch (Exception e)
        {
            LOG.warn("Bad bundle location", e);
            return null;
        }
    }
}
