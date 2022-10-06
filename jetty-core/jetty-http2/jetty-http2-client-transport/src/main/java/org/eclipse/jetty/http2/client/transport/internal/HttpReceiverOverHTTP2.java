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

package org.eclipse.jetty.http2.client.transport.internal;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.internal.HTTP2Channel;
import org.eclipse.jetty.http2.internal.HTTP2Stream;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpReceiverOverHTTP2 extends HttpReceiver implements HTTP2Channel.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverOverHTTP2.class);

    private final AtomicBoolean firstContent = new AtomicBoolean(true);
    private ContentSource contentSource;

    public HttpReceiverOverHTTP2(HttpChannel channel)
    {
        super(channel);
    }

    @Override
    protected Content.Source newContentSource()
    {
        contentSource = new ContentSource();
        return contentSource;
    }

    @Override
    protected void reset()
    {
        firstContent.set(true);
        super.reset();
    }

    private class ContentSource implements Content.Source
    {
        private volatile Content.Chunk currentChunk;
        private volatile Runnable demandCallback;
        private volatile boolean reachedEndStream = false;
        private volatile boolean readEof = false;

        @Override
        public Content.Chunk read()
        {
            Content.Chunk chunk = consumeCurrentChunk();
            if (chunk != null)
                return chunk;
            currentChunk = doRead();
            return consumeCurrentChunk();
        }

        private Content.Chunk doRead()
        {
            if (reachedEndStream)
            {
                readEof = true;
                return Content.Chunk.EOF;
            }

            Stream.Data data = getStream().readData();
            if (data == null)
                return null;
            DataFrame frame = data.frame();
            if (frame.isEndStream())
                reachedEndStream = true;
            // TODO optimize when frame.isEndStream() and BB is empty
            return Content.Chunk.from(frame.getData(), false, data);
        }

        public void onDataAvailable()
        {
            Runnable demandCallback = this.demandCallback;
            this.demandCallback = null;
            if (demandCallback != null)
            {
                try
                {
                    demandCallback.run();
                }
                catch (Throwable x)
                {
                    fail(x);
                }
            }
        }

        private Content.Chunk consumeCurrentChunk()
        {
            if (currentChunk != null)
            {
                Content.Chunk rc = currentChunk;
                if (!(rc instanceof Content.Chunk.Error))
                    currentChunk = currentChunk.isLast() ? Content.Chunk.EOF : null;
                return rc;
            }
            return null;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (demandCallback == null)
                throw new IllegalArgumentException();
            if (this.demandCallback != null)
                throw new IllegalStateException();
            this.demandCallback = demandCallback;
            getStream().demand();
        }

        @Override
        public void fail(Throwable failure)
        {
            if (currentChunk != null)
            {
                currentChunk.release();
                failAndClose(failure);
            }
            currentChunk = Content.Chunk.from(failure);
        }

        private Stream getStream()
        {
            // TODO stream should be a field
            return getHttpChannel().getStream();
        }

        private boolean hasReadEof()
        {
            return readEof;
        }
    }

    @Override
    protected HttpChannelOverHTTP2 getHttpChannel()
    {
        return (HttpChannelOverHTTP2)super.getHttpChannel();
    }

    @Override
    protected void receive()
    {
        throw new UnsupportedOperationException("receive should not be called under HTTP/2");
    }

    void onHeaders(Stream stream, HeadersFrame frame)
    {
        MetaData metaData = frame.getMetaData();
        if (metaData.isResponse())
            onResponse(stream, frame);
        else
            onTrailer(frame);
    }

    private void onResponse(Stream stream, HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        MetaData.Response response = (MetaData.Response)frame.getMetaData();
        HttpResponse httpResponse = exchange.getResponse();
        httpResponse.version(response.getHttpVersion()).status(response.getStatus()).reason(response.getReason());

        if (responseBegin(exchange))
        {
            HttpFields headers = response.getFields();
            for (HttpField header : headers)
            {
                if (!responseHeader(exchange, header))
                    return;
            }

            HttpRequest httpRequest = exchange.getRequest();
            if (MetaData.isTunnel(httpRequest.getMethod(), httpResponse.getStatus()))
            {
                ClientHTTP2StreamEndPoint endPoint = new ClientHTTP2StreamEndPoint((HTTP2Stream)stream);
                long idleTimeout = httpRequest.getIdleTimeout();
                if (idleTimeout > 0)
                    endPoint.setIdleTimeout(idleTimeout);
                if (LOG.isDebugEnabled())
                    LOG.debug("Successful HTTP2 tunnel on {} via {}", stream, endPoint);
                ((HTTP2Stream)stream).setAttachment(endPoint);
                HttpConversation conversation = httpRequest.getConversation();
                conversation.setAttribute(EndPoint.class.getName(), endPoint);
                HttpUpgrader upgrader = (HttpUpgrader)conversation.getAttribute(HttpUpgrader.class.getName());
                if (upgrader != null)
                    upgrade(upgrader, httpResponse, endPoint);
            }

            if (responseHeaders(exchange))
            {
                int status = response.getStatus();
                if (frame.isEndStream() || HttpStatus.isInterim(status))
                    responseSuccess(exchange);
                else
                    stream.demand();
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Stalling response processing, no demand after headers on {}", this);
            }
        }
    }

    private void onTrailer(HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpFields trailers = frame.getMetaData().getFields();
        trailers.forEach(exchange.getResponse()::trailer);
    }

    private void upgrade(HttpUpgrader upgrader, HttpResponse response, EndPoint endPoint)
    {
        try
        {
            upgrader.upgrade(response, endPoint, Callback.from(Callback.NOOP::succeeded, this::responseFailure));
        }
        catch (Throwable x)
        {
            responseFailure(x);
        }
    }

    Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return null;

        HttpRequest request = exchange.getRequest();
        MetaData.Request metaData = frame.getMetaData();
        HttpRequest pushRequest = (HttpRequest)getHttpDestination().getHttpClient().newRequest(metaData.getURIString());
        // TODO: copy PUSH_PROMISE headers into pushRequest.

        BiFunction<Request, Request, Response.CompleteListener> pushListener = request.getPushListener();
        if (pushListener != null)
        {
            Response.CompleteListener listener = pushListener.apply(request, pushRequest);
            if (listener != null)
            {
                HttpChannelOverHTTP2 pushChannel = getHttpChannel().getHttpConnection().acquireHttpChannel();
                HttpExchange pushExchange = new HttpExchange(getHttpDestination(), pushRequest, List.of(listener));
                pushChannel.associate(pushExchange);
                pushChannel.setStream(stream);
                // TODO: idle timeout ?
                pushExchange.requestComplete(null);
                pushExchange.terminateRequest();
                return pushChannel.getStreamListener();
            }
        }

        stream.reset(new ResetFrame(stream.getId(), ErrorCode.REFUSED_STREAM_ERROR.code), Callback.NOOP);
        return null;
    }

    @Override
    public void onDataAvailable()
    {
        // TODO these two variables should be given to the contentSource's ctor
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;
        Stream stream = getHttpChannel().getStream();
        if (stream == null)
            return;

        // TODO we could check for contentSource == null and instantiate it here if it was not instantiated by the HttpReceiver ctor
        Runnable contentAction;
        if (firstContent.compareAndSet(true, false))
            contentAction = firstResponseContent(exchange); // This action starts the onContentSource loop.
        else
            contentAction = () -> contentSource.onDataAvailable(); // This action calls the demand callback of the onContentSource loop.

        try
        {
            withinContentState(exchange, contentAction);
            if (contentSource.hasReadEof())
                responseSuccess(exchange);
        }
        catch (IllegalStateException e)
        {
            failAndClose(e);
        }
    }

    private void failAndClose(Throwable failure)
    {
        // TODO cancel or close or both? rework failure handling.
        Stream stream = getHttpChannel().getStream();
        if (responseFailure(failure))
        {
            stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
            getHttpChannel().getHttpConnection().close(failure);
        }
    }

    void onReset(ResetFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;
        int error = frame.getError();
        exchange.getRequest().abort(new IOException(ErrorCode.toString(error, "reset_code_" + error)));
    }

    @Override
    public boolean onTimeout(Throwable failure)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;
        return !exchange.abort(failure);
    }

    @Override
    public void onFailure(Throwable failure, Callback callback)
    {
        responseFailure(failure);
        callback.succeeded();
    }
}
