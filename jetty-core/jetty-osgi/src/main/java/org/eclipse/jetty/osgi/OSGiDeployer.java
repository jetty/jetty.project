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

package org.eclipse.jetty.osgi;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.bindings.StandardDeployer;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.osgi.util.EventSender;
import org.eclipse.jetty.server.Server;

/**
 * OSGiDeployer
 *
 * Extension of standard Jetty deployer that emits OSGi EventAdmin
 * events whenever a webapp is deployed into OSGi via Jetty.
 */
public class OSGiDeployer extends StandardDeployer
{

    private Server _server;

    public OSGiDeployer(Server server)
    {
        _server = server;
    }

    @Override
    public void processBinding(Node node, App app) throws Exception
    {
        //TODO  how to NOT send this event if its not a webapp: 
        //OSGi Enterprise Spec only wants an event sent if its a webapp bundle (ie not a ContextHandler)
        if (!(app instanceof AbstractOSGiApp))
        {
            doProcessBinding(node, app);
        }
        else
        {
            EventSender.getInstance().send(EventSender.DEPLOYING_EVENT, ((AbstractOSGiApp)app).getBundle(), app.getContextPath());
            try
            {
                doProcessBinding(node, app);
                ((AbstractOSGiApp)app).registerAsOSGiService();
                EventSender.getInstance().send(EventSender.DEPLOYED_EVENT, ((AbstractOSGiApp)app).getBundle(), app.getContextPath());
            }
            catch (Exception e)
            {
                EventSender.getInstance().send(EventSender.FAILED_EVENT, ((AbstractOSGiApp)app).getBundle(), app.getContextPath());
                throw e;
            }
        }
    }

    protected void doProcessBinding(Node node, App app) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        ClassLoader cl = (ClassLoader)_server.getAttribute(OSGiServerConstants.SERVER_CLASSLOADER);
        Thread.currentThread().setContextClassLoader(cl);
        try
        {
            super.processBinding(node, app);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
}
