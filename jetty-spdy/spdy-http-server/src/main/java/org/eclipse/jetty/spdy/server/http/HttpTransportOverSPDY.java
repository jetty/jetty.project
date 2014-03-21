//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.server.http;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ConcurrentArrayQueue;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpTransportOverSPDY implements HttpTransport
{
    private static final Logger LOG = Log.getLogger(HttpTransportOverSPDY.class);

    private final Connector connector;
    private final HttpConfiguration configuration;
    private final EndPoint endPoint;
    private final PushStrategy pushStrategy;
    private final Stream stream;
    private final short version;
    private final Fields requestHeaders;
    private final AtomicBoolean committed = new AtomicBoolean();

    public HttpTransportOverSPDY(Connector connector, HttpConfiguration configuration, EndPoint endPoint, PushStrategy pushStrategy, Stream stream, Fields requestHeaders)
    {
        this.connector = connector;
        this.configuration = configuration;
        this.endPoint = endPoint;
        this.pushStrategy = pushStrategy == null ? new PushStrategy.None() : pushStrategy;
        this.stream = stream;
        this.requestHeaders = requestHeaders;
        Session session = stream.getSession();
        this.version = session.getVersion();
    }

    protected Stream getStream()
    {
        return stream;
    }

    protected Fields getRequestHeaders()
    {
        return requestHeaders;
    }


    @Override
    public void send(ByteBuffer responseBodyContent, boolean lastContent, Callback callback)
    {
        // TODO can this be more efficient?
        send(null, responseBodyContent, lastContent, callback);
    }

    @Override
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent, final Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Sending {} {} {} {} last={}", this, stream, info, BufferUtil.toDetailString(content), lastContent);

        if (stream.isClosed() || stream.isReset())
        {
            EofException exception = new EofException("stream closed");
            callback.failed(exception);
            return;
        }

        // info==null content==null lastContent==false          should not happen
        // info==null content==null lastContent==true           signals no more content - complete
        // info==null content!=null lastContent==false          send data on committed response
        // info==null content!=null lastContent==true           send last data on committed response - complete
        // info!=null content==null lastContent==false          reply, commit
        // info!=null content==null lastContent==true           reply, commit and complete
        // info!=null content!=null lastContent==false          reply, commit with content
        // info!=null content!=null lastContent==true           reply, commit with content and complete

        boolean isHeadRequest = HttpMethod.HEAD.name().equalsIgnoreCase(requestHeaders.get(HTTPSPDYHeader.METHOD.name(version)).getValue());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        boolean close = !hasContent && lastContent;

        if (info != null)
        {
            if (!committed.compareAndSet(false, true))
            {
                StreamException exception = new StreamException(stream.getId(), StreamStatus.PROTOCOL_ERROR,
                        "Stream already committed!");
                callback.failed(exception);
                LOG.debug("Committed response twice.", exception);
                return;
            }
            sendReply(info, !hasContent ? callback : new Callback.Adapter()
            {
                @Override
                public void failed(Throwable x)
                {
                    callback.failed(x);
                }
            }, close);
        }

        // Do we have some content to send as well
        if (hasContent)
        {
            // send the data and let it call the callback
            LOG.debug("Send content: {} on stream: {} lastContent={}", BufferUtil.toDetailString(content), stream,
                    lastContent);
            stream.data(new ByteBufferDataInfo(endPoint.getIdleTimeout(), TimeUnit.MILLISECONDS, content, lastContent
            ), callback);
        }
        // else do we need to close
        else if (lastContent && info == null)
        {
            // send empty data to close and let the send call the callback
            LOG.debug("No content and lastContent=true. Sending empty ByteBuffer to close stream: {}", stream);
            stream.data(new ByteBufferDataInfo(endPoint.getIdleTimeout(), TimeUnit.MILLISECONDS,
                    BufferUtil.EMPTY_BUFFER, lastContent), callback);
        }
        else if (!lastContent && !hasContent && info == null)
            throw new IllegalStateException("not lastContent, no content and no responseInfo!");

    }

    private void sendReply(HttpGenerator.ResponseInfo info, Callback callback, boolean close)
    {
        Fields headers = new Fields();

        HttpVersion httpVersion = HttpVersion.HTTP_1_1;
        headers.put(HTTPSPDYHeader.VERSION.name(version), httpVersion.asString());

        int status = info.getStatus();
        StringBuilder httpStatus = new StringBuilder().append(status);
        String reason = info.getReason();
        if (reason == null)
            reason = HttpStatus.getMessage(status);
        if (reason != null)
            httpStatus.append(" ").append(reason);
        headers.put(HTTPSPDYHeader.STATUS.name(version), httpStatus.toString());
        LOG.debug("HTTP < {} {}", httpVersion, httpStatus);

        // TODO merge the two Field classes into one
        HttpFields fields = info.getHttpFields();
        if (fields != null)
        {
            for (int i = 0; i < fields.size(); ++i)
            {
                HttpField field = fields.getField(i);
                String name = field.getName();
                String value = field.getValue();
                headers.add(name, value);
                LOG.debug("HTTP < {}: {}", name, value);
            }
        }

        if (configuration.getSendServerVersion())
            headers.add(HttpHeader.SERVER.asString(), HttpConfiguration.SERVER_VERSION);
        if (configuration.getSendXPoweredBy())
            headers.add(HttpHeader.X_POWERED_BY.asString(), HttpConfiguration.SERVER_VERSION);

        ReplyInfo reply = new ReplyInfo(headers, close);
        LOG.debug("Sending reply: {} on stream: {}", reply, stream);
        reply(stream, reply, callback);
    }

    @Override
    public void completed()
    {
        LOG.debug("Completed {}", this);
    }

    private void reply(Stream stream, ReplyInfo replyInfo, Callback callback)
    {
        if (!stream.isUnidirectional())
            stream.reply(replyInfo, callback);
        else
            stream.headers(new HeadersInfo(replyInfo.getHeaders(), replyInfo.isClose()), callback);

        Fields responseHeaders = replyInfo.getHeaders();
        if (responseHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().startsWith("200") && !stream.isClosed())
        {
            Set<String> pushResources = pushStrategy.apply(stream, requestHeaders, responseHeaders);
            if (pushResources.size() > 0)
            {
                PushResourceCoordinator pushResourceCoordinator = new PushResourceCoordinator(pushResources);
                pushResourceCoordinator.coordinate();
            }
        }
    }

    private static class PushHttpTransportOverSPDY extends HttpTransportOverSPDY
    {
        private final PushResourceCoordinator coordinator;
        private final short version;

        private PushHttpTransportOverSPDY(Connector connector, HttpConfiguration configuration, EndPoint endPoint,
                                          PushStrategy pushStrategy, Stream stream, Fields requestHeaders,
                                          PushResourceCoordinator coordinator, short version)
        {
            super(connector, configuration, endPoint, pushStrategy, stream, requestHeaders);
            this.coordinator = coordinator;
            this.version = version;
        }

        @Override
        public void completed()
        {
            Stream stream = getStream();
            LOG.debug("Resource pushed for {} on {}",
                    getRequestHeaders().get(HTTPSPDYHeader.URI.name(version)), stream);
            coordinator.complete();
        }
    }

    private class PushResourceCoordinator
    {
        private final Queue<PushResource> queue = new ConcurrentArrayQueue<>();
        private final Set<String> resources;
        private AtomicBoolean active = new AtomicBoolean(false);

        private PushResourceCoordinator(Set<String> resources)
        {
            this.resources = resources;
        }

        private void coordinate()
        {
            LOG.debug("Pushing resources: {}", resources);
            // Must send all push frames to the client at once before we
            // return from this method and send the main resource data
            for (String pushResource : resources)
                pushResource(pushResource);
        }

        private void sendNextResourceData()
        {
            LOG.debug("{} sendNextResourceData active: {}", hashCode(), active.get());
            if (active.compareAndSet(false, true))
            {
                PushResource resource = queue.poll();
                if (resource != null)
                {
                    LOG.debug("Opening new push channel for: {}", resource);
                    HttpChannelOverSPDY pushChannel = newHttpChannelOverSPDY(resource.getPushStream(), resource.getPushRequestHeaders());
                    pushChannel.requestStart(resource.getPushRequestHeaders(), true);
                    return;
                }

                if (active.compareAndSet(true, false))
                {
                    if (queue.peek() != null)
                        sendNextResourceData();
                }
                else
                {
                    throw new IllegalStateException("active must not be false here! Concurrency bug!");
                }
            }
        }

        private HttpChannelOverSPDY newHttpChannelOverSPDY(Stream pushStream, Fields pushRequestHeaders)
        {
            HttpTransport transport = new PushHttpTransportOverSPDY(connector, configuration, endPoint, pushStrategy,
                    pushStream, pushRequestHeaders, this, version);
            HttpInputOverSPDY input = new HttpInputOverSPDY();
            return new HttpChannelOverSPDY(connector, configuration, endPoint, transport, input, pushStream);
        }

        private void pushResource(String pushResource)
        {
            Fields.Field scheme = requestHeaders.get(HTTPSPDYHeader.SCHEME.name(version));
            Fields.Field host = requestHeaders.get(HTTPSPDYHeader.HOST.name(version));
            Fields.Field uri = requestHeaders.get(HTTPSPDYHeader.URI.name(version));
            final Fields pushHeaders = createPushHeaders(scheme, host, pushResource);
            final Fields pushRequestHeaders = createRequestHeaders(scheme, host, uri, pushResource);

            stream.push(new PushInfo(pushHeaders, false), new Promise<Stream>()
            {
                @Override
                public void succeeded(Stream pushStream)
                {
                    LOG.debug("Headers pushed for {} on {}", pushHeaders.get(HTTPSPDYHeader.URI.name(version)), pushStream);
                    queue.offer(new PushResource(pushStream, pushRequestHeaders));
                    sendNextResourceData();
                }

                @Override
                public void failed(Throwable x)
                {
                    LOG.debug("Creating push stream failed.", x);
                    sendNextResourceData();
                }
            });
        }

        private void complete()
        {
            if (!active.compareAndSet(true, false))
                throw new IllegalStateException();
            sendNextResourceData();
        }

        private Fields createRequestHeaders(Fields.Field scheme, Fields.Field host, Fields.Field uri, String pushResourcePath)
        {
            final Fields newRequestHeaders = new Fields(requestHeaders, false);
            newRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version), "GET");
            newRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
            newRequestHeaders.put(scheme);
            newRequestHeaders.put(host);
            newRequestHeaders.put(HTTPSPDYHeader.URI.name(version), pushResourcePath);
            String referrer = scheme.getValue() + "://" + host.getValue() + uri.getValue();
            newRequestHeaders.put("referer", referrer);
            newRequestHeaders.put("x-spdy-push", "true");
            return newRequestHeaders;
        }

        private Fields createPushHeaders(Fields.Field scheme, Fields.Field host, String pushResourcePath)
        {
            final Fields pushHeaders = new Fields();
            if (version == SPDY.V2)
                pushHeaders.put(HTTPSPDYHeader.URI.name(version), scheme.getValue() + "://" + host.getValue() + pushResourcePath);
            else
            {
                pushHeaders.put(HTTPSPDYHeader.URI.name(version), pushResourcePath);
                pushHeaders.put(scheme);
                pushHeaders.put(host);
            }
            return pushHeaders;
        }
    }

    private static class PushResource
    {
        private final Stream pushStream;
        private final Fields pushRequestHeaders;

        public PushResource(Stream pushStream, Fields pushRequestHeaders)
        {
            this.pushStream = pushStream;
            this.pushRequestHeaders = pushRequestHeaders;
        }

        public Stream getPushStream()
        {
            return pushStream;
        }

        public Fields getPushRequestHeaders()
        {
            return pushRequestHeaders;
        }

        @Override
        public String toString()
        {
            return "PushResource{" +
                    "pushStream=" + pushStream +
                    ", pushRequestHeaders=" + pushRequestHeaders +
                    '}';
        }
    }

    @Override
    public void abort()
    {
        // TODO close the stream in a way to indicate an incomplete response?
    }
}
