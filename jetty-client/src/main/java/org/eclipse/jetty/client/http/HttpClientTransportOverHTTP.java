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

package org.eclipse.jetty.client.http;

import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

public class HttpClientTransportOverHTTP extends AbstractHttpClientTransport
{
    public HttpClientTransportOverHTTP()
    {
        this(1);
    }

    public HttpClientTransportOverHTTP(int selectors)
    {
        super(selectors);
    }

    @Override
    public HttpDestination newHttpDestination(String scheme, String host, int port)
    {
        return new HttpDestinationOverHTTP(getHttpClient(), scheme, host, port);
    }

    @Override
    protected Connection newConnection(EndPoint endPoint, HttpDestination destination)
    {
        return new HttpConnectionOverHTTP(endPoint, destination);
    }

    @Override
    public org.eclipse.jetty.client.api.Connection tunnel(org.eclipse.jetty.client.api.Connection connection)
    {
        HttpConnectionOverHTTP httpConnection = (HttpConnectionOverHTTP)connection;
        return tunnel(httpConnection.getEndPoint(), httpConnection.getHttpDestination(), connection);
    }
}
