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

package org.eclipse.jetty.spdy.client.http;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.util.Callback;

public class HttpConnectionOverSPDY extends HttpConnection
{
    private final Session session;

    public HttpConnectionOverSPDY(HttpDestination destination, Session session)
    {
        super(destination);
        this.session = session;
    }

    @Override
    protected void send(HttpExchange exchange)
    {
        normalizeRequest(exchange.getRequest());
        // One connection maps to N channels, so for each exchange we create a new channel
        HttpChannel channel = new HttpChannelOverSPDY(getHttpDestination(), session);
        channel.associate(exchange);
        channel.send();
    }

    @Override
    public void close()
    {
        session.goAway(new GoAwayInfo(), new Callback.Adapter());
    }
}
