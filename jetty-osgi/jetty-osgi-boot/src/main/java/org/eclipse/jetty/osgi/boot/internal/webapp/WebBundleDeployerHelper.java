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
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;

import org.eclipse.jetty.deploy.ContextDeployer;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.osgi.boot.utils.BundleClassLoaderHelper;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.osgi.boot.utils.WebappRegistrationCustomizer;
import org.eclipse.jetty.osgi.boot.utils.internal.DefaultBundleClassLoaderHelper;
import org.eclipse.jetty.osgi.boot.utils.internal.DefaultFileLocatorHelper;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.xml.sax.SAXException;

/**
 * Bridges the jetty deployers with the OSGi lifecycle where applications are
 * managed inside OSGi-bundles.
 * <p>
 * This class should be called as a consequence of the activation of a new
 * service that is a ContextHandler.<br/>
 * This way the new webapps are exposed as OSGi services.
 * </p>
 * <p>
 * Helper methods to register a bundle that is a web-application or a context.
 * </p>
 * Limitations:
 * <ul>
 * <li>support for jarred webapps is somewhat limited.</li>
 * </ul>
 */
public class WebBundleDeployerHelper implements IWebBundleDeployerHelper
{

    private static Logger __logger = Log.getLogger(WebBundleDeployerHelper.class.getName());

    private static boolean INITIALIZED = false;
    
    /**
     * By default set to: {@link DefaultBundleClassLoaderHelper}. It supports
     * equinox and apache-felix fragment bundles that are specific to an OSGi
     * implementation should set a different implementation.
     */
    public static BundleClassLoaderHelper BUNDLE_CLASS_LOADER_HELPER = null;
    /**
     * By default set to: {@link DefaultBundleClassLoaderHelper}. It supports
     * equinox and apache-felix fragment bundles that are specific to an OSGi
     * implementation should set a different implementation.
     */
    public static BundleFileLocatorHelper BUNDLE_FILE_LOCATOR_HELPER = null;

    /**
     * By default set to: {@link DefaultBundleClassLoaderHelper}. It supports
     * equinox and apache-felix fragment bundles that are specific to an OSGi
     * implementation should set a different implementation.
     * <p>
     * Several of those objects can be added here: For example we could have an optional fragment that setups
     * a specific implementation of JSF for the whole of jetty-osgi.
     * </p>
     */
    public static Collection<WebappRegistrationCustomizer> JSP_REGISTRATION_HELPERS = new ArrayList<WebappRegistrationCustomizer>();

    /**
     * this class loader loads the jars inside {$jetty.home}/lib/ext it is meant
     * as a migration path and for jars that are not OSGi ready. also gives
     * access to the jsp jars.
     */
    // private URLClassLoader _libExtClassLoader;

    private ServerInstanceWrapper _wrapper;

    public WebBundleDeployerHelper(ServerInstanceWrapper wrapper)
    {
    	staticInit();
    	_wrapper = wrapper;
    }

    // Inject the customizing classes that might be defined in fragment bundles.
    public static synchronized void staticInit()
    {
        if (!INITIALIZED)
        {
            INITIALIZED = true;
            // setup the custom BundleClassLoaderHelper
            try
            {
                BUNDLE_CLASS_LOADER_HELPER = (BundleClassLoaderHelper)Class.forName(BundleClassLoaderHelper.CLASS_NAME).newInstance();
            }
            catch (Throwable t)
            {
                // System.err.println("support for equinox and felix");
                BUNDLE_CLASS_LOADER_HELPER = new DefaultBundleClassLoaderHelper();
            }
            // setup the custom FileLocatorHelper
            try
            {
                BUNDLE_FILE_LOCATOR_HELPER = (BundleFileLocatorHelper)Class.forName(BundleFileLocatorHelper.CLASS_NAME).newInstance();
            }
            catch (Throwable t)
            {
                // System.err.println("no jsp/jasper support");
                BUNDLE_FILE_LOCATOR_HELPER = new DefaultFileLocatorHelper();
            }
        }
    }

    /* (non-Javadoc)
	 * @see org.eclipse.jetty.osgi.boot.internal.webapp.IWebBundleDeployerHelper#registerWebapplication(org.osgi.framework.Bundle, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)
	 */
    public WebAppContext registerWebapplication(Bundle bundle, String webappFolderPath, String contextPath, String extraClasspath,
            String overrideBundleInstallLocation, String webXmlPath, String defaultWebXmlPath, WebAppContext webAppContext) throws Exception
    {
        File bundleInstall = overrideBundleInstallLocation == null?BUNDLE_FILE_LOCATOR_HELPER.getBundleInstallLocation(bundle):new File(
                overrideBundleInstallLocation);
        File webapp = null;
        URL baseWebappInstallURL = null;
        if (webappFolderPath != null && webappFolderPath.length() != 0 && !webappFolderPath.equals("."))
        {
            if (webappFolderPath.startsWith("/") || webappFolderPath.startsWith("file:"))
            {
                webapp = new File(webappFolderPath);
            }
            else if (bundleInstall != null && bundleInstall.isDirectory())
            {
                webapp = new File(bundleInstall,webappFolderPath);
            }
            else if (bundleInstall != null)
            {
            	Enumeration<URL> urls = BUNDLE_FILE_LOCATOR_HELPER.findEntries(bundle, webappFolderPath);
            	if (urls != null && urls.hasMoreElements())
            	{
            		baseWebappInstallURL = urls.nextElement();
            	}
            }
        }
        else
        {
            webapp = bundleInstall;
        }
        if (baseWebappInstallURL == null && (webapp == null || !webapp.exists()))
        {
            throw new IllegalArgumentException("Unable to locate " + webappFolderPath + " inside "
                    + (bundleInstall != null?bundleInstall.getAbsolutePath():"unlocated bundle '" + bundle.getSymbolicName() + "'"));
        }
        if (baseWebappInstallURL == null && webapp != null)
        {
        	baseWebappInstallURL = webapp.toURI().toURL();
        }
        return registerWebapplication(bundle,webappFolderPath,baseWebappInstallURL,contextPath,
        		extraClasspath,bundleInstall,webXmlPath,defaultWebXmlPath,webAppContext);
    }
    
    /* (non-Javadoc)
	 * @see org.eclipse.jetty.osgi.boot.internal.webapp.IWebBundleDeployerHelper#registerWebapplication(org.osgi.framework.Bundle, java.lang.String, java.io.File, java.lang.String, java.lang.String, java.io.File, java.lang.String, java.lang.String)
	 */
    private WebAppContext registerWebapplication(Bundle contributor, String pathInBundleToWebApp,
    		URL baseWebappInstallURL, String contextPath, String extraClasspath, File bundleInstall,
            String webXmlPath, String defaultWebXmlPath, WebAppContext context) throws Exception
    {

        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        String[] oldServerClasses = null;
        
        try
        {
            // make sure we provide access to all the jetty bundles by going
            // through this bundle.
            OSGiWebappClassLoader composite = createWebappClassLoader(contributor);
            // configure with access to all jetty classes and also all the classes
            // that the contributor gives access to.
            Thread.currentThread().setContextClassLoader(composite);

            context.setWar(baseWebappInstallURL.toString());
            context.setContextPath(contextPath);
            context.setExtraClasspath(extraClasspath);

            if (webXmlPath != null && webXmlPath.length() != 0)
            {
                File webXml = null;
                if (webXmlPath.startsWith("/") || webXmlPath.startsWith("file:/"))
                {
                    webXml = new File(webXmlPath);
                }
                else
                {
                    webXml = new File(bundleInstall,webXmlPath);
                }
                if (webXml.exists())
                {
                    context.setDescriptor(webXml.getAbsolutePath());
                }
            }

            if (defaultWebXmlPath == null || defaultWebXmlPath.length() == 0)
            {
            	//use the one defined by the OSGiAppProvider.
            	defaultWebXmlPath = _wrapper.getOSGiAppProvider().getDefaultsDescriptor();
            }
            if (defaultWebXmlPath != null && defaultWebXmlPath.length() != 0)
            {
                File defaultWebXml = null;
                if (defaultWebXmlPath.startsWith("/") || defaultWebXmlPath.startsWith("file:/"))
                {
                    defaultWebXml = new File(webXmlPath);
                }
                else
                {
                    defaultWebXml = new File(bundleInstall,defaultWebXmlPath);
                }
                if (defaultWebXml.exists())
                {
                    context.setDefaultsDescriptor(defaultWebXml.getAbsolutePath());
                }
            }
            
            //other parameters that might be defines on the OSGiAppProvider:
            context.setParentLoaderPriority(_wrapper.getOSGiAppProvider().isParentLoaderPriority());

            configureWebAppContext(context,contributor);
            configureWebappClassLoader(contributor,context,composite);

            // @see
            // org.eclipse.jetty.webapp.JettyWebXmlConfiguration#configure(WebAppContext)
            // during initialization of the webapp all the jetty packages are
            // visible
            // through the webapp classloader.
            oldServerClasses = context.getServerClasses();
            context.setServerClasses(null);
            
            _wrapper.getOSGiAppProvider().addContext(contributor,pathInBundleToWebApp,context);
            
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

    /* (non-Javadoc)
	 * @see org.eclipse.jetty.osgi.boot.internal.webapp.IWebBundleDeployerHelper#unregister(org.eclipse.jetty.server.handler.ContextHandler)
	 */
    public void unregister(ContextHandler contextHandler) throws Exception
    {
    	_wrapper.getOSGiAppProvider().removeContext(contextHandler);
    }

    /* (non-Javadoc)
	 * @see org.eclipse.jetty.osgi.boot.internal.webapp.IWebBundleDeployerHelper#registerContext(org.osgi.framework.Bundle, java.lang.String, java.lang.String, java.lang.String)
	 */
    public ContextHandler registerContext(Bundle contributor, String contextFileRelativePath, String extraClasspath, 
    			String overrideBundleInstallLocation, ContextHandler handler)
            throws Exception
    {
        File contextsHome = _wrapper.getOSGiAppProvider().getContextXmlDirAsFile();
        if (contextsHome != null)
        {
            File prodContextFile = new File(contextsHome,contributor.getSymbolicName() + "/" + contextFileRelativePath);
            if (prodContextFile.exists())
            {
                return registerContext(contributor,contextFileRelativePath,prodContextFile,extraClasspath,
                		overrideBundleInstallLocation,handler);
            }
        }
        File rootFolder = overrideBundleInstallLocation != null
        	? Resource.newResource(overrideBundleInstallLocation).getFile()
        	: BUNDLE_FILE_LOCATOR_HELPER.getBundleInstallLocation(contributor);
        File contextFile = rootFolder != null?new File(rootFolder,contextFileRelativePath):null;
        if (contextFile != null && contextFile.exists())
        {
            return registerContext(contributor,contextFileRelativePath,contextFile,extraClasspath,overrideBundleInstallLocation,handler);
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
            
            URL contextURL = contributor.getEntry(contextFileRelativePath);
            if (contextURL != null)
            {
                return registerContext(contributor,contextFileRelativePath,contextURL.openStream(),extraClasspath,overrideBundleInstallLocation,handler);
            }
            throw new IllegalArgumentException("Could not find the context " + "file " + contextFileRelativePath + " for the bundle "
                    + contributor.getSymbolicName() + (overrideBundleInstallLocation != null?" using the install location " + overrideBundleInstallLocation:""));
        }
    }

    /**
     * This type of registration relies on jetty's complete context xml file.
     * Context encompasses jndi and all other things. This makes the definition
     * of the webapp a lot more self-contained.
     * 
     * @param webapp
     * @param contextPath
     * @param classInBundle
     * @throws Exception
     */
    private ContextHandler registerContext(Bundle contributor, String pathInBundle, File contextFile,
    		String extraClasspath, String overrideBundleInstallLocation, ContextHandler handler) throws Exception
    {
        InputStream contextFileInputStream = null;
        try
        {
            contextFileInputStream = new BufferedInputStream(new FileInputStream(contextFile));
            return registerContext(contributor, pathInBundle, contextFileInputStream,extraClasspath,overrideBundleInstallLocation, handler);
        }
        finally
        {
        	IO.close(contextFileInputStream);
        }
    }

    /**
     * @param contributor
     * @param contextFileInputStream
     * @return The ContextHandler created and registered or null if it did not
     *         happen.
     * @throws Exception
     */
    private ContextHandler registerContext(Bundle contributor, String pathInsideBundle, InputStream contextFileInputStream,
    		String extraClasspath, String overrideBundleInstallLocation, ContextHandler handler)
            throws Exception
    {
        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        String[] oldServerClasses = null;
        WebAppContext webAppContext = null;
        try
        {
            // make sure we provide access to all the jetty bundles by going
            // through this bundle.
            OSGiWebappClassLoader composite = createWebappClassLoader(contributor);
            // configure with access to all jetty classes and also all the
            // classes
            // that the contributor gives access to.
            Thread.currentThread().setContextClassLoader(composite);
            ContextHandler context = createContextHandler(handler, contributor,contextFileInputStream,extraClasspath,overrideBundleInstallLocation);
            if (context == null)
            {
                return null;// did not happen
            }

            // ok now register this webapp. we checked when we started jetty
            // that there
            // was at least one such handler for webapps.
            //the actual registration must happen via the new Deployment API.
//            _ctxtHandler.addHandler(context);

            configureWebappClassLoader(contributor,context,composite);
            if (context instanceof WebAppContext)
            {
                webAppContext = (WebAppContext)context;
                // @see
                // org.eclipse.jetty.webapp.JettyWebXmlConfiguration#configure(WebAppContext)
                oldServerClasses = webAppContext.getServerClasses();
                webAppContext.setServerClasses(null);
            }
            _wrapper.getOSGiAppProvider().addContext(contributor, pathInsideBundle, context);
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
     * Applies the properties of WebAppDeployer as defined in jetty.xml.
     * 
     * @see {WebAppDeployer#scan} around the comment
     *      <code>// configure it</code>
     */
    protected void configureWebAppContext(WebAppContext wah, Bundle contributor)
    {
        // rfc66
        wah.setAttribute(OSGiWebappConstants.RFC66_OSGI_BUNDLE_CONTEXT,contributor.getBundleContext());

        //spring-dm-1.2.1 looks for the BundleContext as a different attribute.
        //not a spec... but if we want to support 
        //org.springframework.osgi.web.context.support.OsgiBundleXmlWebApplicationContext
        //then we need to do this to:
        wah.setAttribute("org.springframework.osgi.web." + BundleContext.class.getName(),
                        contributor.getBundleContext());
        
    }

    /**
     * @See {@link ContextDeployer#scan}
     * @param contextFile
     * @return
     */
    protected ContextHandler createContextHandler(ContextHandler handlerToConfigure,
    		Bundle bundle, File contextFile, String extraClasspath, String overrideBundleInstallLocation)
    {
        try
        {
            return createContextHandler(handlerToConfigure,bundle,new BufferedInputStream(new FileInputStream(contextFile)),extraClasspath,overrideBundleInstallLocation);
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
    protected ContextHandler createContextHandler(ContextHandler handlerToConfigure,
    		Bundle bundle, InputStream contextInputStream, String extraClasspath, String overrideBundleInstallLocation)
    {
        /*
         * Do something identical to what the ContextProvider would have done:
         * XmlConfiguration xmlConfiguration=new
         * XmlConfiguration(resource.getURL()); HashMap properties = new
         * HashMap(); properties.put("Server", _contexts.getServer()); if
         * (_configMgr!=null) properties.putAll(_configMgr.getProperties());
         * 
         * xmlConfiguration.setProperties(properties); ContextHandler
         * context=(ContextHandler)xmlConfiguration.configure();
         * context.setAttributes(new AttributesMap(_contextAttributes));
         */
        try
        {
            XmlConfiguration xmlConfiguration = new XmlConfiguration(contextInputStream);
            HashMap properties = new HashMap();
            properties.put("Server",_wrapper.getServer());
            
            // insert the bundle's location as a property.
            setThisBundleHomeProperty(bundle,properties,overrideBundleInstallLocation);
            xmlConfiguration.setProperties(properties);

            ContextHandler context = null;
            if (handlerToConfigure == null)
            {
            	context = (ContextHandler)xmlConfiguration.configure();
            }
            else
            {
            	xmlConfiguration.configure(handlerToConfigure);
            	context = handlerToConfigure;
            }
            
            if (context instanceof WebAppContext)
            {
                ((WebAppContext)context).setExtraClasspath(extraClasspath);
                ((WebAppContext)context).setParentLoaderPriority(_wrapper.getOSGiAppProvider().isParentLoaderPriority());
                if (_wrapper.getOSGiAppProvider().getDefaultsDescriptor() != null && _wrapper.getOSGiAppProvider().getDefaultsDescriptor().length() != 0)
                {
                	((WebAppContext)context).setDefaultsDescriptor(_wrapper.getOSGiAppProvider().getDefaultsDescriptor());
                }
            }

            // rfc-66:
            context.setAttribute(OSGiWebappConstants.RFC66_OSGI_BUNDLE_CONTEXT,bundle.getBundleContext());

            //spring-dm-1.2.1 looks for the BundleContext as a different attribute.
            //not a spec... but if we want to support 
            //org.springframework.osgi.web.context.support.OsgiBundleXmlWebApplicationContext
            //then we need to do this to:
            context.setAttribute("org.springframework.osgi.web." + BundleContext.class.getName(),
                            bundle.getBundleContext());
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
        	IO.close(contextInputStream);
        }
        return null;
    }

    /**
     * Configure a classloader onto the context. If the context is a
     * WebAppContext, build a WebAppClassLoader that has access to all the jetty
     * classes thanks to the classloader of the JettyBootStrapper bundle and
     * also has access to the classloader of the bundle that defines this
     * context.
     * <p>
     * If the context is not a WebAppContext, same but with a simpler
     * URLClassLoader. Note that the URLClassLoader is pretty much fake: it
     * delegate all actual classloading to the parent classloaders.
     * </p>
     * <p>
     * The URL[] returned by the URLClassLoader create contained specifically
     * the jars that some j2ee tools expect and look into. For example the jars
     * that contain tld files for jasper's jstl support.
     * </p>
     * <p>
     * Also as the jars in the lib folder and the classes in the classes folder
     * might already be in the OSGi classloader we filter them out of the
     * WebAppClassLoader
     * </p>
     * 
     * @param context
     * @param contributor
     * @param webapp
     * @param contextPath
     * @param classInBundle
     * @throws Exception
     */
    protected void configureWebappClassLoader(Bundle contributor, ContextHandler context, OSGiWebappClassLoader webappClassLoader) throws Exception
    {
        if (context instanceof WebAppContext)
        {
            WebAppContext webappCtxt = (WebAppContext)context;
            context.setClassLoader(webappClassLoader);
            webappClassLoader.setWebappContext(webappCtxt);
        }
        else
        {
            context.setClassLoader(webappClassLoader);
        }
    }

    /**
     * No matter what the type of webapp, we create a WebappClassLoader.
     */
    protected OSGiWebappClassLoader createWebappClassLoader(Bundle contributor) throws Exception
    {
        // we use a temporary WebAppContext object.
        // if this is a real webapp we will set it on it a bit later: once we
        // know.
        OSGiWebappClassLoader webappClassLoader = new OSGiWebappClassLoader(
        	_wrapper.getParentClassLoaderForWebapps(),new WebAppContext(),contributor,BUNDLE_CLASS_LOADER_HELPER);
        return webappClassLoader;
    }

    /**
     * Set the property &quot;this.bundle.install&quot; to point to the location
     * of the bundle. Useful when <SystemProperty name="this.bundle.home"/> is
     * used.
     */
    private void setThisBundleHomeProperty(Bundle bundle, HashMap<String, Object> properties, String overrideBundleInstallLocation)
    {
        try
        {
            File location = overrideBundleInstallLocation != null?new File(overrideBundleInstallLocation):BUNDLE_FILE_LOCATOR_HELPER
                    .getBundleInstallLocation(bundle);
            properties.put("this.bundle.install",location.getCanonicalPath());
        }
        catch (Throwable t)
        {
            System.err.println("Unable to set 'this.bundle.install' " + " for the bundle " + bundle.getSymbolicName());
            t.printStackTrace();
        }
    }


}
