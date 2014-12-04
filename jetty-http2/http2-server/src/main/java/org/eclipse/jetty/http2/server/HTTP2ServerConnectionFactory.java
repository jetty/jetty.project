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
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCodes;
import org.eclipse.jetty.http2.HTTP2Cipher;
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
import org.eclipse.jetty.server.NegotiatingServerConnection.CipherDiscriminator;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HTTP2ServerConnectionFactory extends AbstractHTTP2ServerConnectionFactory implements CipherDiscriminator
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
        return new ServerParser(byteBufferPool, listener, getMaxDynamicTableSize(), httpConfiguration.getRequestHeaderSize());
    }

    
    @Override
    public boolean isAcceptable(String protocol, String tlsProtocol, String tlsCipher)
    {
        // Implement 9.2.2
        if (HTTP2Cipher.isBlackListProtocol(tlsProtocol) && HTTP2Cipher.isBlackListCipher(tlsCipher))
            return false;
            
        return true;
    }


    private class HTTPServerSessionListener extends ServerSessionListener.Adapter implements Stream.Listener
    {
        private final Connector connector;
        private final HttpConfiguration httpConfiguration;
        private final EndPoint endPoint;

        // TODO This pool should be on the HTTP2ServerConnection
        // TODO Evaluate if this is be best data structure to use
        private final ConcurrentLinkedQueue<HttpChannelOverHTTP2> channels = new ConcurrentLinkedQueue<>();
        
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
            settings.put(SettingsFrame.HEADER_TABLE_SIZE, getMaxDynamicTableSize());
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

            HttpChannelOverHTTP2 channel = channels.poll();
            if (channel!=null)
            {
                channel.getHttp2Transport().setStream((IStream)stream);
                if (LOG.isDebugEnabled())
                    LOG.debug("recycled :{}",channel);
            }
            else
            {
                channel = new HttpChannelOverHTTP2(connector, httpConfiguration, endPoint, new HttpTransportOverHTTP2(connector, httpConfiguration, endPoint, (IStream)stream))
                {
                    @Override
                    public void onCompleted()
                    {
                        super.onCompleted();
                        recycle();
                        // TODO limit size?
                        channels.add(this);
                    } 
                };
            }
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
            session.close(ErrorCodes.PROTOCOL_ERROR, reason, Callback.Adapter.INSTANCE);
        }
        
    }
}
