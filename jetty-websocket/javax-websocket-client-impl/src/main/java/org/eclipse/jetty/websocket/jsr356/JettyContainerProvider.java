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

package org.eclipse.jetty.websocket.jsr356;

import java.util.LinkedList;
import java.util.ServiceLoader;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class JettyContainerProvider extends ContainerProvider
{
    private static final Logger LOG = Log.getLogger(JettyContainerProvider.class);
    private final WebSocketContainer websocketContainer;

    public JettyContainerProvider()
    {
        // Holds the list of ContainerService implementations found.
        LinkedList<ContainerService> services = new LinkedList<ContainerService>();

        for (ContainerService impl : ServiceLoader.load(ContainerService.class))
        {
            if (impl instanceof ClientContainer)
            {
                // Sort client impls last.
                services.addLast(impl);
            }
            else
            {
                // All others first. (such as ServiceContainer impls)
                services.addFirst(impl);
            }
        }

        if (services.size() <= 0)
        {
            LOG.warn("Found no {} in classloader",ContainerService.class.getName());
            websocketContainer = null;
            return;
        }

        if (LOG.isDebugEnabled())
        {
            StringBuilder str = new StringBuilder();

            int len = services.size();

            str.append("Found ").append(len).append(" websocket container");
            if (len > 1)
            {
                str.append('s');
            }

            for (int i = 0; i < len; i++)
            {
                ContainerService service = services.get(i);
                str.append("\n [").append(i).append("] ").append(service.getClass().getName());
            }

            LOG.debug(str.toString());
        }

        // Use first one (in list)
        ContainerService chosen = services.getFirst();
        LOG.debug("Using WebSocketContainer: {}",chosen.getClass().getName());
        chosen.start();

        websocketContainer = chosen;
    }

    @Override
    protected WebSocketContainer getContainer()
    {
        return websocketContainer;
    }
}
