// ========================================================================
// Copyright (c) 2010 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot.internal.serverfactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;
import java.util.StringTokenizer;

import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.server.Server;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * Called by the {@link JettyBootstrapActivator} during the starting of the bundle.
 * If the system property 'jetty.home' is defined and points to a folder,
 * then setup the corresponding jetty server and starts it.
 */
public class DefaultJettyAtJettyHomeHelper {
	
    /**
     * contains a comma separated list of pathes to the etc/jetty-*.xml files
     * used to configure jetty. By default the value is 'etc/jetty.xml' when the
     * path is relative the file is resolved relatively to jettyhome.
     */
    public static final String SYS_PROP_JETTY_ETC_FILES = "jetty.etc.config.urls";

    /**
     * Usual system property used as the hostname for a typical jetty configuration.
     */
    public static final String SYS_PROP_JETTY_HOME = "jetty.home";
    /**
     * System property to point to a bundle that embeds a jetty configuration
     * and that jetty configuration should be the default jetty server.
     * First we look for jetty.home. If we don't find it then we look for this property.
     */
    public static final String SYS_PROP_JETTY_HOME_BUNDLE = "jetty.home.bundle";
    /**
     * Usual system property used as the hostname for a typical jetty configuration.
     */
    public static final String SYS_PROP_JETTY_HOST = "jetty.host";
    /**
     * Usual system property used as the port for http for a typical jetty configuration.
     */
    public static final String SYS_PROP_JETTY_PORT = "jetty.port";
    /**
     * Usual system property used as the port for https for a typical jetty configuration.
     */
    public static final String SYS_PROP_JETTY_PORT_SSL = "jetty.port.ssl";
	

    /**
     * Called by the JettyBootStrapActivator.
     * If the system property jetty.home is defined and points to a folder,
     * deploys the corresponding jetty server.
     * <p>
     * If the system property jetty.home.bundle is defined and points to a bundle.
     * Look for the configuration of jetty inside that bundle and deploys the corresponding bundle.
     * </p>
     * <p>
     * In both cases reads the system property 'jetty.etc.config.urls' to locate the configuration
     * files for the deployed jetty. It is a comma spearate list of URLs or relative paths inside the bundle or folder
     * to the config files. If underfined it defaults to 'etc/jetty.xml'.
     * </p>
     * <p>
     * In both cases the system properties jetty.host, jetty.port and jetty.port.ssl are passed to the configuration files
     * that might use them as part of their properties.
     * </p>
     */
    public static void startJettyAtJettyHome(BundleContext bundleContext)
    {
    	String jettyHomeSysProp = System.getProperty(SYS_PROP_JETTY_HOME);
    	String jettyHomeBundleSysProp = System.getProperty(SYS_PROP_JETTY_HOME_BUNDLE);
    	File jettyHome = null;
    	Bundle jettyHomeBundle = null;
    	if (jettyHomeSysProp != null)
    	{
    		if (jettyHomeBundleSysProp != null)
    		{
    			System.err.println("WARN: both the jetty.home property and the jetty.home.bundle property are defined."
    					+ " jetty.home.bundle is not taken into account.");
    		}
    		jettyHome = new File(jettyHomeSysProp);
    		if (!jettyHome.exists() || !jettyHome.isDirectory())
    		{
    			System.err.println("Unable to locate the jetty.home folder " + jettyHomeSysProp);
    			return;
    		}
    	}
    	else if (jettyHomeBundleSysProp != null)
    	{
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
    			System.err.println("Unable to find the jetty.home.bundle named " + jettyHomeSysProp);
    			return;
    		}
    		
    	}
    	if (jettyHome == null && jettyHomeBundle == null)
    	{
    		System.err.println("No default jetty started.");
    		return;
    	}
		try
		{
			Server server = new Server();
			Properties properties = new Properties();
			properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME);
			
			String configURLs = jettyHome != null ? getJettyConfigurationURLs(jettyHome) : getJettyConfigurationURLs(jettyHomeBundle);
			properties.put(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS, configURLs);

			//these properties usually are the ones passed to this type of configuration.
			//TODO remove the hardcoded defaults once we use 7.1.5
			setProperty(properties,SYS_PROP_JETTY_HOME,System.getProperty(SYS_PROP_JETTY_HOME));
			setProperty(properties,SYS_PROP_JETTY_HOST,System.getProperty(SYS_PROP_JETTY_HOST));
			setProperty(properties,SYS_PROP_JETTY_PORT,System.getProperty(SYS_PROP_JETTY_PORT, "8080"));
			setProperty(properties,SYS_PROP_JETTY_PORT_SSL,System.getProperty(SYS_PROP_JETTY_PORT_SSL, "8443"));

   			bundleContext.registerService(Server.class.getName(), server, properties);
		}
		catch (Throwable t)
		{
			t.printStackTrace();
		}
    }
    
    /**
     * Minimum setup for the location of the configuration files given a jettyhome folder.
     * Reads the system property jetty.etc.config.urls and look for the corresponding jetty
     * configuration files that will be used to setup the jetty server.
     * @param jettyhome
     * @return
     */
    private static String getJettyConfigurationURLs(File jettyhome)
    {
    	String jettyetc = System.getProperty(SYS_PROP_JETTY_ETC_FILES,"etc/jetty.xml");
        StringTokenizer tokenizer = new StringTokenizer(jettyetc,";,", false);
        StringBuilder res = new StringBuilder();
        while (tokenizer.hasMoreTokens())
        {
        	String next = tokenizer.nextToken().trim();
        	if (!next.startsWith("/") && next.indexOf(':') == -1)
        	{
        		try {
        			next = new File(jettyhome, next).toURI().toURL().toString();
				} catch (MalformedURLException e) {
					e.printStackTrace();
					continue;
				}
        	}
        	appendToCommaSeparatedList(res, next);
        }
        return res.toString();
    }

    /**
     * Minimum setup for the location of the configuration files given a configuration
     * embedded inside a bundle.
     * Reads the system property jetty.etc.config.urls and look for the corresponding jetty
     * configuration files that will be used to setup the jetty server.
     * @param jettyhome
     * @return
     */
    private static String getJettyConfigurationURLs(Bundle configurationBundle)
    {
    	String jettyetc = System.getProperty(SYS_PROP_JETTY_ETC_FILES,"etc/jetty.xml");
        StringTokenizer tokenizer = new StringTokenizer(jettyetc,";,", false);
        StringBuilder res = new StringBuilder();
        
        while (tokenizer.hasMoreTokens())
        {
            String etcFile = tokenizer.nextToken().trim();
            if (etcFile.indexOf(":") != -1)
            {
            	appendToCommaSeparatedList(res, etcFile);
            }
            else
            {
            	int last = etcFile.lastIndexOf('/');
            	String path = last != -1 && last < etcFile.length() -2
            		? etcFile.substring(0, last) : "/";
            	if (!path.startsWith("/"))
            	{
            		path = "/" + path;
            	}
            	String pattern = last != -1 && last < etcFile.length() -2
        			? etcFile.substring(last+1) : etcFile;
        		Enumeration<URL> enUrls = configurationBundle.findEntries(path, pattern, false);
        		
        		//default for org.eclipse.osgi.boot where we look inside jettyhome for the default embedded configuration.
        		//default inside jettyhome. this way fragments to the bundle can define their own configuration.
            	if ((enUrls == null || !enUrls.hasMoreElements()) && pattern.equals("jetty.xml"))
            	{
            		path = "/jettyhome" + path;
            		pattern = "jetty-osgi-default.xml";
            		enUrls = configurationBundle.findEntries(path, pattern, false);
            		System.err.println("Configuring jetty with the default embedded configuration:" +
            				"bundle: " + configurationBundle.getSymbolicName() + " config: /jettyhome/etc/jetty-osgi-default.xml");
            	}
        		if (enUrls != null)
        		{
        			while (enUrls.hasMoreElements())
        			{
        				appendToCommaSeparatedList(res, enUrls.nextElement().toString());
        			}
        		}
            }
        }
        return res.toString();
    }
	
	private static void appendToCommaSeparatedList(StringBuilder buffer, String value)
	{
		if (buffer.length() != 0)
		{
			buffer.append(",");
		}
		buffer.append(value);
	}
	
	private static void setProperty(Properties properties, String key, String value)
	{
		if (value != null)
		{
			properties.put(key, value);
		}
	}
}
