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
import java.net.URL;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.StringTokenizer;

import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.internal.webapp.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * DefaultJettyAtJettyHomeHelper
 * 
 * 
 * Called by the {@link JettyBootstrapActivator} during the starting of the
 * bundle. If the system property 'jetty.home' is defined and points to a
 * folder, then setup the corresponding jetty server.
 */
public class DefaultJettyAtJettyHomeHelper
{
    private static final Logger LOG = Log.getLogger(DefaultJettyAtJettyHomeHelper.class);

    /**
     * contains a comma separated list of pathes to the etc/jetty-*.xml files
     * used to configure jetty. By default the value is 'etc/jetty.xml' when the
     * path is relative the file is resolved relatively to jettyhome.
     */
    public static final String JETTY_ETC_FILES = OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS;

    /**
     * Set of config files to apply to a jetty Server instance if none are supplied by SYS_PROP_JETTY_ETC_FILES
     */
    public static final String DEFAULT_JETTY_ETC_FILES = "etc/jetty.xml,etc/jetty-selector.xml,etc/jetty-deployer.xml";
    
    /**
     * Default location within bundle of a jetty home dir.
     */
    public static final String DEFAULT_JETTYHOME = "/jettyhome";
    
    
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
     * files. If undefined it defaults to 'etc/jetty.xml'. In the case of the jetty.home.bundle,
     * if no etc/jetty.xml file is found in the bundle, it will look for 
     * /jettyhome/etc/jetty-osgi-default.xml
     * </p>
     * <p>
     * In both cases the system properties jetty.host, jetty.port and
     * jetty.port.ssl are passed to the configuration files that might use them
     * as part of their properties.
     * </p>
     */
    public static void startJettyAtJettyHome(BundleContext bundleContext) throws Exception
    {
        String jettyHomeSysProp = System.getProperty(OSGiServerConstants.JETTY_HOME);
        String jettyHomeBundleSysProp = System.getProperty(OSGiServerConstants.JETTY_HOME_BUNDLE);
        File jettyHome = null;
        Bundle jettyHomeBundle = null;
        if (jettyHomeSysProp != null)
        {
            jettyHomeSysProp = resolvePropertyValue(jettyHomeSysProp);
            // bug 329621
            if (jettyHomeSysProp.startsWith("\"") && jettyHomeSysProp.endsWith("\"") || (jettyHomeSysProp.startsWith("'") && jettyHomeSysProp.endsWith("'")))
            {
                jettyHomeSysProp = jettyHomeSysProp.substring(1, jettyHomeSysProp.length() - 1);
            }
            if (jettyHomeBundleSysProp != null)
            {
                LOG.warn("Both jetty.home and jetty.home.bundle property defined: jetty.home.bundle ignored.");
            }
            jettyHome = new File(jettyHomeSysProp);
            if (!jettyHome.exists() || !jettyHome.isDirectory())
            {
                LOG.warn("Unable to locate the jetty.home folder " + jettyHomeSysProp);
                return;
            }
        }
        else if (jettyHomeBundleSysProp != null)
        {
            jettyHomeBundleSysProp = resolvePropertyValue(jettyHomeBundleSysProp);
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
                return;
            }

        }
        if (jettyHome == null && jettyHomeBundle == null)
        {
            LOG.warn("No default jetty created.");
            return;
        }

        Server server = new Server();
        Dictionary<String,String> properties = new Hashtable<String,String>();
        properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME);

        String configURLs = jettyHome != null ? getJettyConfigurationURLs(jettyHome) : getJettyConfigurationURLs(jettyHomeBundle);
        properties.put(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS, configURLs);

        LOG.info("Configuring the default jetty server with " + configURLs);

        // these properties usually are the ones passed to this type of
        // configuration.
        setProperty(properties, OSGiServerConstants.JETTY_HOME, System.getProperty(OSGiServerConstants.JETTY_HOME));
        setProperty(properties, OSGiServerConstants.JETTY_HOST, System.getProperty(OSGiServerConstants.JETTY_HOST));
        setProperty(properties, OSGiServerConstants.JETTY_PORT, System.getProperty(OSGiServerConstants.JETTY_PORT));
        setProperty(properties, OSGiServerConstants.JETTY_PORT_SSL, System.getProperty(OSGiServerConstants.JETTY_PORT_SSL));

        //register the Server instance as an OSGi service.
        bundleContext.registerService(Server.class.getName(), server, properties);
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
     */
    private static String getJettyConfigurationURLs(File jettyhome)
    {
        String jettyetc = System.getProperty(JETTY_ETC_FILES, DEFAULT_JETTY_ETC_FILES);
        StringTokenizer tokenizer = new StringTokenizer(jettyetc, ";,", false);
        StringBuilder res = new StringBuilder();
        while (tokenizer.hasMoreTokens())
        {
            String next = tokenizer.nextToken().trim();
            if (!next.startsWith("/") && next.indexOf(':') == -1)
            {
                try
                {
                    next = new File(jettyhome, next).toURI().toURL().toString();
                }
                catch (MalformedURLException e)
                {
                    LOG.warn(e);
                    continue;
                }
            }
            appendToCommaSeparatedList(res, next);
        }
        return res.toString();
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
    private static String getJettyConfigurationURLs(Bundle configurationBundle)
    {
        String files = System.getProperty(JETTY_ETC_FILES, DEFAULT_JETTY_ETC_FILES);
       
        StringTokenizer tokenizer = new StringTokenizer(files, ";,", false);
        StringBuilder res = new StringBuilder();

        while (tokenizer.hasMoreTokens())
        {
            String etcFile = tokenizer.nextToken().trim();
            if (etcFile.startsWith("/") || etcFile.indexOf(":") != -1)
            {
                //file path is absolute
                appendToCommaSeparatedList(res, etcFile);
            }
            else
            {
                //relative file path
                Enumeration<URL> enUrls = BundleFileLocatorHelperFactory.getFactory().getHelper().findEntries(configurationBundle, etcFile);
                      
                // default for org.eclipse.osgi.boot where we look inside
                // jettyhome for the default embedded configuration.
                // default inside jettyhome. this way fragments to the bundle
                // can define their own configuration.
                if ((enUrls == null || !enUrls.hasMoreElements()))
                {
                    String tmp = DEFAULT_JETTYHOME+etcFile;
                    enUrls = BundleFileLocatorHelperFactory.getFactory().getHelper().findEntries(configurationBundle, tmp);                    
                    LOG.info("Configuring jetty from bundle: "
                                       + configurationBundle.getSymbolicName()
                                       + " with "+tmp);
                }
                if (enUrls == null || !enUrls.hasMoreElements())
                {
                    throw new IllegalStateException ("Unable to locate a jetty configuration file for " + etcFile);
                }
                if (enUrls != null)
                {
                    while (enUrls.hasMoreElements())
                    {
                        URL url = BundleFileLocatorHelperFactory.getFactory().getHelper().getFileURL(enUrls.nextElement());
                        appendToCommaSeparatedList(res, url.toString());
                    }
                }
            }
        }
        return res.toString();
    }
    
    
    /* ------------------------------------------------------------ */
    private static void appendToCommaSeparatedList(StringBuilder buffer, String value)
    {
        if (buffer.length() != 0)
        {
            buffer.append(",");
        }
        buffer.append(value);
    }
    
    
    /* ------------------------------------------------------------ */
    private static void setProperty(Dictionary<String,String> properties, String key, String value)
    {
        if (value != null)
        {
            properties.put(key, value);
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * recursively substitute the ${sysprop} by their actual system property.
     * ${sysprop,defaultvalue} will use 'defaultvalue' as the value if no
     * sysprop is defined. Not the most efficient code but we are shooting for
     * simplicity and speed of development here.
     * 
     * @param value
     * @return
     */
    public static String resolvePropertyValue(String value)
    {
        int ind = value.indexOf("${");
        if (ind == -1) { return value; }
        int ind2 = value.indexOf('}', ind);
        if (ind2 == -1) { return value; }
        String sysprop = value.substring(ind + 2, ind2);
        String defaultValue = null;
        int comma = sysprop.indexOf(',');
        if (comma != -1 && comma + 1 != sysprop.length())
        {
            defaultValue = sysprop.substring(comma + 1);
            defaultValue = resolvePropertyValue(defaultValue);
            sysprop = sysprop.substring(0, comma);
        }
        else
        {
            defaultValue = "${" + sysprop + "}";
        }

        String v = System.getProperty(sysprop);

        String reminder = value.length() > ind2 + 1 ? value.substring(ind2 + 1) : "";
        reminder = resolvePropertyValue(reminder);
        if (v != null)
        {
            return value.substring(0, ind) + v + reminder;
        }
        else
        {
            return value.substring(0, ind) + defaultValue + reminder;
        }
    }
}
