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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.eclipse.equinox.servletbridge.FrameworkLauncher;

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
	public static final String	FRAMEWORK_BOOTDELEGATION = "org.osgi.framework.bootdelegation";
	
    private boolean deployedInPlace = false;
    private URL resourceBaseAsURL = null;
    
    protected static Boolean ASYNCH_START_IN_PROGRESS;
    protected static Throwable ASYNCH_START_FAILURE = null;
    
    /**
     * If the start is asynch we do it in a different thread and return immediately.
     */
    @Override
    public synchronized void start() {
    	if (ASYNCH_START_IN_PROGRESS == null && "true".equals(super.config.getInitParameter("asyncStart"))) {
    		final ClassLoader webappCl = Thread.currentThread().getContextClassLoader();
    		Thread th = new Thread() {
    			public void run() {
    				Thread.currentThread().setContextClassLoader(webappCl);
    				System.out.println("Jetty-Nested: Starting equinox asynchroneously.");
    				FrameworkLauncherExtended.this.start();
    				System.out.println("Jetty-Nested: Finished starting equinox asynchroneously.");
    			}
    		};
    		ASYNCH_START_IN_PROGRESS = true;
    		try {
    			th.start();
    		} catch (Throwable t) {
    			ASYNCH_START_FAILURE = t;
    			if (t instanceof RuntimeException) {
    				throw (RuntimeException)t;
    			} else {
    				throw new RuntimeException("Equinox failed to start", t);
    			}
    		} finally {
    			ASYNCH_START_IN_PROGRESS = false;
    		}
    	} else {
			System.out.println("Jetty-Nested: Starting equinox synchroneously.");
    		super.start();
			System.out.println("Jetty-Nested: Finished starting equinox synchroneously.");
    	}
    }

    /**
     * try to find the resource base for this webapp by looking for the launcher initialization file.
     */
    protected void initResourceBase()
    {
        try
        {
            String resourceBaseStr = System.getProperty(OSGI_INSTALL_AREA);
            if (resourceBaseStr == null || resourceBaseStr.length() == 0)
            {
                resourceBaseStr = config.getInitParameter(OSGI_INSTALL_AREA);
            }
            if (resourceBaseStr != null && resourceBaseStr.length() != 0)
            {
                // If the path starts with a reference to a system property, resolve it.
                resourceBaseStr = resolveSystemProperty(resourceBaseStr);
                if (resourceBaseStr.startsWith("/WEB-INF/"))
                {
                	String rpath = context.getRealPath(resourceBaseStr);
                	if (rpath != null)
                	{
                		File rpathFile = new File(rpath);
                		if (rpathFile.exists() && rpathFile.isDirectory() && rpathFile.canWrite())
                		{
                			resourceBaseStr = rpath;
                		}
                	}
                }
                
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
            	if (context.getResource(RESOURCE_BASE + ECLIPSE) != null)
            	{
            		resourceBase = RESOURCE_BASE + ECLIPSE;
            	}
            	else
            	{
                    super.initResourceBase();
            	}
                resourceBaseAsURL = context.getResource(resourceBase);
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
            File pluginsFolder = null;
            if (osgiFramework == null && getPlatformDirectory() != null)
            {
                File osgiFrameworkF = findOsgiFramework(getPlatformDirectory());
                pluginsFolder = osgiFrameworkF.getParentFile();
                props.put(OSGI_FRAMEWORK,osgiFrameworkF.getAbsoluteFile().getAbsolutePath());
            }
            String osgiFrameworkExtensions = props.getProperty(OSGI_FRAMEWORK_EXTENSIONS);
            if (osgiFrameworkExtensions == null)
            {
                //this bundle will make the javax.servlet and javax.servlet.http packages passed from
                //the bootstrap classloader into equinox
                osgiFrameworkExtensions = "org.eclipse.equinox.servletbridge.extensionbundle";
            }
            File configIni = new File(getPlatformDirectory(), "configuration/config.ini");
            Properties configIniProps = new Properties();
            if (configIni.exists())
            {
            	System.out.println("Got the " + configIni.getAbsolutePath());
                InputStream configIniStream = null;
                try
                {
                    configIniStream = new FileInputStream(configIni);
                    configIniProps.load(configIniStream);
                }
                catch (IOException ioe)
                {
                    
                }
                finally
                {
                    try { configIniStream.close(); } catch (IOException ioe2) {}
                }
                String confIniFrameworkExt = configIniProps.getProperty(OSGI_FRAMEWORK_EXTENSIONS);
                if (confIniFrameworkExt != null)
                {
                    osgiFrameworkExtensions = osgiFrameworkExtensions + "," + confIniFrameworkExt;
                }
            }
            else
            {
            	System.out.println("Unable to locate the " + configIni.getAbsolutePath());
            }
            props.setProperty(OSGI_FRAMEWORK_EXTENSIONS,osgiFrameworkExtensions);
            //__deployExtensionBundle(pluginsFolder);
            deployExtensionBundle(pluginsFolder, true);
            
            
            String bootDeleg = props.getProperty(FRAMEWORK_BOOTDELEGATION);
            if (bootDeleg == null)
            {
            	bootDeleg = configIniProps.getProperty(FRAMEWORK_BOOTDELEGATION);
            }
            if (bootDeleg == null || bootDeleg.indexOf("javax.servlet.http") == -1)
            {
            	String add = "javax.servlet,javax.servlet.http,javax.servlet.resources";
            	if (bootDeleg != null)
            	{
            		bootDeleg += add;
            	}
            	else
            	{
            		bootDeleg = add;
            	}
            	props.setProperty(FRAMEWORK_BOOTDELEGATION, bootDeleg);
            }
            
            String jettyHome = System.getProperty("jetty.home");
            if (jettyHome == null)
            {
            	jettyHome = getPlatformDirectory().getAbsolutePath();
                System.setProperty("jetty.home",jettyHome);
                props.setProperty("jetty.home",jettyHome);
            }
            else
            {
            	jettyHome = resolveSystemProperty(jettyHome);
            }
            String etcJettyXml = System.getProperty("jetty.etc.config.urls");
            if (etcJettyXml == null)
            {
            	if (new File(jettyHome,"etc/jetty-osgi-nested.xml").exists())
            	{
            		System.setProperty("jetty.etc.config.urls","etc/jetty-osgi-nested.xml");
            		props.setProperty("jetty.etc.config.urls","etc/jetty-osgi-nested.xml");
            	}
            }
            String startLevel = System.getProperty("osgi.startLevel");
            if (startLevel == null)
            {
            	startLevel = props.getProperty("osgi.startLevel");
            	if (startLevel == null)
            	{
            		startLevel = configIniProps.getProperty("osgi.startLevel");
            	}
            	if (startLevel != null)
            	{
            		props.setProperty("osgi.startLevel",startLevel);
            		System.setProperty("osgi.startLevel",startLevel);
            	}
            }
            String logback = System.getProperty("logback.configurationFile");
            if (logback == null)
            {
            	File etcLogback = new File(jettyHome,"etc/logback-nested.xml");
            	if (!etcLogback.exists()) {
            		etcLogback = new File(jettyHome,"etc/logback.xml");
            	}
            	if (etcLogback.exists())
            	{
            		System.setProperty("logback.configurationFile",etcLogback.getAbsolutePath());
            		props.setProperty("logback.configurationFile",etcLogback.getAbsolutePath());
            	}
            }
            else
            {
            	logback = resolveSystemProperty(logback);
            }
            System.out.println("sysout: logback.configurationFile=" + System.getProperty("logback.configurationFile"));
        }
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


    
    // introspection trick to be able to set the private field platformDirectory
    private static Field _field;
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
    
    //introspection trick to invoke the generateExtensionBundle method
    private static Method _deployExtensionBundleMethod;
    private void __deployExtensionBundle(File plugins)
    {
        //look for the extensionbundle
        //if it is already there no need to do something:
        for (String file : plugins.list())
        {
            if (file.startsWith("org.eclipse.equinox.servletbridge.extensionbundle"))//EXTENSIONBUNDLE_DEFAULT_BSN 
            {
                return;
            }
        }
        
        try
        {
            //invoke deployExtensionBundle(File plugins)
            if (_deployExtensionBundleMethod == null)
            {
                _deployExtensionBundleMethod = FrameworkLauncher.class.getDeclaredMethod("deployExtensionBundle", File.class);
                _deployExtensionBundleMethod.setAccessible(true);
            }
            _deployExtensionBundleMethod.invoke(this, plugins);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }
//--end of introspection to invoke deployExtensionBundle
    
//from Framework with support for the equinox hook
  	private static final String EXTENSIONBUNDLE_DEFAULT_BSN = "org.eclipse.equinox.servletbridge.extensionbundle"; //$NON-NLS-1$
  	private static final String EXTENSIONBUNDLE_DEFAULT_VERSION = "1.2.0"; //$NON-NLS-1$
  	private static final String MANIFEST_VERSION = "Manifest-Version"; //$NON-NLS-1$
  	private static final String BUNDLE_MANIFEST_VERSION = "Bundle-ManifestVersion"; //$NON-NLS-1$
  	private static final String BUNDLE_NAME = "Bundle-Name"; //$NON-NLS-1$
  	private static final String BUNDLE_SYMBOLIC_NAME = "Bundle-SymbolicName"; //$NON-NLS-1$
  	private static final String BUNDLE_VERSION = "Bundle-Version"; //$NON-NLS-1$
  	private static final String FRAGMENT_HOST = "Fragment-Host"; //$NON-NLS-1$
  	private static final String EXPORT_PACKAGE = "Export-Package"; //$NON-NLS-1$

  	private static final String CONFIG_EXTENDED_FRAMEWORK_EXPORTS = "extendedFrameworkExports"; //$NON-NLS-1$

	private void deployExtensionBundle(File plugins, boolean configureEquinoxHook) {
		// we might want to parameterize the extension bundle BSN in the future
		final String extensionBundleBSN = EXTENSIONBUNDLE_DEFAULT_BSN;
		File extensionBundleFile = findExtensionBundleFile(plugins, extensionBundleBSN);

		if (extensionBundleFile == null)
			generateExtensionBundle(plugins, extensionBundleBSN, EXTENSIONBUNDLE_DEFAULT_VERSION, configureEquinoxHook);
		else /*if (Boolean.valueOf(config.getInitParameter(CONFIG_OVERRIDE_AND_REPLACE_EXTENSION_BUNDLE)).booleanValue())*/ {
			String extensionBundleVersion = findExtensionBundleVersion(extensionBundleFile, extensionBundleBSN);
			if (extensionBundleFile.isDirectory()) {
				deleteDirectory(extensionBundleFile);
			} else {
				extensionBundleFile.delete();
			}
			generateExtensionBundle(plugins, extensionBundleBSN, extensionBundleVersion, true);
//		} else {
//			processExtensionBundle(extensionBundleFile);
		}
	}

	private File findExtensionBundleFile(File plugins, final String extensionBundleBSN) {
		FileFilter extensionBundleFilter = new FileFilter() {
			public boolean accept(File candidate) {
				return candidate.getName().startsWith(extensionBundleBSN + "_"); //$NON-NLS-1$
			}
		};
		File[] extensionBundles = plugins.listFiles(extensionBundleFilter);
		if (extensionBundles.length == 0)
			return null;

		if (extensionBundles.length > 1) {
			for (int i = 1; i < extensionBundles.length; i++) {
				if (extensionBundles[i].isDirectory()) {
					deleteDirectory(extensionBundles[i]);
				} else {
					extensionBundles[i].delete();
				}
			}
		}
		return extensionBundles[0];
	}

	private String findExtensionBundleVersion(File extensionBundleFile, String extensionBundleBSN) {
		String fileName = extensionBundleFile.getName();
		if (fileName.endsWith(".jar")) {
			return fileName.substring(extensionBundleBSN.length() + 1, fileName.length() - ".jar".length());
		}
		return fileName.substring(extensionBundleBSN.length() + 1);
	}

    
	private void generateExtensionBundle(File plugins, String extensionBundleBSN, String extensionBundleVersion,
			boolean configureEquinoxHook) {
		Manifest mf = new Manifest();
		Attributes attribs = mf.getMainAttributes();
		attribs.putValue(MANIFEST_VERSION, "1.0"); //$NON-NLS-1$
		attribs.putValue(BUNDLE_MANIFEST_VERSION, "2"); //$NON-NLS-1$
		attribs.putValue(BUNDLE_NAME, "Servletbridge Extension Bundle"); //$NON-NLS-1$
		attribs.putValue(BUNDLE_SYMBOLIC_NAME, extensionBundleBSN);
		attribs.putValue(BUNDLE_VERSION, extensionBundleVersion);
		attribs.putValue(FRAGMENT_HOST, "system.bundle; extension:=framework"); //$NON-NLS-1$

		String servletVersion = context.getMajorVersion() + "." + context.getMinorVersion(); //$NON-NLS-1$
		String packageExports = "org.eclipse.equinox.servletbridge; version=1.1" + //$NON-NLS-1$
				", javax.servlet; version=" + servletVersion + //$NON-NLS-1$
				", javax.servlet.http; version=" + servletVersion + //$NON-NLS-1$
				", javax.servlet.resources; version=" + servletVersion; //$NON-NLS-1$

		String extendedExports = config.getInitParameter(CONFIG_EXTENDED_FRAMEWORK_EXPORTS);
		if (extendedExports != null && extendedExports.trim().length() != 0)
			packageExports += ", " + extendedExports; //$NON-NLS-1$

		attribs.putValue(EXPORT_PACKAGE, packageExports);
		writeJarFile(new File(plugins, extensionBundleBSN + "_" + extensionBundleVersion + ".jar"), mf, configureEquinoxHook); //$NON-NLS-1$
	}

	private void writeJarFile(File jarFile, Manifest mf, boolean configureEquinoxHook) {
		try {
			JarOutputStream jos = null;
			try {
				jos = new JarOutputStream(new FileOutputStream(jarFile), mf);
				
				if (configureEquinoxHook) {
					//hook configurator properties:
					ZipEntry e = new ZipEntry("hookconfigurators.properties");
					jos.putNextEntry(e);
					Properties props = new Properties();
					props.put("hook.configurators", "org.eclipse.jetty.osgi.servletbridge.hook.ServletBridgeClassLoaderDelegateHook");
					props.store(jos, "");
					jos.closeEntry();
	
					//the hook class
					e = new ZipEntry("org/eclipse/jetty/osgi/servletbridge/hook/ServletBridgeClassLoaderDelegateHook.class");
					jos.putNextEntry(e);
					InputStream in = getClass().getResourceAsStream("/org/eclipse/jetty/osgi/servletbridge/hook/ServletBridgeClassLoaderDelegateHook.class");
					
		            byte[] buffer = new byte[512];
		            try {
		                int n;
		                while ((n = in.read(buffer)) != -1)
		                {
		                    jos.write(buffer, 0, n);
		                }
		            } finally {
		                in.close();
		            }
					jos.closeEntry();
				}
				
				jos.finish();
			} finally {
				if (jos != null)
					jos.close();
			}
		} catch (IOException e) {
			context.log("Error writing extension bundle", e); //$NON-NLS-1$
		}
	}
//--from Framework with support for the equinox hook

}
