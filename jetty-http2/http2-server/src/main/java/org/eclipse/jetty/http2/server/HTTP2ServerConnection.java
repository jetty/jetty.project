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

package org.eclipse.jetty.http2.server;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MetaData.Request;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.http2.parser.SettingsBodyParser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;

public class HTTP2ServerConnection extends HTTP2Connection implements Connection.UpgradeTo
{
    /**
     * @param protocol An HTTP2 protocol variant
     * @return True if the protocol version is supported
     */
    public static boolean isSupportedProtocol(String protocol)
    {
        switch (protocol)
        {
            case "h2":
            case "h2-17":
            case "h2-16":
            case "h2-15":
            case "h2-14":
            case "h2c":
            case "h2c-17":
            case "h2c-16":
            case "h2c-15":
            case "h2c-14":
                return true;
            default:
                return false;
        }
    }

    private final Queue<HttpChannelOverHTTP2> channels = new ArrayDeque<>();
    private final List<Frame> upgradeFrames = new ArrayList<>();
    private final ServerSessionListener listener;
    private final HttpConfiguration httpConfig;
    private boolean recycleHttpChannels = true;

    public HTTP2ServerConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, HttpConfiguration httpConfig, ServerParser parser, ISession session, int inputBufferSize, ServerSessionListener listener)
    {
        super(byteBufferPool, executor, endPoint, parser, session, inputBufferSize);
        this.listener = listener;
        this.httpConfig = httpConfig;
    }

    @Override
    protected ServerParser getParser()
    {
        return (ServerParser)super.getParser();
    }

    public boolean isRecycleHttpChannels()
    {
        return recycleHttpChannels;
    }

    public void setRecycleHttpChannels(boolean recycleHttpChannels)
    {
        this.recycleHttpChannels = recycleHttpChannels;
    }

    @Override
    public void onUpgradeTo(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 onUpgradeTo {} {}", this, BufferUtil.toDetailString(buffer));
        setInputBuffer(buffer);
    }

    @Override
    public void onOpen()
    {
        notifyAccept(getSession());
        for (Frame frame : upgradeFrames)
        {
            getSession().onFrame(frame);
        }
        super.onOpen();
        produce();
    }

    private void notifyAccept(ISession session)
    {
        try
        {
            listener.onAccept(session);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    public void onNewStream(Connector connector, IStream stream, HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing {} on {}", frame, stream);
        HttpChannelOverHTTP2 channel = provideHttpChannel(connector, stream);
        Runnable task = channel.onRequest(frame);
        if (task != null)
            offerTask(task, false);
    }

    public void onData(IStream stream, DataFrame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing {} on {}", frame, stream);
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttachment();
        if (channel != null)
        {
            Runnable task = channel.onRequestContent(frame, callback);
            if (task != null)
                offerTask(task, false);
        }
        else
        {
            callback.failed(new IOException("channel_not_found"));
        }
    }

    public void onTrailers(IStream stream, HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing trailers {} on {}", frame, stream);
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttachment();
        if (channel != null)
        {
            Runnable task = channel.onRequestTrailers(frame);
            if (task != null)
                offerTask(task, false);
        }
    }

    public boolean onStreamTimeout(IStream stream, Throwable failure)
    {
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttachment();
        boolean result = channel != null && channel.onStreamTimeout(failure, task -> offerTask(task, true));
        if (LOG.isDebugEnabled())
            LOG.debug("{} idle timeout on {}: {}", result ? "Processed" : "Ignored", stream, failure);
        return result;
    }

    public void onStreamFailure(IStream stream, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing stream failure on {}", stream, failure);
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttachment();
        if (channel != null)
        {
            Runnable task = channel.onFailure(failure, callback);
            if (task != null)
            {
                // We must dispatch to another thread because the task
                // may call application code that performs blocking I/O.
                offerTask(task, true);
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    public boolean onSessionTimeout(Throwable failure)
    {
        ISession session = getSession();
        // Compute whether all requests are idle.
        boolean result = session.getStreams().stream()
            .map(stream -> (IStream)stream)
            .map(stream -> (HttpChannelOverHTTP2)stream.getAttachment())
            .filter(Objects::nonNull)
            .map(HttpChannelOverHTTP2::isRequestIdle)
            .reduce(true, Boolean::logicalAnd);
        if (LOG.isDebugEnabled())
            LOG.debug("{} idle timeout on {}: {}", result ? "Processed" : "Ignored", session, failure);
        return result;
    }

    public void onSessionFailure(Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing session failure on {}", getSession(), failure);
        // All the streams have already been failed, just succeed the callback.
        callback.succeeded();
    }

    public void push(Connector connector, IStream stream, MetaData.Request request)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing push {} on {}", request, stream);
        HttpChannelOverHTTP2 channel = provideHttpChannel(connector, stream);
        Runnable task = channel.onPushRequest(request);
        if (task != null)
            offerTask(task, true);
    }

    private HttpChannelOverHTTP2 provideHttpChannel(Connector connector, IStream stream)
    {
        HttpChannelOverHTTP2 channel = pollHttpChannel();
        if (channel != null)
        {
            channel.getHttpTransport().setStream(stream);
            if (LOG.isDebugEnabled())
                LOG.debug("Recycling channel {} for {}", channel, this);
        }
        else
        {
            HttpTransportOverHTTP2 transport = new HttpTransportOverHTTP2(connector, this);
            transport.setStream(stream);
            channel = newServerHttpChannelOverHTTP2(connector, httpConfig, transport);
            if (LOG.isDebugEnabled())
                LOG.debug("Creating channel {} for {}", channel, this);
        }
        stream.setAttachment(channel);
        return channel;
    }

    protected ServerHttpChannelOverHTTP2 newServerHttpChannelOverHTTP2(Connector connector, HttpConfiguration httpConfig, HttpTransportOverHTTP2 transport)
    {
        return new ServerHttpChannelOverHTTP2(connector, httpConfig, getEndPoint(), transport);
    }

    private void offerHttpChannel(HttpChannelOverHTTP2 channel)
    {
        if (isRecycleHttpChannels())
        {
            synchronized (this)
            {
                channels.offer(channel);
            }
        }
    }

    private HttpChannelOverHTTP2 pollHttpChannel()
    {
        if (isRecycleHttpChannels())
        {
            synchronized (this)
            {
                return channels.poll();
            }
        }
        else
        {
            return null;
        }
    }

    public boolean upgrade(Request request)
    {
        if (HttpMethod.PRI.is(request.getMethod()))
        {
            getParser().directUpgrade();
        }
        else
        {
            HttpField settingsField = request.getFields().getField(HttpHeader.HTTP2_SETTINGS);
            if (settingsField == null)
                throw new BadMessageException("Missing " + HttpHeader.HTTP2_SETTINGS + " header");
            String value = settingsField.getValue();
            final byte[] settings = Base64.getUrlDecoder().decode(value == null ? "" : value);

            if (LOG.isDebugEnabled())
                LOG.debug("{} settings {}", this, TypeUtil.toHexString(settings));

            SettingsFrame settingsFrame = SettingsBodyParser.parseBody(BufferUtil.toBuffer(settings));
            if (settingsFrame == null)
            {
                LOG.warn("Invalid {} header value: {}", HttpHeader.HTTP2_SETTINGS, value);
                throw new BadMessageException();
            }

            getParser().standardUpgrade();

            upgradeFrames.add(new PrefaceFrame());
            upgradeFrames.add(settingsFrame);
            // Remember the request to send a response from onOpen().
            upgradeFrames.add(new HeadersFrame(1, new Request(request), null, true));
        }
        return true;
    }

    protected class ServerHttpChannelOverHTTP2 extends HttpChannelOverHTTP2 implements Closeable
    {
        public ServerHttpChannelOverHTTP2(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransportOverHTTP2 transport)
        {
            super(connector, configuration, endPoint, transport);
        }

        @Override
        public void onCompleted()
        {
            super.onCompleted();
            if (!getStream().isReset())
                recycle();
        }

        @Override
        public void recycle()
        {
            getStream().setAttachment(null);
            super.recycle();
            offerHttpChannel(this);
        }

        @Override
        public void close()
        {
            IStream stream = getStream();
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 Request #{}/{} rejected", stream.getId(), Integer.toHexString(stream.getSession().hashCode()));
            stream.reset(new ResetFrame(stream.getId(), ErrorCode.ENHANCE_YOUR_CALM_ERROR.code), Callback.NOOP);
            // Consume the existing queued data frames to
            // avoid stalling the session flow control.
            consumeInput();
        }
    }
}
