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

package org.eclipse.jetty.http2.client.http.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
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
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.internal.HTTP2Channel;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
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

                if (responseHeaders(exchange))
                {
                    int status = response.getStatus();
                    if (frame.isEndStream() || HttpStatus.isInterim(status))
                        responseSuccess(exchange);
                }
                else
                {
                    if (frame.isEndStream())
                    {
                        // There is no demand to trigger response success, so add
                        // a poison pill to trigger it when there will be demand.
                        notifyContent(exchange, new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
                    }
                }
            }
        }
        else // Response trailers.
        {
            HttpFields trailers = metaData.getFields();
            trailers.forEach(httpResponse::trailer);
            // Previous DataFrames had endStream=false, so
            // add a poison pill to trigger response success
            // after all normal DataFrames have been consumed.
            notifyContent(exchange, new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
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

    private void notifyContent(HttpExchange exchange, DataFrame frame, Callback callback)
    {
        contentNotifier.offer(exchange, frame, callback);
    }

    private class ContentNotifier
    {
        private final AutoLock lock = new AutoLock();
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
            try (AutoLock l = lock.lock())
            {
                queue.offer(dataInfo);
            }
        }

        private void process(boolean resume)
        {
            // Allow only one thread at a time.
            boolean busy = active(resume);
            if (LOG.isDebugEnabled())
                LOG.debug("Resuming({}) processing({}) of content", resume, !busy);
            if (busy)
                return;

            // Process only if there is demand.
            try (AutoLock l = lock.lock())
            {
                if (!resume && demand() <= 0)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Stalling processing, content available but no demand");
                    active = false;
                    stalled = true;
                    return;
                }
            }

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

                try (AutoLock l = lock.lock())
                {
                    dataInfo = queue.poll();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Processing content {}", dataInfo);
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
                        // The call to responseContent() said we should
                        // stall, but another thread may have just resumed.
                        boolean stall = stall();
                        if (LOG.isDebugEnabled())
                            LOG.debug("Stalling({}) processing", stall);
                        if (stall)
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
            try (AutoLock l = lock.lock())
            {
                if (active)
                {
                    // There is a thread in process(),
                    // but it may be about to exit, so
                    // remember "resume" to signal the
                    // processing thread to continue.
                    if (resume)
                        this.resume = true;
                    return true;
                }

                // If there is no demand (i.e. stalled
                // and not resuming) then don't process.
                if (stalled && !resume)
                    return true;

                // Start processing.
                active = true;
                stalled = false;
                return false;
            }
        }

        /**
         * Called when there is no demand, this method checks whether
         * the processing should really stop or it should continue.
         *
         * @return true to stop processing, false to continue processing
         */
        private boolean stall()
        {
            try (AutoLock l = lock.lock())
            {
                if (resume)
                {
                    // There was no demand, but another thread
                    // just demanded, continue processing.
                    resume = false;
                    return false;
                }

                // There is no demand, stop processing.
                active = false;
                stalled = true;
                return true;
            }
        }

        private void reset()
        {
            dataInfo = null;
            try (AutoLock l = lock.lock())
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

        private class DataInfo
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
