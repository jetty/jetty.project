// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
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
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;

import org.eclipse.jetty.deploy.ContextDeployer;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.osgi.boot.utils.BundleClassLoaderHelper;
import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelper;
import org.eclipse.jetty.osgi.boot.utils.WebappRegistrationCustomizer;
import org.eclipse.jetty.osgi.boot.utils.internal.DefaultBundleClassLoaderHelper;
import org.eclipse.jetty.osgi.boot.utils.internal.DefaultFileLocatorHelper;
import org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.TagLibConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleReference;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.util.tracker.ServiceTracker;
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

    /**
     * Deploy a new web application on the jetty server.
     * 
     * @param bundle
     *            The bundle
     * @param webappFolderPath
     *            The path to the root of the webapp. Must be a path relative to
     *            bundle; either an absolute path.
     * @param contextPath
     *            The context path. Must start with "/"
     * @param extraClasspath
     * @param overrideBundleInstallLocation
     * @param requireTldBundle The list of bundles's symbolic names that contain
     * tld files that are required by this WAB.
     * @param webXmlPath
     * @param defaultWebXmlPath
     *            TODO: parameter description
     * @return The contexthandler created and started
     * @throws Exception
     */
    public WebAppContext registerWebapplication(Bundle bundle,
            String webappFolderPath, String contextPath, String extraClasspath,
            String overrideBundleInstallLocation,
            String requireTldBundle, String webXmlPath,
            String defaultWebXmlPath, WebAppContext webAppContext) throws Exception
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
                extraClasspath,bundleInstall,requireTldBundle,webXmlPath,defaultWebXmlPath,webAppContext);
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.jetty.osgi.boot.internal.webapp.IWebBundleDeployerHelper#registerWebapplication(org.osgi.framework.Bundle, java.lang.String, java.io.File, java.lang.String, java.lang.String, java.io.File, java.lang.String, java.lang.String)
     */
    private WebAppContext registerWebapplication(Bundle contributor, String pathInBundleToWebApp,
            URL baseWebappInstallURL, String contextPath, String extraClasspath, File bundleInstall,
            String requireTldBundle, String webXmlPath, String defaultWebXmlPath, WebAppContext context)
    throws Exception
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
                    defaultWebXml = new File(defaultWebXmlPath);
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

            configureWebappClassLoader(contributor,context,composite, requireTldBundle);
            configureWebAppContext(context,contributor,requireTldBundle);
            

            // @see
            // org.eclipse.jetty.webapp.JettyWebXmlConfiguration#configure(WebAppContext)
            // during initialization of the webapp all the jetty packages are
            // visible
            // through the webapp classloader.
            oldServerClasses = context.getServerClasses();
            context.setServerClasses(null);
            
            _wrapper.getOSGiAppProvider().addContext(contributor,pathInBundleToWebApp,context);
            
            //support for patch resources. ideally this should be done inside a configurator.
            List<Resource> patchResources =
            		(List<Resource>)context.getAttribute(WebInfConfiguration.RESOURCE_URLS+".patch");
            if (patchResources != null)
            {
	            LinkedList<Resource> resourcesPath = new LinkedList<Resource>();
	            //place the patch resources at the beginning of the lookup path.
	            resourcesPath.addAll(patchResources);
	            //then place the ones from the host web bundle.
	            Resource hostResources = context.getBaseResource();
	            if (hostResources instanceof ResourceCollection)
	            {
	            	for (Resource re : ((ResourceCollection)hostResources).getResources())
	            	{
	            		resourcesPath.add(re);
	            	}
	            }
	            else
	            {
	            	resourcesPath.add(hostResources);
	            }
	            
	            ResourceCollection rc = new ResourceCollection(resourcesPath.toArray(
	                    new Resource[resourcesPath.size()]));
	            context.setBaseResource(rc);
            }
            
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
                String overrideBundleInstallLocation, String requireTldBundle, ContextHandler handler)
            throws Exception
    {
        File contextsHome = _wrapper.getOSGiAppProvider().getContextXmlDirAsFile();
        if (contextsHome != null)
        {
            File prodContextFile = new File(contextsHome,contributor.getSymbolicName() + "/" + contextFileRelativePath);
            if (prodContextFile.exists())
            {
                return registerContext(contributor,contextFileRelativePath,prodContextFile,extraClasspath,
                        overrideBundleInstallLocation,requireTldBundle,handler);
            }
        }
        File rootFolder = overrideBundleInstallLocation != null
            ? Resource.newResource(overrideBundleInstallLocation).getFile()
            : BUNDLE_FILE_LOCATOR_HELPER.getBundleInstallLocation(contributor);
        File contextFile = rootFolder != null?new File(rootFolder,contextFileRelativePath):null;
        if (contextFile != null && contextFile.exists())
        {
            return registerContext(contributor,contextFileRelativePath,contextFile,extraClasspath,overrideBundleInstallLocation,requireTldBundle,handler);
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
                Resource r = Resource.newResource(contextURL);              
                return registerContext(contributor,contextFileRelativePath,r.getInputStream(),extraClasspath,overrideBundleInstallLocation,requireTldBundle,handler);
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
            String extraClasspath, String overrideBundleInstallLocation,
            String requireTldBundle, ContextHandler handler) throws Exception
    {
        InputStream contextFileInputStream = null;
        try
        {
            contextFileInputStream = new BufferedInputStream(new FileInputStream(contextFile));
            return registerContext(contributor, pathInBundle, contextFileInputStream,
                    extraClasspath,overrideBundleInstallLocation,requireTldBundle,handler);
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
    private ContextHandler registerContext(Bundle contributor,
            String pathInsideBundle, InputStream contextFileInputStream,
            String extraClasspath, String overrideBundleInstallLocation,
            String requireTldBundle, ContextHandler handler)
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
            ContextHandler context = createContextHandler(handler, contributor,
                    contextFileInputStream,extraClasspath,
                    overrideBundleInstallLocation,requireTldBundle);
            if (context == null)
            {
                return null;// did not happen
            }

            // ok now register this webapp. we checked when we started jetty
            // that there
            // was at least one such handler for webapps.
            //the actual registration must happen via the new Deployment API.
//            _ctxtHandler.addHandler(context);

            configureWebappClassLoader(contributor,context,composite, requireTldBundle);
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
    protected void configureWebAppContext(ContextHandler wah, Bundle contributor,
            String requireTldBundle) throws IOException
    {
        // rfc66
        wah.setAttribute(OSGiWebappConstants.RFC66_OSGI_BUNDLE_CONTEXT,contributor.getBundleContext());

        //spring-dm-1.2.1 looks for the BundleContext as a different attribute.
        //not a spec... but if we want to support 
        //org.springframework.osgi.web.context.support.OsgiBundleXmlWebApplicationContext
        //then we need to do this to:
        wah.setAttribute("org.springframework.osgi.web." + BundleContext.class.getName(),
                        contributor.getBundleContext());
        
        //also pass the bundle directly. sometimes a bundle does not have a bundlecontext.
        //it is still useful to have access to the Bundle from the servlet context.
        wah.setAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE, contributor);
        
        //pass the value of the require tld bundle so that the TagLibOSGiConfiguration
        //can pick it up.
        wah.setAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, requireTldBundle);

        
        Bundle[] fragments = PackageAdminServiceTracker.INSTANCE.getFragmentsAndRequiredBundles(contributor);
        if (fragments != null && fragments.length != 0)
        {
            //sorted extra resource base found in the fragments.
            //the resources are either overriding the resourcebase found in the web-bundle
            //or appended.
            //amongst each resource we sort them according to the alphabetical order
            //of the name of the internal folder and the symbolic name of the fragment.
            //this is useful to make sure that the lookup path of those
            //resource base defined by fragments is always the same.
            //This natural order could be abused to define the order in which the base resources are
            //looked up.
            TreeMap<String,Resource> patchResourcesPath = new TreeMap<String,Resource>();
            TreeMap<String,Resource> appendedResourcesPath = new TreeMap<String,Resource>();
            for (Bundle frag : fragments) {
                String fragFolder = (String)frag.getHeaders().get(OSGiWebappConstants.JETTY_WAR_FRAGMENT_FOLDER_PATH);
                String patchFragFolder = (String)frag.getHeaders().get(OSGiWebappConstants.JETTY_WAR_PATCH_FRAGMENT_FOLDER_PATH);
                if (fragFolder != null)
                {
                    URL fragUrl = frag.getEntry(fragFolder);
                    if (fragUrl == null)
                    {
                        throw new IllegalArgumentException("Unable to locate " + fragFolder + " inside "
                                + " the fragment '" + frag.getSymbolicName() + "'");
                    }
                    fragUrl = DefaultFileLocatorHelper.getLocalURL(fragUrl);
                    String key = fragFolder.startsWith("/") ? fragFolder.substring(1) : fragFolder;
                    appendedResourcesPath.put(key + ";" + frag.getSymbolicName(), Resource.newResource(fragUrl));
                }
                if (patchFragFolder != null)
                {
                    URL patchFragUrl = frag.getEntry(patchFragFolder);
                    if (patchFragUrl == null)
                    {
                        throw new IllegalArgumentException("Unable to locate " + patchFragUrl + " inside "
                                + " the fragment '" + frag.getSymbolicName() + "'");
                    }
                    patchFragUrl = DefaultFileLocatorHelper.getLocalURL(patchFragUrl);
                    String key = patchFragFolder.startsWith("/") ? patchFragFolder.substring(1) : patchFragFolder;
                    patchResourcesPath.put(key + ";" + frag.getSymbolicName(), Resource.newResource(patchFragUrl));
                }
            }
            if (!appendedResourcesPath.isEmpty())
            {
            	wah.setAttribute(WebInfConfiguration.RESOURCE_URLS, new ArrayList<Resource>(appendedResourcesPath.values()));
            }
            if (!patchResourcesPath.isEmpty())
            {
            	wah.setAttribute(WebInfConfiguration.RESOURCE_URLS + ".patch", new ArrayList<Resource>(patchResourcesPath.values()));
            }
            
            if (wah instanceof WebAppContext)
            {
            	//This is the equivalent of what MetaInfConfiguration does. For OSGi bundles without the JarScanner
            	WebAppContext webappCtxt = (WebAppContext)wah;
	            //take care of the web-fragments, meta-inf resources and tld resources:
	            //similar to what MetaInfConfiguration does.
	            List<Resource> frags = (List<Resource>)wah.getAttribute(FragmentConfiguration.FRAGMENT_RESOURCES);
	            List<Resource> resfrags = (List<Resource>)wah.getAttribute(WebInfConfiguration.RESOURCE_URLS);
	            List<Resource> tldfrags = (List<Resource>)wah.getAttribute(TagLibConfiguration.TLD_RESOURCES);
	            for (Bundle frag : fragments)
	            {
	            	URL webFrag = frag.getEntry("/META-INF/web-fragment.xml");
	            	Enumeration<URL> resEnum = frag.findEntries("/META-INF/resources", "*", true);
	            	Enumeration<URL> tldEnum = frag.findEntries("/META-INF", "*.tld", false);
	            	if (webFrag != null || (resEnum != null && resEnum.hasMoreElements())
	            			|| (tldEnum != null && tldEnum.hasMoreElements()))
	                {
	                    try
	                    {
	                        File fragFile = BUNDLE_FILE_LOCATOR_HELPER.getBundleInstallLocation(frag);
	                        //add it to the webinf jars collection:
	                        //no need to check that it was not there yet: it was not there yet for sure.
	                        Resource fragFileAsResource = Resource.newResource(fragFile.toURI());
	                        webappCtxt.getMetaData().addWebInfJar(fragFileAsResource);
	                        
	                        if (webFrag != null)
	                        {
		                        if (frags == null)
		                        {
		                            frags = new ArrayList<Resource>();
		                            wah.setAttribute(FragmentConfiguration.FRAGMENT_RESOURCES, frags);
		                        }
		                        frags.add(fragFileAsResource);
	                        }
	                        if (resEnum != null && resEnum.hasMoreElements())
	                        {
	                        	URL resourcesEntry = frag.getEntry("/META-INF/resources/");
	                        	if (resourcesEntry == null)
	                        	{
	                        		//probably we found some fragments to a bundle.
			                        //those are already contributed.
	                        		//so we skip this.
	                        	}
	                        	else
	                        	{
			                        if (resfrags == null)
			                        {
			                        	resfrags = new ArrayList<Resource>();
			                            wah.setAttribute(WebInfConfiguration.RESOURCE_URLS, resfrags);
			                        }
	                        		resfrags.add(Resource.newResource(
	                        				DefaultFileLocatorHelper.getLocalURL(resourcesEntry)));
	                        	}
	                        }
	                        if (tldEnum != null && tldEnum.hasMoreElements())
	                        {
		                        if (tldfrags == null)
		                        {
		                        	tldfrags = new ArrayList<Resource>();
		                            wah.setAttribute(TagLibConfiguration.TLD_RESOURCES, tldfrags);
		                        }
		                        while (tldEnum.hasMoreElements())
		                        {
		                            URL tldUrl = tldEnum.nextElement();	                            
		                        	tldfrags.add(Resource.newResource(
		                        			DefaultFileLocatorHelper.getLocalURL(tldUrl)));
		                        }
	                        }
	                    }
	                    catch (Exception e)
	                    {
	                        __logger.warn("Unable to locate the bundle " + frag.getBundleId(),e);
	                    }
	                }
	            }
            }
        }
        
        
    }

    /**
     * @See {@link ContextDeployer#scan}
     * @param contextFile
     * @return
     */
    protected ContextHandler createContextHandler(ContextHandler handlerToConfigure,
            Bundle bundle, File contextFile, String extraClasspath,
            String overrideBundleInstallLocation, String requireTldBundle)
    {
        try
        {
            return createContextHandler(handlerToConfigure,bundle,
                    new BufferedInputStream(new FileInputStream(contextFile)),
                    extraClasspath,overrideBundleInstallLocation,requireTldBundle);
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
            Bundle bundle, InputStream contextInputStream, String extraClasspath,
            String overrideBundleInstallLocation, String requireTldBundle)
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
            xmlConfiguration.getProperties().putAll(properties);

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

            configureWebAppContext(context, bundle, requireTldBundle);
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
    protected void configureWebappClassLoader(Bundle contributor, ContextHandler context, OSGiWebappClassLoader webappClassLoader, String requireTldBundle) throws Exception
    {
        if (context instanceof WebAppContext)
        {
            WebAppContext webappCtxt = (WebAppContext)context;
            context.setClassLoader(webappClassLoader);
            webappClassLoader.setWebappContext(webappCtxt);

            String pathsToRequiredBundles = getPathsToRequiredBundles(context, requireTldBundle);
            if (pathsToRequiredBundles != null)
                webappClassLoader.addClassPath(pathsToRequiredBundles);
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
        /* DEBUG
        try {
        Class c = webappClassLoader.loadClass("org.glassfish.jsp.api.ResourceInjector");
        System.err.println("LOADED org.glassfish.jsp.api.ResourceInjector from "+c.getClassLoader());
        }
        catch (Exception e) {e.printStackTrace();}
        try {
            Class c = webappClassLoader.loadClass("org.apache.jasper.xmlparser.ParserUtils");
            System.err.println("LOADED org.apache.jasper.xmlparser.ParserUtils from "+c.getClassLoader());
            }
            catch (Exception e) {e.printStackTrace();}
        */
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
            properties.put("this.bundle.install.url",bundle.getEntry("/").toString());
        }
        catch (Throwable t)
        {
            __logger.warn("Unable to set 'this.bundle.install' " + " for the bundle " + bundle.getSymbolicName(), t);
        }
    }

    
    private String getPathsToRequiredBundles (ContextHandler context, String requireTldBundle) throws Exception
    {
        if (requireTldBundle == null)
            return null;

        StringBuilder paths = new StringBuilder();
        Bundle bundle = (Bundle)context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE);
        PackageAdmin packAdmin = getBundleAdmin();
        DefaultFileLocatorHelper fileLocatorHelper = new DefaultFileLocatorHelper();
        
        String[] symbNames = requireTldBundle.split(", ");

        for (String symbName : symbNames)
        {
            Bundle[] bs = packAdmin.getBundles(symbName, null);
            if (bs == null || bs.length == 0)
            {
                throw new IllegalArgumentException("Unable to locate the bundle '"
                                                   + symbName + "' specified in the "
                                                   + OSGiWebappConstants.REQUIRE_TLD_BUNDLE
                                                   + " of the manifest of "
                                                   + bundle.getSymbolicName());
            }

            
            File f = fileLocatorHelper.getBundleInstallLocation(bs[0]);
            if (paths.length() > 0)
                paths.append(", ");
            System.err.println("getPathsToRequiredBundles: bundle path="+bs[0].getLocation()+" uri="+f.toURI());
            paths.append(f.toURI().toURL().toString());
        }

        return paths.toString();
    }

    private PackageAdmin getBundleAdmin()
    {
        Bundle bootBundle = ((BundleReference)OSGiWebappConstants.class.getClassLoader()).getBundle();
        ServiceTracker serviceTracker = new ServiceTracker(bootBundle.getBundleContext(), PackageAdmin.class.getName(), null);
        serviceTracker.open();

        return (PackageAdmin) serviceTracker.getService();
    }


}
