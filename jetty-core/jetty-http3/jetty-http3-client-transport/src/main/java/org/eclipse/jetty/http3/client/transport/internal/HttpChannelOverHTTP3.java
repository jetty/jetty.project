//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.client.transport.internal;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;

public class HttpChannelOverHTTP3 extends HttpChannel
{
    private final HttpConnectionOverHTTP3 connection;
    private final HTTP3SessionClient session;
    private final HttpSenderOverHTTP3 sender;
    private final HttpReceiverOverHTTP3 receiver;
    private Stream stream;

    public HttpChannelOverHTTP3(HttpDestination destination, HttpConnectionOverHTTP3 connection, HTTP3SessionClient session)
    {
        super(destination);
        this.connection = connection;
        this.session = session;
        sender = new HttpSenderOverHTTP3(this);
        receiver = new HttpReceiverOverHTTP3(this);
    }

    public HttpConnectionOverHTTP3 getHttpConnection()
    {
        return connection;
    }

    public HTTP3SessionClient getSession()
    {
        return session;
    }

    public Stream.Client.Listener getStreamListener()
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

    public Stream getStream()
    {
        return stream;
    }

    public void setStream(Stream stream)
    {
        this.stream = stream;
    }

    @Override
    public void send(HttpExchange exchange)
    {
        sender.send(exchange);
    }

    @Override
    public void exchangeTerminated(HttpExchange exchange, Result result)
    {
        super.exchangeTerminated(exchange, result);

        Stream stream = getStream();
        if (stream != null && result.isFailed())
            stream.reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), result.getFailure());
        else
            release();
    }

    @Override
    public void release()
    {
        setStream(null);
        connection.release(this);
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
