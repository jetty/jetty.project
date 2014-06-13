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

import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2ServerConnectionFactory extends AbstractHTTP2ServerConnectionFactory
{
    private static final Logger LOG = Log.getLogger(HTTP2ServerConnectionFactory.class);
    private static final String CHANNEL_ATTRIBUTE = HttpChannelOverHTTP2.class.getName();

    private final HttpConfiguration httpConfiguration;

    public HTTP2ServerConnectionFactory(HttpConfiguration httpConfiguration)
    {
        this.httpConfiguration = httpConfiguration;
    }

    @Override
    protected ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint)
    {
        return new HTTPServerSessionListener(connector, httpConfiguration, endPoint);
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
            settings.put(SettingsFrame.HEADER_TABLE_SIZE, getHeaderTableSize());
            settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, getInitialWindowSize());
            int maxConcurrentStreams = getMaxConcurrentStreams();
            if (maxConcurrentStreams >= 0)
                settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, maxConcurrentStreams);
            return settings;
        }

        @Override
        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
        {
            LOG.debug("Processing {} on {}", frame, stream);

            HttpTransportOverHTTP2 transport = new HttpTransportOverHTTP2((IStream)stream, frame);
            HttpInputOverHTTP2 input = new HttpInputOverHTTP2();
            HttpChannelOverHTTP2 channel = new HttpChannelOverHTTP2(connector, httpConfiguration, endPoint, transport, input, stream);
            stream.setAttribute(CHANNEL_ATTRIBUTE, channel);

            channel.requestHeaders(frame);

            return frame.isEndStream() ? null : this;
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame)
        {
            // Servers do not receive responses.
        }

        @Override
        public void onData(Stream stream, DataFrame frame, Callback callback)
        {
            LOG.debug("Processing {} on {}", frame, stream);

            HttpChannelOverHTTP2 channel = (HttpChannelOverHTTP2)stream.getAttribute(CHANNEL_ATTRIBUTE);
            channel.requestContent(frame, callback);
        }

        @Override
        public void onFailure(Stream stream, Throwable x)
        {
            // TODO
        }
    }
}
