//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.bindings.StandardUndeployer;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.osgi.boot.utils.EventSender;

/**
 * OSGiUndeployer
 *
 * Extension of the Jetty Undeployer which emits OSGi EventAdmin events
 * whenever a webapp is undeployed from Jetty.
 */
public class OSGiUndeployer extends StandardUndeployer
{
    private ServerInstanceWrapper _server;

    public OSGiUndeployer(ServerInstanceWrapper server)
    {
        _server = server;
    }

    @Override
    public void processBinding(Node node, App app) throws Exception
    {
        EventSender.getInstance().send(EventSender.UNDEPLOYING_EVENT, ((AbstractOSGiApp)app).getBundle(), app.getContextPath());
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_server.getParentClassLoaderForWebapps());
        try
        {
            super.processBinding(node, app);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
        EventSender.getInstance().send(EventSender.UNDEPLOYED_EVENT, ((AbstractOSGiApp)app).getBundle(), app.getContextPath());
        ((AbstractOSGiApp)app).deregisterAsOSGiService();
    }
}
