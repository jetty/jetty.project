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

package org.eclipse.jetty.osgi.boot;

import org.eclipse.jetty.osgi.boot.internal.serverfactory.DefaultJettyAtJettyHomeHelper;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.JettyServerServiceTracker;
import org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.server.Server;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JettyBootstrapActivator
 * <p>
 * Bootstrap jetty and publish a default Server instance as an OSGi service.
 * <p>
 * Listen for other Server instances to be published as services and support them as deployment targets.
 * <p>
 * Listen for Bundles to be activated, and deploy those that represent webapps/ContextHandlers to one of the known Server instances.
 */
public class JettyBootstrapActivator implements BundleActivator
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyBootstrapActivator.class);

    private static JettyBootstrapActivator INSTANCE = null;

    public static JettyBootstrapActivator getInstance()
    {
        return INSTANCE;
    }

    private ServiceRegistration _registeredServer;

    private PackageAdminServiceTracker _packageAdminServiceTracker;

    private ServiceTracker _jettyServerServiceTracker;

    /**
     * Setup a new jetty Server, registers it as a service. Setup the Service
     * tracker for the jetty ContextHandlers that are in charge of deploying the
     * webapps. Setup the BundleListener that supports the extender pattern for
     * the jetty ContextHandler.
     *
     * @param context the bundle context
     */
    @Override
    public void start(final BundleContext context) throws Exception
    {
        ServiceReference[] references = context.getAllServiceReferences("org.eclipse.jetty.http.HttpFieldPreEncoder", null);

        if (references == null || references.length == 0)
            LOG.warn("OSGi support for java.util.ServiceLoader may not be present. You may experience runtime errors.");

        INSTANCE = this;

        // track other bundles and fragments attached to this bundle that we
        // should activate.
        _packageAdminServiceTracker = new PackageAdminServiceTracker(context);

        // track jetty Server instances that we should support as deployment targets
        _jettyServerServiceTracker = new ServiceTracker(context, context.createFilter("(objectclass=" + Server.class.getName() + ")"), new JettyServerServiceTracker());
        _jettyServerServiceTracker.open();

        // Create a default jetty instance right now.
        DefaultJettyAtJettyHomeHelper.startJettyAtJettyHome(context);
    }

    /**
     * Stop the activator.
     *
     * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    @Override
    public void stop(BundleContext context) throws Exception
    {
        try
        {
            if (_jettyServerServiceTracker != null)
            {
                _jettyServerServiceTracker.close();
                _jettyServerServiceTracker = null;
            }
            if (_packageAdminServiceTracker != null)
            {
                _packageAdminServiceTracker.stop();
                context.removeServiceListener(_packageAdminServiceTracker);
                _packageAdminServiceTracker = null;
            }
            if (_registeredServer != null)
            {
                try
                {
                    _registeredServer.unregister();
                }
                catch (IllegalArgumentException ill)
                {
                    // already unregistered.
                }
                finally
                {
                    _registeredServer = null;
                }
            }
        }
        finally
        {
            INSTANCE = null;
        }
    }
}
