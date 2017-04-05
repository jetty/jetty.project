//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.Locale;
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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;

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
        else
        {
            HttpFields trailers = metaData.getFields();
            trailers.forEach(httpResponse::trailer);
            responseSuccess(exchange);
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

        BiFunction<Request, Request, Response.CompleteListener> pushListener = request.getPushListener();
        if (pushListener != null)
        {
            Response.CompleteListener listener = pushListener.apply(request, pushRequest);
            if (listener != null)
            {
                HttpChannelOverHTTP2 pushChannel = getHttpChannel().getHttpConnection().newHttpChannel(true);
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
            return;
        }

        // We must copy the data since we do not know when the
        // application will consume the bytes and the parsing
        // will continue as soon as this method returns, eventually
        // leading to reusing the underlying buffer for more reads.
        ByteBufferPool byteBufferPool = getHttpDestination().getHttpClient().getByteBufferPool();
        ByteBuffer original = frame.getData();
        int length = original.remaining();
        final ByteBuffer copy = byteBufferPool.acquire(length, original.isDirect());
        BufferUtil.clearToFill(copy);
        copy.put(original);
        BufferUtil.flipToFlush(copy, 0);

        contentNotifier.offer(new DataInfo(exchange, copy, callback, frame.isEndStream()));
        contentNotifier.iterate();
    }

    @Override
    public void onReset(Stream stream, ResetFrame frame)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        ErrorCode error = ErrorCode.from(frame.getError());
        String reason = error == null ? "reset" : error.name().toLowerCase(Locale.ENGLISH);
        exchange.getRequest().abort(new IOException(reason));
    }

    @Override
    public boolean onIdleTimeout(Stream stream, Throwable x)
    {
        responseFailure(x);
        return true;
    }

    private class ContentNotifier extends IteratingCallback
    {
        private final Queue<DataInfo> queue = new ArrayDeque<>();
        private DataInfo dataInfo;

        private boolean offer(DataInfo dataInfo)
        {
            synchronized (this)
            {
                return queue.offer(dataInfo);
            }
        }

        @Override
        protected Action process() throws Exception
        {
            DataInfo dataInfo;
            synchronized (this)
            {
                dataInfo = queue.poll();
            }

            if (dataInfo == null)
            {
                DataInfo prevDataInfo = this.dataInfo;
                if (prevDataInfo != null && prevDataInfo.last)
                    return Action.SUCCEEDED;
                return Action.IDLE;
            }

            this.dataInfo = dataInfo;
            responseContent(dataInfo.exchange, dataInfo.buffer, this);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            ByteBufferPool byteBufferPool = getHttpDestination().getHttpClient().getByteBufferPool();
            byteBufferPool.release(dataInfo.buffer);
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
            ByteBufferPool byteBufferPool = getHttpDestination().getHttpClient().getByteBufferPool();
            byteBufferPool.release(dataInfo.buffer);
            dataInfo.callback.failed(failure);
            responseFailure(failure);
        }
    }

    private static class DataInfo
    {
        private final HttpExchange exchange;
        private final ByteBuffer buffer;
        private final Callback callback;
        private final boolean last;

        private DataInfo(HttpExchange exchange, ByteBuffer buffer, Callback callback, boolean last)
        {
            this.exchange = exchange;
            this.buffer = buffer;
            this.callback = callback;
            this.last = last;
        }
    }
}
