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

package org.eclipse.jetty.http2.server;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MetaData.Request;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Channel;
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
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.AutoLock;

public class HTTP2ServerConnection extends HTTP2Connection
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

    private final AutoLock lock = new AutoLock();
    private final Queue<HttpChannelOverHTTP2> channels = new ArrayDeque<>();
    private final List<Frame> upgradeFrames = new ArrayList<>();
    private final ServerSessionListener listener;
    private final HttpConfiguration httpConfig;
    private boolean recycleHttpChannels = true;

    public HTTP2ServerConnection(RetainableByteBufferPool retainableByteBufferPool, Executor executor, EndPoint endPoint, HttpConfiguration httpConfig, ServerParser parser, ISession session, int inputBufferSize, ServerSessionListener listener)
    {
        super(retainableByteBufferPool, executor, endPoint, parser, session, inputBufferSize);
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
    public void onOpen()
    {
        ISession session = getSession();
        notifyAccept(session);
        for (Frame frame : upgradeFrames)
        {
            session.onFrame(frame);
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
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    public void onNewStream(Connector connector, IStream stream, HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing {} on {}", frame, stream);
        HttpChannelOverHTTP2 channel = provideHttpChannel(connector, stream);
        Runnable task = channel.onRequest(frame);
        if (task != null)
        {
            if (isUseVirtualThreadToInvokeRootHandler())
            {
                if (VirtualThreads.startVirtualThread(task))
                    return;
            }
            offerTask(task, false);
        }
    }

    public void onData(IStream stream, DataFrame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing {} on {}", frame, stream);
        HTTP2Channel.Server channel = (HTTP2Channel.Server)stream.getAttachment();
        if (channel != null)
        {
            Runnable task = channel.onData(frame, callback);
            if (task != null)
            {
                if (isUseVirtualThreadToInvokeRootHandler())
                {
                    if (VirtualThreads.startVirtualThread(task))
                        return;
                }
                offerTask(task, false);
            }
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
        HTTP2Channel.Server channel = (HTTP2Channel.Server)stream.getAttachment();
        if (channel != null)
        {
            Runnable task = channel.onTrailer(frame);
            if (task != null)
            {
                if (isUseVirtualThreadToInvokeRootHandler())
                {
                    if (VirtualThreads.startVirtualThread(task))
                        return;
                }
                offerTask(task, false);
            }
        }
    }

    public boolean onStreamTimeout(IStream stream, Throwable failure)
    {
        HTTP2Channel.Server channel = (HTTP2Channel.Server)stream.getAttachment();
        boolean result = channel != null && channel.onTimeout(failure, task -> offerTask(task, true));
        if (LOG.isDebugEnabled())
            LOG.debug("{} idle timeout on {}: {}", result ? "Processed" : "Ignored", stream, failure);
        return result;
    }

    public void onStreamFailure(IStream stream, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing stream failure on {}", stream, failure);
        HTTP2Channel.Server channel = (HTTP2Channel.Server)stream.getAttachment();
        if (channel != null)
        {
            Runnable task = channel.onFailure(failure, callback);
            if (task != null)
            {
                if (isUseVirtualThreadToInvokeRootHandler())
                {
                    if (VirtualThreads.startVirtualThread(task))
                        return;
                }
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
                .map(stream -> (HTTP2Channel.Server)stream.getAttachment())
                .filter(Objects::nonNull)
                .map(HTTP2Channel.Server::isIdle)
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
        {
            if (isUseVirtualThreadToInvokeRootHandler())
            {
                if (VirtualThreads.startVirtualThread(task))
                    return;
            }
            offerTask(task, true);
        }
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
            channel.setUseOutputDirectByteBuffers(isUseOutputDirectByteBuffers());
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
            try (AutoLock l = lock.lock())
            {
                channels.offer(channel);
            }
        }
    }

    private HttpChannelOverHTTP2 pollHttpChannel()
    {
        if (isRecycleHttpChannels())
        {
            try (AutoLock l = lock.lock())
            {
                return channels.poll();
            }
        }
        else
        {
            return null;
        }
    }

    public boolean upgrade(Request request, HttpFields.Mutable responseFields)
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
                LOG.debug("{} {}: {}", this, HttpHeader.HTTP2_SETTINGS, TypeUtil.toHexString(settings));

            SettingsFrame settingsFrame = SettingsBodyParser.parseBody(BufferUtil.toBuffer(settings));
            if (settingsFrame == null)
            {
                LOG.warn("Invalid {} header value: {}", HttpHeader.HTTP2_SETTINGS, value);
                throw new BadMessageException();
            }

            responseFields.put(HttpHeader.UPGRADE, "h2c");
            responseFields.put(HttpHeader.CONNECTION, "Upgrade");

            getParser().standardUpgrade();

            // We fake that we received a client preface, so that we can send the
            // server preface as the first HTTP/2 frame as required by the spec.
            // When the client sends the real preface, the parser won't notify it.
            upgradeFrames.add(new PrefaceFrame());
            // This is the settings from the HTTP2-Settings header.
            upgradeFrames.add(settingsFrame);
            // Remember the request to send a response.
            upgradeFrames.add(new HeadersFrame(1, request, null, true));
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
        protected boolean checkAndPrepareUpgrade()
        {
            return isTunnel() && getHttpTransport().prepareUpgrade();
        }

        @Override
        public void onCompleted()
        {
            super.onCompleted();
            if (!getStream().isReset() && !isTunnel())
                recycle();
        }

        private boolean isTunnel()
        {
            return MetaData.isTunnel(getRequest().getMethod(), getResponse().getStatus());
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
