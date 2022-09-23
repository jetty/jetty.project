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

package org.eclipse.jetty.docs.programming.server.http2;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.ResourceFactory;

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class HTTP2ServerDocs
{
    public void setup() throws Exception
    {
        // tag::setup[]
        // Create a Server instance.
        Server server = new Server();

        ServerSessionListener sessionListener = new ServerSessionListener() {};

        // Create a ServerConnector with RawHTTP2ServerConnectionFactory.
        RawHTTP2ServerConnectionFactory http2 = new RawHTTP2ServerConnectionFactory(sessionListener);

        // Configure RawHTTP2ServerConnectionFactory, for example:

        // Configure the max number of concurrent requests.
        http2.setMaxConcurrentStreams(128);
        // Enable support for CONNECT.
        http2.setConnectProtocolEnabled(true);

        // Create the ServerConnector.
        ServerConnector connector = new ServerConnector(server, http2);

        // Add the Connector to the Server
        server.addConnector(connector);

        // Start the Server so it starts accepting connections from clients.
        server.start();
        // end::setup[]
    }

    public void accept()
    {
        // tag::accept[]
        ServerSessionListener sessionListener = new ServerSessionListener()
        {
            @Override
            public void onAccept(Session session)
            {
                SocketAddress remoteAddress = session.getRemoteSocketAddress();
                System.getLogger("http2").log(INFO, "Connection from {0}", remoteAddress);
            }
        };
        // end::accept[]
    }

    public void preface()
    {
        // tag::preface[]
        ServerSessionListener sessionListener = new ServerSessionListener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                // Customize the settings, for example:
                Map<Integer, Integer> settings = new HashMap<>();

                // Tell the client that HTTP/2 push is disabled.
                settings.put(SettingsFrame.ENABLE_PUSH, 0);

                return settings;
            }
        };
        // end::preface[]
    }

    public void request()
    {
        // tag::request[]
        ServerSessionListener sessionListener = new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                // This is the "new stream" event, so it's guaranteed to be a request.
                MetaData.Request request = (MetaData.Request)frame.getMetaData();

                // Return a Stream.Listener to handle the request events,
                // for example request content events or a request reset.
                return new Stream.Listener()
                {
                    // Override callback methods for events you are interested in.
                };
            }
        };
        // end::request[]
    }

    public void requestContent()
    {
        // tag::requestContent[]
        ServerSessionListener sessionListener = new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();

                // Demand for request data content.
                stream.demand();

                // Return a Stream.Listener to handle the request events.
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();

                        if (data == null)
                        {
                            stream.demand();
                            return;
                        }

                        // Get the content buffer.
                        ByteBuffer buffer = data.frame().getData();

                        // Consume the buffer, here - as an example - just log it.
                        System.getLogger("http2").log(INFO, "Consuming buffer {0}", buffer);

                        // Tell the implementation that the buffer has been consumed.
                        data.release();

                        if (!data.frame().isEndStream())
                        {
                            // Demand more DATA frames when they are available.
                            stream.demand();
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
        ServerSessionListener sessionListener = new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                // Send a response after reading the request.
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if (frame.isEndStream())
                {
                    respond(stream, request);
                    return null;
                }
                else
                {
                    // Demand for request data.
                    stream.demand();

                    // Return a listener to handle the request events.
                    return new Stream.Listener()
                    {
                        @Override
                        public void onDataAvailable(Stream stream)
                        {
                            Stream.Data data = stream.readData();

                            if (data == null)
                            {
                                stream.demand();
                                return;
                            }

                            // Consume the request content.
                            data.release();

                            if (data.frame().isEndStream())
                                respond(stream, request);
                            else
                                stream.demand();
                        }
                    };
                }
            }

            private void respond(Stream stream, MetaData.Request request)
            {
                // Prepare the response HEADERS frame.

                // The response HTTP status and HTTP headers.
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);

                if (HttpMethod.GET.is(request.getMethod()))
                {
                    // The response content.
                    ByteBuffer resourceBytes = getResourceBytes(request);

                    // Send the HEADERS frame with the response status and headers,
                    // and a DATA frame with the response content bytes.
                    stream.headers(new HeadersFrame(stream.getId(), response, null, false))
                        .thenCompose(s -> s.data(new DataFrame(s.getId(), resourceBytes, true)));
                }
                else
                {
                    // Send just the HEADERS frame with the response status and headers.
                    stream.headers(new HeadersFrame(stream.getId(), response, null, true));
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
        ServerSessionListener sessionListener = new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                float requestRate = calculateRequestRate();

                if (requestRate > maxRequestRate)
                {
                    stream.reset(new ResetFrame(stream.getId(), ErrorCode.REFUSED_STREAM_ERROR.code), Callback.NOOP);
                    return null;
                }
                else
                {
                    // The request is accepted.
                    MetaData.Request request = (MetaData.Request)frame.getMetaData();
                    // Return a Stream.Listener to handle the request events.
                    return new Stream.Listener()
                    {
                        // Override callback methods for events you are interested in.
                    };
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

    public void push() throws Exception
    {
        // tag::push[]
        // The favicon bytes.
        ByteBuffer faviconBuffer = BufferUtil.toBuffer(ResourceFactory.root().newResource("/path/to/favicon.ico"), true);

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
                return new Stream.Listener()
                {
                    // Override callback methods for events you are interested in.
                };
            }
        };
        // end::push[]
    }
}
