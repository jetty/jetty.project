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

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.deploy.StandardDeployer;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.util.FakeURLClassLoader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
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

public abstract class AbstractEEActivator implements BundleActivator, ServerClasspathContributor.Registry
{
    private BundleContext _bootBundleContext;
    private ServiceTracker<Server, Object> _serverTracker;
    private PackageAdminServiceListener _packageAdminServiceListener;
    private final Collection<ServerClasspathContributor> _serverClasspathContributors = new ArrayList<>();


    /**
     * Track jetty Server instances and add ability to deploy EE contexts/webapps
     *
     * @param context the bundle context
     */
    @Override
    public void start(final BundleContext context) throws Exception
    {
        _bootBundleContext = context;

        // track other bundles and fragments attached to this bundle that we should activate.
        _packageAdminServiceListener = new PackageAdminServiceListener(this, _bootBundleContext);

        //track jetty Server instances
        _serverTracker = new ServiceTracker<>(context, context.createFilter("(objectclass=" + Server.class.getName() + ")"), new ServerTracker(_bootBundleContext.getBundle()));
        _serverTracker.open();

        //register for bundleresource: url resource handling
        ResourceFactory.registerResourceFactory("bundleresource", new URLResourceFactory());
    }

    public BundleContext getBootBundleContext()
    {
        return _bootBundleContext;
    }

    public void registerServerClasspathContributor(ServerClasspathContributor contributor)
    {
        _serverClasspathContributors.add(contributor);
    }

    public void unregisterServerClasspathContributor(ServerClasspathContributor contributor)
    {
        _serverClasspathContributors.remove(contributor);
    }

    public Collection<ServerClasspathContributor> getServerClasspathContributors()
    {
        return _serverClasspathContributors;
    }

    /**
     * Stop the activator.
     *
     * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    @Override
    public void stop(BundleContext context) throws Exception
    {
        if (_serverTracker != null)
        {
            _serverTracker.close();
            _serverTracker = null;
        }

        if (_packageAdminServiceListener != null)
        {
            _packageAdminServiceListener.stop();
            _packageAdminServiceListener = null;
        }
    }

    public abstract String getEnvironment();

    public abstract ContextFactory newContextFactory(Bundle bundle);

    public abstract ContextFactory newWebAppFactory(Bundle bundle);

    public abstract String getMetaInfContainerBundlePatternAttributeName();

    /**
     * ServerTracker
     * <p>
     * Tracks appearance of Server instances as OSGi services, and then configures them
     * for deployment of EE contexts and webapps.
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
            serverClasspathContributors.forEach(c -> contributedBundles.addAll(c.getScannableBundles()));
            contributedBundles.forEach(b -> contributedURLs.addAll(convertBundleToURL(b)));

            if (!contributedURLs.isEmpty())
            {
                //There should already be a default set up by the JettyServerFactory
                ClassLoader serverClassLoader = (ClassLoader)server.getAttribute(OSGiServerConstants.SERVER_CLASSLOADER);
                if (serverClassLoader != null)
                {
                    server.setAttribute(OSGiServerConstants.SERVER_CLASSLOADER,
                        new FakeURLClassLoader(serverClassLoader, contributedURLs.toArray(new URL[0])));

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
                    contextProvider = new BundleContextProvider(server, deployer, getEnvironment(), newContextFactory(_myBundle));
                    deployer.addBean(contextProvider, true);
                    LifeCycle.start(contextProvider);
                }

                if (webAppProvider == null)
                {
                    webAppProvider = new BundleWebAppProvider(server, deployer, getEnvironment(), newWebAppFactory(_myBundle));
                    deployer.addBean(webAppProvider, true);
                    LifeCycle.start(webAppProvider);
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
            List<URI> uris = new ArrayList<>();
            try
            {
                Path location = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle).toPath();

                if (Files.isDirectory(location))
                {
                    for (Path path : listing(location))
                    {
                        if (FileID.isJavaArchive(path))
                        {
                            uris.add(path.toUri());
                        }
                        else if (Files.isDirectory(path))
                        {
                            Path filename = path.getFileName();
                            if (filename != null && filename.toString().equalsIgnoreCase("lib"))
                            {
                                for (Path lib : listing(path))
                                {
                                    if (FileID.isJavaArchive(lib))
                                        uris.add(lib.toUri());
                                }
                            }
                        }
                    }
                }
                else
                {
                    uris.add(location.toUri());
                }
            }
            catch (Exception e)
            {
                LOG.warn("Unable to convert bundle {} to url", bundle, e);
            }

            List<URL> list = new ArrayList<>();
            try
            {
                for (URI uri : uris)
                {
                    URI correctURI = URIUtil.correctURI(uri);
                    URL url = correctURI.toURL();
                    list.add(url);
                }
            }
            catch (IOException e)
            {
                LOG.warn("Unable to convert bundle {} to url", bundle, e);
            }
            return list;
        }

        private List<Path> listing(Path dir) throws IOException
        {
            try (Stream<Path> listing = Files.list(dir))
            {
                return listing.toList();
            }
        }
    }
}
