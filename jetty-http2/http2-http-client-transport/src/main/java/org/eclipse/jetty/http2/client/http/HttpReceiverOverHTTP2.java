//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.http.HttpMethod;
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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class HttpReceiverOverHTTP2 extends HttpReceiver implements HTTP2Channel.Client
{
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
        contentNotifier.process(true);
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
                if (HttpMethod.CONNECT.is(httpRequest.getMethod()) && httpResponse.getStatus() == HttpStatus.OK_200)
                {
                    ClientHTTP2StreamEndPoint endPoint = new ClientHTTP2StreamEndPoint((IStream)stream);
                    long idleTimeout = httpRequest.getIdleTimeout();
                    if (idleTimeout > 0)
                        endPoint.setIdleTimeout(idleTimeout);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Successful HTTP2 tunnel on {} via {}", stream, endPoint);
                    ((IStream)stream).setAttachment(endPoint);
                    httpRequest.getConversation().setAttribute(EndPoint.class.getName(), endPoint);
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
    public void onData(DataFrame frame, Callback callback)
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

    void onClosed(Stream stream)
    {
        getHttpChannel().onStreamClosed((IStream)stream);
    }

    private void notifyContent(HttpExchange exchange, DataFrame frame, Callback callback)
    {
        contentNotifier.offer(exchange, frame, callback);
    }

    private static class ContentNotifier
    {
        private final Queue<DataInfo> queue = new ArrayDeque<>();
        private final HttpReceiverOverHTTP2 receiver;
        private DataInfo dataInfo;
        private boolean active;
        private boolean resume;
        private boolean stalled;

        private ContentNotifier(HttpReceiverOverHTTP2 receiver)
        {
            this.receiver = receiver;
        }

        private void offer(HttpExchange exchange, DataFrame frame, Callback callback)
        {
            DataInfo dataInfo = new DataInfo(exchange, frame, callback);
            if (LOG.isDebugEnabled())
                LOG.debug("Queueing content {}", dataInfo);
            enqueue(dataInfo);
            process(false);
        }

        private void enqueue(DataInfo dataInfo)
        {
            synchronized (this)
            {
                queue.offer(dataInfo);
            }
        }

        private void process(boolean resume)
        {
            // Allow only one thread at a time.
            if (active(resume))
                return;

            while (true)
            {
                if (dataInfo != null)
                {
                    if (dataInfo.frame.isEndStream())
                    {
                        receiver.responseSuccess(dataInfo.exchange);
                        // Return even if active, as reset() will be called later.
                        return;
                    }
                }

                synchronized (this)
                {
                    dataInfo = queue.poll();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Dequeued content {}", dataInfo);
                    if (dataInfo == null)
                    {
                        active = false;
                        return;
                    }
                }


                ByteBuffer buffer = dataInfo.frame.getData();
                Callback callback = dataInfo.callback;
                if (buffer.hasRemaining())
                {
                    boolean proceed = receiver.responseContent(dataInfo.exchange, buffer, Callback.from(callback::succeeded, x -> fail(callback, x)));
                    if (!proceed)
                    {
                        // Should stall, unless just resumed.
                        if (stall())
                            return;
                    }
                }
                else
                {
                    callback.succeeded();
                }
            }
        }

        private boolean active(boolean resume)
        {
            synchronized (this)
            {
                if (active)
                {
                    if (resume)
                        this.resume = true;
                    return true;
                }
                if (stalled && !resume)
                    return true;
                active = true;
                stalled = false;
                return false;
            }
        }

        private boolean stall()
        {
            synchronized (this)
            {
                if (resume)
                {
                    resume = false;
                    return false;
                }
                active = false;
                stalled = true;
                return true;
            }
        }

        private void reset()
        {
            dataInfo = null;
            synchronized (this)
            {
                queue.clear();
                active = false;
                resume = false;
                stalled = false;
            }
        }

        private void fail(Callback callback, Throwable failure)
        {
            callback.failed(failure);
            receiver.responseFailure(failure);
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

            @Override
            public String toString()
            {
                return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), frame);
            }
        }
    }
}
