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
    protected void reset()
    {
        super.reset();
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
        if (last)
            responseSuccess(getHttpExchange(), null);
        return Content.Chunk.from(frame.getData(), last, data);
    }

    @Override
    public void failAndClose(Throwable failure)
    {
        Stream stream = getHttpChannel().getStream();
        responseFailure(failure, Promise.from(failed ->
        {
            stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
            getHttpChannel().getHttpConnection().close(failure);
        }, x ->
        {
            stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
            getHttpChannel().getHttpConnection().close(failure);
        }));
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
        if (exchange.isResponseComplete())
            return;

        HttpFields headers = response.getFields();
        for (HttpField header : headers)
        {
            responseHeader(exchange, header);
            if (exchange.isResponseComplete())
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

        HttpFields trailers = frame.getMetaData().getFields();
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
        HttpRequest pushRequest = (HttpRequest)getHttpDestination().getHttpClient().newRequest(metaData.getURIString());
        // TODO: copy PUSH_PROMISE headers into pushRequest.

        BiFunction<Request, Request, Response.CompleteListener> pushListener = request.getPushHandler();
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
