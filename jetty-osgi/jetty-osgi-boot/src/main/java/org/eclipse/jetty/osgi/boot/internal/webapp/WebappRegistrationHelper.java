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
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.eclipse.jetty.deploy.ConfigurationManager;
import org.eclipse.jetty.deploy.ContextDeployer;
import org.eclipse.jetty.deploy.WebAppDeployer;
import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.internal.jsp.TldLocatableURLClassloader;
import org.eclipse.jetty.osgi.boot.internal.jsp.TldLocatableURLClassloaderWithInsertedJettyClassloader;
import org.eclipse.jetty.osgi.boot.utils.BundleClassLoaderHelper;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.osgi.boot.utils.WebappRegistrationCustomizer;
import org.eclipse.jetty.osgi.boot.utils.internal.DefaultBundleClassLoaderHelper;
import org.eclipse.jetty.osgi.boot.utils.internal.DefaultFileLocatorHelper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.xml.sax.SAXException;

/**
 * Bridges the traditional web-application deployers: {@link WebAppDeployer} and
 *  {@link ContextDeployer} with the OSGi lifecycle where applications are managed
 * inside OSGi-bundles.
 * <p>
 * This class should be called as a consequence of the activation of a new service that is a ContextHandler.<br/>
 * This way the new webapps are exposed as OSGi services.
 * </p>
 * <p>
 * Helper methods to register a bundle that is a web-application or a context.
 * It is deployed as if the server was using its WebAppDeployer or ContextDeployer
 * as configured in its etc/jetty.xml file. Well as close as possible to that.
 * </p>
 * Limitations:
 * <ul>
 * <li>support for jarred webapps is somewhat limited.</li>
 * </ul>
 */
class WebappRegistrationHelper
{
    
    private static boolean INITIALIZED = false;
    
    /** By default set to: {@link DefaultBundleClassLoaderHelper}. It supports equinox and apache-felix
     * fragment bundles that are specific to an OSGi implementation should set a different implementation. */
    public static BundleClassLoaderHelper BUNDLE_CLASS_LOADER_HELPER = null;
    /** By default set to: {@link DefaultBundleClassLoaderHelper}. It supports equinox and apache-felix
     * fragment bundles that are specific to an OSGi implementation should set a different implementation. */
    public static BundleFileLocatorHelper BUNDLE_FILE_LOCATOR_HELPER = null;
    
    /** By default set to: {@link DefaultBundleClassLoaderHelper}. It supports equinox and apache-felix
     * fragment bundles that are specific to an OSGi implementation should set a different implementation. */
    public static WebappRegistrationCustomizer JSP_REGISTRATION_HELPER = null;
    
    private Server _server;
    private ContextDeployer _ctxtDeployer;
    private WebAppDeployer _webappDeployer;
    private ContextHandlerCollection _ctxtHandler;

    /**
     * this class loader loads the jars inside {$jetty.home}/lib/ext it is meant as a migration path and for jars that are not OSGi ready.
     */
    private ClassLoader _libEtcClassLoader;

    public WebappRegistrationHelper(Server server)
    {
        _server = server;
        staticInit();
    }
    
  //Inject the customizing classes that might be defined in fragment bundles.
    private static synchronized void staticInit() {
        if (!INITIALIZED) {
            INITIALIZED = true;
            //setup the custom WebappRegistrationCustomizer
            try
            {
                Class<?> cl = Class.forName(WebappRegistrationCustomizer.CLASS_NAME);
                JSP_REGISTRATION_HELPER = (WebappRegistrationCustomizer)
                    cl.newInstance();
            }
            catch (Throwable t)
            {
//                System.err.println("no jsp/jasper support");
//                System.exit(1);
            }
            //setup the custom BundleClassLoaderHelper
            try
            {
                BUNDLE_CLASS_LOADER_HELPER = (BundleClassLoaderHelper)
                    Class.forName(BundleClassLoaderHelper.CLASS_NAME).newInstance();
            }
            catch (Throwable t)
            {
//                System.err.println("support for equinox and felix");
                BUNDLE_CLASS_LOADER_HELPER = new DefaultBundleClassLoaderHelper();
            }
            //setup the custom FileLocatorHelper
            try
            {
                BUNDLE_FILE_LOCATOR_HELPER = (BundleFileLocatorHelper)
                    Class.forName(BundleFileLocatorHelper.CLASS_NAME).newInstance();
            }
            catch (Throwable t)
            {
//                System.err.println("no jsp/jasper support");
                BUNDLE_FILE_LOCATOR_HELPER = new DefaultFileLocatorHelper();
            }
        }
    }
    
    /**
     * Removes quotes around system property values before we try to make them into
     * file pathes.
     */
    public static String stripQuotesIfPresent(String filePath) {
        if (filePath == null)
            return null;
        
        if ((filePath.startsWith("\"") || filePath.startsWith("'"))
                && (filePath.endsWith("\"") || filePath.endsWith("'")))
            return filePath.substring(1, filePath.length()-1);
        return filePath;
    }

    /**
     * Look for the home directory of jetty as defined by the system property 
     * 'jetty.home'. If undefined, look at the current bundle and uses its own jettyhome
     * folder for this feature.
     * <p>
     * Special case: inside eclipse-SDK:<br/>
     * If the bundle is jarred, see if we are inside eclipse-PDE itself. In that case, 
     * look for the installation directory of eclipse-PDE, try to create a
     * jettyhome folder there and install the sample jettyhome folder at that location. 
     * This makes the installation in eclipse-SDK easier.
     * <br/>This is a bit redundant with the work done by the jetty configuration launcher.
     * </p>
     * 
     * @param context
     * @throws Exception
     */
    public void setup(BundleContext context, Map<String, String> configProperties) throws Exception
    {
        File _installLocation = BUNDLE_FILE_LOCATOR_HELPER.getBundleInstallLocation(context.getBundle());
        // debug:
        // new File("~/proj/eclipse-install/eclipse-3.5.1-SDK-jetty7/" +
        // "dropins/jetty7/plugins/org.eclipse.jetty.osgi.boot_0.0.1.001-SNAPSHOT.jar");
        boolean bootBundleCanBeJarred = true;
        String jettyHome = stripQuotesIfPresent(System.getProperty("jetty.home"));
        
        if (jettyHome == null || jettyHome.length() == 0)
        {
            if (_installLocation.getName().endsWith(".jar"))
            {
                jettyHome = JettyHomeHelper.setupJettyHomeInEclipsePDE(_installLocation);
            }
            if (jettyHome == null)
            {
                jettyHome = _installLocation.getAbsolutePath() + "/jettyhome";
                bootBundleCanBeJarred = false;
            }
        }
        //in case we stripped the quotes.
        System.setProperty("jetty.home",jettyHome);
        
        String jettyLogs = stripQuotesIfPresent(System.getProperty("jetty.logs"));
        if (jettyLogs == null || jettyLogs.length() == 0)
        {
            System.setProperty("jetty.logs",jettyHome + "/logs");
        }

        if (!bootBundleCanBeJarred && !_installLocation.isDirectory())
        {
            String install = _installLocation != null
                ? _installLocation.getCanonicalPath()
                : " unresolved_install_location";
            throw new IllegalArgumentException("The system property -Djetty.home"
                    + " must be set to a directory or the bundle "
                    + context.getBundle().getSymbolicName()
                    + " installed here " + install + " must be unjarred.");

        }
        try
        {
            System.err.println("JETTY_HOME set to " + new File(jettyHome).getCanonicalPath());
        }
        catch (Throwable t)
        {
            System.err.println("JETTY_HOME _set to " + new File(jettyHome).getAbsolutePath());
        }

        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        try
        {

            XmlConfiguration config = new XmlConfiguration(
                    new FileInputStream(jettyHome + "/etc/jetty.xml"));
            config.getProperties().put("jetty.home", jettyHome);

            // passing this bundle's classloader as the context classlaoder
            // makes sure there is access to all the jetty's bundles

            File jettyHomeF = new File(jettyHome);
            try
            {
                _libEtcClassLoader = LibExtClassLoaderHelper.createLibEtcClassLoaderHelper(
                        jettyHomeF,_server,JettyBootstrapActivator.class.getClassLoader());
            }
            catch (MalformedURLException e)
            {
                e.printStackTrace();
            }

            Thread.currentThread().setContextClassLoader(_libEtcClassLoader);
            config.configure(_server);

            init();

            _server.start();
            // _server.join();
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(contextCl);
        }

    }

    /**
     * Must be called after the server is configured.
     * 
     * Locate the actual instance of the ContextDeployer and WebAppDeployer
     * that was created when configuring the server through jetty.xml. If there is no such
     * thing it won't be possible to deploy webapps from a context and we 
     * throw IllegalStateExceptions.
     */
    private void init()
    {

        // [Hugues] if no jndi is setup let's do it.
        // we could also get the bundle for jetty-jndi and open the corresponding properties file
        // instead of hardcoding the values: but they are unlikely to change.
        if (System.getProperty("java.naming.factory.initial") == null)
        {
            System.setProperty("java.naming.factory.initial",
                    "org.eclipse.jetty.jndi.InitialContextFactory");
        }
        if (System.getProperty("java.naming.factory.url.pkgs") == null)
        {
            System.setProperty("java.naming.factory.url.pkgs","org.eclipse.jetty.jndi");
        }

        _ctxtHandler = (ContextHandlerCollection)_server.getChildHandlerByClass(ContextHandlerCollection.class);
        if (_ctxtHandler == null)
        {
            throw new IllegalStateException("ERROR: No ContextHandlerCollection was configured"
                    + " with the server to add applications to."
                    + "Using a default one is not supported at"
                    + " this point. " + " Please review the jetty.xml file used.");
        }
        List<ContextDeployer> ctxtDeployers = _server.getBeans(ContextDeployer.class);

        if (ctxtDeployers == null || ctxtDeployers.isEmpty())
        {
            System.err.println("Warn: No ContextDeployer was configured"
                    + " with the server. Using a default one is not supported at" + " this point. "
                    + " Please review the jetty.xml file used.");
        }
        else
        {
            _ctxtDeployer = ctxtDeployers.get(0);
        }
        List<WebAppDeployer> wDeployers = _server.getBeans(WebAppDeployer.class);

        if (wDeployers == null || wDeployers.isEmpty())
        {
            System.err.println("Warn: No WebappDeployer was configured"
                    + " with the server. Using a default one is not supported at"
                    + " this point. "
                    + " Please review the jetty.xml file used.");
        }
        else
        {
            _webappDeployer = (WebAppDeployer)wDeployers.get(0);
        }

    }

    /**
     * Deploy a new web application on the jetty server.
     * 
     * @param context
     *            The current bundle context
     * @param webappFolderPath
     *            The path to the root of the webapp. Must be a path relative to bundle;
     *            either an absolute path.
     * @param contextPath
     *            The context path. Must start with "/"
     * @param classInBundle
     *            A class that belongs to the current bundle to inherit from the osgi classloader.
     *            Null to not have access to the OSGI classloader.
     * @throws Exception
     */
    public ContextHandler registerWebapplication(Bundle bundle, String webappFolderPath,
            String contextPath, String extraClasspath, String overrideBundleInstallLocation) throws Exception
    {
        File bundleInstall = overrideBundleInstallLocation == null
            ? BUNDLE_FILE_LOCATOR_HELPER.getBundleInstallLocation(bundle)
            : new File(overrideBundleInstallLocation);
        File webapp = null;
        if (webappFolderPath != null && webappFolderPath.length() != 0 
                && !webappFolderPath.equals("."))
        {
            if (webappFolderPath.startsWith("/") || webappFolderPath.startsWith("file:/"))
            {
                webapp = new File(webappFolderPath);
            }
            else
            {
                webapp = new File(bundleInstall, webappFolderPath);
            }
        }
        else
        {
            webapp = bundleInstall;
        }
        if (!webapp.exists())
        {
            throw new IllegalArgumentException("Unable to locate "
                    + webappFolderPath + " inside "
                    + (bundleInstall != null
               ? bundleInstall.getAbsolutePath()
               : "unlocated bundle '" + bundle.getSymbolicName() + "'"));
        }
        return registerWebapplication(bundle,webapp,contextPath,extraClasspath);
    }

    /**
     * @See {@link WebAppDeployer#scan()}
     * 
     * @param webapp
     * @param contextPath
     * @param classInBundle
     * @return The contexthandler created and started
     * @throws Exception
     */
    public ContextHandler registerWebapplication(Bundle contributor, File webapp,
            String contextPath, String extraClasspath) throws Exception
    {

        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        String[] oldServerClasses = null;
        WebAppContext context = null;
        try
        {
            // make sure we provide access to all the jetty bundles by going through this bundle.
            TldLocatableURLClassloader composite = createContextClassLoader(contributor);
            // configure with access to all jetty classes and also all the classes
            // that the contributor gives access to.
            Thread.currentThread().setContextClassLoader(composite);

            context = new WebAppContext(webapp.getAbsolutePath(),contextPath);
            context.setExtraClasspath(extraClasspath);

            WebXmlConfiguration webXml = new WebXmlConfiguration();
            webXml.configure(context);

            JettyWebXmlConfiguration jettyXml = new JettyWebXmlConfiguration();
            jettyXml.configure(context);

            configureWebAppContext(context);

            // ok now register this webapp. we checked when we started jetty that there
            // was at least one such handler for webapps.
            _ctxtHandler.addHandler(context);

            configureContextClassLoader(contributor,context,composite);

            // @see org.eclipse.jetty.webapp.JettyWebXmlConfiguration#configure(WebAppContext)
            // during initialization of the webapp all the jetty packages are visible
            // through the webapp classloader.
            oldServerClasses = context.getServerClasses();
            context.setServerClasses(null);
            context.start();

            return context;
        }
        finally
        {
            if (context != null && oldServerClasses != null)
            {
                context.setServerClasses(oldServerClasses);
            }
            Thread.currentThread().setContextClassLoader(contextCl);
        }

    }

    /**
     * Stop a ContextHandler and remove it from the collection.
     * 
     * @See ContextDeployer#undeploy
     * @param contextHandler
     * @throws Exception
     */
    public void unregister(ContextHandler contextHandler) throws Exception
    {
        contextHandler.stop();
        _ctxtHandler.removeHandler(contextHandler);
    }

    /**
     * @return The folder in which the context files of the osgi bundles are
     *         located and watched. Or null when the system property
     *         "jetty.osgi.contexts.home" is not defined.
     */
    File getOSGiContextsHome()
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
        return null;
    }

    /**
     * This type of registration relies on jetty's complete context xml file.
     *          Context encompasses jndi and all other things.
     *          This makes the definition of the webapp a lot more self-contained.
     * 
     * @param webapp
     * @param contextPath
     * @param classInBundle
     * @throws Exception
     */
    public ContextHandler registerContext(Bundle contributor, String contextFileRelativePath,
            String extraClasspath, String overrideBundleInstallLocation) throws Exception
    {
        File contextsHome = getOSGiContextsHome();
        if (contextsHome != null)
        {
            File prodContextFile = new File(contextsHome,contributor.getSymbolicName() + "/" + contextFileRelativePath);
            if (prodContextFile.exists())
            {
                return registerContext(contributor,prodContextFile,
                            extraClasspath, overrideBundleInstallLocation);
            }
        }
        File contextFile = overrideBundleInstallLocation != null
            ? new File(overrideBundleInstallLocation, contextFileRelativePath)
            : new File(BUNDLE_FILE_LOCATOR_HELPER.getBundleInstallLocation(contributor),contextFileRelativePath);
        if (contextFile.exists())
        {
            return registerContext(contributor, contextFile,
                        extraClasspath, overrideBundleInstallLocation);
        }
        else
        {
            if (contextFileRelativePath.startsWith("./"))
            {
                contextFileRelativePath = contextFileRelativePath.substring(1);
            }
            if (!contextFileRelativePath.startsWith("/"))
            {
                contextFileRelativePath = "/" + contextFileRelativePath;
            }
            if (overrideBundleInstallLocation == null)
            {
                URL contextURL = contributor.getEntry(contextFileRelativePath);
                if (contextURL != null)
                {
                    return registerContext(contributor,
                                contextURL.openStream(), extraClasspath,
                                overrideBundleInstallLocation);
                }
            }
            else
            {
                JarFile zipFile = null;
                try
                {
                    zipFile = new JarFile(overrideBundleInstallLocation);
                    ZipEntry entry = zipFile.getEntry(contextFileRelativePath.substring(1));
                    return registerContext(contributor,
                            zipFile.getInputStream(entry), extraClasspath,
                            overrideBundleInstallLocation);
                }
                catch (Throwable t)
                {
                    
                }
                finally
                {
                    if (zipFile != null) try { zipFile.close(); } catch (IOException ioe) {}
                }
            }
            throw new IllegalArgumentException("Could not find the context "
                    + "file " + contextFileRelativePath + " for the bundle "
                    + contributor.getSymbolicName()
                    + (overrideBundleInstallLocation != null
                            ? " using the install location " + overrideBundleInstallLocation : ""));
        }
    }

    /**
     * This type of registration relies on jetty's complete context xml file.
     * Context encompasses jndi and all other things. This makes the definition of the
     * webapp a lot more self-contained.
     * 
     * @param webapp
     * @param contextPath
     * @param classInBundle
     * @throws Exception
     */
    private ContextHandler registerContext(Bundle contributor, File contextFile,
            String extraClasspath, String overrideBundleInstallLocation) throws Exception
    {
        InputStream contextFileInputStream = null;
        try
        {
            contextFileInputStream = new BufferedInputStream(new FileInputStream(contextFile));
            return registerContext(contributor, contextFileInputStream,
                        extraClasspath, overrideBundleInstallLocation);
        }
        finally
        {
            if (contextFileInputStream != null)
                try
                {
                    contextFileInputStream.close();
                }
                catch (IOException ioe)
                {
                }
        }
    }

    /**
     * @param contributor
     * @param contextFileInputStream
     * @return The ContextHandler created and registered or null if it did not happen.
     * @throws Exception
     */
    private ContextHandler registerContext(Bundle contributor, InputStream contextFileInputStream,
            String extraClasspath, String overrideBundleInstallLocation) throws Exception
    {
        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        String[] oldServerClasses = null;
        WebAppContext webAppContext = null;
        try
        {
            // make sure we provide access to all the jetty bundles by going through this bundle.
            TldLocatableURLClassloader composite = createContextClassLoader(contributor);
            // configure with access to all jetty classes and also all the classes
            // that the contributor gives access to.
            Thread.currentThread().setContextClassLoader(composite);
            ContextHandler context = createContextHandler(contributor,
                    contextFileInputStream, extraClasspath, overrideBundleInstallLocation);
            if (context == null) {
            	return null;//did not happen
            }
            
            // ok now register this webapp. we checked when we started jetty that there
            // was at least one such handler for webapps.
            _ctxtHandler.addHandler(context);

            configureContextClassLoader(contributor,context,composite);
            if (context instanceof WebAppContext)
            {
                webAppContext = (WebAppContext)context;
                // @see org.eclipse.jetty.webapp.JettyWebXmlConfiguration#configure(WebAppContext)
                oldServerClasses = webAppContext.getServerClasses();
                webAppContext.setServerClasses(null);
            }
            
            context.start();
            return context;
        }
        finally
        {
            if (webAppContext != null)
            {
                webAppContext.setServerClasses(oldServerClasses);
            }
            Thread.currentThread().setContextClassLoader(contextCl);
        }

    }

    /**
     * TODO: right now only the jetty-jsp bundle is scanned for common taglibs. Should support a way to plug more bundles that contain taglibs.
     * 
     * The jasper TldScanner expects a URLClassloader to parse a jar for the /META-INF/*.tld it may contain. We place the bundles that we know contain such
     * tag-libraries. Please note that it will work if and only if the bundle is a jar (!) Currently we just hardcode the bundle that contains the jstl
     * implemenation.
     * 
     * A workaround when the tld cannot be parsed with this method is to copy and paste it inside the WEB-INF of the webapplication where it is used.
     * 
     * Support only 2 types of packaging for the bundle: - the bundle is a jar (recommended for runtime.) - the bundle is a folder and contain jars in the root
     * and/or in the lib folder (nice for PDE developement situations) Unsupported: the bundle is a jar that embeds more jars.
     * 
     * @return
     * @throws Exception
     */
    private URL[] getJarsWithTlds() throws Exception
    {
        if (JSP_REGISTRATION_HELPER != null)
        {
            return JSP_REGISTRATION_HELPER.getJarsWithTlds(BUNDLE_FILE_LOCATOR_HELPER);
        }
        else
        {
            return null;
        }
        
    }

    /**
     * Applies the properties of WebAppDeployer as defined in jetty.xml.
     * 
     * @see {WebAppDeployer#scan} around the comment <code>// configure it</code>
     */
    protected void configureWebAppContext(WebAppContext wah)
    {
        // configure it
        // wah.setContextPath(context);
        String[] _configurationClasses = _webappDeployer.getConfigurationClasses();
        String _defaultsDescriptor = _webappDeployer.getDefaultsDescriptor();
        boolean _parentLoaderPriority = _webappDeployer.isParentLoaderPriority();
        AttributesMap _contextAttributes = getWebAppDeployerContextAttributes();

        if (_configurationClasses != null)
            wah.setConfigurationClasses(_configurationClasses);
        if (_defaultsDescriptor != null)
            wah.setDefaultsDescriptor(_defaultsDescriptor);
        // wah.setExtractWAR(_extract);//[H]should we force to extract ?
        // wah.setWar(app.toString());//[H]should we force to extract ?
        wah.setParentLoaderPriority(_parentLoaderPriority);

        // set up any contextAttributes
        wah.setAttributes(new AttributesMap(_contextAttributes));

    }

    /**
     * @See {@link ContextDeployer#scan}
     * @param contextFile
     * @return
     */
    protected ContextHandler createContextHandler(Bundle bundle, File contextFile,
            String extraClasspath, String overrideBundleInstallLocation)
    {
        try
        {
            return createContextHandler(bundle,new BufferedInputStream(
                    new FileInputStream(contextFile)), extraClasspath, overrideBundleInstallLocation);
        }
        catch (FileNotFoundException e)
        {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * @See {@link ContextDeployer#scan}
     * @param contextFile
     * @return
     */
    @SuppressWarnings("unchecked")
    protected ContextHandler createContextHandler(Bundle bundle, InputStream contextInputStream,
            String extraClasspath, String overrideBundleInstallLocation)
    {

        /*
         * Do something identical to what the ContextDeployer would have done: XmlConfiguration xmlConfiguration=new XmlConfiguration(resource.getURL());
         * HashMap properties = new HashMap(); properties.put("Server", _contexts.getServer()); if (_configMgr!=null)
         * properties.putAll(_configMgr.getProperties());
         * 
         * xmlConfiguration.setProperties(properties); ContextHandler context=(ContextHandler)xmlConfiguration.configure(); context.setAttributes(new
         * AttributesMap(_contextAttributes));
         */
        ConfigurationManager _configMgr = getContextDeployerConfigurationManager();
        AttributesMap _contextAttributes = getContextDeployerContextAttributes();
        try
        {
            XmlConfiguration xmlConfiguration = new XmlConfiguration(contextInputStream);
            HashMap<String, Object> properties = new HashMap<String, Object>();
            properties.put("Server",_server);
            if (_configMgr != null)
            {
                properties.putAll(_configMgr.getProperties());
            }
            // insert the bundle's location as a property.
            setThisBundleHomeProperty(bundle,properties,overrideBundleInstallLocation);
            xmlConfiguration.setProperties(properties);

            ContextHandler context = (ContextHandler)xmlConfiguration.configure();
            if (context instanceof WebAppContext)
            {
                ((WebAppContext)context).setExtraClasspath(extraClasspath);
            }
            context.setAttributes(new AttributesMap(_contextAttributes));

            // rfc-66:
            context.setAttribute("osgi-bundlecontext",bundle.getBundleContext());

            return context;
        }
        catch (FileNotFoundException e)
        {
            return null;
        }
        catch (SAXException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        catch (Throwable e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        finally
        {
            if (contextInputStream != null)
                try { contextInputStream.close(); } catch (IOException ioe) {}
        }
        return null;
    }

    /**
     * Configure a classloader onto the context. If the context is a WebAppContext,
     * build a WebAppClassLoader that has access to all the jetty classes thanks to
     * the classloader of the JettyBootStrapper bundle and also has access to the
     * classloader of the bundle that defines this context.
     * <p>
     * If the context is not a WebAppContext, same but with a simpler URLClassLoader.
     * Note that the URLClassLoader is pretty much fake: it delegate all actual
     * classloading to the parent classloaders.
     * </p>
     * <p>
     * The URL[] returned by the URLClassLoader create contained specifically
     * the jars that some j2ee tools expect and look into. For example the jars that
     * contain tld files for jasper's jstl support.
     * </p>
     * <p>
     * Also as the jars in the lib folder and the classes in the classes folder
     * might already be in the OSGi classloader we filter them out
     * of the WebAppClassLoader
     * </p>
     * 
     * @param context
     * @param contributor
     * @param webapp
     * @param contextPath
     * @param classInBundle
     * @throws Exception
     */
    protected void configureContextClassLoader(
            Bundle contributor, ContextHandler context,
            TldLocatableURLClassloader composite) throws Exception
    {
        if (context instanceof WebAppContext)
        {
            WebAppContext webappCtxt = (WebAppContext)context;
            // updateServerClasses(webappCtxt);
            WebAppClassLoader wcl = new WebAppClassLoader(composite,webappCtxt) {

                @Override
                public void addJars(Resource lib)
                {
                    //TODO: look in the Bundle-Classpath header
                    //of the contributor bundle and don't add a lib that is already
                    //there.
                    super.addJars(lib);
                }
                
            };

            // addJarsWithTlds(wcl);
            context.setClassLoader(wcl);
        }
        else
        {
            context.setClassLoader(composite);
        }

    }

    protected TldLocatableURLClassloader createContextClassLoader(Bundle contributor) throws Exception
    {
        ClassLoader osgiCl = BUNDLE_CLASS_LOADER_HELPER.getBundleClassLoader(contributor);
        if (osgiCl != null)
        {
            // this solution does not insert all the jetty related classes in the webapp's classloader:
            // WebAppClassLoader cl = new WebAppClassLoader(classInBundle.getClassLoader(), context);
            // context.setClassLoader(cl);

            // Make all of the jetty's classes available to the webapplication classloader
            // also add the contributing bundle's classloader to give access to osgi to
            // the contributed webapp.
            TldLocatableURLClassloader composite =
                new TldLocatableURLClassloaderWithInsertedJettyClassloader(
                    _libEtcClassLoader, osgiCl, getJarsWithTlds());
            return composite;
        }
        else
        {
            // Make all of the jetty's classes available to the webapplication classloader
            TldLocatableURLClassloader composite = new TldLocatableURLClassloader(
                    _libEtcClassLoader,getJarsWithTlds());
            return composite;

        }

    }

    /**
     * Set the property &quot;this.bundle.install&quot; to point to the location of the bundle.
     * Useful when <SystemProperty name="this.bundle.home"/> is used.
     */
    private void setThisBundleHomeProperty(Bundle bundle, HashMap<String, Object> properties,String overrideBundleInstallLocation)
    {
        try
        {
            File location = overrideBundleInstallLocation != null
                ? new File(overrideBundleInstallLocation)
                : BUNDLE_FILE_LOCATOR_HELPER.getBundleInstallLocation(bundle);
            properties.put("this.bundle.install",location.getCanonicalPath());
        }
        catch (Throwable t)
        {
            System.err.println("Unable to set 'this.bundle.install' " + " for the bundle " + bundle.getSymbolicName());
            t.printStackTrace();
        }
    }

    // some private suff in ContextDeployer that we need to
    // be faithful to the ContextDeployer definition created in etc/jetty.xml
    // kindly ask to have a public getter for those?
    private static Field CONTEXT_DEPLOYER_CONFIGURATION_MANAGER_FIELD = null;
    private static Field CONTEXT_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD = null;

    private ConfigurationManager getContextDeployerConfigurationManager()
    {
        try
        {
            if (CONTEXT_DEPLOYER_CONFIGURATION_MANAGER_FIELD == null)
            {
                CONTEXT_DEPLOYER_CONFIGURATION_MANAGER_FIELD = ContextDeployer.class.getDeclaredField("_configMgr");
                CONTEXT_DEPLOYER_CONFIGURATION_MANAGER_FIELD.setAccessible(true);
            }
            return (ConfigurationManager)CONTEXT_DEPLOYER_CONFIGURATION_MANAGER_FIELD.get(_ctxtDeployer);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        return null;
    }

    private AttributesMap getContextDeployerContextAttributes()
    {
        try
        {
            if (CONTEXT_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD == null)
            {
                CONTEXT_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD = ContextDeployer.class.getDeclaredField("_contextAttributes");
                CONTEXT_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD.setAccessible(true);
            }
            return (AttributesMap)CONTEXT_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD.get(_ctxtDeployer);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        return null;
    }
    private static Field WEBAPP_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD = null;

    private AttributesMap getWebAppDeployerContextAttributes()
    {
        try
        {
            if (WEBAPP_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD == null)
            {
                WEBAPP_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD = WebAppDeployer.class.getDeclaredField("_contextAttributes");
                WEBAPP_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD.setAccessible(true);
            }
            return (AttributesMap)WEBAPP_DEPLOYER_CONTEXT_ATTRIBUTES_FIELD.get(_webappDeployer);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        return null;
    }

}
