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

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee9.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.osgi.AbstractContextProvider;
import org.eclipse.jetty.osgi.BundleContextProvider;
import org.eclipse.jetty.osgi.BundleWebAppProvider;
import org.eclipse.jetty.osgi.ContextFactory;
import org.eclipse.jetty.osgi.OSGiApp;
import org.eclipse.jetty.osgi.OSGiServerConstants;
import org.eclipse.jetty.osgi.OSGiWebappConstants;
import org.eclipse.jetty.osgi.util.OSGiClassLoader;
import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.osgi.util.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.ResourceFactory;
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
 * EE9Activator
 * <p>
 * Enable deployment of webapps/contexts to EE9
 */
public class EE9Activator implements BundleActivator
{
    private static final Logger LOG = LoggerFactory.getLogger(EE9Activator.class);
    
    public static final String ENVIRONMENT = "ee9";
    
    /**
     * ServerTracker
     * 
     * Tracks appearance of Server instances as OSGi services, and then configures them 
     * for deployment of EE9 contexts and webapps.
     *
     */
    public static class ServerTracker implements ServiceTrackerCustomizer
    {
        @Override
        public Object addingService(ServiceReference sr)
        {
            Bundle contributor = sr.getBundle();
            Server server = (Server)contributor.getBundleContext().getService(sr);
            //configure for ee9 webapp and context deployment if not already done so
            Optional<DeploymentManager> deployer = getDeploymentManager(server);
            if (deployer.isPresent())
            {
                //TODO only add if not already present
                deployer.get().addAppProvider(new BundleContextProvider(ENVIRONMENT, server, new EE9ContextFactory()));
                deployer.get().addAppProvider(new BundleWebAppProvider(ENVIRONMENT, server, new EE9WebAppFactory()));
            }
            else
                LOG.info("No DeploymentManager for Server {}", server);
            
            return server;
        }

        @Override
        public void modifiedService(ServiceReference reference, Object service)
        {
            removedService(reference, service);
            addingService(reference);
        }

        @Override
        public void removedService(ServiceReference reference, Object service)
        {
        }

        private Optional<DeploymentManager> getDeploymentManager(Server server)
        {
            Collection<DeploymentManager> deployers = server.getBeans(DeploymentManager.class);
            return deployers.stream().findFirst();
        }
    }
    
    public static class EE9ContextFactory implements ContextFactory
    {
        @Override
        public ContextHandler createContextHandler(AbstractContextProvider provider, App app)
        throws Exception
        {
            OSGiApp osgiApp = OSGiApp.class.cast(app);
            String jettyHome = (String)app.getDeploymentManager().getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
            Path jettyHomePath = (StringUtil.isBlank(jettyHome) ? null : Paths.get(jettyHome));

            ContextHandler contextHandler = new ContextHandler();

            //Make base resource that of the bundle
            contextHandler.setBaseResource(osgiApp.getPath());

            //Use a classloader that knows about the common jetty parent loader, and also the bundle                  
            OSGiClassLoader classLoader = new OSGiClassLoader((ClassLoader)app.getDeploymentManager().getServer().getAttribute(OSGiServerConstants.SERVER_CLASSLOADER),
                osgiApp.getBundle());
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

    public static class EE9WebAppFactory implements ContextFactory
    {
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

            // make sure we provide access to all the jetty bundles by going
            // through this bundle.
            OSGiWebappClassLoader webAppLoader = new OSGiWebappClassLoader((ClassLoader)osgiApp.getDeploymentManager().getServer().getAttribute(OSGiServerConstants.SERVER_CLASSLOADER), 
                webApp, osgiApp.getBundle());

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

            //ensure the context path is set
            if (webApp.getContextPath() == null)
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
            String baseResource = osgiApp.getBaseResource();
            
            //if the path wasn't set or it was ., then it is the root of the bundle's installed location
            if (StringUtil.isBlank(baseResource) ||  ".".equals(baseResource))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Webapp base using bundle install location: {}", bundlePath);
                webApp.setWar(bundlePath.toUri().toString());
            }
            else
            {
                if (baseResource.startsWith("/") || baseResource.startsWith("file:"))
                {
                    //The baseResource is outside of the bundle
                    Path p = Paths.get(baseResource);
                    webApp.setWar(p.toUri().toString());
                    if (LOG.isDebugEnabled())
                        LOG.debug("Webapp base using absolute location: {}", p);
                }
                else
                {
                    //The baseResource is relative to the root of the bundle
                    Path p = bundlePath.resolve(baseResource);
                    webApp.setWar(p.toUri().toString());
                    if (LOG.isDebugEnabled())
                        LOG.debug("Webapp base using path relative to bundle unpacked install location: {}", p);
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

            // webdefault-ee9.xml
            tmp = osgiApp.getProperties().get(OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH);
            if (tmp != null)
            {
                Path defaultWebXml = Util.resolvePath(tmp, bundlePath, jettyHomePath);
                if (defaultWebXml != null)
                {
                    webApp.setDefaultsDescriptor(defaultWebXml.toUri().toString());
                }
            }
            
            return webApp.getCoreContextHandler();  //TODO or return the CoreContextHandler???
        }      
    }
    
    private PackageAdminServiceTracker _packageAdminServiceTracker;
    private ServiceTracker _tracker;

    /**
     * Track jetty Server instances and add ability to deploy EE9 contexts/webapps
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
        _tracker = new ServiceTracker(context, context.createFilter("(objectclass=" + Server.class.getName() + ")"), new ServerTracker());
        _tracker.open();
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
