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

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.PoolingHttpDestination;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.parser.ClientParser;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpConnectionOverFCGI extends AbstractConnection implements Connection
{
    private static final Logger LOG = Log.getLogger(HttpConnectionOverFCGI.class);

    private final LinkedList<Integer> requests = new LinkedList<>();
    private final Map<Integer, HttpChannelOverFCGI> channels = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Flusher flusher;
    private final HttpDestination destination;
    private final Delegate delegate;
    private final ClientParser parser;

    public HttpConnectionOverFCGI(EndPoint endPoint, HttpDestination destination)
    {
        super(endPoint, destination.getHttpClient().getExecutor(), destination.getHttpClient().isDispatchIO());
        this.flusher = new Flusher(endPoint);
        this.destination = destination;
        this.delegate = new Delegate(destination);
        this.parser = new ClientParser(new ResponseListener());
        requests.addLast(0);
    }

    public HttpDestination getHttpDestination()
    {
        return destination;
    }

    @Override
    public void send(Request request, Response.CompleteListener listener)
    {
        delegate.send(request, listener);
    }

    protected void send(HttpExchange exchange)
    {
        delegate.send(exchange);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    public void onFillable()
    {
        EndPoint endPoint = getEndPoint();
        HttpClient client = destination.getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        ByteBuffer buffer = bufferPool.acquire(client.getResponseBufferSize(), true);
        try
        {
            while (true)
            {
                int read = endPoint.fill(buffer);
                if (LOG.isDebugEnabled()) // Avoid boxing of variable 'read'
                    LOG.debug("Read {} bytes from {}", read, endPoint);
                if (read > 0)
                {
                    parse(buffer);
                }
                else if (read == 0)
                {
                    fillInterested();
                    break;
                }
                else
                {
                    shutdown();
                    break;
                }
            }
        }
        catch (Exception x)
        {
            LOG.debug(x);
            // TODO: fail and close ?
        }
        finally
        {
            bufferPool.release(buffer);
        }
    }

    private void parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
            parser.parse(buffer);
    }

    private void shutdown()
    {
        // First close then abort, to be sure that the
        // connection cannot be reused from an onFailure()
        // handler or by blocking code waiting for completion.
        close();
        for (HttpChannelOverFCGI channel : channels.values())
            channel.abort(new EOFException());
    }

    @Override
    protected boolean onReadTimeout()
    {
        for (HttpChannelOverFCGI channel : channels.values())
            channel.abort(new TimeoutException());
        close();
        return false;
    }

    public void release()
    {
        if (destination instanceof PoolingHttpDestination)
        {
            @SuppressWarnings("unchecked")
            PoolingHttpDestination<HttpConnectionOverFCGI> fcgiDestination =
                    (PoolingHttpDestination<HttpConnectionOverFCGI>)destination;
            fcgiDestination.release(this);
        }
    }

    @Override
    public void close()
    {
        if (closed.compareAndSet(false, true))
        {
            getHttpDestination().close(this);
            getEndPoint().shutdownOutput();
            LOG.debug("{} oshut", this);
            getEndPoint().close();
            LOG.debug("{} closed", this);
        }
    }

    private int acquireRequest()
    {
        synchronized (requests)
        {
            int last = requests.getLast();
            int request = last + 1;
            requests.addLast(request);
            return request;
        }
    }

    private void releaseRequest(int request)
    {
        synchronized (requests)
        {
            requests.removeFirstOccurrence(request);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%h(l:%s <-> r:%s)",
                getClass().getSimpleName(),
                this,
                getEndPoint().getLocalAddress(),
                getEndPoint().getRemoteAddress());
    }

    private class Delegate extends HttpConnection
    {
        private Delegate(HttpDestination destination)
        {
            super(destination);
        }

        @Override
        protected void send(HttpExchange exchange)
        {
            Request request = exchange.getRequest();
            normalizeRequest(request);

            // FCGI may be multiplexed, so create one channel for each request.
            int id = acquireRequest();
            HttpChannelOverFCGI channel = new HttpChannelOverFCGI(HttpConnectionOverFCGI.this, flusher, id, request.getIdleTimeout());
            channels.put(id, channel);
            channel.associate(exchange);
            channel.send();
        }

        @Override
        public void close()
        {
            HttpConnectionOverFCGI.this.close();
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
            HttpChannelOverFCGI channel = channels.get(request);
            if (channel != null)
                channel.responseBegin(code, reason);
            else
                noChannel(request);
        }

        @Override
        public void onHeader(int request, HttpField field)
        {
            HttpChannelOverFCGI channel = channels.get(request);
            if (channel != null)
                channel.responseHeader(field);
            else
                noChannel(request);
        }

        @Override
        public void onHeaders(int request)
        {
            HttpChannelOverFCGI channel = channels.get(request);
            if (channel != null)
                channel.responseHeaders();
            else
                noChannel(request);
        }

        @Override
        public void onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
        {
            switch (stream)
            {
                case STD_OUT:
                {
                    HttpChannelOverFCGI channel = channels.get(request);
                    if (channel != null)
                        channel.content(buffer);
                    else
                        noChannel(request);
                    break;
                }
                case STD_ERR:
                {
                    LOG.info(BufferUtil.toUTF8String(buffer));
                    break;
                }
                default:
                {
                    throw new IllegalArgumentException();
                }
            }
        }

        @Override
        public void onEnd(int request)
        {
            HttpChannelOverFCGI channel = channels.remove(request);
            if (channel != null)
            {
                channel.responseSuccess();
                releaseRequest(request);
            }
            else
            {
                noChannel(request);
            }
        }

        private void noChannel(int request)
        {
            // TODO: what here ?
        }
    }
}
