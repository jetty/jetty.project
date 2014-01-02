//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Socket;
import javax.net.SocketFactory;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

class SocketConnector extends AbstractLifeCycle implements HttpClient.Connector
{
    private static final Logger LOG = Log.getLogger(SocketConnector.class);

    /**
     *
     */
    private final HttpClient _httpClient;

    /**
     * @param httpClient
     */
    SocketConnector(HttpClient httpClient)
    {
        _httpClient = httpClient;
    }

    public void startConnection(final HttpDestination destination) throws IOException
    {
        Socket socket= destination.isSecure()
            ? destination.getSslContextFactory().newSslSocket()
            : SocketFactory.getDefault().createSocket();

        socket.setSoTimeout(0);
        socket.setTcpNoDelay(true);

        Address address = destination.isProxied() ? destination.getProxy() : destination.getAddress();
        socket.connect(address.toSocketAddress(), _httpClient.getConnectTimeout());

        final EndPoint endpoint=new SocketEndPoint(socket);

        final AbstractHttpConnection connection=new BlockingHttpConnection(_httpClient.getRequestBuffers(),_httpClient.getResponseBuffers(),endpoint);
        connection.setDestination(destination);
        destination.onNewConnection(connection);
        _httpClient.getThreadPool().dispatch(new Runnable()
        {
            public void run()
            {
                try
                {
                    Connection con = connection;
                    while(true)
                    {
                        final Connection next = con.handle();
                        if (next!=con)
                        {
                            con=next;
                            continue;
                        }
                        break;
                    }
                }
                catch (IOException e)
                {
                    if (e instanceof InterruptedIOException)
                        LOG.ignore(e);
                    else
                    {
                        LOG.debug(e);
                        destination.onException(e);
                    }
                }
                finally
                {
                    try
                    {
                        destination.returnConnection(connection,true);
                    }
                    catch (IOException e)
                    {
                        LOG.debug(e);
                    }
                }
            }
        });

    }
}
