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

package org.eclipse.jetty.ee10.osgi.boot;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.Configurations;
import org.eclipse.jetty.ee10.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.osgi.AbstractContextProvider;
import org.eclipse.jetty.osgi.BundleContextProvider;
import org.eclipse.jetty.osgi.BundleWebAppProvider;
import org.eclipse.jetty.osgi.ContextFactory;
import org.eclipse.jetty.osgi.OSGiApp;
import org.eclipse.jetty.osgi.OSGiServerConstants;
import org.eclipse.jetty.osgi.OSGiWebappConstants;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.util.FakeURLClassLoader;
import org.eclipse.jetty.osgi.util.OSGiClassLoader;
import org.eclipse.jetty.osgi.util.ServerClasspathContributor;
import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.URLResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EE10Activator
 * <p>
 * Enable deployment of webapps/contexts to E10E
 */
public class EE10Activator implements BundleActivator
{
    private static final Logger LOG = LoggerFactory.getLogger(EE10Activator.class);
    
    public static final String ENVIRONMENT = "ee10";
    
    private static Collection<ServerClasspathContributor> __serverClasspathContributors = new ArrayList<>();

    public static void registerServerClasspathContributor(ServerClasspathContributor contributor)
    {
        __serverClasspathContributors.add(contributor);
    }

    public static void unregisterServerClasspathContributor(ServerClasspathContributor contributor)
    {
        __serverClasspathContributors.remove(contributor);
    }

    public static Collection<ServerClasspathContributor> getServerClasspathContributors()
    {
        return __serverClasspathContributors;
    }

    /**
     * ServerTracker
     * 
     * Tracks appearance of Server instances as OSGi services, and then configures them 
     * for deployment of EE10 contexts and webapps.
     *
     */
    public static class ServerTracker implements ServiceTrackerCustomizer<Server, Object>
    {
        private Bundle _myBundle = null;
        
        public ServerTracker(Bundle bundle)
        {
            _myBundle = bundle;
        }
        
        @Override
        public Object addingService(ServiceReference<Server> sr)
        {
            Bundle contributor = sr.getBundle();
            Server server = contributor.getBundleContext().getService(sr);
            //find bundles that should be on the container classpath and convert to URLs
            List<URL> contributedURLs = new ArrayList<>();
            List<Bundle> contributedBundles = new ArrayList<>();
            Collection<ServerClasspathContributor> serverClasspathContributors = getServerClasspathContributors();
            serverClasspathContributors.stream().forEach(c -> contributedBundles.addAll(c.getScannableBundles()));
            contributedBundles.stream().forEach(b -> contributedURLs.addAll(convertBundleToURL(b)));

            if (!contributedURLs.isEmpty())
            {
                //There should already be a default set up by the JettyServerFactory
                ClassLoader serverClassLoader = (ClassLoader)server.getAttribute(OSGiServerConstants.SERVER_CLASSLOADER);
                if (serverClassLoader != null)
                {
                    server.setAttribute(OSGiServerConstants.SERVER_CLASSLOADER,
                        new FakeURLClassLoader(serverClassLoader, contributedURLs.toArray(new URL[contributedURLs.size()])));

                    if (LOG.isDebugEnabled())
                        LOG.debug("Server classloader for contexts = {}", server.getAttribute(OSGiServerConstants.SERVER_CLASSLOADER));
                }          
                server.setAttribute(OSGiServerConstants.SERVER_CLASSPATH_BUNDLES, contributedBundles);
            }

            Optional<DeploymentManager> deployer = getDeploymentManager(server);
            BundleWebAppProvider webAppProvider = null;
            BundleContextProvider contextProvider = null;

            String containerScanBundlePattern = null;
            if (contributedBundles != null)
            {
                StringBuffer strbuff = new StringBuffer();
                contributedBundles.stream().forEach(b -> strbuff.append(b.getSymbolicName()).append("|"));

                if (strbuff.length() > 0)
                    containerScanBundlePattern = strbuff.toString().substring(0, strbuff.length() - 1);
            }

            if (deployer.isPresent())
            {
                for (AppProvider provider : deployer.get().getAppProviders())
                {
                    if (BundleContextProvider.class.isInstance(provider) && ENVIRONMENT.equalsIgnoreCase(provider.getEnvironmentName()))
                        contextProvider = BundleContextProvider.class.cast(provider);
                    if (BundleWebAppProvider.class.isInstance(provider) && ENVIRONMENT.equalsIgnoreCase(provider.getEnvironmentName()))
                        webAppProvider = BundleWebAppProvider.class.cast(provider);
                }
                if (contextProvider == null)
                {
                    contextProvider = new BundleContextProvider(ENVIRONMENT, server, new EE10ContextFactory(_myBundle));
                    deployer.get().addAppProvider(contextProvider);
                }

                if (webAppProvider == null)
                {
                    webAppProvider = new BundleWebAppProvider(ENVIRONMENT, server, new EE10WebAppFactory(_myBundle));
                    deployer.get().addAppProvider(webAppProvider);
                }

                //ensure the providers are configured with the extra bundles that must be scanned from the container classpath
                if (containerScanBundlePattern != null)
                {
                    contextProvider.getProperties().put(OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN, containerScanBundlePattern);
                    webAppProvider.getProperties().put(OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN, containerScanBundlePattern);
                }
            }
            else
                LOG.info("No DeploymentManager for Server {}", server);

            try
            {
                if (!server.isStarted())
                    server.start();
            }
            catch (Exception e)
            {
                LOG.warn("Failed to start server {}", server);
            }
            return server;
        }

        @Override
        public void modifiedService(ServiceReference<Server> reference, Object service)
        {
            removedService(reference, service);
            addingService(reference);
        }

        @Override
        public void removedService(ServiceReference<Server> reference, Object service)
        {
        }

        private Optional<DeploymentManager> getDeploymentManager(Server server)
        {
            Collection<DeploymentManager> deployers = server.getBeans(DeploymentManager.class);
            return deployers.stream().findFirst();
        }
        
        private List<URL> convertBundleToURL(Bundle bundle)
        {
            List<URL> urls = new ArrayList<>();
            try
            {
                File file = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);

                if (file.isDirectory())
                {
                    for (File f : file.listFiles())
                    {
                        if (FileID.isJavaArchive(f.getName()) && f.isFile())
                        {
                            urls.add(f.toURI().toURL());
                        }
                        else if (f.isDirectory() && f.getName().equals("lib"))
                        {
                            for (File f2 : file.listFiles())
                            {
                                if (FileID.isJavaArchive(f2.getName()) && f2.isFile())
                                {
                                    urls.add(f2.toURI().toURL());
                                }
                            }
                        }
                    }
                    urls.add(file.toURI().toURL());
                }
                else
                {
                    urls.add(file.toURI().toURL());
                }
            }
            catch (Exception e)
            {
                LOG.warn("Unable to convert bundle {} to url", bundle, e);
            }

            return urls;
        }
    }
    
    public static class EE10ContextFactory implements ContextFactory
    {
        private Bundle _myBundle;
        
        public EE10ContextFactory(Bundle bundle)
        {
            _myBundle = bundle;
        }
        
        @Override
        public ContextHandler createContextHandler(AbstractContextProvider provider, App app)
        throws Exception
        {
            OSGiApp osgiApp = OSGiApp.class.cast(app);
            String jettyHome = (String)app.getDeploymentManager().getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
            Path jettyHomePath = (StringUtil.isBlank(jettyHome) ? null : Paths.get(jettyHome));

            ContextHandler contextHandler = new ContextHandler();

            //Make base resource that of the bundle
            contextHandler.setBaseResource(osgiApp.getBundleResource());
            
            // provides access to core classes
            ClassLoader coreLoader = (ClassLoader)osgiApp.getDeploymentManager().getServer().getAttribute(OSGiServerConstants.SERVER_CLASSLOADER); 
            if (LOG.isDebugEnabled())
                LOG.debug("Core classloader = {}", coreLoader.getClass());
            
            //provide access to all ee10 classes
            ClassLoader environmentLoader = new OSGiClassLoader(coreLoader, _myBundle);

            //Use a classloader that knows about the common jetty parent loader, and also the bundle                  
            OSGiClassLoader classLoader = new OSGiClassLoader(environmentLoader, osgiApp.getBundle());
            contextHandler.setClassLoader(classLoader);

            //Apply any context xml file
            String tmp = osgiApp.getProperties().get(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);
            final Path contextXmlPath = Util.resolvePath(tmp, osgiApp.getPath(), jettyHomePath);

            if (contextXmlPath != null)
            {
                ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
                try
                {
                    Thread.currentThread().setContextClassLoader(contextHandler.getClassLoader());
                    WebAppClassLoader.runWithServerClassAccess(() ->
                    {
                        XmlConfiguration xmlConfiguration = new XmlConfiguration(ResourceFactory.of(contextHandler).newResource(contextXmlPath));
                        WebAppClassLoader.runWithServerClassAccess(() ->
                        {
                            Map<String, String> properties = new HashMap<>();
                            xmlConfiguration.getIdMap().put("Server", osgiApp.getDeploymentManager().getServer());
                            properties.put(OSGiWebappConstants.JETTY_BUNDLE_ROOT, osgiApp.getPath().toUri().toString());
                            properties.put(OSGiServerConstants.JETTY_HOME, (String)osgiApp.getDeploymentManager().getServer().getAttribute(OSGiServerConstants.JETTY_HOME));
                            xmlConfiguration.getProperties().putAll(properties);
                            xmlConfiguration.configure(contextHandler);
                            return null;
                        });
                        return null;
                    });
                }
                catch (Exception e)
                {
                    LOG.warn("Error applying context xml", e);
                    throw e;
                }
                finally
                {
                    Thread.currentThread().setContextClassLoader(oldLoader);
                }
            }

            //osgi Enterprise Spec r4 p.427
            contextHandler.setAttribute(OSGiWebappConstants.OSGI_BUNDLECONTEXT, osgiApp.getBundle().getBundleContext());

            //make sure we protect also the osgi dirs specified by OSGi Enterprise spec
            String[] targets = contextHandler.getProtectedTargets();
            int length = (targets == null ? 0 : targets.length);

            String[] updatedTargets = null;
            if (targets != null)
            {
                updatedTargets = new String[length + OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
                System.arraycopy(targets, 0, updatedTargets, 0, length);
            }
            else
                updatedTargets = new String[OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
            System.arraycopy(OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS, 0, updatedTargets, length, OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length);
            contextHandler.setProtectedTargets(updatedTargets);
            
            return contextHandler;
        }
    }

    public static class EE10WebAppFactory implements ContextFactory
    {
        private Bundle _myBundle;
        
        public EE10WebAppFactory(Bundle bundle)
        {
            _myBundle = bundle;
        }
        
        @Override
        public ContextHandler createContextHandler(AbstractContextProvider provider, App app)
            throws Exception
        {
            OSGiApp osgiApp = OSGiApp.class.cast(app);
            String jettyHome = (String)app.getDeploymentManager().getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
            Path jettyHomePath = (StringUtil.isBlank(jettyHome) ? null : Paths.get(jettyHome));

            WebAppContext webApp = new WebAppContext();

            //Apply defaults from the deployer providers
            webApp.initializeDefaults(provider.getProperties());
            
            // provides access to core classes
            ClassLoader coreLoader = (ClassLoader)osgiApp.getDeploymentManager().getServer().getAttribute(OSGiServerConstants.SERVER_CLASSLOADER); 
            if (LOG.isDebugEnabled())
                LOG.debug("Core classloader = {}", coreLoader);
            
            //provide access to all ee10 classes
            ClassLoader environmentLoader = new OSGiClassLoader(coreLoader, _myBundle);
            if (LOG.isDebugEnabled())
                LOG.debug("Environment classloader = {}", environmentLoader);
            
            //Ensure Configurations.getKnown is called with a classloader that can see all of the ee10 and core classes
            
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            try
            {
                Thread.currentThread().setContextClassLoader(environmentLoader);
                WebAppClassLoader.runWithServerClassAccess(() ->
                {
                    Configurations.getKnown();
                    return null;
                });
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(old);
            }
            
            webApp.setConfigurations(Configurations.getKnown().stream()
                .filter(c -> c.isEnabledByDefault())
                .toArray(Configuration[]::new));

            //Make a webapp classloader
            OSGiWebappClassLoader webAppLoader = new OSGiWebappClassLoader(environmentLoader, webApp, osgiApp.getBundle());

            //Handle Require-TldBundle
            //This is a comma separated list of names of bundles that contain tlds that this webapp uses.
            //We add them to the webapp classloader.
            String requireTldBundles = (String)osgiApp.getProperties().get(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);

            List<Path> pathsToTldBundles = Util.getPathsToBundlesBySymbolicNames(requireTldBundles, osgiApp.getBundle().getBundleContext());
            for (Path p : pathsToTldBundles)
                webAppLoader.addClassPath(p.toUri().toString());
            
            //Set up configuration from manifest headers
            //extra classpath
            String extraClasspath = osgiApp.getProperties().get(OSGiWebappConstants.JETTY_EXTRA_CLASSPATH);
            if (extraClasspath != null)
                webApp.setExtraClasspath(extraClasspath);

            webApp.setClassLoader(webAppLoader);
            
            //Take care of extra provider properties
            webApp.setAttribute(OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN, provider.getProperties().get(OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN));
            
            //TODO needed?
            webApp.setAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, requireTldBundles);

            //Set up some attributes
            // rfc66
            webApp.setAttribute(OSGiWebappConstants.RFC66_OSGI_BUNDLE_CONTEXT, osgiApp.getBundle().getBundleContext());

            // spring-dm-1.2.1 looks for the BundleContext as a different attribute.
            // not a spec... but if we want to support
            // org.springframework.osgi.web.context.support.OsgiBundleXmlWebApplicationContext
            // then we need to do this to:
            webApp.setAttribute("org.springframework.osgi.web." + BundleContext.class.getName(), osgiApp.getBundle().getBundleContext());

            // also pass the bundle directly. sometimes a bundle does not have a
            // bundlecontext.
            // it is still useful to have access to the Bundle from the servlet
            // context.
            webApp.setAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE, osgiApp.getBundle());


            // apply any META-INF/context.xml file that is found to configure
            // the webapp first
            //First try looking for one in /META-INF
        
            Path tmpPath = null;
            
            URL contextXmlURL = osgiApp.getBundle().getEntry("/META-INF/jetty-webapp-context.xml");
            if (contextXmlURL != null)
                tmpPath = Paths.get(contextXmlURL.toURI());

            //Then look in the property OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH and apply the first one
            if (contextXmlURL == null)
            {
                String tmp = osgiApp.getProperties().get(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);
                if (tmp != null)
                {
                    String[] filenames = tmp.split("[,;]");
                    tmpPath = Util.resolvePath(filenames[0], osgiApp.getPath(), jettyHomePath);
                }
            }

            //apply a context xml if there is one
            if (tmpPath != null)
            {
                final Path contextXmlPath = tmpPath;
                ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
                try
                {
                    Thread.currentThread().setContextClassLoader(webApp.getClassLoader());
                    WebAppClassLoader.runWithServerClassAccess(() ->
                    {
                        XmlConfiguration xmlConfiguration = new XmlConfiguration(ResourceFactory.of(webApp).newResource(contextXmlPath));
                        WebAppClassLoader.runWithServerClassAccess(() ->
                        {
                            Map<String, String> properties = new HashMap<>();
                            xmlConfiguration.getIdMap().put("Server", osgiApp.getDeploymentManager().getServer());
                            properties.put(OSGiWebappConstants.JETTY_BUNDLE_ROOT, osgiApp.getPath().toUri().toString());
                            properties.put(OSGiServerConstants.JETTY_HOME, (String)osgiApp.getDeploymentManager().getServer().getAttribute(OSGiServerConstants.JETTY_HOME));
                            xmlConfiguration.getProperties().putAll(properties);
                            xmlConfiguration.configure(webApp);
                            return null;
                        });
                        return null;
                    });
                }
                catch (Exception e)
                {
                    LOG.warn("Error applying context xml", e);
                    throw e;
                }
                finally
                {
                    Thread.currentThread().setContextClassLoader(oldLoader);
                }
            }

            //ensure the context path is set
            webApp.setContextPath(osgiApp.getContextPath());

            //osgi Enterprise Spec r4 p.427
            webApp.setAttribute(OSGiWebappConstants.OSGI_BUNDLECONTEXT, osgiApp.getBundle().getBundleContext());
    
            //Indicate the webapp has been deployed, so that we don't try and redeploy again
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


            Path bundlePath = osgiApp.getPath();

            Resource bundleResource = osgiApp.getBundleResource();
            
            String pathToResourceBase = osgiApp.getPathToResourceBase();
            
            //if the path wasn't set or it was ., then it is the root of the bundle's installed location
            if (StringUtil.isBlank(pathToResourceBase) ||  ".".equals(pathToResourceBase))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Webapp base using bundle install location: {}", bundleResource);
                webApp.setWarResource(bundleResource);
            }
            else
            {
                if (pathToResourceBase.startsWith("/") || pathToResourceBase.startsWith("file:"))
                {
                    //The baseResource is outside of the bundle
                    Path p = Paths.get(pathToResourceBase);
                    webApp.setWar(p.toUri().toString());
                    if (LOG.isDebugEnabled())
                        LOG.debug("Webapp base using absolute location: {}", p);
                }
                else
                {
                    //The baseResource is relative to the root of the bundle
                    Resource r = bundleResource.resolve(pathToResourceBase);
                    webApp.setWarResource(r);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Webapp base using path relative to bundle unpacked install location: {}", r);
                }
            }

            //web.xml
            String tmp = osgiApp.getProperties().get(OSGiWebappConstants.JETTY_WEB_XML_PATH);
            if (!StringUtil.isBlank(tmp))
            {
                Path webXml = Util.resolvePath(tmp, bundlePath, jettyHomePath);
                if (webXml != null)
                    webApp.setDescriptor(webXml.toUri().toString());
            }

            // webdefault-ee10.xml
            tmp = osgiApp.getProperties().get(OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH);
            if (tmp != null)
            {
                Path defaultWebXml = Util.resolvePath(tmp, bundlePath, jettyHomePath);
                if (defaultWebXml != null)
                {
                    webApp.setDefaultsDescriptor(defaultWebXml.toUri().toString());
                }
            }
            
            return webApp;
        }      
    }
    
    private PackageAdminServiceTracker _packageAdminServiceTracker;
    private ServiceTracker<Server, Object> _tracker;

    /**
     * Track jetty Server instances and add ability to deploy EE10 contexts/webapps
     *
     * @param context the bundle context
     */
    @Override
    public void start(final BundleContext context) throws Exception
    {
        // track other bundles and fragments attached to this bundle that we
        // should activate.
        _packageAdminServiceTracker = new PackageAdminServiceTracker(context);

        //track jetty Server instances
        _tracker = new ServiceTracker<Server, Object>(context, context.createFilter("(objectclass=" + Server.class.getName() + ")"), new ServerTracker(context.getBundle()));
        _tracker.open();

        //register for bundleresource: url resource handling
        ResourceFactory.registerResourceFactory("bundleresource", new URLResourceFactory());
    }

    /**
     * Stop the activator.
     *
     * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    @Override
    public void stop(BundleContext context) throws Exception
    {
        if (_tracker != null)
        {
            _tracker.close();
            _tracker = null;
        }
    }
}
