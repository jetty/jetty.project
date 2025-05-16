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

package org.eclipse.jetty.http2.server.internal;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MetaData.Request;
import org.eclipse.jetty.http2.HTTP2Channel;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.http2.parser.SettingsBodyParser;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2ServerConnection extends HTTP2Connection implements ConnectionMetaData, ServerParser.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2ServerConnection.class);

    private final HttpChannel.Factory httpChannelFactory = new HttpChannel.DefaultFactory();
    // This unbounded queue will always be limited by the max number of concurrent streams per connection.
    private final Queue<HttpChannel> httpChannels = new ConcurrentLinkedQueue<>();
    private final Attributes attributes = new Lazy();
    private final List<Frame> upgradeFrames = new ArrayList<>();
    private final Connector connector;
    private final ServerSessionListener listener;
    private final HttpConfiguration httpConfig;
    private final String id;
    private final SocketAddress localSocketAddress;
    private final SocketAddress remoteSocketAddress;
    private boolean recycleHttpChannels;

    public HTTP2ServerConnection(Connector connector, EndPoint endPoint, HttpConfiguration httpConfig, HTTP2ServerSession session, int inputBufferSize, ServerSessionListener listener)
    {
        super(connector.getByteBufferPool(), connector.getExecutor(), endPoint, session, inputBufferSize);
        this.connector = connector;
        this.listener = listener;
        this.httpConfig = httpConfig;
        this.id = StringUtil.randomAlphaNumeric(16);
        localSocketAddress = httpConfig.getLocalAddress() != null ? httpConfig.getLocalAddress() : endPoint.getLocalSocketAddress();
        remoteSocketAddress = endPoint.getRemoteSocketAddress();
        setRecycleHttpChannels(true);
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
    public HTTP2ServerSession getSession()
    {
        return (HTTP2ServerSession)super.getSession();
    }

    @Override
    public void onOpen()
    {
        HTTP2Session session = getSession();
        session.notifyLifeCycleOpen();
        notifyAccept(session);
        for (Frame frame : upgradeFrames)
        {
            session.onFrame(frame);
        }
        super.onOpen();
        produce();
    }

    private void notifyAccept(HTTP2Session session)
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

    @Override
    public void onPreface()
    {
        getSession().onPreface();
    }

    public void onNewStream(HTTP2Stream stream, HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing {} on {}", frame, stream);

        HttpChannel httpChannel = pollHttpChannel();
        HttpStreamOverHTTP2 httpStream = new HttpStreamOverHTTP2(this, httpChannel, stream);
        httpChannel.setHttpStream(httpStream);
        stream.setAttachment(httpStream);
        Runnable task = httpStream.onRequest(frame);
        if (task != null)
            offerTask(task, false);
    }

    public void onDataAvailable(Stream stream)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing data available on {}", stream);

        HTTP2Channel.Server channel = (HTTP2Channel.Server)((HTTP2Stream)stream).getAttachment();
        if (channel != null)
        {
            Runnable task = channel.onDataAvailable();
            if (task != null)
                offerTask(task, false);
        }
    }

    public void onTrailers(Stream stream, HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing trailers {} on {}", frame, stream);
        HTTP2Channel.Server channel = (HTTP2Channel.Server)((HTTP2Stream)stream).getAttachment();
        if (channel != null)
        {
            Runnable task = channel.onTrailer(frame);
            if (task != null)
                offerTask(task, false);
        }
    }

    public void onStreamTimeout(Stream stream, TimeoutException timeout, Promise<Boolean> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Idle timeout on {}", stream, timeout);
        HTTP2Channel.Server channel = (HTTP2Channel.Server)((HTTP2Stream)stream).getAttachment();
        if (channel == null)
        {
            promise.succeeded(false);
            return;
        }
        channel.onTimeout(timeout, (task, timedOut) ->
        {
            if (task == null)
            {
                promise.succeeded(timedOut);
                return;
            }
            ThreadPool.executeImmediately(getExecutor(), () ->
            {
                try
                {
                    task.run();
                    promise.succeeded(timedOut);
                }
                catch (Throwable x)
                {
                    promise.failed(x);
                }
            });
        });
    }

    public void onStreamFailure(Stream stream, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing stream failure on {}", stream, failure);
        HTTP2Stream http2Stream = (HTTP2Stream)stream;
        HTTP2Channel.Server channel = (HTTP2Channel.Server)(http2Stream).getAttachment();
        if (channel == null)
        {
            HttpChannel httpChannel = pollHttpChannel();
            HttpStreamOverHTTP2 httpStream = new HttpStreamOverHTTP2(this, httpChannel, http2Stream);
            httpChannel.setHttpStream(httpStream);
            http2Stream.setAttachment(httpStream);
            channel = httpStream;
        }
        Runnable task = channel.onFailure(failure, callback);
        // The task may unblock a blocked read or write, so it cannot be
        // queued, because there may be no threads available to run it.
        ThreadPool.executeImmediately(getExecutor(), task);
    }

    public boolean onSessionTimeout(Throwable failure)
    {
        HTTP2Session session = getSession();
        // Compute whether all requests are idle.
        boolean result = session.getStreams().stream()
                .map(stream -> (HTTP2Stream)stream)
                .map(stream -> (HTTP2Channel.Server)stream.getAttachment())
                .filter(Objects::nonNull)
                .map(HTTP2Channel.Server::isIdle)
                .reduce(true, Boolean::logicalAnd);
        if (LOG.isDebugEnabled())
            LOG.debug("{} idle timeout on {}", result ? "Processed" : "Ignored", session, failure);
        return result;
    }

    public void onSessionFailure(Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing session failure on {}", getSession(), failure);
        // All the streams have already been failed, just succeed the callback.
        callback.succeeded();
    }

    public void push(HTTP2Stream stream, MetaData.Request request)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing push {} on {}", request, stream);

        HttpChannel httpChannel = pollHttpChannel();
        HttpStreamOverHTTP2 httpStream = new HttpStreamOverHTTP2(this, httpChannel, stream);
        httpChannel.setHttpStream(httpStream);
        Runnable task = httpStream.onPushRequest(request);
        if (task != null)
            offerTask(task, true);
    }

    private HttpChannel pollHttpChannel()
    {
        HttpChannel httpChannel = null;
        if (isRecycleHttpChannels())
            httpChannel = httpChannels.poll();
        if (httpChannel == null)
            httpChannel = httpChannelFactory.newHttpChannel(this);
        httpChannel.initialize();
        return httpChannel;
    }

    void offerHttpChannel(HttpChannel channel)
    {
        if (isRecycleHttpChannels())
            httpChannels.offer(channel);
    }

    public boolean upgrade(Request request, HttpFields.Mutable responseFields)
    {
        if (HttpMethod.PRI.is(request.getMethod()))
        {
            getSession().directUpgrade();
        }
        else
        {
            HttpField settingsField = request.getHttpFields().getField(HttpHeader.HTTP2_SETTINGS);
            if (settingsField == null)
                throw new BadMessageException("Missing " + HttpHeader.HTTP2_SETTINGS + " header");
            String value = settingsField.getValue();
            final byte[] settings = Base64.getUrlDecoder().decode(value == null ? "" : value);

            if (LOG.isDebugEnabled())
                LOG.debug("{} {}: {}", this, HttpHeader.HTTP2_SETTINGS, StringUtil.toHexString(settings));

            SettingsFrame settingsFrame = SettingsBodyParser.parseBody(BufferUtil.toBuffer(settings));
            if (settingsFrame == null)
            {
                LOG.warn("Invalid {} header value: {}", HttpHeader.HTTP2_SETTINGS, value);
                throw new BadMessageException();
            }

            responseFields.put(HttpHeader.UPGRADE, "h2c");
            responseFields.put(HttpHeader.CONNECTION, "Upgrade");

            getSession().standardUpgrade();

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

    @Override
    public String getId()
    {
        return id;
    }

    @Override
    public HttpConfiguration getHttpConfiguration()
    {
        return httpConfig;
    }

    @Override
    public HttpVersion getHttpVersion()
    {
        return HttpVersion.HTTP_2;
    }

    @Override
    public String getProtocol()
    {
        return getHttpVersion().asString();
    }

    @Override
    public Connection getConnection()
    {
        return this;
    }

    @Override
    public Connector getConnector()
    {
        return connector;
    }

    @Override
    public boolean isPersistent()
    {
        return true;
    }

    @Override
    public boolean isPushSupported()
    {
        return getSession().isPushEnabled();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return remoteSocketAddress;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return localSocketAddress;
    }

    @Override
    public Object getAttribute(String name)
    {
        return attributes.getAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return attributes.setAttribute(name, attribute);
    }

    @Override
    public Object removeAttribute(String name)
    {
        return attributes.removeAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return attributes.getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        attributes.clearAttributes();
    }
}
