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

package org.eclipse.jetty.fcgi.client.http;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.MultiplexHttpDestination;
import org.eclipse.jetty.client.Origin;

public class MultiplexHttpDestinationOverFCGI extends MultiplexHttpDestination<HttpConnectionOverFCGI>
{
    public MultiplexHttpDestinationOverFCGI(HttpClient client, Origin origin)
    {
        super(client, origin);
    }

    @Override
    protected void send(HttpConnectionOverFCGI connection, HttpExchange exchange)
    {
        connection.send(exchange);
    }
}
