//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.server;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
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
import org.eclipse.jetty.http2.HTTP2Channel;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpChannelOverHTTP2 extends HttpChannel implements Closeable, WriteFlusher.Listener, HTTP2Channel.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelOverHTTP2.class);
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);
    private static final HttpField POWERED_BY = new PreEncodedHttpField(HttpHeader.X_POWERED_BY, HttpConfiguration.SERVER_VERSION);

    private boolean _expect100Continue;
    private boolean _delayedUntilContent;
    private boolean _useOutputDirectByteBuffers;

    public HttpChannelOverHTTP2(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransportOverHTTP2 transport)
    {
        super(connector, configuration, endPoint, transport);
    }

    protected IStream getStream()
    {
        return getHttpTransport().getStream();
    }

    @Override
    public boolean isUseOutputDirectByteBuffers()
    {
        return _useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        _useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @Override
    public boolean isExpecting100Continue()
    {
        return _expect100Continue;
    }

    @Override
    public void setIdleTimeout(long timeoutMs)
    {
        getStream().setIdleTimeout(timeoutMs);
    }

    @Override
    public long getIdleTimeout()
    {
        return getStream().getIdleTimeout();
    }

    @Override
    public void onFlushed(long bytes) throws IOException
    {
        getResponse().getHttpOutput().onFlushed(bytes);
    }

    public Runnable onRequest(HeadersFrame frame)
    {
        try
        {
            MetaData.Request request = (MetaData.Request)frame.getMetaData();
            HttpFields fields = request.getFields();

            _expect100Continue = fields.contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

            HttpFields.Mutable response = getResponse().getHttpFields();
            if (getHttpConfiguration().getSendServerVersion())
                response.add(SERVER_VERSION);
            if (getHttpConfiguration().getSendXPoweredBy())
                response.add(POWERED_BY);

            onRequest(request);

            boolean endStream = frame.isEndStream();
            if (endStream)
            {
                onContentComplete();
                onRequestComplete();
            }

            boolean connect = request instanceof MetaData.ConnectRequest;
            _delayedUntilContent = getHttpConfiguration().isDelayDispatchUntilContent() &&
                    !endStream && !_expect100Continue && !connect;

            // Delay the demand of DATA frames for CONNECT with :protocol.
            if (!connect || request.getProtocol() == null)
                getStream().demand(1);

            if (LOG.isDebugEnabled())
            {
                Stream stream = getStream();
                LOG.debug("HTTP2 Request #{}/{}, delayed={}:{}{} {} {}{}{}",
                        stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                        _delayedUntilContent, System.lineSeparator(),
                        request.getMethod(), request.getURI(), request.getHttpVersion(),
                        System.lineSeparator(), fields);
            }

            return _delayedUntilContent ? null : this;
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

    public Runnable onPushRequest(MetaData.Request request)
    {
        try
        {
            onRequest(request);
            getRequest().setAttribute("org.eclipse.jetty.pushed", Boolean.TRUE);
            onContentComplete();
            onRequestComplete();

            if (LOG.isDebugEnabled())
            {
                Stream stream = getStream();
                LOG.debug("HTTP2 PUSH Request #{}/{}:{}{} {} {}{}{}",
                        stream.getId(), Integer.toHexString(stream.getSession().hashCode()), System.lineSeparator(),
                        request.getMethod(), request.getURI(), request.getHttpVersion(),
                        System.lineSeparator(), request.getFields());
            }

            return this;
        }
        catch (BadMessageException x)
        {
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
    public HttpTransportOverHTTP2 getHttpTransport()
    {
        return (HttpTransportOverHTTP2)super.getHttpTransport();
    }

    @Override
    public void recycle()
    {
        _expect100Continue = false;
        _delayedUntilContent = false;
        super.recycle();
        getHttpTransport().recycle();
    }

    @Override
    protected void commit(MetaData.Response info)
    {
        super.commit(info);
        if (LOG.isDebugEnabled())
        {
            Stream stream = getStream();
            LOG.debug("HTTP2 Commit Response #{}/{}:{}{} {} {}{}{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()), System.lineSeparator(), info.getHttpVersion(), info.getStatus(), info.getReason(),
                    System.lineSeparator(), info.getFields());
        }
    }

    @Override
    public Runnable onData(DataFrame frame, Callback callback)
    {
        return onRequestContent(frame, callback);
    }

    public Runnable onRequestContent(DataFrame frame, final Callback callback)
    {
        Stream stream = getStream();
        if (stream.isReset())
        {
            // Consume previously queued content to
            // enlarge the session flow control window.
            consumeInput();
            // Consume immediately this content.
            callback.succeeded();
            return null;
        }

        ByteBuffer buffer = frame.getData();
        int length = buffer.remaining();
        boolean handle = onContent(new HttpInput.Content(buffer)
        {
            @Override
            public void succeeded()
            {
                callback.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                callback.failed(x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return callback.getInvocationType();
            }
        });

        boolean endStream = frame.isEndStream();
        if (endStream)
        {
            boolean handleContent = onContentComplete();
            boolean handleRequest = onRequestComplete();
            handle |= handleContent | handleRequest;
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Request #{}/{}: {} bytes of {} content, handle: {}",
                    stream.getId(),
                    Integer.toHexString(stream.getSession().hashCode()),
                    length,
                    endStream ? "last" : "some",
                    handle);
        }

        boolean wasDelayed = _delayedUntilContent;
        _delayedUntilContent = false;
        return handle || wasDelayed ? this : null;
    }

    @Override
    public Runnable onTrailer(HeadersFrame frame)
    {
        HttpFields trailers = frame.getMetaData().getFields();
        if (trailers.size() > 0)
            onTrailers(trailers);

        if (LOG.isDebugEnabled())
        {
            Stream stream = getStream();
            LOG.debug("HTTP2 Request #{}/{}, trailers:{}{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    System.lineSeparator(), trailers);
        }

        boolean handle = onRequestComplete();

        boolean wasDelayed = _delayedUntilContent;
        _delayedUntilContent = false;
        return handle || wasDelayed ? this : null;
    }

    @Override
    public boolean isIdle()
    {
        return getState().isIdle();
    }

    @Override
    public boolean onTimeout(Throwable failure, Consumer<Runnable> consumer)
    {
        final boolean delayed = _delayedUntilContent;
        _delayedUntilContent = false;

        boolean result = isIdle();
        if (result)
            consumeInput();

        getHttpTransport().onStreamTimeout(failure);
        if (getRequest().getHttpInput().onIdleTimeout(failure) || delayed)
        {
            consumer.accept(this::handleWithContext);
            result = false;
        }

        return result;
    }

    @Override
    public Runnable onFailure(Throwable failure, Callback callback)
    {
        getHttpTransport().onStreamFailure(failure);
        boolean handle = getRequest().getHttpInput().failed(failure);
        consumeInput();
        return new FailureTask(failure, callback, handle);
    }

    protected void consumeInput()
    {
        getRequest().getHttpInput().consumeAll();
    }

    private void handleWithContext()
    {
        ContextHandler context = getState().getContextHandler();
        if (context != null)
            context.handle(getRequest(), this);
        else
            handle();
    }

    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @throws IOException if the InputStream cannot be created
     */
    @Override
    public void continue100(int available) throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        // TODO: consider using an AtomicBoolean ?
        if (isExpecting100Continue())
        {
            _expect100Continue = false;

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

    @Override
    public boolean isTunnellingSupported()
    {
        return true;
    }

    @Override
    public EndPoint getTunnellingEndPoint()
    {
        return new ServerHTTP2StreamEndPoint(getStream());
    }

    @Override
    public void close()
    {
        abort(new IOException("Unexpected close"));
    }

    @Override
    public String toString()
    {
        IStream stream = getStream();
        long streamId = stream == null ? -1 : stream.getId();
        return String.format("%s#%d", super.toString(), streamId);
    }

    private class FailureTask implements Runnable
    {
        private final Throwable failure;
        private final Callback callback;
        private final boolean handle;

        public FailureTask(Throwable failure, Callback callback, boolean handle)
        {
            this.failure = failure;
            this.callback = callback;
            this.handle = handle;
        }

        @Override
        public void run()
        {
            try
            {
                if (handle)
                    handleWithContext();
                else if (getHttpConfiguration().isNotifyRemoteAsyncErrors())
                    getState().asyncError(failure);
                callback.succeeded();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getName(), hashCode(), failure);
        }
    }
}
