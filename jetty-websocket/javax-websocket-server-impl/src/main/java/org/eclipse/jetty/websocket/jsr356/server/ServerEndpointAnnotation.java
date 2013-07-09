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

package org.eclipse.jetty.websocket.jsr356.server;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.WebAppContext;

public class ServerEndpointAnnotation extends DiscoveredAnnotation
{
    private static final Logger LOG = Log.getLogger(ServerEndpointAnnotation.class);

    public ServerEndpointAnnotation(WebAppContext context, String className)
    {
        super(context,className);
    }

    public ServerEndpointAnnotation(WebAppContext context, String className, Resource resource)
    {
        super(context,className,resource);
    }

    @Override
    public void apply()
    {
        Class<?> clazz = getTargetClass();

        if (clazz == null)
        {
            LOG.warn(_className + " cannot be loaded");
            return;
        }

        ServerEndpoint annotation = clazz.getAnnotation(ServerEndpoint.class);

        String path = annotation.value();
        LOG.info("Got path: \"{}\"",path);

        ServerContainer container = ServerContainer.get(_context);
        try
        {
            container.addEndpoint(clazz);
        }
        catch (DeploymentException e)
        {
            e.printStackTrace();
        }
    }
}
