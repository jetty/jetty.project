//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server.internal;

import java.io.IOException;
import java.util.function.Consumer;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpChannelOverHTTP3 extends HttpChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelOverHTTP3.class);
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);
    private static final HttpField POWERED_BY = new PreEncodedHttpField(HttpHeader.X_POWERED_BY, HttpConfiguration.SERVER_VERSION);

    private final AutoLock lock = new AutoLock();
    private final HTTP3Stream stream;
    private final ServerHTTP3StreamConnection connection;
    private HttpInput.Content content;
    private boolean expect100Continue;
    private boolean delayedUntilContent;

    public HttpChannelOverHTTP3(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransportOverHTTP3 transport, HTTP3Stream stream, ServerHTTP3StreamConnection connection)
    {
        super(connector, configuration, endPoint, transport);
        this.stream = stream;
        this.connection = connection;
    }

    @Override
    public HttpTransportOverHTTP3 getHttpTransport()
    {
        return (HttpTransportOverHTTP3)super.getHttpTransport();
    }

    @Override
    public void setIdleTimeout(long timeoutMs)
    {
        stream.setIdleTimeout(timeoutMs);
    }

    void consumeInput()
    {
        getRequest().getHttpInput().consumeAll();
    }

    @Override
    public boolean isExpecting100Continue()
    {
        return expect100Continue;
    }

    @Override
    public void continue100(int available) throws IOException
    {
        if (isExpecting100Continue())
        {
            expect100Continue = false;

            // is content missing?
            if (available == 0)
            {
                if (getResponse().isCommitted())
                    throw new IOException("Committed before 100 Continues");

                boolean committed = sendResponse(HttpGenerator.CONTINUE_100_INFO, null, false);
                if (!committed)
                    throw new IOException("Concurrent commit while trying to send 100-Continue");
            }
        }
    }

    public Runnable onRequest(HeadersFrame frame)
    {
        try
        {
            MetaData.Request request = (MetaData.Request)frame.getMetaData();
            HttpFields fields = request.getFields();

            expect100Continue = fields.contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

            HttpFields.Mutable response = getResponse().getHttpFields();
            if (getHttpConfiguration().getSendServerVersion())
                response.add(SERVER_VERSION);
            if (getHttpConfiguration().getSendXPoweredBy())
                response.add(POWERED_BY);

            onRequest(request);

            boolean endStream = frame.isLast();
            if (endStream)
            {
                onContentComplete();
                onRequestComplete();
            }

            boolean connect = request instanceof MetaData.ConnectRequest;
            delayedUntilContent = getHttpConfiguration().isDelayDispatchUntilContent() &&
                !endStream && !expect100Continue && !connect;

            if (connect)
            {
                // Delay the demand of DATA frames for CONNECT with :protocol,
                // since we want the other protocol to trigger content demand.
                if (request.getProtocol() == null)
                    stream.demand();
            }
            else
            {
                // When the dispatch to the application is delayed, then
                // demand for content, so when it arrives we can dispatch.
                if (delayedUntilContent)
                    stream.demand();
                else
                    connection.setApplicationMode(true);
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP3 request #{}/{}, delayed={}:{}{} {} {}{}{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    delayedUntilContent, System.lineSeparator(),
                    request.getMethod(), request.getURI(), request.getHttpVersion(),
                    System.lineSeparator(), fields);
            }

            return delayedUntilContent ? null : this;
        }
        catch (BadMessageException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onRequest() failure", x);
            onBadMessage(x);
            return null;
        }
        catch (Throwable x)
        {
            onBadMessage(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, x));
            return null;
        }
    }

    @Override
    protected void commit(MetaData.Response info)
    {
        super.commit(info);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 commit response #{}/{}:{}{} {} {}{}{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()), System.lineSeparator(), info.getHttpVersion(), info.getStatus(), info.getReason(),
                System.lineSeparator(), info.getFields());
        }
    }

    public Runnable onDataAvailable()
    {
        boolean woken = getRequest().getHttpInput().onContentProducible();
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 request data available #{}/{} woken: {}",
                stream.getId(),
                Integer.toHexString(stream.getSession().hashCode()),
                woken);
        }

        boolean wasDelayed = delayedUntilContent;
        delayedUntilContent = false;

        if (wasDelayed)
            connection.setApplicationMode(true);

        return wasDelayed || woken ? this : null;
    }

    public Runnable onTrailer(HeadersFrame frame)
    {
        HttpFields trailers = frame.getMetaData().getFields();
        if (trailers.size() > 0)
            onTrailers(trailers);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 Request #{}/{}, trailers:{}{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                System.lineSeparator(), trailers);
        }

        // This will generate EOF -> need to call onContentProducible.
        boolean handle = onRequestComplete();
        boolean woken = getRequest().getHttpInput().onContentProducible();
        handle |= woken;

        boolean wasDelayed = delayedUntilContent;
        delayedUntilContent = false;

        if (wasDelayed)
            connection.setApplicationMode(true);

        return wasDelayed || handle ? this : null;
    }

    public boolean onIdleTimeout(Throwable failure, Consumer<Runnable> consumer)
    {
        boolean wasDelayed = delayedUntilContent;
        delayedUntilContent = false;

        if (wasDelayed)
            connection.setApplicationMode(true);

        getHttpTransport().onIdleTimeout(failure);

        boolean neverDispatched = getState().isIdle();
        boolean hasDemand = stream.hasDemand();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 request idle timeout #{}/{}, dispatched={} demand={}",
                stream.getId(),
                Integer.toHexString(stream.getSession().hashCode()),
                !neverDispatched,
                hasDemand);
        }

        if (neverDispatched)
        {
            try (AutoLock l = lock.lock())
            {
                content = new HttpInput.ErrorContent(failure);
            }
            consumer.accept(this::handleWithContext);
        }
        else if (hasDemand)
        {
            try (AutoLock l = lock.lock())
            {
                content = new HttpInput.ErrorContent(failure);
            }
            if (getRequest().getHttpInput().onContentProducible())
                consumer.accept(this::handleWithContext);
        }
        return false;
    }

    private void handleWithContext()
    {
        ContextHandler context = getState().getContextHandler();
        if (context != null)
            context.handle(getRequest(), this);
        else
            handle();
    }

    public Runnable onFailure(Throwable failure)
    {
        consumeInput();

        getHttpTransport().onFailure(failure);

        boolean handle = failed(failure);

        return () ->
        {
            if (handle)
                handleWithContext();
            else if (getHttpConfiguration().isNotifyRemoteAsyncErrors())
                getState().asyncError(failure);
        };
    }

    @Override
    public boolean needContent()
    {
        try (AutoLock l = lock.lock())
        {
            if (content != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("need content has immediate content {} on {}", content, this);
                return true;
            }
        }

        HttpInput.Content result = readContent();

        if (result != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("need content read content {} on {}", this.content, this);
            return true;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("need content demanding content on {}", this);
        stream.demand();
        return false;
    }

    @Override
    public HttpInput.Content produceContent()
    {
        HttpInput.Content result;
        try (AutoLock l = lock.lock())
        {
            result = content;
        }

        if (result == null)
            result = readContent();

        if (result == null)
            return null;

        if (!result.isSpecial())
        {
            HttpInput.Content newContent = result.isEof() ? new HttpInput.EofContent() : null;
            try (AutoLock l = lock.lock())
            {
                content = newContent;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("produced content {} on {}", result, this);
        return result;
    }

    private HttpInput.Content readContent()
    {
        Stream.Data data = stream.readData();
        if (LOG.isDebugEnabled())
            LOG.debug("read data {} on {}", data, this);
        if (data != null)
        {
            HttpInput.Content result = newContent(data);

            boolean handle = onContent(result);

            try (AutoLock l = lock.lock())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("read content {} on {}", result, this);
                content = result;
            }

            if (data.isLast())
            {
                boolean handleContent = onContentComplete();
                // This will generate EOF -> must happen before onContentProducible().
                boolean handleRequest = onRequestComplete();
                handle |= handleContent | handleRequest;
            }

            return result;
        }
        else
        {
            // The call to readData() may have parsed the trailer frame which
            // triggers the content complete event which sets the content to EOF.
            try (AutoLock l = lock.lock())
            {
                return content;
            }
        }
    }

    private HttpInput.Content newContent(Stream.Data data)
    {
        return new DataContent(data);
    }

    @Override
    public boolean failAllContent(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failing all content with {} {}", failure, this);
        // TODO: must read as much as possible to seek EOF.
        HttpInput.Content result;
        try (AutoLock l = lock.lock())
        {
            result = content;
            if (result == null)
                return false;
            if (result.isSpecial())
                return result.isEof();
            content = null;
        }
        result.failed(failure);
        return false;
    }

    @Override
    public boolean failed(Throwable failure)
    {
        HttpInput.Content contentToFail = null;
        try (AutoLock l = lock.lock())
        {
            if (content == null)
            {
                content = new HttpInput.ErrorContent(failure);
            }
            else
            {
                if (content.isSpecial())
                {
                    // Either EOF or error already, no nothing.
                }
                else
                {
                    contentToFail = content;
                    content = new HttpInput.ErrorContent(failure);
                }
            }
        }

        if (contentToFail != null)
            contentToFail.failed(failure);

        return getRequest().getHttpInput().onContentProducible();
    }

    @Override
    protected boolean eof()
    {
        try (AutoLock l = lock.lock())
        {
            if (content == null)
            {
                content = new HttpInput.EofContent();
            }
            else if (!content.isEof())
            {
                if (content.remaining() == 0)
                    content = new HttpInput.EofContent();
                else
                    throw new IllegalStateException();
            }
            return false;
        }
    }

    private static class DataContent extends HttpInput.Content
    {
        private final Stream.Data data;

        public DataContent(Stream.Data data)
        {
            super(data.getByteBuffer());
            this.data = data;
        }

        @Override
        public boolean isEof()
        {
            return data.isLast();
        }

        @Override
        public void succeeded()
        {
            data.complete();
        }

        @Override
        public void failed(Throwable x)
        {
            data.complete();
        }
    }
}
