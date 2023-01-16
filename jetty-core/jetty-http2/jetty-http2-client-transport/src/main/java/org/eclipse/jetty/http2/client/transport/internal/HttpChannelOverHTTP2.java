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

package org.eclipse.jetty.http2.client.transport.internal;

import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.internal.HttpChannel;
import org.eclipse.jetty.client.internal.HttpExchange;
import org.eclipse.jetty.client.internal.HttpReceiver;
import org.eclipse.jetty.client.internal.HttpSender;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.internal.HTTP2Channel;
import org.eclipse.jetty.http2.internal.HTTP2Stream;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpChannelOverHTTP2 extends HttpChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelOverHTTP2.class);

    private final Stream.Listener listener = new Listener();
    private final HttpConnectionOverHTTP2 connection;
    private final Session session;
    private final HttpSenderOverHTTP2 sender;
    private final HttpReceiverOverHTTP2 receiver;
    private Stream stream;

    public HttpChannelOverHTTP2(HttpConnectionOverHTTP2 connection, Session session)
    {
        super(connection.getHttpDestination());
        this.connection = connection;
        this.session = session;
        this.sender = new HttpSenderOverHTTP2(this);
        this.receiver = new HttpReceiverOverHTTP2(this);
    }

    protected HttpConnectionOverHTTP2 getHttpConnection()
    {
        return connection;
    }

    public Session getSession()
    {
        return session;
    }

    public Stream.Listener getStreamListener()
    {
        return listener;
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
        if (stream != null)
            ((HTTP2Stream)stream).setAttachment(receiver);
    }

    public boolean isFailed()
    {
        return sender.isFailed() || receiver.isFailed();
    }

    @Override
    public void send(HttpExchange exchange)
    {
        sender.send(exchange);
    }

    @Override
    public void release()
    {
        setStream(null);
        boolean released = connection.release(this);
        if (LOG.isDebugEnabled())
            LOG.debug("released channel? {} {}", released, this);
        if (released)
            getHttpDestination().release(getHttpConnection());
    }

    @Override
    public void exchangeTerminated(HttpExchange exchange, Result result)
    {
        super.exchangeTerminated(exchange, result);
        Stream stream = getStream();
        if (LOG.isDebugEnabled())
            LOG.debug("exchange terminated {} {}", result, stream);
        if (result.isSucceeded())
        {
            release();
        }
        else
        {
            if (stream != null)
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), new ReleaseCallback());
            else
                release();
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[send=%s,recv=%s]",
            super.toString(),
            sender,
            receiver);
    }

    private class ReleaseCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            release();
        }

        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ReleaseCallback failed", x);
            release();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }

    private class Listener implements Stream.Listener
    {
        @Override
        public void onNewStream(Stream stream)
        {
            setStream(stream);
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame)
        {
            receiver.onHeaders(stream, frame);
        }

        @Override
        public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
        {
            return receiver.onPush(stream, frame);
        }

        @Override
        public void onDataAvailable(Stream stream)
        {
            HTTP2Channel.Client channel = (HTTP2Channel.Client)((HTTP2Stream)stream).getAttachment();
            channel.onDataAvailable();
        }

        @Override
        public void onReset(Stream stream, ResetFrame frame, Callback callback)
        {
            // TODO: needs to call HTTP2Channel?
            receiver.onReset(frame);
            callback.succeeded();
        }

        @Override
        public void onIdleTimeout(Stream stream, Throwable x, Promise<Boolean> promise)
        {
            HTTP2Channel.Client channel = (HTTP2Channel.Client)((HTTP2Stream)stream).getAttachment();
            channel.onTimeout(x, promise);
        }

        @Override
        public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
        {
            HTTP2Channel.Client channel = (HTTP2Channel.Client)((HTTP2Stream)stream).getAttachment();
            channel.onFailure(failure, callback);
        }
    }
}
