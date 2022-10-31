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

import java.util.Collection;
import java.util.Optional;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.osgi.util.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.server.Server;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
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
                //TODO correct classnames
                //TODO only add if not already present
                deployer.get().addAppProvider(new BundleContextProvider("org.eclipse.jetty.ee9.server.ContextHandler"));
                deployer.get().addAppProvider(new BundleWebAppProvider("org.eclipse.jetty.ee9.webapp.WebAppContext"));
                deployer.get().addAppProvider(new ServiceWebAppProvider("org.eclipse.jetty.ee9.webapp.WebAppContext"));
                deployer.get().addAppProvider(new ServiceContextProvider("org.eclipse.jetty.ee9.server.ContextHandler"));
            }
            else
                LOG.info("No DeploymentManager for Server {}", server);
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
