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

package org.eclipse.jetty.ee9.osgi.boot;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee9.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.ee9.osgi.boot.internal.webapp.OSGiWebappClassLoader;
import org.eclipse.jetty.ee9.osgi.boot.utils.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.ee9.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.packageadmin.PackageAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractWebAppProvider
 * <p>
 * Base class for Jetty DeploymentManager Providers that are capable of deploying a webapp,
 * either from a bundle or an OSGi service.
 */
public abstract class AbstractWebAppProvider extends AbstractLifeCycle implements AppProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractWebAppProvider.class);

    private boolean _parentLoaderPriority;

    private String _defaultsDescriptor;

    private boolean _extractWars = true; //See WebAppContext.extractWars

    private String _tldBundles;

    private DeploymentManager _deploymentManager;

    private ServerInstanceWrapper _serverWrapper;

    /**
     * OSGiApp
     *
     * Represents a deployable webapp.
     */
    public class OSGiApp extends AbstractOSGiApp
    {
        private String _contextPath;
        private String _webAppPath;
        private WebAppContext _webApp;

        public OSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle, String originId)
        {
            super(manager, provider, bundle, originId);
        }

        public OSGiApp(DeploymentManager manager, AppProvider provider, Bundle bundle, Dictionary properties, String originId)
        {
            super(manager, provider, bundle, properties, originId);
        }

        public void setWebAppContext(WebAppContext webApp)
        {
            _webApp = webApp;
        }

        @Override
        public String getContextPath()
        {
            return _contextPath;
        }

        public void setContextPath(String contextPath)
        {
            this._contextPath = contextPath;
        }

        public String getBundlePath()
        {
            return _webAppPath;
        }

        public void setWebAppPath(String path)
        {
            this._webAppPath = path;
        }

        public ContextHandler createContextHandler()
            throws Exception
        {
            if (_webApp != null)
            {
                configureWebApp();
                return _webApp;
            }

            createWebApp();
            return _webApp;
        }

        protected void createWebApp()
            throws Exception
        {
            _webApp = newWebApp();
            configureWebApp();
        }

        protected WebAppContext newWebApp()
        {
            WebAppContext webApp = new WebAppContext();
            webApp.setAttribute(OSGiWebappConstants.WATERMARK, OSGiWebappConstants.WATERMARK);

            //make sure we protect also the osgi dirs specified by OSGi Enterprise spec
            String[] targets = webApp.getProtectedTargets();
            String[] updatedTargets = null;
            if (targets != null)
            {
                updatedTargets = new String[targets.length + OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
                System.arraycopy(targets, 0, updatedTargets, 0, targets.length);
            }
            else
                updatedTargets = new String[OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
            System.arraycopy(OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS, 0, updatedTargets, targets.length, OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length);
            webApp.setProtectedTargets(updatedTargets);

            return webApp;
        }

        public void configureWebApp()
            throws Exception
        {
            //TODO turn this around and let any context.xml file get applied first, and have the properties override
            _webApp.setContextPath(_contextPath);

            //osgi Enterprise Spec r4 p.427
            _webApp.setAttribute(OSGiWebappConstants.OSGI_BUNDLECONTEXT, _bundle.getBundleContext());

            String overrideBundleInstallLocation = (String)_properties.get(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
            File bundleInstallLocation =
                (overrideBundleInstallLocation == null
                    ? BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(_bundle)
                    : new File(overrideBundleInstallLocation));

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Bundle location is {}, install location: {}", _bundle.getLocation(), bundleInstallLocation);
            }

            URL url = null;
            Resource rootResource = Resource.newResource(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(bundleInstallLocation.toURI().toURL()));
            //try and make sure the rootResource is useable - if its a jar then make it a jar file url
            if (rootResource.exists() && !rootResource.isDirectory() && !rootResource.toString().startsWith("jar:"))
            {
                Resource jarResource = JarResource.newJarResource(rootResource);
                if (jarResource.exists() && jarResource.isDirectory())
                    rootResource = jarResource;
            }

            //if the path wasn't set or it was ., then it is the root of the bundle's installed location
            if (_webAppPath == null || _webAppPath.length() == 0 || ".".equals(_webAppPath))
            {
                url = bundleInstallLocation.toURI().toURL();
                if (LOG.isDebugEnabled())
                    LOG.debug("Webapp base using bundle install location: {}", url);
            }
            else
            {
                //Get the location of the root of the webapp inside the installed bundle
                if (_webAppPath.startsWith("/") || _webAppPath.startsWith("file:"))
                {
                    url = new File(_webAppPath).toURI().toURL();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Webapp base using absolute location: {}", url);
                }
                else if (bundleInstallLocation != null && bundleInstallLocation.isDirectory())
                {
                    url = new File(bundleInstallLocation, _webAppPath).toURI().toURL();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Webapp base using path relative to bundle unpacked install location: {}", url);
                }
                else if (bundleInstallLocation != null)
                {
                    Enumeration<URL> urls = BundleFileLocatorHelperFactory.getFactory().getHelper().findEntries(_bundle, _webAppPath);
                    if (urls != null && urls.hasMoreElements())
                    {
                        url = urls.nextElement();
                        if (LOG.isDebugEnabled())
                            LOG.debug("Webapp base using path relative to packed bundle location: {}", url);
                    }
                }
            }

            if (url == null)
            {
                throw new IllegalArgumentException("Unable to locate " + _webAppPath + " in " +
                    (bundleInstallLocation != null ? bundleInstallLocation.getAbsolutePath() : "unlocated bundle '" + _bundle.getSymbolicName() + "'"));
            }

            //Sets the location of the war file
            // converts bundleentry: protocol if necessary
            _webApp.setWar(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(url).toString());

            // Set up what has been configured on the provider
            _webApp.setParentLoaderPriority(isParentLoaderPriority());
            _webApp.setExtractWAR(isExtract());

            //Set up configuration from manifest headers
            //extra classpath
            String tmp = (String)_properties.get(OSGiWebappConstants.JETTY_EXTRA_CLASSPATH);
            if (tmp != null)
                _webApp.setExtraClasspath(tmp);

            //web.xml
            tmp = (String)_properties.get(OSGiWebappConstants.JETTY_WEB_XML_PATH);
            if (tmp != null && tmp.trim().length() != 0)
            {
                File webXml = getFile(tmp, bundleInstallLocation);
                if (webXml != null && webXml.exists())
                    _webApp.setDescriptor(webXml.getAbsolutePath());
            }

            // webdefault-ee9.xml
            tmp = (String)_properties.get(OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH);
            if (tmp != null)
            {
                File defaultWebXml = getFile(tmp, bundleInstallLocation);
                if (defaultWebXml != null)
                {
                    if (defaultWebXml.exists())
                        _webApp.setDefaultsDescriptor(defaultWebXml.getAbsolutePath());
                    else
                        LOG.warn("{} does not exist", defaultWebXml.getAbsolutePath());
                }
            }

            //Handle Require-TldBundle
            //This is a comma separated list of names of bundles that contain tlds that this webapp uses.
            //We add them to the webapp classloader.
            String requireTldBundles = (String)_properties.get(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
            String pathsToTldBundles = getPathsToRequiredBundles(requireTldBundles);

            // make sure we provide access to all the jetty bundles by going
            // through this bundle.
            OSGiWebappClassLoader webAppLoader = new OSGiWebappClassLoader(_serverWrapper.getParentClassLoaderForWebapps(), _webApp, _bundle);

            if (pathsToTldBundles != null)
                webAppLoader.addClassPath(pathsToTldBundles);
            _webApp.setClassLoader(webAppLoader);

            // apply any META-INF/context.xml file that is found to configure
            // the webapp first
            try
            {
                final Resource finalRootResource = rootResource;
                WebAppClassLoader.runWithServerClassAccess(() ->
                {
                    applyMetaInfContextXml(finalRootResource, overrideBundleInstallLocation);
                    return null;
                });
            }
            catch (Exception e)
            {
                LOG.warn("Error applying context xml");
                throw e;
            }

            _webApp.setAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, requireTldBundles);

            //Set up some attributes
            // rfc66
            _webApp.setAttribute(OSGiWebappConstants.RFC66_OSGI_BUNDLE_CONTEXT, _bundle.getBundleContext());

            // spring-dm-1.2.1 looks for the BundleContext as a different attribute.
            // not a spec... but if we want to support
            // org.springframework.osgi.web.context.support.OsgiBundleXmlWebApplicationContext
            // then we need to do this to:
            _webApp.setAttribute("org.springframework.osgi.web." + BundleContext.class.getName(), _bundle.getBundleContext());

            // also pass the bundle directly. sometimes a bundle does not have a
            // bundlecontext.
            // it is still useful to have access to the Bundle from the servlet
            // context.
            _webApp.setAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE, _bundle);
        }

        protected String getPathsToRequiredBundles(String requireTldBundles)
            throws Exception
        {
            if (requireTldBundles == null)
                return null;

            ServiceReference ref = _bundle.getBundleContext().getServiceReference(org.osgi.service.packageadmin.PackageAdmin.class.getName());
            PackageAdmin packageAdmin = (ref == null) ? null : (PackageAdmin)_bundle.getBundleContext().getService(ref);
            if (packageAdmin == null)
                throw new IllegalStateException("Unable to get PackageAdmin reference to locate required Tld bundles");

            StringBuilder paths = new StringBuilder();
            String[] symbNames = requireTldBundles.split("[, ]");

            for (String symbName : symbNames)
            {
                Bundle[] bs = packageAdmin.getBundles(symbName, null);
                if (bs == null || bs.length == 0)
                {
                    throw new IllegalArgumentException("Unable to locate the bundle '" + symbName + "' specified by " +
                        OSGiWebappConstants.REQUIRE_TLD_BUNDLE + " in manifest of " +
                        (_bundle == null ? "unknown" : _bundle.getSymbolicName()));
                }

                File f = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bs[0]);
                if (paths.length() > 0)
                    paths.append(", ");
                paths.append(f.toURI().toURL().toString());
                if (LOG.isDebugEnabled())
                    LOG.debug("getPathsToRequiredBundles: bundle path={} uri={}", bs[0].getLocation(), f.toURI());
            }

            return paths.toString();
        }

        protected void applyMetaInfContextXml(Resource rootResource, String overrideBundleInstallLocation)
            throws Exception
        {
            if (_bundle == null)
                return;
            if (_webApp == null)
                return;

            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (LOG.isDebugEnabled())
                LOG.debug("Context classloader = {}", cl);
            try
            {

                Thread.currentThread().setContextClassLoader(_webApp.getClassLoader());

                URI contextXmlUri = null;

                //TODO replace this with getting the InputStream so we don't cache in URL
                //Try looking for a context xml file in META-INF with a specific name
                URL url = _bundle.getEntry("/META-INF/jetty-webapp-context.xml");
                if (url != null)
                {
                    contextXmlUri = url.toURI();
                }

                if (contextXmlUri == null)
                {
                    //Didn't find specially named file, try looking for a property that names a context xml file to use
                    if (_properties != null)
                    {
                        String tmp = (String)_properties.get(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);
                        if (tmp != null)
                        {
                            String[] filenames = tmp.split("[,;]");
                            if (filenames != null && filenames.length > 0)
                            {
                                String filename = filenames[0]; //should only be 1 filename in this usage
                                String jettyHome = (String)getServerInstanceWrapper().getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
                                if (jettyHome == null)
                                    jettyHome = System.getProperty(OSGiServerConstants.JETTY_HOME);
                                Resource res = findFile(filename, jettyHome, overrideBundleInstallLocation, _bundle);
                                if (res != null)
                                {
                                    contextXmlUri = res.getURI();
                                }
                            }
                        }
                    }
                }
                if (contextXmlUri == null)
                    return;

                // Apply it just as the standard jetty ContextProvider would do
                LOG.info("Applying {} to {}", contextXmlUri, _webApp);

                XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.newResource(contextXmlUri));
                WebAppClassLoader.runWithServerClassAccess(() ->
                {
                    HashMap<String, String> properties = new HashMap<>();
                    xmlConfiguration.getIdMap().put("Server", getDeploymentManager().getServer());
                    properties.put(OSGiWebappConstants.JETTY_BUNDLE_ROOT, rootResource.toString());
                    properties.put(OSGiServerConstants.JETTY_HOME, (String)getDeploymentManager().getServer().getAttribute(OSGiServerConstants.JETTY_HOME));
                    xmlConfiguration.getProperties().putAll(properties);
                    xmlConfiguration.configure(_webApp);
                    return null;
                });
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(cl);
            }
        }

        private File getFile(String file, File bundleInstall)
        {
            if (file == null)
                return null;

            if (file.startsWith("/") || file.startsWith("file:/")) //absolute location
                return new File(file);
            else
            {
                //relative location
                //try inside the bundle first
                File f = new File(bundleInstall, file);
                if (f.exists())
                    return f;
                String jettyHome = (String)getDeploymentManager().getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
                if (jettyHome != null)
                    return new File(jettyHome, file);
            }

            return null;
        }
    }

    public AbstractWebAppProvider(ServerInstanceWrapper wrapper)
    {
        _serverWrapper = wrapper;
    }

    /**
     * Get the parentLoaderPriority.
     *
     * @return the parentLoaderPriority
     */
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    /**
     * Set the parentLoaderPriority.
     *
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _parentLoaderPriority = parentLoaderPriority;
    }

    /**
     * Get the defaultsDescriptor.
     *
     * @return the defaultsDescriptor
     */
    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /**
     * Set the defaultsDescriptor.
     *
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }

    public boolean isExtract()
    {
        return _extractWars;
    }

    public void setExtract(boolean extract)
    {
        _extractWars = extract;
    }

    /**
     * @param tldBundles Comma separated list of bundles that contain tld jars
     * that should be setup on the jetty instances created here.
     */
    public void setTldBundles(String tldBundles)
    {
        _tldBundles = tldBundles;
    }

    /**
     * @return The list of bundles that contain tld jars that should be setup on
     * the jetty instances created here.
     */
    public String getTldBundles()
    {
        return _tldBundles;
    }

    public void setServerInstanceWrapper(ServerInstanceWrapper wrapper)
    {
        _serverWrapper = wrapper;
    }

    public ServerInstanceWrapper getServerInstanceWrapper()
    {
        return _serverWrapper;
    }

    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }

    @Override
    public ContextHandler createContextHandler(App app) throws Exception
    {
        if (app == null)
            return null;
        if (!(app instanceof OSGiApp))
            throw new IllegalStateException(app + " is not a BundleApp");

        //Create a WebAppContext suitable to deploy in OSGi
        ContextHandler ch = ((OSGiApp)app).createContextHandler();
        return ch;
    }

    public static String getOriginId(Bundle contributor, String path)
    {
        return contributor.getSymbolicName() + "-" + contributor.getVersion().toString() + (path.startsWith("/") ? path : "/" + path);
    }
}
