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

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.bindings.StandardUndeployer;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.ee10.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.ee10.osgi.boot.utils.EventSender;

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
