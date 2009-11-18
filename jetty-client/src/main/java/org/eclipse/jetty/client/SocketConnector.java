// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Socket;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;

class SocketConnector extends AbstractLifeCycle implements HttpClient.Connector
{
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
        Socket socket=null;

        if ( destination.isSecure() )
        {
            SSLContext sslContext = _httpClient.getSSLContext();
            socket = sslContext.getSocketFactory().createSocket();
        }
        else
        {
            Log.debug("Using Regular Socket");
            socket = SocketFactory.getDefault().createSocket();
        }

        Address address = destination.isProxied() ? destination.getProxy() : destination.getAddress();
        socket.connect(address.toSocketAddress(), _httpClient.getConnectTimeout());

        EndPoint endpoint=new SocketEndPoint(socket);

        final HttpConnection connection=new HttpConnection(_httpClient.getRequestBuffers(),_httpClient.getResponseBuffers(),endpoint);
        connection.setDestination(destination);
        destination.onNewConnection(connection);
        _httpClient.getThreadPool().dispatch(new Runnable()
        {
            public void run()
            {
                try
                {
                    connection.handle();
                }
                catch (IOException e)
                {
                    if (e instanceof InterruptedIOException)
                        Log.ignore(e);
                    else
                    {
                        Log.debug(e);
                        destination.onException(e);
                    }
                }
            }
        });

    }
}
