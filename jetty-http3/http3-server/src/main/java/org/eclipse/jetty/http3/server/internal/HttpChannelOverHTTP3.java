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

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpChannelOverHTTP3 extends HttpChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelOverHTTP3.class);

    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);
    private static final HttpField POWERED_BY = new PreEncodedHttpField(HttpHeader.X_POWERED_BY, HttpConfiguration.SERVER_VERSION);

    private final HTTP3Stream stream;
    private final ServerHTTP3StreamConnection connection;
    private HttpInput.Content content;
    private boolean expect100Continue;
    private boolean delayedUntilContent;

    public HttpChannelOverHTTP3(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HTTP3Stream stream, ServerHTTP3StreamConnection connection)
    {
        super(connector, configuration, endPoint, transport);
        this.stream = stream;
        this.connection = connection;
    }

    void consumeInput()
    {
        getRequest().getHttpInput().consumeAll();
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

            // Delay the demand of DATA frames for CONNECT with :protocol
            // or for normal requests expecting 100 continue.
            if (connect)
            {
                if (request.getProtocol() == null)
                    stream.demand();
            }
            else
            {
                if (delayedUntilContent)
                    stream.demand();
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP3 Request #{}/{}, delayed={}:{}{} {} {}{}{}",
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
                LOG.debug("onRequest", x);
            onBadMessage(x);
            return null;
        }
        catch (Throwable x)
        {
            onBadMessage(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, x));
            return null;
        }
    }

    public Runnable onDataAvailable()
    {
        Stream.Data data = stream.readData();
        if (data == null)
        {
            stream.demand();
            return null;
        }

        ByteBuffer buffer = data.getByteBuffer();
        int length = buffer.remaining();
        HttpInput.Content content = new HttpInput.Content(buffer)
        {
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
        };

        this.content = content;
        boolean handle = onContent(content);

        boolean isLast = data.isLast();
        if (isLast)
        {
            boolean handleContent = onContentComplete();
            // This will generate EOF -> must happen before onContentProducible.
            boolean handleRequest = onRequestComplete();
            handle |= handleContent | handleRequest;
        }

        boolean woken = getRequest().getHttpInput().onContentProducible();
        handle |= woken;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 Request #{}/{}: {} bytes of {} content, woken: {}, handle: {}",
                stream.getId(),
                Integer.toHexString(stream.getSession().hashCode()),
                length,
                isLast ? "last" : "some",
                woken,
                handle);
        }

        boolean wasDelayed = delayedUntilContent;
        delayedUntilContent = false;
        return handle || wasDelayed ? this : null;
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
        return handle || wasDelayed ? this : null;
    }

    public boolean onIdleTimeout(Throwable failure, Consumer<Runnable> consumer)
    {
        boolean delayed = delayedUntilContent;
        delayedUntilContent = false;

        boolean reset = getState().isIdle();
        if (reset)
            consumeInput();

        //TODO
//        getHttpTransport().onStreamTimeout(failure);

        failure.addSuppressed(new Throwable("HttpInput idle timeout"));
        // TODO: writing to the content field here is at race with demand?
        if (content == null)
            content = new HttpInput.ErrorContent(failure);
        boolean needed = getRequest().getHttpInput().onContentProducible();

        if (needed || delayed)
        {
            consumer.accept(this::handleWithContext);
            reset = false;
        }

        return reset;
    }

    private void handleWithContext()
    {
        ContextHandler context = getState().getContextHandler();
        if (context != null)
            context.handle(getRequest(), this);
        else
            handle();
    }

    public void onFailure(Throwable failure)
    {
        //TODO
//        getHttpTransport().onStreamFailure(failure);
//        boolean handle = failed(failure);
//        consumeInput();
//        return new FailureTask(failure, callback, handle);
    }

    @Override
    public boolean needContent()
    {
        if (content != null)
            return true;

        MessageParser.Result result = connection.parseAndFill();
        if (result == MessageParser.Result.FRAME)
        {
            DataFrame dataFrame = connection.pollContent();
            content = new HttpInput.Content(dataFrame.getByteBuffer())
            {
                @Override
                public boolean isEof()
                {
                    return dataFrame.isLast();
                }
            };
            return true;
        }
        else
        {
            stream.demand();
            return false;
        }
    }

    @Override
    public HttpInput.Content produceContent()
    {
        HttpInput.Content result = content;
        if (result != null && !result.isSpecial())
            content = result.isEof() ? new HttpInput.EofContent() : null;
        return result;
    }

    @Override
    public boolean failAllContent(Throwable failure)
    {
        return false;
    }

    @Override
    public boolean failed(Throwable failure)
    {
        return false;
    }

    @Override
    protected boolean eof()
    {
        return false;
    }
}
