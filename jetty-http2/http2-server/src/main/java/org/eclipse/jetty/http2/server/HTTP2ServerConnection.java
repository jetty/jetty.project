//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

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
import org.eclipse.jetty.http2.api.Stream;
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
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.ExecutionStrategy;

public class HTTP2ServerConnection extends HTTP2Connection implements Connection.UpgradeTo
{
    private final Queue<HttpChannelOverHTTP2> channels = new ArrayDeque<>();
    private final List<Frame> upgradeFrames = new ArrayList<>();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalResponses = new AtomicLong();
    private final ServerSessionListener listener;
    private final HttpConfiguration httpConfig;

    public HTTP2ServerConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, HttpConfiguration httpConfig, ServerParser parser, ISession session, int inputBufferSize, ServerSessionListener listener)
    {
        this(byteBufferPool, executor, endPoint, httpConfig, parser, session, inputBufferSize, ExecutionStrategy.Factory.getDefault(), listener);
    }

    public HTTP2ServerConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, HttpConfiguration httpConfig, ServerParser parser, ISession session, int inputBufferSize, ExecutionStrategy.Factory executionFactory, ServerSessionListener listener)
    {
        super(byteBufferPool, executor, endPoint, parser, session, inputBufferSize, executionFactory);
        this.listener = listener;
        this.httpConfig = httpConfig;
    }

    @Override
    public int getMessagesIn()
    {
        return totalRequests.intValue();
    }

    @Override
    public int getMessagesOut()
    {
        return totalResponses.intValue();
    }

    @Override
    protected ServerParser getParser()
    {
        return (ServerParser)super.getParser();
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
            getSession().onFrame(frame);
        super.onOpen();
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
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttribute(IStream.CHANNEL_ATTRIBUTE);
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

    public boolean onStreamTimeout(IStream stream, Throwable failure)
    {
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttribute(IStream.CHANNEL_ATTRIBUTE);
        boolean result = channel != null && channel.onStreamTimeout(failure);
        if (LOG.isDebugEnabled())
            LOG.debug("{} idle timeout on {}: {}", result ? "Processed" : "Ignored", stream, failure);
        return result;
    }

    public void onStreamFailure(IStream stream, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing failure on {}: {}", stream, failure);
        HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttribute(IStream.CHANNEL_ATTRIBUTE);
        if (channel != null)
            channel.onFailure(failure);
    }

    public boolean onSessionTimeout(Throwable failure)
    {
        ISession session = getSession();
        boolean result = true;
        for (Stream stream : session.getStreams())
        {
            HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttribute(IStream.CHANNEL_ATTRIBUTE);
            if (channel != null)
                result &= !channel.isRequestExecuting();
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} idle timeout on {}: {}", result ? "Processed" : "Ignored", session, failure);
        return result;
    }

    public void onSessionFailure(Throwable failure)
    {
        ISession session = getSession();
        if (LOG.isDebugEnabled())
            LOG.debug("Processing failure on {}: {}", session, failure);
        for (Stream stream : session.getStreams())
            onStreamFailure((IStream)stream, failure);
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
        HttpChannelOverHTTP2 channel = pollChannel();
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
        stream.setAttribute(IStream.CHANNEL_ATTRIBUTE, channel);
        return channel;
    }

    protected ServerHttpChannelOverHTTP2 newServerHttpChannelOverHTTP2(Connector connector, HttpConfiguration httpConfig, HttpTransportOverHTTP2 transport)
    {
        return new ServerHttpChannelOverHTTP2(connector, httpConfig, getEndPoint(), transport);
    }

    private void offerChannel(HttpChannelOverHTTP2 channel)
    {
        synchronized (this)
        {
            channels.offer(channel);
        }
    }

    private HttpChannelOverHTTP2 pollChannel()
    {
        synchronized (this)
        {
            return channels.poll();
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
            final byte[] settings = B64Code.decodeRFC4648URL(value == null ? "" : value);

            if (LOG.isDebugEnabled())
                LOG.debug("{} settings {}",this,TypeUtil.toHexString(settings));

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

    protected class ServerHttpChannelOverHTTP2 extends HttpChannelOverHTTP2 implements ExecutionStrategy.Rejectable
    {
        public ServerHttpChannelOverHTTP2(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransportOverHTTP2 transport)
        {
            super(connector, configuration, endPoint, transport);
        }

        @Override
        public Runnable onRequest(HeadersFrame frame)
        {
            totalRequests.incrementAndGet();
            return super.onRequest(frame);
        }

        @Override
        public void onCompleted()
        {
            totalResponses.incrementAndGet();
            super.onCompleted();
            if (!getStream().isReset())
                recycle();
        }

        @Override
        public void recycle()
        {
            getStream().removeAttribute(IStream.CHANNEL_ATTRIBUTE);
            super.recycle();
            offerChannel(this);
        }

        @Override
        public void reject()
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
