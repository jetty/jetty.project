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

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.server.SPDYServerConnectionFactory;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTPSPDYServerConnectionFactory extends SPDYServerConnectionFactory implements HttpConfiguration.ConnectionFactory
{
    private static final String CHANNEL_ATTRIBUTE = "org.eclipse.jetty.spdy.server.http.HTTPChannelOverSPDY";
    private static final Logger LOG = Log.getLogger(HTTPSPDYServerConnectionFactory.class);

    private final PushStrategy pushStrategy;
    private final HttpConfiguration httpConfiguration;

    public HTTPSPDYServerConnectionFactory(
        @Name("version") int version,
        @Name("config") HttpConfiguration config)
    {
        this(version,config,new PushStrategy.None());
    }

    public HTTPSPDYServerConnectionFactory(
        @Name("version") int version,
        @Name("config") HttpConfiguration config,
        @Name("pushStrategy") PushStrategy pushStrategy)
    {
        super(version);
        this.pushStrategy = pushStrategy;
        httpConfiguration = config;
        addBean(httpConfiguration);
    }

    @Override
    public HttpConfiguration getHttpConfiguration()
    {
        return httpConfiguration;
    }

    @Override
    protected ServerSessionFrameListener provideServerSessionFrameListener(Connector connector, EndPoint endPoint)
    {
        return new HTTPServerFrameListener(connector,endPoint);
    }

    private class HTTPServerFrameListener extends ServerSessionFrameListener.Adapter implements StreamFrameListener
    {
        private final Connector connector;
        private final EndPoint endPoint;

        public HTTPServerFrameListener(Connector connector,EndPoint endPoint)
        {
            this.endPoint = endPoint;
            this.connector=connector;
        }

        @Override
        public StreamFrameListener onSyn(final Stream stream, SynInfo synInfo)
        {
            // Every time we have a SYN, it maps to a HTTP request.
            // We can have multiple concurrent SYNs on the same connection,
            // and this is very different from HTTP, where only one request
            // can arrive on the same connection, so we need to create an
            // HttpChannel for each SYN in order to run concurrently.

            LOG.debug("Received {} on {}", synInfo, stream);

            Fields headers = synInfo.getHeaders();
            // According to SPDY/3 spec section 3.2.1 user-agents MUST support gzip compression. Firefox omits the
            // accept-encoding header as it is redundant to negotiate gzip compression support with the server,
            // if clients have to accept it.
            // So we inject the accept-encoding header here, even if not set by the client. This will enforce SPDY
            // clients to follow the spec and enable gzip compression if GzipFilter or the like is enabled.
            if (!(headers.get("accept-encoding") != null && headers.get("accept-encoding").getValue().contains
                    ("gzip")))
                headers.add("accept-encoding", "gzip");
            HttpTransportOverSPDY transport = new HttpTransportOverSPDY(connector, httpConfiguration, endPoint,
                    pushStrategy, stream, headers);
            HttpInputOverSPDY input = new HttpInputOverSPDY();
            HttpChannelOverSPDY channel = new HttpChannelOverSPDY(connector, httpConfiguration, endPoint, transport, input, stream);
            stream.setAttribute(CHANNEL_ATTRIBUTE, channel);

            channel.requestStart(headers, synInfo.isClose());

            if (headers.isEmpty())
            {
                // If the SYN has no headers, they may come later in a HEADERS frame
                return this;
            }
            else
            {
                if (synInfo.isClose())
                    return null;
                else
                    return this;
            }
        }

        @Override
        public void onReply(Stream stream, ReplyInfo replyInfo)
        {
            // Do nothing, servers cannot get replies
        }

        @Override
        public void onHeaders(Stream stream, HeadersInfo headersInfo)
        {
            LOG.debug("Received {} on {}", headersInfo, stream);
            HttpChannelOverSPDY channel = (HttpChannelOverSPDY)stream.getAttribute(CHANNEL_ATTRIBUTE);
            channel.requestHeaders(headersInfo.getHeaders(), headersInfo.isClose());
        }

        @Override
        public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
        {
            return null;
        }

        @Override
        public void onData(Stream stream, final DataInfo dataInfo)
        {
            LOG.debug("Received {} on {}", dataInfo, stream);
            HttpChannelOverSPDY channel = (HttpChannelOverSPDY)stream.getAttribute(CHANNEL_ATTRIBUTE);
            channel.requestContent(dataInfo, dataInfo.isClose());
        }

        @Override
        public void onFailure(Stream stream, Throwable x)
        {
            LOG.debug(x);
        }
    }
}
