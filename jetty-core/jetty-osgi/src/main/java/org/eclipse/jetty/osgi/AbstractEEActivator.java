//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.osgi;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.StandardDeployer;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.util.FakeURLClassLoader;
import org.eclipse.jetty.osgi.util.ServerClasspathContributor;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.URLResourceFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractEEActivator implements BundleActivator
{
    private PackageAdminServiceTracker _packageAdminServiceTracker;
    private ServiceTracker<Server, Object> _tracker;

    /**
     * Track jetty Server instances and add ability to deploy EE11 contexts/webapps
     *
     * @param context the bundle context
     */
    @Override
    public void start(final BundleContext context) throws Exception
    {
        // track other bundles and fragments attached to this bundle that we
        // should activate.
        _packageAdminServiceTracker = new PackageAdminServiceTracker(getEnvironment(), context);

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

    public abstract String getEnvironment();

    public abstract ContextFactory getContextFactory(Bundle bundle);

    public abstract ContextFactory getWebAppFactory(Bundle bundle);

    public abstract String getMetaInfContainerBundlePatternAttributeName();

    private static final Collection<ServerClasspathContributor> __serverClasspathContributors = new ArrayList<>();

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
     * for deployment of EE11 contexts and webapps.
     */
    public class ServerTracker implements ServiceTrackerCustomizer<Server, Object>
    {
        private static final Logger LOG = LoggerFactory.getLogger(ServerTracker.class);
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

            Optional<StandardDeployer> serverDeployer = getDeployer(server);
            BundleWebAppProvider webAppProvider = null;
            BundleContextProvider contextProvider = null;

            String containerScanBundlePattern = null;
            if (!contributedBundles.isEmpty())
            {
                containerScanBundlePattern = contributedBundles.stream()
                    .map(Bundle::getSymbolicName)
                    .collect(Collectors.joining("|"));
            }

            if (serverDeployer.isPresent())
            {
                StandardDeployer deployer = serverDeployer.get();

                Collection<AbstractContextProvider> osgiProviders = deployer.getBeans(AbstractContextProvider.class);

                for (AbstractContextProvider provider : osgiProviders)
                {
                    if (provider instanceof BundleContextProvider bundleContextProvider)
                    {
                        if (bundleContextProvider.getEnvironmentName().equalsIgnoreCase(getEnvironment()))
                            contextProvider = bundleContextProvider;
                    }
                    if (provider instanceof BundleWebAppProvider bundleWebAppProvider)
                    {
                        if (bundleWebAppProvider.getEnvironmentName().equalsIgnoreCase(getEnvironment()))
                            webAppProvider = bundleWebAppProvider;
                    }
                }

                if (contextProvider == null)
                {
                    contextProvider = new BundleContextProvider(server, deployer, getEnvironment(), getContextFactory(_myBundle));
                    deployer.addBean(contextProvider);
                }

                if (webAppProvider == null)
                {
                    webAppProvider = new BundleWebAppProvider(server, deployer, getEnvironment(), getWebAppFactory(_myBundle));
                    deployer.addBean(webAppProvider);
                }

                //ensure the providers are configured with the extra bundles that must be scanned from the container classpath
                if (containerScanBundlePattern != null)
                {
                    contextProvider.getAttributes().setAttribute(getMetaInfContainerBundlePatternAttributeName(), containerScanBundlePattern);
                    webAppProvider.getAttributes().setAttribute(getMetaInfContainerBundlePatternAttributeName(), containerScanBundlePattern);
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

        private Optional<StandardDeployer> getDeployer(Server server)
        {
            Collection<StandardDeployer> deployers = server.getBeans(StandardDeployer.class);
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
}
