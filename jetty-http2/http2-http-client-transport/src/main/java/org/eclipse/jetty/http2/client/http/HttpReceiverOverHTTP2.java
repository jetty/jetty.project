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

package org.eclipse.jetty.http2.client.http;

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
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Channel;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpReceiverOverHTTP2 extends HttpReceiver implements HTTP2Channel.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverOverHTTP2.class);

    private final ContentNotifier contentNotifier = new ContentNotifier(this);

    public HttpReceiverOverHTTP2(HttpChannel channel)
    {
        super(channel);
    }

    @Override
    protected HttpChannelOverHTTP2 getHttpChannel()
    {
        return (HttpChannelOverHTTP2)super.getHttpChannel();
    }

    @Override
    protected void receive()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        contentNotifier.receive(getHttpChannel().getStream(), exchange);
    }

    @Override
    protected void reset()
    {
        super.reset();
        contentNotifier.reset();
    }

    void onHeaders(Stream stream, HeadersFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        HttpResponse httpResponse = exchange.getResponse();
        MetaData metaData = frame.getMetaData();
        if (metaData.isResponse())
        {
            MetaData.Response response = (MetaData.Response)frame.getMetaData();
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
                    ClientHTTP2StreamEndPoint endPoint = new ClientHTTP2StreamEndPoint((IStream)stream);
                    long idleTimeout = httpRequest.getIdleTimeout();
                    if (idleTimeout > 0)
                        endPoint.setIdleTimeout(idleTimeout);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Successful HTTP2 tunnel on {} via {}", stream, endPoint);
                    ((IStream)stream).setAttachment(endPoint);
                    HttpConversation conversation = httpRequest.getConversation();
                    conversation.setAttribute(EndPoint.class.getName(), endPoint);
                    HttpUpgrader upgrader = (HttpUpgrader)conversation.getAttribute(HttpUpgrader.class.getName());
                    if (upgrader != null)
                        upgrade(upgrader, httpResponse, endPoint);
                }

                contentNotifier.notifySuccess = frame.isEndStream();
                if (responseHeaders(exchange))
                {
                    int status = response.getStatus();
                    if (frame.isEndStream() || HttpStatus.isInterim(status))
                        responseSuccess(exchange);
                    else
                        stream.demand(1);
                }
            }
        }
        else // Response trailers.
        {
            HttpFields trailers = metaData.getFields();
            trailers.forEach(httpResponse::trailer);

            if (((IStream)stream).dataSize() == 0)
                responseSuccess(exchange);
            else
                contentNotifier.notifySuccess = true;
        }
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
    public void onData(DataFrame frame, Callback callback)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            callback.failed(new IOException("terminated"));
        else
            notifyContent(exchange, frame, callback);
    }

    void onReset(Stream stream, ResetFrame frame)
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

    private void notifyContent(HttpExchange exchange, DataFrame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Received content {}", frame);
        contentNotifier.process(getHttpChannel().getStream(), exchange, frame, callback);
    }

    private static class ContentNotifier
    {
        private final HttpReceiverOverHTTP2 receiver;
        private volatile boolean notifySuccess;

        private ContentNotifier(HttpReceiverOverHTTP2 receiver)
        {
            this.receiver = receiver;
        }

        public void receive(Stream stream, HttpExchange exchange)
        {
            if (notifySuccess && ((IStream)stream).dataSize() == 0)
                receiver.responseSuccess(exchange);
            else
                stream.demand(1);
        }

        private void process(Stream stream, HttpExchange exchange, DataFrame dataFrame, Callback callback)
        {
            if (dataFrame.getData().hasRemaining())
            {
                if (dataFrame.isEndStream())
                    notifySuccess = true;
                boolean proceed = receiver.responseContent(exchange, dataFrame.getData(), Callback.from(callback::succeeded, x -> fail(callback, x)));
                if (proceed)
                {
                    if (dataFrame.isEndStream())
                        receiver.responseSuccess(exchange);
                    else
                        stream.demand(1);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Stalling processing, no demand after {} on {}", dataFrame, this);
                }
            }
            else
            {
                callback.succeeded();
                if (dataFrame.isEndStream())
                    receiver.responseSuccess(exchange);
                else
                    stream.demand(1);
            }
        }

        private void reset()
        {
            notifySuccess = false;
        }

        private void fail(Callback callback, Throwable failure)
        {
            callback.failed(failure);
            receiver.responseFailure(failure);
        }
    }
}
