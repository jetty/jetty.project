//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.osgi.boot;

import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.osgi.boot.internal.serverfactory.DefaultJettyAtJettyHomeHelper;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.JettyServerServiceTracker;
import org.eclipse.jetty.osgi.boot.internal.webapp.IWebBundleDeployerHelper;
import org.eclipse.jetty.osgi.boot.internal.webapp.JettyContextHandlerServiceTracker;
import org.eclipse.jetty.osgi.boot.internal.webapp.WebBundleTrackerCustomizer;
import org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.BundleTracker;

/**
 * Bootstrap jetty and publish a default Server instance as an OSGi service.
 * 
 * Listen for other Server instances to be published as services and support them as deployment targets.
 * 
 * Listen for Bundles to be activated, and deploy those that represent webapps to one of the known Server instances.
 * 
 * <ol>
 * <li>basic servlet [ok]</li>
 * <li>basic jetty.xml [ok]</li>
 * <li>basic jetty.xml and jetty-plus.xml [ok]</li>
 * <li>basic jsp [ok]</li>
 * <li>jsp with tag-libs [ok]</li>
 * <li>test-jndi with atomikos and derby inside ${jetty.home}/lib/ext [ok]</li>
 * </ul>
 */
public class JettyBootstrapActivator implements BundleActivator
{

    private static JettyBootstrapActivator INSTANCE = null;

    public static JettyBootstrapActivator getInstance()
    {
        return INSTANCE;
    }

    private ServiceRegistration _registeredServer;

    private JettyContextHandlerServiceTracker _jettyContextHandlerTracker;

    private PackageAdminServiceTracker _packageAdminServiceTracker;

    private BundleTracker _webBundleTracker;

    private BundleContext _bundleContext;

    private JettyServerServiceTracker _jettyServerServiceTracker;

    /**
     * Setup a new jetty Server, registers it as a service. Setup the Service
     * tracker for the jetty ContextHandlers that are in charge of deploying the
     * webapps. Setup the BundleListener that supports the extender pattern for
     * the jetty ContextHandler.
     * 
     * @param context
     */
    public void start(BundleContext context) throws Exception
    {
        INSTANCE = this;
        _bundleContext = context;

        // track other bundles and fragments attached to this bundle that we
        // should activate.
        _packageAdminServiceTracker = new PackageAdminServiceTracker(context);

        // track jetty Server instances that we should support as deployment targets
        _jettyServerServiceTracker = new JettyServerServiceTracker();
        context.addServiceListener(_jettyServerServiceTracker, "(objectclass=" + Server.class.getName() + ")");

        // track ContextHandler class instances and deploy them to one of the known Servers
        _jettyContextHandlerTracker = new JettyContextHandlerServiceTracker();
        context.addServiceListener(_jettyContextHandlerTracker, "(objectclass=" + ContextHandler.class.getName() + ")");

        // Create a default jetty instance right now.
        DefaultJettyAtJettyHomeHelper.startJettyAtJettyHome(context);

        // track Bundles and deploy those that represent webapps to one of the known Servers
        WebBundleTrackerCustomizer customizer = new WebBundleTrackerCustomizer();
        _webBundleTracker = new BundleTracker(context, Bundle.ACTIVE | Bundle.STOPPING, customizer);
        customizer.setAndOpenWebBundleTracker(_webBundleTracker);
    }

    /**
     * Stop the activator.
     * 
     * @see
     * org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    public void stop(BundleContext context) throws Exception
    {
        try
        {

            if (_webBundleTracker != null)
            {
                _webBundleTracker.close();
                _webBundleTracker = null;
            }
            if (_jettyContextHandlerTracker != null)
            {
                context.removeServiceListener(_jettyContextHandlerTracker);
                _jettyContextHandlerTracker = null;
            }
            if (_jettyServerServiceTracker != null)
            {
                _jettyServerServiceTracker.stop();
                context.removeServiceListener(_jettyServerServiceTracker);
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

    /**
     * Helper method that creates a new org.jetty.webapp.WebAppContext and
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param contributor The bundle
     * @param webappFolderPath The path to the root of the webapp. Must be a
     *            path relative to bundle; either an absolute path.
     * @param contextPath The context path. Must start with "/"
     * @throws Exception
     */
    public static void registerWebapplication(Bundle contributor, String webappFolderPath, String contextPath) throws Exception
    {
        checkBundleActivated();
        WebAppContext contextHandler = new WebAppContext();
        Dictionary<String,String> dic = new Hashtable<String,String>();
        dic.put(OSGiWebappConstants.SERVICE_PROP_WAR, webappFolderPath);
        dic.put(OSGiWebappConstants.SERVICE_PROP_CONTEXT_PATH, contextPath);
        String requireTldBundle = (String) contributor.getHeaders().get(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
        if (requireTldBundle != null)
        {
            dic.put(OSGiWebappConstants.SERVICE_PROP_REQUIRE_TLD_BUNDLE, requireTldBundle);
        }
        contributor.getBundleContext().registerService(ContextHandler.class.getName(), contextHandler, dic);
    }

    /**
     * Helper method that creates a new org.jetty.webapp.WebAppContext and
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param contributor The bundle
     * @param webappFolderPath The path to the root of the webapp. Must be a
     *            path relative to bundle; either an absolute path.
     * @param contextPath The context path. Must start with "/"
     * @param dic TODO: parameter description
     * @throws Exception
     */
    public static void registerWebapplication(Bundle contributor, String webappFolderPath, String contextPath, Dictionary<String, String> dic) throws Exception
    {
        checkBundleActivated();
        WebAppContext contextHandler = new WebAppContext();
        dic.put(OSGiWebappConstants.SERVICE_PROP_WAR, webappFolderPath);
        dic.put(OSGiWebappConstants.SERVICE_PROP_CONTEXT_PATH, contextPath);
        contributor.getBundleContext().registerService(ContextHandler.class.getName(), contextHandler, dic);
    }

    /**
     * Helper method that creates a new skeleton of a ContextHandler and
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param contributor The bundle that registers a new context
     * @param contextFilePath The path to the file inside the bundle that
     *            defines the context.
     * @throws Exception
     */
    public static void registerContext(Bundle contributor, String contextFilePath) throws Exception
    {
        registerContext(contributor, contextFilePath, new Hashtable<String, String>());
    }

    /**
     * Helper method that creates a new skeleton of a ContextHandler and
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param contributor The bundle that registers a new context
     * @param contextFilePath The path to the file inside the bundle that
     *            defines the context.
     * @param dic TODO: parameter description
     * @throws Exception
     */
    public static void registerContext(Bundle contributor, String contextFilePath, Dictionary<String, String> dic) throws Exception
    {
        checkBundleActivated();
        ContextHandler contextHandler = new ContextHandler();
        dic.put(OSGiWebappConstants.SERVICE_PROP_CONTEXT_FILE_PATH, contextFilePath);
        dic.put(IWebBundleDeployerHelper.INTERNAL_SERVICE_PROP_UNKNOWN_CONTEXT_HANDLER_TYPE, Boolean.TRUE.toString());
        contributor.getBundleContext().registerService(ContextHandler.class.getName(), contextHandler, dic);
    }

    public static void unregister(String contextPath)
    {
        // todo
    }

    /**
     * Since org.eclipse.jetty.osgi.boot does not have a lazy activation policy
     * when one of the static methods to register a webapp is called we should
     * make sure that the bundle is started.
     */
    private static void checkBundleActivated()
    {
        if (INSTANCE == null)
        {
            Bundle thisBundle = FrameworkUtil.getBundle(JettyBootstrapActivator.class);
            try
            {
                thisBundle.start();
            }
            catch (BundleException e)
            {
                // nevermind.
            }
        }
    }

    /**
     * @return The bundle context for this bundle.
     */
    public static BundleContext getBundleContext()
    {
        checkBundleActivated();
        return INSTANCE._bundleContext;
    }

}
