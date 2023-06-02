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

package org.eclipse.jetty.http2.client.transport.internal;

import java.io.IOException;
import java.util.function.BiFunction;

import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.transport.HttpChannel;
import org.eclipse.jetty.client.transport.HttpConversation;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpReceiver;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.HttpResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Channel;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpReceiverOverHTTP2 extends HttpReceiver implements HTTP2Channel.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverOverHTTP2.class);

    public HttpReceiverOverHTTP2(HttpChannel channel)
    {
        super(channel);
    }

    @Override
    protected void onInterim()
    {
    }

    @Override
    public Content.Chunk read(boolean fillInterestIfNeeded)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Reading, fillInterestIfNeeded={} in {}", fillInterestIfNeeded, this);
        Stream stream = getHttpChannel().getStream();
        Stream.Data data = stream.readData();
        if (LOG.isDebugEnabled())
            LOG.debug("Read stream data {} in {}", data, this);
        if (data == null)
        {
            if (fillInterestIfNeeded)
                stream.demand();
            return null;
        }
        DataFrame frame = data.frame();
        boolean last = frame.remaining() == 0 && frame.isEndStream();
        if (!last)
            return Content.Chunk.asChunk(frame.getByteBuffer(), last, data);
        data.release();
        responseSuccess(getHttpExchange(), null);
        return Content.Chunk.EOF;
    }

    @Override
    public void failAndClose(Throwable failure)
    {
        Stream stream = getHttpChannel().getStream();
        responseFailure(failure, Promise.from(failed ->
        {
            if (failed)
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        }, x -> stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP)));
    }

    @Override
    protected HttpChannelOverHTTP2 getHttpChannel()
    {
        return (HttpChannelOverHTTP2)super.getHttpChannel();
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

        responseBegin(exchange);

        HttpFields headers = response.getHttpFields();
        for (HttpField header : headers)
        {
            responseHeader(exchange, header);
        }

        HttpRequest httpRequest = exchange.getRequest();
        if (MetaData.isTunnel(httpRequest.getMethod(), httpResponse.getStatus()))
        {
            ClientHTTP2StreamEndPoint endPoint = new ClientHTTP2StreamEndPoint((HTTP2Stream)stream);
            long idleTimeout = httpRequest.getIdleTimeout();
            if (idleTimeout > 0)
                endPoint.setIdleTimeout(idleTimeout);
            if (LOG.isDebugEnabled())
                LOG.debug("Successful HTTP2 tunnel on {} via {} in {}", stream, endPoint, this);
            ((HTTP2Stream)stream).setAttachment(endPoint);
            HttpConversation conversation = httpRequest.getConversation();
            conversation.setAttribute(EndPoint.class.getName(), endPoint);
            HttpUpgrader upgrader = (HttpUpgrader)conversation.getAttribute(HttpUpgrader.class.getName());
            if (upgrader != null)
                upgrade(upgrader, httpResponse, endPoint);
        }

        responseHeaders(exchange);
    }

    private void onTrailer(HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpFields trailers = frame.getMetaData().getHttpFields();
        trailers.forEach(exchange.getResponse()::trailer);
    }

    private void upgrade(HttpUpgrader upgrader, HttpResponse response, EndPoint endPoint)
    {
        try
        {
            upgrader.upgrade(response, endPoint, Callback.from(Callback.NOOP::succeeded, failure -> responseFailure(failure, Promise.noop())));
        }
        catch (Throwable x)
        {
            responseFailure(x, Promise.noop());
        }
    }

    Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return null;

        HttpRequest request = exchange.getRequest();
        MetaData.Request metaData = frame.getMetaData();
        HttpRequest pushRequest = (HttpRequest)getHttpDestination().getHttpClient().newRequest(metaData.getHttpURI().toString());
        // TODO: copy PUSH_PROMISE headers into pushRequest.

        BiFunction<Request, Request, Response.CompleteListener> pushHandler = request.getPushHandler();
        if (pushHandler != null)
        {
            Response.CompleteListener listener = pushHandler.apply(request, pushRequest);
            if (listener != null)
            {
                HttpChannelOverHTTP2 pushChannel = getHttpChannel().getHttpConnection().acquireHttpChannel();
                pushRequest.getResponseListeners().addCompleteListener(listener);
                HttpExchange pushExchange = new HttpExchange(getHttpDestination(), pushRequest);
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
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        responseContentAvailable();
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
    public void onTimeout(Throwable failure, Promise<Boolean> promise)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            exchange.abort(failure, Promise.from(aborted -> promise.succeeded(!aborted), promise::failed));
        else
            promise.succeeded(false);
    }

    @Override
    public void onFailure(Throwable failure, Callback callback)
    {
        responseFailure(failure, Promise.from(failed -> callback.succeeded(), callback::failed));
    }
}
