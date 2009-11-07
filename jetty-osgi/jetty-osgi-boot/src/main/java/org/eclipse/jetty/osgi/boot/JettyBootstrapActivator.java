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
package org.eclipse.jetty.osgi.boot;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Properties;

import org.eclipse.jetty.osgi.boot.internal.webapp.JettyContextHandlerExtender;
import org.eclipse.jetty.osgi.boot.internal.webapp.JettyContextHandlerServiceTracker;
import org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Experiment: bootstrap jetty's complete distrib from an OSGi bundle.
 * Progress:
 * <ol>
 * <li> basic servlet [ok]</li>
 * <li> basic jetty.xml [ok]</li>
 * <li> basic jetty.xml and jetty-plus.xml [ok]</li>
 * <li> basic jsp [ok with modifications]
 *   <ul>
 *     <li>Needed to modify the headers of jdt.core-3.1.1 so that its dependency on 
 * eclipse.runtime, eclipse.resources and eclipse.text are optional.
 * Also we should depend on the latest jdt.core from eclipse-3.5 not from eclipse-3.1.1
 * although that will require actual changes to jasper as some internal APIs of
 * jdt.core have changed.</li>
 *     <li>Modifications to org.mortbay.jetty.jsp-2.1-glassfish:
 * made all imports to ant, xalan and sun packages optional.</li>
 *   </ul>
 * </li>
 * <li> jsp with tag-libs [ok]</li>
 * <li> test-jndi with atomikos and derby inside ${jetty.home}/lib/ext [ok]</li>
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
    private Server _server;
    private JettyContextHandlerServiceTracker _jettyContextHandlerTracker;
    private PackageAdminServiceTracker _packageAdminServiceTracker;

    /**
     * Setup a new jetty Server, registers it as a service. Setup the Service
     * tracker for the jetty ContextHandlers that are in charge of deploying the webapps.
     * Setup the BundleListener that supports the extender pattern for the
     * jetty ContextHandler.
     * 
     * @param context
     */
    public void start(BundleContext context) throws Exception
    {
        INSTANCE = this;
        
        //track other bundles and fragments attached to this bundle that we should activate.
        _packageAdminServiceTracker = new PackageAdminServiceTracker(context);
        
        
        // todo: replace all this by the ManagedFactory so that we can start multiple jetty servers.
        _server = new Server();
        // expose the server as a service.
        _registeredServer = context.registerService(_server.getClass().getName(),_server,new Properties());
        // the tracker in charge of the actual deployment
        // and that will configure and start the jetty server.
        _jettyContextHandlerTracker = new JettyContextHandlerServiceTracker(context,_server);

        // TODO: add a couple more checks on the properties?
        // kind of nice not to so we can debug what is missing easily.
        context.addServiceListener(_jettyContextHandlerTracker,"(objectclass=" + ContextHandler.class.getName() + ")");

        // now ready to support the Extender pattern:
        JettyContextHandlerExtender jettyContexHandlerExtender = new JettyContextHandlerExtender();
        context.addBundleListener(jettyContexHandlerExtender);

        jettyContexHandlerExtender.init(context);

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    public void stop(BundleContext context) throws Exception
    {
        try
        {
            if (_jettyContextHandlerTracker != null)
            {
                _jettyContextHandlerTracker.stop();
                context.removeServiceListener(_jettyContextHandlerTracker);
            }
            if (_packageAdminServiceTracker != null)
            {
                _packageAdminServiceTracker.stop();
                context.removeServiceListener(_packageAdminServiceTracker);
            }
            if (_registeredServer != null)
            {
                try
                {
                    _registeredServer.unregister();
                    _registeredServer = null;
                }
                catch (IllegalArgumentException ill)
                {
                    // already unregistered.
                }
            }
        }
        finally
        {
            _server.stop();
            INSTANCE = null;
        }
    }

    /**
     * Helper method that creates a new org.jetty.webapp.WebAppContext and 
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param context
     *            The current bundle context
     * @param webappFolderPath
     *            The path to the root of the webapp. Must be a path relative 
     *            to bundle; either an absolute path.
     * @param contextPath
     *            The context path. Must start with "/"
     * @throws Exception
     */
    public static void registerWebapplication(Bundle contributor,
            String webappFolderPath, String contextPath) throws Exception
    {
        WebAppContext contextHandler = new WebAppContext();
        Properties dic = new Properties();
        dic.put(OSGiWebappConstants.SERVICE_PROP_WAR,webappFolderPath);
        dic.put(OSGiWebappConstants.SERVICE_PROP_CONTEXT_PATH,contextPath);
        contributor.getBundleContext().registerService(ContextHandler.class.getName(),contextHandler,dic);
    }
    /**
     * Helper method that creates a new org.jetty.webapp.WebAppContext and 
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param context
     *            The current bundle context
     * @param webappFolderPath
     *            The path to the root of the webapp. Must be a path relative 
     *            to bundle; either an absolute path.
     * @param contextPath
     *            The context path. Must start with "/"
     * @param thisBundleInstallationOverride The location to a folder where the context file is located
     *            This overrides the default behavior that consists of using the location
     *            where the bundle is installed. Useful when in fact the webapp contributed is not inside a bundle.
     * @throws Exception
     */
    public static void registerWebapplication(Bundle contributor,
            String webappFolderPath, String contextPath,
            Dictionary<String, String> dic) throws Exception
    {
        WebAppContext contextHandler = new WebAppContext();
        dic.put(OSGiWebappConstants.SERVICE_PROP_WAR, webappFolderPath);
        dic.put(OSGiWebappConstants.SERVICE_PROP_CONTEXT_PATH, contextPath);
        contributor.getBundleContext().registerService(ContextHandler.class.getName(),contextHandler,dic);
    }

    /**
     * Helper method that creates a new skeleton of a ContextHandler and registers it as an OSGi service.
     * The tracker {@link JettyContextHandlerServiceTracker}  will do the actual deployment.
     * 
     * @param contributor
     *            The bundle that registers a new context
     * @param contextFilePath
     *            The path to the file inside the bundle that defines the context.
     * @throws Exception
     */
    public static void registerContext(Bundle contributor, String contextFilePath) throws Exception
    {
        registerContext(contributor,contextFilePath,new Hashtable<String, String>());
    }

    /**
     * Helper method that creates a new skeleton of a ContextHandler and registers it as an OSGi service.
     * The tracker {@link JettyContextHandlerServiceTracker}  will do the actual deployment.
     * 
     * @param contributor
     *            The bundle that registers a new context
     * @param contextFilePath
     *            The path to the file inside the bundle that defines the context.
     * @param thisBundleInstallationOverride The location to a folder where the context file is located
     *            This overrides the default behavior that consists of using the location
     *            where the bundle is installed. Useful when in fact the webapp contributed is not inside a bundle.
     * @throws Exception
     */
    public static void registerContext(Bundle contributor, String contextFilePath,
            Dictionary<String,String> dic) throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        dic.put(OSGiWebappConstants.SERVICE_PROP_CONTEXT_FILE_PATH, contextFilePath);
        contributor.getBundleContext().registerService(ContextHandler.class.getName(),contextHandler,dic);
    }

    public static void unregister(String contextPath)
    {
        // todo
    }

}