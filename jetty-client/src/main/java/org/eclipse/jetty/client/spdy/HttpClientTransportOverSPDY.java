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

package org.eclipse.jetty.client.spdy;

import java.net.SocketAddress;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.util.Promise;

public class HttpClientTransportOverSPDY implements HttpClientTransport
{
    private final SPDYClient client;
    private volatile HttpClient httpClient;

    public HttpClientTransportOverSPDY(SPDYClient client)
    {
        this.client = client;
    }

    @Override
    public void setHttpClient(HttpClient client)
    {
        httpClient = client;
    }

    @Override
    public HttpDestination newHttpDestination(HttpClient httpClient, String scheme, String host, int port)
    {
        return new HttpDestinationOverSPDY(httpClient, scheme, host, port);
    }

    @Override
    public void connect(final HttpDestination destination, SocketAddress address, final Promise<Connection> promise)
    {
        SessionFrameListener.Adapter listener = new SessionFrameListener.Adapter()
        {
            @Override
            public void onException(Throwable x)
            {
                // TODO: is this correct ?
                // TODO: if I get a stream error (e.g. invalid response headers)
                // TODO: I must abort the *current* exchange, while below I will abort
                // TODO: the queued exchanges only.
                // TODO: The problem is that a single destination/connection multiplexes
                // TODO: several exchanges, so I would need to cancel them all,
                // TODO: or only the one that failed ?
                destination.abort(x);
            }
        };

        client.connect(address, listener, new Promise<Session>()
                {
                    @Override
                    public void succeeded(Session session)
                    {
                        Connection result = new HttpConnectionOverSPDY(httpClient, destination, session);
                        promise.succeeded(result);
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        promise.failed(x);
                    }
                }
        );
    }
}
