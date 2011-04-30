// ========================================================================
// Copyright (c) 2010-2011 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.osgi.servletbridge;

import org.eclipse.equinox.servletbridge.FrameworkLauncher;

import java.io.*;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Extend the servletbridge FrameworkLauncher to support launching an equinox installation
 * made by p2director.
 */
public class FrameworkLauncherExtended extends FrameworkLauncher
{

    /**
     * if the OSGI_INSTALL_AREA installed area is specified as a sytem property and matches a Folder on the file system, we don't copy the whole eclipse
     * installation instead we use that folder as it is
     */
    private static final String DEPLOY_IN_PLACE_WHEN_INSTALL_AREA_IS_FOLDER = "org.eclipse.equinox.servletbridge.deployinplace"; //$NON-NLS-1$

    private boolean deployedInPlace = false;
    private URL resourceBaseAsURL = null;

    /**
     * try to find the resource base for this webapp by looking for the launcher initialization file.
     */
    protected void initResourceBase()
    {
        try
        {
            String resourceBaseStr = System.getProperty(OSGI_INSTALL_AREA,config.getInitParameter(OSGI_INSTALL_AREA));
            if (resourceBaseStr != null)
            {
                // If the path starts with a reference to a nsystem property, resolve it.
                resourceBaseStr = resolveSystemProperty(resourceBaseStr);
                if (resourceBaseStr.startsWith("file://"))
                {
                    resourceBaseAsURL = new URL(resourceBaseStr.replace(" ","%20")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else if (new File(resourceBaseStr).exists())
                {
                    resourceBaseAsURL = new URL("file://" + new File(resourceBaseStr).getCanonicalPath().replace(" ","%20")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else
                {
                    resourceBaseAsURL = context.getResource(resourceBaseStr);
                }
            }
            else
            {
                super.initResourceBase();
                resourceBaseAsURL = context.getResource(resourceBaseStr);
            }
        }
        catch (MalformedURLException e)
        {
            // ignore
        }
        catch (IOException e)
        {
            // ignore
        }
        if (resourceBaseAsURL != null && resourceBaseAsURL.getProtocol().equals("file")) { //$NON-NLS-1$
            File resBase = new File(resourceBaseAsURL.getPath());
            if (resBase.exists() && resBase.isDirectory()
                    && !Boolean.FALSE.toString().equalsIgnoreCase(System.getProperty(DEPLOY_IN_PLACE_WHEN_INSTALL_AREA_IS_FOLDER)))
            {
                __setPlatformDirectory(resBase);
                deployedInPlace = true;
            }
        }
    }

    /**
     * Override this method to be able to set default system properties computed on the fly depending on the environment where equinox and jetty-osgi are
     * deployed.
     * 
     * @param resource
     *            - The target to read properties from
     * @return the properties
     */
    protected Properties loadProperties(String resource)
    {
        Properties props = super.loadProperties(resource);
        if (resource.equals(resourceBase + LAUNCH_INI) && deployedInPlace)
        {
            String osgiInstall = props.getProperty(OSGI_INSTALL_AREA);
            if (osgiInstall == null)
            {
                // compute the osgi install dynamically.
                props.put(OSGI_INSTALL_AREA,getPlatformDirectory().getAbsolutePath());
            }
            String osgiFramework = props.getProperty(OSGI_FRAMEWORK);
            if (osgiFramework == null && getPlatformDirectory() != null)
            {
                File osgiFrameworkF = findOsgiFramework(getPlatformDirectory());
                props.put(OSGI_FRAMEWORK,osgiFrameworkF.getAbsoluteFile().getAbsolutePath());
            }
            String jettyHome = System.getProperty("jetty.home");
            if (jettyHome == null)
            {
                System.setProperty("jetty.home",getPlatformDirectory().getAbsolutePath());
                // System.setProperty("jetty.port", "9080");
                // System.setProperty("jetty.port.ssl", "9443");
            }
            String etcJettyXml = System.getProperty("jetty.etc.config.urls");
            if (etcJettyXml == null && new File(jettyHome,"etc/jetty-osgi-nested.xml").exists())
            {
                System.setProperty("jetty.etc.config.urls","etc/jetty-osgi-nested.xml");
            }
            System.setProperty("java.naming.factory.initial","org.eclipse.jetty.jndi.InitialContextFactory");
            System.setProperty("java.naming.factory.url.pkgs","org.eclipse.jetty.jndi");
        }
        // String sysPackagesExtra = "org.osgi.framework.system.packages.extra";
        // System.setProperty(sysPackagesExtra, "javax.servlet,javax.servlet.http");
        return props;
    }

    /**
     * Look for the eclipse.ini file. or any *.ini Search for the argument -startup The next line is a relative path to the launcher osgi bundle:
     * ../bundlepool/plugins/org.eclipse.equinox.launcher_1.1.0.v20100507.jar Get that file, get the parent folder. This is where the plugins are located. In
     * that folder look for the
     * 
     * @param installFolder
     * @return The osgi framework bundle.
     */
    private File findOsgiFramework(File installFolder)
    {
        File[] fs = installFolder.listFiles();
        for (int i = 0; i < fs.length; i++)
        {
            File f = fs[i];
            if (f.isFile() && f.getName().endsWith(".ini") && !f.getName().equals(LAUNCH_INI)) { //$NON-NLS-1$
                BufferedReader br = null;
                try
                {
                    br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
                    String line = null;
                    String pathToLauncherJar = null;
                    boolean gotStartArg = false;
                    while ((line = br.readLine()) != null)
                    {
                        if (gotStartArg)
                        {
                            pathToLauncherJar = line.trim();
                            if (pathToLauncherJar.length() == 0)
                            {
                                continue;
                            }
                            break;
                        }
                        else if (line.trim().equals("-startup")) { //$NON-NLS-1$
                            gotStartArg = true;
                        }
                    }
                    if (pathToLauncherJar != null)
                    {
                        File currFolder = getPlatformDirectory();
                        String oriStartup = pathToLauncherJar;
                        while (pathToLauncherJar.startsWith("../")) { //$NON-NLS-1$
                            currFolder = currFolder.getParentFile();
                            pathToLauncherJar = pathToLauncherJar.substring(3);
                        }
                        File pluginsfolder = new File(currFolder,pathToLauncherJar).getParentFile();
                        // System.err.println("Got the pluginsfolder " + pluginsfolder);
                        if (!pluginsfolder.exists())
                        {
                            throw new IllegalStateException("The -startup argument in " + f.getPath() + //$NON-NLS-1$
                                    " is " + oriStartup + ". It points to " + pluginsfolder.getPath() + //$NON-NLS-1$ //$NON-NLS-2$
                                    " plugins directory that does not exists."); //$NON-NLS-1$
                        }
                        TreeMap osgis = new TreeMap();
                        File[] plugins = pluginsfolder.listFiles();
                        for (int j = 0; j < plugins.length; j++)
                        {
                            File b = plugins[j];
                            if (b.isFile() && b.getName().startsWith(FRAMEWORK_BUNDLE_NAME + "_") && b.getName().endsWith(".jar")) { //$NON-NLS-1$ //$NON-NLS-2$
                                osgis.put(b.getName(),b);
                            }
                        }
                        if (osgis.isEmpty())
                        {
                            throw new IllegalStateException("The -startup argument in " + f.getPath() + //$NON-NLS-1$
                                    " is " + oriStartup + //$NON-NLS-1$
                                    ". It points to " + pluginsfolder.getPath() + //$NON-NLS-1$
                                    " plugins directory but there is no org.eclipse.osgi.*.jar files there."); //$NON-NLS-1$
                        }
                        File osgiFramework = (File)osgis.values().iterator().next();
                        String path = osgiFramework.getPath();
                        System.err.println("Using " + path + " for the osgi framework.");
                        return osgiFramework;
                    }
                }
                catch (IOException ioe)
                {
                    //
                }
                finally
                {
                    if (br != null)
                        try
                        {
                            br.close();
                        }
                        catch (IOException ii)
                        {
                        }
                }

            }
        }
        return null;
    }

    private static Field _field;

    // introspection trick to be able to set the private field platformDirectory
    void __setPlatformDirectory(File platformDirectory)
    {
        try
        {
            if (_field == null)
            {
                _field = org.eclipse.equinox.servletbridge.FrameworkLauncher.class.getDeclaredField("platformDirectory"); //$NON-NLS-1$
                _field.setAccessible(true);
            }
            _field.set(this,platformDirectory);
        }
        catch (SecurityException e)
        {
            e.printStackTrace();
        }
        catch (NoSuchFieldException e)
        {
            e.printStackTrace();
        }
        catch (IllegalArgumentException e)
        {
            e.printStackTrace();
        }
        catch (IllegalAccessException e)
        {
            e.printStackTrace();
        }
    }
    
    /**
     * recursively substitute the ${sysprop} by their actual system property.
     * ${sysprop,defaultvalue} will use 'defaultvalue' as the value if no sysprop is defined.
     * Not the most efficient code but we are shooting for simplicity and speed of development here.
     * 
     * @param value
     * @return
     */
    public static String resolveSystemProperty(String value)
    {       
        int ind = value.indexOf("${");
        if (ind == -1) {
                return value;
        }
        int ind2 = value.indexOf('}', ind);
        if (ind2 == -1) {
            return value;
        }
        String sysprop = value.substring(ind+2, ind2);
        String defaultValue = null;
        int comma = sysprop.indexOf(',');
        if (comma != -1 && comma+1 != sysprop.length())
        {
            defaultValue = sysprop.substring(comma+1);
            defaultValue = resolveSystemProperty(defaultValue);
            sysprop = sysprop.substring(0,comma);
        }
        else
        {
                defaultValue = "${" + sysprop + "}";
        }
        
        String v = System.getProperty(sysprop);
        
        String reminder = value.length() > ind2 + 1 ? value.substring(ind2+1) : "";
        reminder = resolveSystemProperty(reminder);
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
