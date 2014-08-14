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

package org.eclipse.jetty.http2.server;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCodes;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2ServerConnectionFactory extends AbstractHTTP2ServerConnectionFactory
{
    private static final Logger LOG = Log.getLogger(HTTP2ServerConnectionFactory.class);

    private final HttpConfiguration httpConfiguration;

    public HTTP2ServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration)
    {
        this.httpConfiguration = httpConfiguration;
    }

    @Override
    protected ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint)
    {
        return new HTTPServerSessionListener(connector, httpConfiguration, endPoint);
    }

    @Override
    protected ServerParser newServerParser(ByteBufferPool byteBufferPool, ServerParser.Listener listener)
    {
        return new ServerParser(byteBufferPool, listener, getMaxHeaderTableSize(), httpConfiguration.getRequestHeaderSize());
    }

    private class HTTPServerSessionListener extends ServerSessionListener.Adapter implements Stream.Listener
    {
        private final Connector connector;
        private final HttpConfiguration httpConfiguration;
        private final EndPoint endPoint;

        public HTTPServerSessionListener(Connector connector, HttpConfiguration httpConfiguration, EndPoint endPoint)
        {
            this.connector = connector;
            this.httpConfiguration = httpConfiguration;
            this.endPoint = endPoint;
        }

        @Override
        public Map<Integer, Integer> onPreface(Session session)
        {
            Map<Integer, Integer> settings = new HashMap<>();
            settings.put(SettingsFrame.HEADER_TABLE_SIZE, getMaxHeaderTableSize());
            settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, getInitialStreamWindow());
            int maxConcurrentStreams = getMaxConcurrentStreams();
            if (maxConcurrentStreams >= 0)
                settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, maxConcurrentStreams);
            return settings;
        }

        @Override
        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Processing {} on {}", frame, stream);

            MetaData.Request request = (MetaData.Request)frame.getMetaData();
            HttpTransportOverHTTP2 transport = new HttpTransportOverHTTP2(connector, httpConfiguration, endPoint, (IStream)stream, request);
            HttpInputOverHTTP2 input = new HttpInputOverHTTP2();
            // TODO pool HttpChannels per connection - maybe associate with thread?
            HttpChannelOverHTTP2 channel = new HttpChannelOverHTTP2(connector, httpConfiguration, endPoint, transport, input, stream);
            stream.setAttribute(IStream.CHANNEL_ATTRIBUTE, channel);

            channel.onRequest(frame);

            return frame.isEndStream() ? null : this;
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame)
        {
            // Servers do not receive responses.
            close(stream, "response_headers");
        }

        @Override
        public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
        {
            // Servers do not receive pushes.
            close(stream, "push_promise");
            return null;
        }

        @Override
        public void onData(Stream stream, DataFrame frame, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Processing {} on {}", frame, stream);

            HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttribute(IStream.CHANNEL_ATTRIBUTE);
            channel.requestContent(frame, callback);
        }

        @Override
        public void onFailure(Stream stream, Throwable x)
        {
            // TODO
        }

        private void close(Stream stream, String reason)
        {
            final Session session = stream.getSession();
            session.close(ErrorCodes.PROTOCOL_ERROR, reason, new Callback.Adapter()
            {
                @Override
                public void failed(Throwable x)
                {
                    ((ISession)session).disconnect();
                }
            });
        }
    }
}
