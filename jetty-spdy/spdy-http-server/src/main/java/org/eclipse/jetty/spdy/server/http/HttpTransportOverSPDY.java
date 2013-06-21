//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
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
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.util.BlockingCallback;
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
    private final Fields requestHeaders;
    private final BlockingCallback streamBlocker = new BlockingCallback();
    private final AtomicBoolean committed = new AtomicBoolean();

    public HttpTransportOverSPDY(Connector connector, HttpConfiguration configuration, EndPoint endPoint, PushStrategy pushStrategy, Stream stream, Fields requestHeaders)
    {
        this.connector = connector;
        this.configuration = configuration;
        this.endPoint = endPoint;
        this.pushStrategy = pushStrategy == null ? new PushStrategy.None() : pushStrategy;
        this.stream = stream;
        this.requestHeaders = requestHeaders;
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
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback)
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

        boolean hasContent = BufferUtil.hasContent(content);

        if (info != null)
        {
            if (!committed.compareAndSet(false, true))
            {
                StreamException exception = new StreamException(stream.getId(), StreamStatus.PROTOCOL_ERROR,
                        "Stream already committed!");
                callback.failed(exception);
                LOG.warn("Committed response twice.", exception);
                return;
            }
            short version = stream.getSession().getVersion();
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

            boolean close = !hasContent && lastContent;
            ReplyInfo reply = new ReplyInfo(headers, close);
            reply(stream, reply);
        }

        // Do we have some content to send as well
        if (hasContent)
        {
            // Is the stream still open?
            if (stream.isClosed() || stream.isReset())
                // tell the callback about the EOF
                callback.failed(new EofException("stream closed"));
            else
                // send the data and let it call the callback
                stream.data(new ByteBufferDataInfo(endPoint.getIdleTimeout(), TimeUnit.MILLISECONDS, content, lastContent
                ), callback);
        }
        // else do we need to close
        else if (lastContent)
        {
            // Are we closed ?
            if (stream.isClosed() || stream.isReset())
                // already closed by reply, so just tell callback we are complete
                callback.succeeded();
            else
                // send empty data to close and let the send call the callback
                stream.data(new ByteBufferDataInfo(endPoint.getIdleTimeout(), TimeUnit.MILLISECONDS,
                        BufferUtil.EMPTY_BUFFER, lastContent), callback);
        }
        else
            // No data and no close so tell callback we are completed
            callback.succeeded();
    }

    @Override
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent) throws IOException
    {
        send(info, content, lastContent, streamBlocker);
        try
        {
            streamBlocker.block();
        }
        catch (Exception e)
        {
            LOG.debug(e);
        }
    }

    @Override
    public void completed()
    {
        LOG.debug("Completed");
    }

    private void reply(Stream stream, ReplyInfo replyInfo)
    {
        if (!stream.isUnidirectional())
            stream.reply(replyInfo, new Callback.Adapter());
        else
            stream.headers(new HeadersInfo(replyInfo.getHeaders(), replyInfo.isClose()), new Callback.Adapter());

        Fields responseHeaders = replyInfo.getHeaders();
        short version = stream.getSession().getVersion();
        if (responseHeaders.get(HTTPSPDYHeader.STATUS.name(version)).value().startsWith("200") && !stream.isClosed())
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

        private PushHttpTransportOverSPDY(Connector connector, HttpConfiguration configuration, EndPoint endPoint,
                                          PushStrategy pushStrategy, Stream stream, Fields requestHeaders,
                                          PushResourceCoordinator coordinator)
        {
            super(connector, configuration, endPoint, pushStrategy, stream, requestHeaders);
            this.coordinator = coordinator;
        }

        @Override
        public void completed()
        {
            Stream stream = getStream();
            LOG.debug("Resource pushed for {} on {}",
                    getRequestHeaders().get(HTTPSPDYHeader.URI.name(stream.getSession().getVersion())), stream);
            coordinator.complete();
        }
    }

    private class PushResourceCoordinator
    {
        private final Queue<PushResource> queue = new ConcurrentArrayQueue<>();
        private final Set<String> resources;
        private boolean active;

        private PushResourceCoordinator(Set<String> resources)
        {
            this.resources = resources;
        }

        private void coordinate()
        {
            // Must send all push frames to the client at once before we
            // return from this method and send the main resource data
            for (String pushResource : resources)
                pushResource(pushResource);
        }

        private void sendNextResourceData()
        {
            PushResource resource;
            synchronized (this)
            {
                if (active)
                    return;
                resource = queue.poll();
                if (resource == null)
                    return;
                active = true;
            }
            HttpChannelOverSPDY pushChannel = newHttpChannelOverSPDY(resource.getPushStream(), resource.getPushRequestHeaders());
            pushChannel.requestStart(resource.getPushRequestHeaders(), true);
        }

        private HttpChannelOverSPDY newHttpChannelOverSPDY(Stream pushStream, Fields pushRequestHeaders)
        {
            HttpTransport transport = new PushHttpTransportOverSPDY(connector, configuration, endPoint, pushStrategy,
                    pushStream, pushRequestHeaders, this);
            HttpInputOverSPDY input = new HttpInputOverSPDY();
            return new HttpChannelOverSPDY(connector, configuration, endPoint, transport, input, pushStream);
        }

        private void pushResource(String pushResource)
        {
            final short version = stream.getSession().getVersion();
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
                }
            });
        }

        private Fields createRequestHeaders(Fields.Field scheme, Fields.Field host, Fields.Field uri, String pushResourcePath)
        {
            final Fields newRequestHeaders = new Fields(requestHeaders, false);
            short version = stream.getSession().getVersion();
            newRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version), "GET");
            newRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
            newRequestHeaders.put(scheme);
            newRequestHeaders.put(host);
            newRequestHeaders.put(HTTPSPDYHeader.URI.name(version), pushResourcePath);
            String referrer = scheme.value() + "://" + host.value() + uri.value();
            newRequestHeaders.put("referer", referrer);
            newRequestHeaders.put("x-spdy-push", "true");
            return newRequestHeaders;
        }

        private Fields createPushHeaders(Fields.Field scheme, Fields.Field host, String pushResourcePath)
        {
            final Fields pushHeaders = new Fields();
            short version = stream.getSession().getVersion();
            if (version == SPDY.V2)
                pushHeaders.put(HTTPSPDYHeader.URI.name(version), scheme.value() + "://" + host.value() + pushResourcePath);
            else
            {
                pushHeaders.put(HTTPSPDYHeader.URI.name(version), pushResourcePath);
                pushHeaders.put(scheme);
                pushHeaders.put(host);
            }
            return pushHeaders;
        }

        private void complete()
        {
            synchronized (this)
            {
                active = false;
            }
            sendNextResourceData();
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
    }
}
