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

package org.eclipse.jetty.fcgi.client.http;

import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.EndPoint;

// TODO: add parameter to tell whether use multiplex destinations or not
public class HttpClientTransportOverFCGI extends AbstractHttpClientTransport
{
    public HttpClientTransportOverFCGI()
    {
        this(1);
    }

    public HttpClientTransportOverFCGI(int selectors)
    {
        super(selectors);
    }

    @Override
    public HttpDestination newHttpDestination(String scheme, String host, int port)
    {
        return new MultiplexHttpDestinationOverFCGI(getHttpClient(), scheme, host, port);
    }

    @Override
    protected org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, HttpDestination destination)
    {
        return new HttpConnectionOverFCGI(endPoint, destination);
    }

    @Override
    public Connection tunnel(Connection connection)
    {
        HttpConnectionOverFCGI httpConnection = (HttpConnectionOverFCGI)connection;
        return tunnel(httpConnection.getEndPoint(), httpConnection.getHttpDestination(), connection);
    }
}
