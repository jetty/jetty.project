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

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.bindings.StandardUndeployer;
import org.eclipse.jetty.osgi.util.EventSender;
import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.osgi.framework.Bundle;

/**
 * OSGiUndeployer
 *
 * Extension of the Jetty Undeployer which emits OSGi EventAdmin events
 * whenever a webapp is undeployed from Jetty.
 */
public class OSGiUndeployer extends StandardUndeployer
{
    private final Server _server;

    public OSGiUndeployer(Server server)
    {
        _server = server;
    }

    @Override
    public void processBinding(DeploymentManager deploymentManager, String nodeName, ContextHandler contextHandler) throws Exception
    {
        String contextPath = contextHandler.getContextPath();

        Bundle bundle = (Bundle)contextHandler.getAttribute(BundleMetadata.BUNDLE);
        if (bundle != null)
        {
            // This is a ContextHandler that is managed by jetty-osgi.
            EventSender.getInstance().send(EventSender.UNDEPLOYING_EVENT, bundle, contextPath);
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            ClassLoader cl = (ClassLoader)_server.getAttribute(OSGiServerConstants.SERVER_CLASSLOADER);
            Thread.currentThread().setContextClassLoader(cl);
            try
            {
                super.processBinding(deploymentManager, nodeName, contextHandler);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(old);
            }
            EventSender.getInstance().send(EventSender.UNDEPLOYED_EVENT, bundle, contextPath);
            Util.deregisterAsOSGiService(contextHandler);
        }
    }
}
