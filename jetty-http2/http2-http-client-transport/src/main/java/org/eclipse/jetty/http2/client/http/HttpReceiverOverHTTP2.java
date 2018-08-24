//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.function.BiFunction;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Retainable;

public class HttpReceiverOverHTTP2 extends HttpReceiver implements Stream.Listener
{
    private final ContentNotifier contentNotifier = new ContentNotifier();

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
    protected void reset()
    {
        super.reset();
        contentNotifier.reset();
    }

    @Override
    public void onHeaders(Stream stream, HeadersFrame frame)
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

                if (responseHeaders(exchange))
                {
                    int status = response.getStatus();
                    boolean informational = HttpStatus.isInformational(status) && status != HttpStatus.SWITCHING_PROTOCOLS_101;
                    if (frame.isEndStream() || informational)
                        responseSuccess(exchange);
                }
            }
        }
        else // Response trailers.
        {
            HttpFields trailers = metaData.getFields();
            trailers.forEach(httpResponse::trailer);
            notifyContent(exchange, new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
        }
    }

    @Override
    public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return null;

        HttpRequest request = exchange.getRequest();
        MetaData.Request metaData = (MetaData.Request)frame.getMetaData();
        HttpRequest pushRequest = (HttpRequest)getHttpDestination().getHttpClient().newRequest(metaData.getURIString());
        // TODO: copy PUSH_PROMISE headers into pushRequest.

        BiFunction<Request, Request, Response.CompleteListener> pushListener = request.getPushListener();
        if (pushListener != null)
        {
            Response.CompleteListener listener = pushListener.apply(request, pushRequest);
            if (listener != null)
            {
                HttpChannelOverHTTP2 pushChannel = getHttpChannel().getHttpConnection().acquireHttpChannel();
                List<Response.ResponseListener> listeners = Collections.singletonList(listener);
                HttpExchange pushExchange = new HttpExchange(getHttpDestination(), pushRequest, listeners);
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
    public void onData(Stream stream, DataFrame frame, Callback callback)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
        {
            callback.failed(new IOException("terminated"));
        }
        else
        {
            notifyContent(exchange, frame, callback);
        }
    }

    @Override
    public void onReset(Stream stream, ResetFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;
        int error = frame.getError();
        exchange.getRequest().abort(new IOException(ErrorCode.toString(error, "reset_code_" + error)));
    }

    @Override
    public boolean onIdleTimeout(Stream stream, Throwable x)
    {
        responseFailure(x);
        return true;
    }

    @Override
    public void onFailure(Stream stream, int error, String reason, Callback callback)
    {
        responseFailure(new IOException(String.format("%s/%s", ErrorCode.toString(error, null), reason)));
        callback.succeeded();
    }

    private void notifyContent(HttpExchange exchange, DataFrame frame, Callback callback)
    {
        contentNotifier.offer(new DataInfo(exchange, frame, callback));
        contentNotifier.iterate();
    }

    private class ContentNotifier extends IteratingCallback implements Retainable
    {
        private final Queue<DataInfo> queue = new ArrayDeque<>();
        private DataInfo dataInfo;

        private void offer(DataInfo dataInfo)
        {
            synchronized (this)
            {
                queue.offer(dataInfo);
            }
        }

        @Override
        protected Action process()
        {
            DataInfo dataInfo;
            synchronized (this)
            {
                dataInfo = queue.poll();
            }

            if (dataInfo == null)
            {
                DataInfo prevDataInfo = this.dataInfo;
                if (prevDataInfo != null && prevDataInfo.frame.isEndStream())
                    return Action.SUCCEEDED;
                return Action.IDLE;
            }

            this.dataInfo = dataInfo;
            ByteBuffer buffer = dataInfo.frame.getData();
            if (buffer.hasRemaining())
                responseContent(dataInfo.exchange, buffer, this);
            else
                succeeded();
            return Action.SCHEDULED;
        }

        @Override
        public void retain()
        {
            Callback callback = dataInfo.callback;
            if (callback instanceof Retainable)
                ((Retainable)callback).retain();
        }

        @Override
        public void succeeded()
        {
            dataInfo.callback.succeeded();
            super.succeeded();
        }

        @Override
        protected void onCompleteSuccess()
        {
            responseSuccess(dataInfo.exchange);
        }

        @Override
        protected void onCompleteFailure(Throwable failure)
        {
            dataInfo.callback.failed(failure);
            responseFailure(failure);
        }
    }

    private static class DataInfo
    {
        private final HttpExchange exchange;
        private final DataFrame frame;
        private final Callback callback;

        private DataInfo(HttpExchange exchange, DataFrame frame, Callback callback)
        {
            this.exchange = exchange;
            this.frame = frame;
            this.callback = callback;
        }
    }
}
