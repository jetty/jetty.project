//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.transport.HttpChannel;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpReceiver;
import org.eclipse.jetty.client.transport.HttpSender;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.util.Promise;

public class HttpChannelOverHTTP3 extends HttpChannel
{
    private final Stream.Client.Listener listener = new Listener();
    private final HttpConnectionOverHTTP3 connection;
    private final HTTP3SessionClient session;
    private final HttpSenderOverHTTP3 sender;
    private final HttpReceiverOverHTTP3 receiver;
    private Stream stream;

    public HttpChannelOverHTTP3(HttpConnectionOverHTTP3 connection, HTTP3SessionClient session)
    {
        super(connection.getHttpDestination());
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
        return listener;
    }

    @Override
    protected Connection getConnection()
    {
        return connection;
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
        getHttpConnection().release(this);
    }

    @Override
    public String toString()
    {
        return String.format("%s[send=%s,recv=%s]",
            super.toString(),
            sender,
            receiver);
    }

    private class Listener implements Stream.Client.Listener
    {
        @Override
        public void onNewStream(Stream.Client stream)
        {
            setStream(stream);
        }

        @Override
        public void onResponse(Stream.Client stream, HeadersFrame frame)
        {
            offerTask(receiver.onResponse(frame));
        }

        @Override
        public void onDataAvailable(Stream.Client stream)
        {
            offerTask(receiver.onDataAvailable());
        }

        @Override
        public void onTrailer(Stream.Client stream, HeadersFrame frame)
        {
            offerTask(receiver.onTrailer(frame));
        }

        @Override
        public void onIdleTimeout(Stream.Client stream, Throwable failure, Promise<Boolean> promise)
        {
            offerTask(receiver.onIdleTimeout(failure, promise));
        }

        @Override
        public void onFailure(Stream.Client stream, long error, Throwable failure)
        {
            offerTask(receiver.onFailure(failure));
        }

        private void offerTask(Runnable task)
        {
            getSession().getProtocolSession().offer(task, false);
        }
    }
}
