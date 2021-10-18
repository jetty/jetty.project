//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.client.http.internal;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;

public class HttpChannelOverHTTP3 extends HttpChannel
{
    private final HTTP3SessionClient session;
    private final HttpSenderOverHTTP3 sender;
    private final HttpReceiverOverHTTP3 receiver;

    public HttpChannelOverHTTP3(HttpDestination destination, HTTP3SessionClient session)
    {
        super(destination);
        this.session = session;
        sender = new HttpSenderOverHTTP3(this);
        receiver = new HttpReceiverOverHTTP3(this);
    }

    public HTTP3SessionClient getSession()
    {
        return session;
    }

    public Stream.Listener getStreamListener()
    {
        return receiver;
    }

    @Override
    protected HttpSender getHttpSender()
    {
        return sender;
    }

    @Override
    protected HttpReceiver getHttpReceiver()
    {
        return receiver;
    }

    @Override
    public void send(HttpExchange exchange)
    {
        sender.send(exchange);
    }

    @Override
    public void release()
    {
        // TODO
    }

    @Override
    public String toString()
    {
        return String.format("%s[send=%s,recv=%s]",
            super.toString(),
            sender,
            receiver);
    }
}
