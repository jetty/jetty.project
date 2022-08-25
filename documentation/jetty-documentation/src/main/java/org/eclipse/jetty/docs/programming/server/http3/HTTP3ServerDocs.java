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

package org.eclipse.jetty.docs.programming.server.http3;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.http3.server.HTTP3ServerConnector;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class HTTP3ServerDocs
{
    public void setup() throws Exception
    {
        // tag::setup[]
        // Create a Server instance.
        Server server = new Server();

        // HTTP/3 is always secure, so it always need a SslContextFactory.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");

        // The listener for session events.
        Session.Server.Listener sessionListener = new Session.Server.Listener() {};

        // Create and configure the RawHTTP3ServerConnectionFactory.
        RawHTTP3ServerConnectionFactory http3 = new RawHTTP3ServerConnectionFactory(sessionListener);
        http3.getHTTP3Configuration().setStreamIdleTimeout(15000);

        // Create and configure the HTTP3ServerConnector.
        HTTP3ServerConnector connector = new HTTP3ServerConnector(server, sslContextFactory, http3);
        // Configure the max number of requests per QUIC connection.
        connector.getQuicConfiguration().setMaxBidirectionalRemoteStreams(1024);

        // Add the Connector to the Server.
        server.addConnector(connector);

        // Start the Server so it starts accepting connections from clients.
        server.start();
        // end::setup[]
    }

    public void accept()
    {
        // tag::accept[]
        Session.Server.Listener sessionListener = new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session session)
            {
                SocketAddress remoteAddress = session.getRemoteSocketAddress();
                System.getLogger("http3").log(INFO, "Connection from {0}", remoteAddress);
            }
        };
        // end::accept[]
    }

    public void preface()
    {
        // tag::preface[]
        Session.Server.Listener sessionListener = new Session.Server.Listener()
        {
            @Override
            public Map<Long, Long> onPreface(Session session)
            {
                Map<Long, Long> settings = new HashMap<>();

                // Customize the settings

                return settings;
            }
        };
        // end::preface[]
    }

    public void request()
    {
        // tag::request[]
        Session.Server.Listener sessionListener = new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();

                // Return a Stream.Server.Listener to handle the request events,
                // for example request content events or a request reset.
                return new Stream.Server.Listener() {};
            }
        };
        // end::request[]
    }

    public void requestContent()
    {
        // tag::requestContent[]
        Session.Server.Listener sessionListener = new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();

                // Demand to be called back when data is available.
                stream.demand();

                // Return a Stream.Server.Listener to handle the request content.
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        // Read a chunk of the request content.
                        Stream.Data data = stream.readData();

                        if (data == null)
                        {
                            // No data available now, demand to be called back.
                            stream.demand();
                        }
                        else
                        {
                            // Get the content buffer.
                            ByteBuffer buffer = data.getByteBuffer();

                            // Consume the buffer, here - as an example - just log it.
                            System.getLogger("http3").log(INFO, "Consuming buffer {0}", buffer);

                            // Tell the implementation that the buffer has been consumed.
                            data.release();

                            if (!data.isLast())
                            {
                                // Demand to be called back.
                                stream.demand();
                            }
                        }
                    }
                };
            }
        };
        // end::requestContent[]
    }

    public void response()
    {
        // tag::response[]
        Session.Server.Listener sessionListener = new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                // Send a response after reading the request.
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if (frame.isLast())
                {
                    respond(stream, request);
                    return null;
                }
                else
                {
                    // Demand to be called back when data is available.
                    stream.demand();
                    return new Stream.Server.Listener()
                    {
                        @Override
                        public void onDataAvailable(Stream.Server stream)
                        {
                            Stream.Data data = stream.readData();
                            if (data == null)
                            {
                                stream.demand();
                            }
                            else
                            {
                                // Consume the request content.
                                data.release();

                                if (data.isLast())
                                    respond(stream, request);
                                else
                                    stream.demand();
                            }
                        }
                    };
                }
            }

            private void respond(Stream.Server stream, MetaData.Request request)
            {
                // Prepare the response HEADERS frame.

                // The response HTTP status and HTTP headers.
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);

                if (HttpMethod.GET.is(request.getMethod()))
                {
                    // The response content.
                    ByteBuffer resourceBytes = getResourceBytes(request);

                    // Send the HEADERS frame with the response status and headers,
                    // and a DATA frame with the response content bytes.
                    stream.respond(new HeadersFrame(response, false))
                        .thenCompose(s -> s.data(new DataFrame(resourceBytes, true)));
                }
                else
                {
                    // Send just the HEADERS frame with the response status and headers.
                    stream.respond(new HeadersFrame(response, true));
                }
            }
            // tag::exclude[]

            private ByteBuffer getResourceBytes(MetaData.Request request)
            {
                return ByteBuffer.allocate(1024);
            }
            // end::exclude[]
        };
        // end::response[]
    }

    public void reset()
    {
        float maxRequestRate = 0F;
        // tag::reset[]
        Session.Server.Listener sessionListener = new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                float requestRate = calculateRequestRate();

                if (requestRate > maxRequestRate)
                {
                    stream.reset(HTTP3ErrorCode.REQUEST_REJECTED_ERROR.code(), new RejectedExecutionException());
                    return null;
                }
                else
                {
                    // The request is accepted.
                    MetaData.Request request = (MetaData.Request)frame.getMetaData();
                    // Return a Stream.Listener to handle the request events.
                    return new Stream.Server.Listener() {};
                }
            }
            // tag::exclude[]

            private float calculateRequestRate()
            {
                return 0F;
            }
            // end::exclude[]
        };
        // end::reset[]
    }

    // TODO: push not yet implemented in HTTP/3.
/*
    public void push() throws Exception
    {
        // tag::push[]
        // The favicon bytes.
        ByteBuffer faviconBuffer = BufferUtil.toBuffer(server.getDefaultFavicon(), true);

        ServerSessionListener sessionListener = new ServerSessionListener()
        {
            // By default, push is enabled.
            private boolean pushEnabled = true;

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                // Check whether the client sent an ENABLE_PUSH setting.
                Map<Integer, Integer> settings = frame.getSettings();
                Integer enablePush = settings.get(SettingsFrame.ENABLE_PUSH);
                if (enablePush != null)
                    pushEnabled = enablePush == 1;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if (pushEnabled && request.getURIString().endsWith("/index.html"))
                {
                    // Push the favicon.
                    HttpURI pushedURI = HttpURI.build(request.getURI()).path("/favicon.ico");
                    MetaData.Request pushedRequest = new MetaData.Request("GET", pushedURI, HttpVersion.HTTP_2, HttpFields.EMPTY);
                    PushPromiseFrame promiseFrame = new PushPromiseFrame(stream.getId(), 0, pushedRequest);
                    stream.push(promiseFrame, null)
                        .thenCompose(pushedStream ->
                        {
                            // Send the favicon "response".
                            MetaData.Response pushedResponse = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                            return pushedStream.headers(new HeadersFrame(pushedStream.getId(), pushedResponse, null, false))
                                .thenCompose(pushed -> pushed.data(new DataFrame(pushed.getId(), faviconBuffer, true)));
                        });
                }
                // Return a Stream.Listener to handle the request events.
                return new Stream.Listener();
            }
        };
        // end::push[]
    }
 */
}
