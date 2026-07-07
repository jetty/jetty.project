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

package org.eclipse.jetty.fcgi.client.transport.internal;

import java.net.SocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.transport.HttpChannel;
import org.eclipse.jetty.client.transport.HttpConnection;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.IConnection;
import org.eclipse.jetty.client.transport.SendFailure;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.parser.ClientParser;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

public class HttpConnectionOverFCGI extends AbstractConnection implements IConnection, Attachable, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectionOverFCGI.class);

    private final Callback fillableCallback = new FillableCallback();
    private final WritableBufferPool networkBufferPool;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final HttpDestination destination;
    private final Promise<Connection> promise;
    private final Flusher flusher;
    private final Delegate delegate;
    private final ClientParser parser;
    private final HttpChannelOverFCGI channel;
    private final InvocationType invocationType;
    private ReadableBuffer networkBuffer;
    private Object attachment;
    private State state = State.IDLE;
    private long idleTimeout;
    private boolean shutdown;
    private Throwable failure;

    public HttpConnectionOverFCGI(EndPoint endPoint, Destination destination, Promise<Connection> promise)
    {
        super(endPoint, destination.getHttpClient().getExecutor());
        this.destination = (HttpDestination)destination;
        this.promise = promise;
        this.flusher = new Flusher(endPoint);
        this.delegate = new Delegate(destination);
        this.parser = new ClientParser(new ResponseListener());
        this.channel = newHttpChannel();
        HttpClient client = destination.getHttpClient();
        this.networkBufferPool = WritableBufferPool.wrap(client.getByteBufferPool());
        this.invocationType = client.getHttpClientTransport().getInvocationType();
    }

    public HttpDestination getHttpDestination()
    {
        return destination;
    }

    @Override
    public InvocationType getInvocationType()
    {
        return invocationType;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return delegate.getLocalSocketAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return delegate.getRemoteSocketAddress();
    }

    @Override
    public EndPoint.SslSessionData getSslSessionData()
    {
        return delegate.getSslSessionData();
    }

    protected Flusher getFlusher()
    {
        return flusher;
    }

    @Override
    public void send(Request request, Response.CompleteListener listener)
    {
        delegate.send(request, listener);
    }

    @Override
    public SendFailure send(HttpExchange exchange)
    {
        return delegate.send(exchange);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
        promise.succeeded(this);
    }

    @Override
    public void fillInterested()
    {
        fillInterested(fillableCallback);
    }

    @Override
    public void onFillable()
    {
        channel.receive();
    }

    private WritableBuffer newNetworkBuffer()
    {
        HttpClient client = destination.getHttpClient();
        return networkBufferPool.acquire(client.getResponseBufferSize(), client.isUseInputDirectByteBuffers());
    }

    private void disposeNetworkBuffer()
    {
        if (networkBuffer == null)
            return;
        networkBuffer.release();
        if (LOG.isDebugEnabled())
            LOG.debug("Disposed {}", networkBuffer);
        networkBuffer = null;
    }

    boolean process(boolean notifyContentAvailable)
    {
        boolean setFillInterested = parseAndFill();

        switch (state)
        {
            case IDLE ->
            {
            }
            case HEADERS ->
            {
                state = State.CONTENT;
                channel.responseHeaders();
            }
            case CONTENT ->
            {
                if (notifyContentAvailable)
                    channel.responseContentAvailable();
            }
            case COMPLETE ->
            {
                channel.end();
                // Do not call channel.responseSuccess() here to give HttpReceiverOverFCGI.read(boolean)
                // a chance to read the chunk field before channel.responseSuccess() resets it to null.
            }
            case FAILED ->
            {
                failAndClose(failure);
            }
            default -> throw new IllegalStateException("Invalid state " + state);
        }

        return setFillInterested;
    }

    private boolean parseAndFill()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parseAndFill {}", networkBuffer);

        ReadableBuffer readable = null;
        WritableBuffer writable = null;
        if (networkBuffer != null)
        {
            readable = networkBuffer;
            networkBuffer = null;
        }
        else
        {
            writable = newNetworkBuffer();
        }

        EndPoint endPoint = getEndPoint();
        try
        {
            while (true)
            {
                if (writable != null)
                {
                    int read = endPoint.fill(writable);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Read {} bytes from {}", read, endPoint);

                    if (read <= 0)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Releasing {}", writable);
                        writable.release();

                        if (read == 0)
                            return true;

                        shutdown();
                        return false;
                    }

                    readable = writable.toReadable();
                }

                assert readable != null;

                if (parse(readable))
                {
                    if (readable.remaining() == 0)
                        readable.release();
                    else
                        networkBuffer = readable;
                    return false;
                }

                if (readable.isRetained())
                {
                    readable.release();
                    writable = newNetworkBuffer();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Reacquired {}", writable);
                }
                else
                {
                    writable = readable.toWritable();
                }
            }
        }
        catch (Exception x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to fill from endpoint {}", endPoint, x);
            if (writable != null)
                writable.release();
            else
                readable.release();
            close(x);
            return false;
        }
    }

    private boolean parse(ReadableBuffer buffer)
    {
        while (buffer.remaining() > 0)
        {
            boolean result = parser.parse(buffer);
            if (result)
                return true;
        }
        return false;
    }

    boolean isComplete()
    {
        return state == State.COMPLETE;
    }

    void complete()
    {
        channel.responseSuccess();
        state = State.IDLE;
    }

    private void shutdown()
    {
        // Mark this receiver as shutdown, so that we can
        // close the connection when the exchange terminates.
        // We cannot close the connection from here because
        // the request may still be in process.
        shutdown = true;
        if (!parser.eof())
            channel.eof();
    }

    boolean isShutdown()
    {
        return shutdown;
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
    {
        long idleTimeout = getEndPoint().getIdleTimeout();
        boolean close = delegate.onIdleTimeout(idleTimeout, timeoutException);
        if (close)
            close(timeoutException);
        return false;
    }

    protected void release()
    {
        // Restore idle timeout
        getEndPoint().setIdleTimeout(idleTimeout);
        destination.release(this);
    }

    @Override
    public void close()
    {
        close(new AsynchronousCloseException());
    }

    protected void close(Throwable failure)
    {
        if (closed.compareAndSet(false, true))
        {
            getHttpDestination().remove(this);
            abort(failure);
            channel.destroy();
            delegate.destroy();
            disposeNetworkBuffer();
            getEndPoint().shutdownOutput();
            if (LOG.isDebugEnabled())
                LOG.debug("Shutdown {}", this);
            getEndPoint().close();
            if (LOG.isDebugEnabled())
                LOG.debug("Closed {}", this);
        }
    }

    @Override
    public boolean isClosed()
    {
        return closed.get();
    }

    @Override
    public void setAttachment(Object obj)
    {
        this.attachment = obj;
    }

    @Override
    public Object getAttachment()
    {
        return attachment;
    }

    protected boolean isCloseByHTTP(HttpFields fields)
    {
        return fields.contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
    }

    protected void abort(Throwable failure)
    {
        HttpExchange exchange = channel.getHttpExchange();
        if (exchange != null)
            exchange.getRequest().abort(failure);
    }

    private void failAndClose(Throwable failure)
    {
        channel.responseFailure(failure, Promise.from(failed ->
        {
            if (failed)
                close(failure);
        }, _ -> close(failure)));
    }

    protected HttpChannelOverFCGI newHttpChannel()
    {
        return new HttpChannelOverFCGI(this);
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x[l:%s<->r:%s]",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            getEndPoint().getLocalSocketAddress(),
            getEndPoint().getRemoteSocketAddress());
    }

    private class Delegate extends HttpConnection
    {
        private Delegate(Destination destination)
        {
            super((HttpDestination)destination);
        }

        @Override
        protected HttpVersion getHttpVersion()
        {
            return null;
        }

        @Override
        protected boolean requiresHostHeader()
        {
            return true;
        }

        @Override
        protected Iterator<HttpChannel> getHttpChannels()
        {
            return Collections.<HttpChannel>singleton(channel).iterator();
        }

        @Override
        public SocketAddress getLocalSocketAddress()
        {
            return getEndPoint().getLocalSocketAddress();
        }

        @Override
        public SocketAddress getRemoteSocketAddress()
        {
            return getEndPoint().getRemoteSocketAddress();
        }

        @Override
        public EndPoint.SslSessionData getSslSessionData()
        {
            return getEndPoint().getSslSessionData();
        }

        @Override
        public SendFailure send(HttpExchange exchange)
        {
            HttpRequest request = exchange.getRequest();
            normalizeRequest(request);

            // Save the old idle timeout to restore it.
            EndPoint endPoint = getEndPoint();
            idleTimeout = endPoint.getIdleTimeout();
            long requestIdleTimeout = request.getIdleTimeout();
            if (requestIdleTimeout >= 0)
                endPoint.setIdleTimeout(requestIdleTimeout);

            channel.setRequest(requests.incrementAndGet());
            return send(channel, exchange);
        }

        @Override
        public void close()
        {
            HttpConnectionOverFCGI.this.close();
        }

        @Override
        public boolean isClosed()
        {
            return HttpConnectionOverFCGI.this.isClosed();
        }

        @Override
        public String toString()
        {
            return HttpConnectionOverFCGI.this.toString();
        }
    }

    private class ResponseListener implements ClientParser.Listener
    {
        @Override
        public void onBegin(int request, int code, String reason)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onBegin r={},c={},reason={}", request, code, reason);
            channel.responseBegin(code, reason);
        }

        @Override
        public void onHeader(int request, HttpField field)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onHeader r={},f={}", request, field);
            channel.responseHeader(field);
        }

        @Override
        public boolean onHeaders(int request)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onHeaders r={}", request);
            state = State.HEADERS;
            return true;
        }

        @Override
        public boolean onContent(int request, FCGI.StreamType stream, ReadableBuffer buffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onContent r={},t={},b={}", request, stream, buffer);
            switch (stream)
            {
                case STD_OUT ->
                {
                    Content.Chunk chunk = Content.Chunk.from(buffer, false);
                    channel.content(chunk);
                    state = State.CONTENT;
                    return true;
                }
                case STD_ERR -> LOG.info(BufferUtil.toString(buffer, UTF_8));
                default -> throw new IllegalArgumentException();
            }
            return false;
        }

        @Override
        public boolean onEnd(int request)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onEnd r={}", request);
            state = State.COMPLETE;
            return true;
        }

        @Override
        public void onFailure(int request, Throwable cause)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onFailure request={}", request, cause);
            state = State.FAILED;
            failure = cause;
        }
    }

    private enum State
    {
        IDLE,
        HEADERS,
        CONTENT,
        COMPLETE,
        FAILED
    }

    private class FillableCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(Throwable failure)
        {
            onFillInterestedFailed(failure);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return HttpConnectionOverFCGI.this.getInvocationType();
        }
    }
}
