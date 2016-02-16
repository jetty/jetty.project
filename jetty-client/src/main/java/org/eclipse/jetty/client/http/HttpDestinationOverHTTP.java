//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.PoolingHttpDestination;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.client.api.Connection;

public class HttpDestinationOverHTTP extends PoolingHttpDestination
{
    public HttpDestinationOverHTTP(HttpClient client, Origin origin)
    {
        super(client, origin);
    }

    @Override
    protected SendFailure send(Connection connection, HttpExchange exchange)
    {
        return ((HttpConnectionOverHTTP)connection).send(exchange);
    }
}
