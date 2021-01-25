//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.fcgi.server;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.parser.ServerParser;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ServerFCGIConnection extends AbstractConnection
{
    private static final Logger LOG = Log.getLogger(ServerFCGIConnection.class);

    private final ConcurrentMap<Integer, HttpChannelOverFCGI> channels = new ConcurrentHashMap<>();
    private final Connector connector;
    private final boolean sendStatus200;
    private final Flusher flusher;
    private final HttpConfiguration configuration;
    private final ServerParser parser;

    public ServerFCGIConnection(Connector connector, EndPoint endPoint, HttpConfiguration configuration, boolean sendStatus200)
    {
        super(endPoint, connector.getExecutor());
        this.connector = connector;
        this.flusher = new Flusher(endPoint);
        this.configuration = configuration;
        this.sendStatus200 = sendStatus200;
        this.parser = new ServerParser(new ServerListener());
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
        ByteBufferPool bufferPool = connector.getByteBufferPool();
        ByteBuffer buffer = bufferPool.acquire(configuration.getResponseHeaderSize(), true);
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
                    bufferPool.release(buffer);
                    fillInterested();
                    break;
                }
                else
                {
                    bufferPool.release(buffer);
                    shutdown();
                    break;
                }
            }
        }
        catch (Exception x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            bufferPool.release(buffer);
            // TODO: fail and close ?
        }
    }

    @Override
    protected boolean onReadTimeout(Throwable timeout)
    {
        return channels.values().stream()
            .mapToInt(channel -> channel.onIdleTimeout(timeout) ? 0 : 1)
            .sum() == 0;
    }

    private void parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            parser.parse(buffer);
        }
    }

    private void shutdown()
    {
        flusher.shutdown();
    }

    private class ServerListener implements ServerParser.Listener
    {
        @Override
        public void onStart(int request, FCGI.Role role, int flags)
        {
            // TODO: handle flags
            HttpChannelOverFCGI channel = new HttpChannelOverFCGI(connector, configuration, getEndPoint(),
                new HttpTransportOverFCGI(connector.getByteBufferPool(), flusher, request, sendStatus200));
            HttpChannelOverFCGI existing = channels.putIfAbsent(request, channel);
            if (existing != null)
                throw new IllegalStateException();
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} start on {}", request, channel);
        }

        @Override
        public void onHeader(int request, HttpField field)
        {
            HttpChannelOverFCGI channel = channels.get(request);
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} header {} on {}", request, field, channel);
            if (channel != null)
                channel.header(field);
        }

        @Override
        public boolean onHeaders(int request)
        {
            HttpChannelOverFCGI channel = channels.get(request);
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} headers on {}", request, channel);
            if (channel != null)
            {
                channel.onRequest();
                channel.dispatch();
            }
            return false;
        }

        @Override
        public boolean onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
        {
            HttpChannelOverFCGI channel = channels.get(request);
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} {} content {} on {}", request, stream, buffer, channel);
            if (channel != null)
            {
                ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());
                copy.put(buffer).flip();
                channel.onContent(new HttpInput.Content(copy));
            }
            return false;
        }

        @Override
        public void onEnd(int request)
        {
            HttpChannelOverFCGI channel = channels.remove(request);
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} end on {}", request, channel);
            if (channel != null)
            {
                channel.onContentComplete();
                channel.onRequestComplete();
            }
        }

        @Override
        public void onFailure(int request, Throwable failure)
        {
            HttpChannelOverFCGI channel = channels.remove(request);
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} failure on {}: {}", request, channel, failure);
            if (channel != null)
                channel.onBadMessage(new BadMessageException(HttpStatus.BAD_REQUEST_400, null, failure));
        }
    }
}
