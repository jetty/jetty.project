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

package org.eclipse.jetty.websocket.client.io;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.client.WebSocketClient;

/**
 * Deprecated ConnectionManager
 *
 * @deprecated use {@link HttpClient} with WebSocketClient directly
 */
@Deprecated
public class ConnectionManager extends ContainerLifeCycle
{
    public static InetSocketAddress toSocketAddress(URI uri)
    {
        if (!uri.isAbsolute())
        {
            throw new IllegalArgumentException("Cannot get InetSocketAddress of non-absolute URIs");
        }

        int port = uri.getPort();
        String scheme = uri.getScheme().toLowerCase(Locale.ENGLISH);
        if ("ws".equals(scheme))
        {
            if (port == (-1))
            {
                port = 80;
            }
        }
        else if ("wss".equals(scheme))
        {
            if (port == (-1))
            {
                port = 443;
            }
        }
        else
        {
            throw new IllegalArgumentException("Only support ws:// and wss:// URIs");
        }

        return new InetSocketAddress(uri.getHost(), port);
    }

    public ConnectionManager(WebSocketClient client)
    {
    }
}
