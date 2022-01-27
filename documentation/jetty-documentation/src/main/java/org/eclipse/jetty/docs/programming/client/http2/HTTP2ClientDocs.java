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

package org.eclipse.jetty.docs.programming.client.http2;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.Callback;

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class HTTP2ClientDocs
{
    public void start() throws Exception
    {
        // tag::start[]
        // Instantiate HTTP2Client.
        HTTP2Client http2Client = new HTTP2Client();

        // Configure HTTP2Client, for example:
        http2Client.setStreamIdleTimeout(15000);

        // Start HTTP2Client.
        http2Client.start();
        // end::start[]
    }

    public void stop() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();
        // tag::stop[]
        // Stop HTTP2Client.
        http2Client.stop();
        // end::stop[]
    }

    public void clearTextConnect() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();
        // tag::clearTextConnect[]
        // Address of the server's clear-text port.
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);

        // Connect to the server, the CompletableFuture will be
        // notified when the connection is succeeded (or failed).
        CompletableFuture<Session> sessionCF = http2Client.connect(serverAddress, new Session.Listener.Adapter());

        // Block to obtain the Session.
        // Alternatively you can use the CompletableFuture APIs to avoid blocking.
        Session session = sessionCF.get();
        // end::clearTextConnect[]
    }

    public void encryptedConnect() throws Exception
    {
        // tag::encryptedConnect[]
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();

        ClientConnector connector = http2Client.getClientConnector();

        // Address of the server's encrypted port.
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8443);

        // Connect to the server, the CompletableFuture will be
        // notified when the connection is succeeded (or failed).
        CompletableFuture<Session> sessionCF = http2Client.connect(connector.getSslContextFactory(), serverAddress, new Session.Listener.Adapter());

        // Block to obtain the Session.
        // Alternatively you can use the CompletableFuture APIs to avoid blocking.
        Session session = sessionCF.get();
        // end::encryptedConnect[]
    }

    public void configure() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();

        // tag::configure[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        http2Client.connect(serverAddress, new Session.Listener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> configuration = new HashMap<>();

                // Disable push from the server.
                configuration.put(SettingsFrame.ENABLE_PUSH, 0);

                // Override HTTP2Client.initialStreamRecvWindow for this session.
                configuration.put(SettingsFrame.INITIAL_WINDOW_SIZE, 1024 * 1024);

                return configuration;
            }
        });
        // end::configure[]
    }

    public void newStream() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();
        // tag::newStream[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        CompletableFuture<Session> sessionCF = http2Client.connect(serverAddress, new Session.Listener.Adapter());
        Session session = sessionCF.get();

        // Configure the request headers.
        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP2Client {version}");

        // The request metadata with method, URI and headers.
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);

        // The HTTP/2 HEADERS frame, with endStream=true
        // to signal that this request has no content.
        HeadersFrame headersFrame = new HeadersFrame(request, null, true);

        // Open a Stream by sending the HEADERS frame.
        session.newStream(headersFrame, new Stream.Listener.Adapter());
        // end::newStream[]
    }

    public void newStreamWithData() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();
        // tag::newStreamWithData[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        CompletableFuture<Session> sessionCF = http2Client.connect(serverAddress, new Session.Listener.Adapter());
        Session session = sessionCF.get();

        // Configure the request headers.
        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.CONTENT_TYPE, "application/json");

        // The request metadata with method, URI and headers.
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);

        // The HTTP/2 HEADERS frame, with endStream=false to
        // signal that there will be more frames in this stream.
        HeadersFrame headersFrame = new HeadersFrame(request, null, false);

        // Open a Stream by sending the HEADERS frame.
        CompletableFuture<Stream> streamCF = session.newStream(headersFrame, new Stream.Listener.Adapter());

        // Block to obtain the Stream.
        // Alternatively you can use the CompletableFuture APIs to avoid blocking.
        Stream stream = streamCF.get();

        // The request content, in two chunks.
        String content1 = "{\"greet\": \"hello world\"}";
        ByteBuffer buffer1 = StandardCharsets.UTF_8.encode(content1);
        String content2 = "{\"user\": \"jetty\"}";
        ByteBuffer buffer2 = StandardCharsets.UTF_8.encode(content2);

        // Send the first DATA frame on the stream, with endStream=false
        // to signal that there are more frames in this stream.
        CompletableFuture<Stream> dataCF1 = stream.data(new DataFrame(stream.getId(), buffer1, false));

        // Only when the first chunk has been sent we can send the second,
        // with endStream=true to signal that there are no more frames.
        dataCF1.thenCompose(s -> s.data(new DataFrame(s.getId(), buffer2, true)));
        // end::newStreamWithData[]
    }

    public void responseListener() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        CompletableFuture<Session> sessionCF = http2Client.connect(serverAddress, new Session.Listener.Adapter());
        Session session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP2Client {version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, null, true);

        // tag::responseListener[]
        // Open a Stream by sending the HEADERS frame.
        session.newStream(headersFrame, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData metaData = frame.getMetaData();

                // Is this HEADERS frame the response or the trailers?
                if (metaData.isResponse())
                {
                    MetaData.Response response = (MetaData.Response)metaData;
                    System.getLogger("http2").log(INFO, "Received response {0}", response);
                }
                else
                {
                    System.getLogger("http2").log(INFO, "Received trailers {0}", metaData.getFields());
                }
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                // Get the content buffer.
                ByteBuffer buffer = frame.getData();

                // Consume the buffer, here - as an example - just log it.
                System.getLogger("http2").log(INFO, "Consuming buffer {0}", buffer);

                // Tell the implementation that the buffer has been consumed.
                callback.succeeded();

                // By returning from the method, implicitly tell the implementation
                // to deliver to this method more DATA frames when they are available.
            }
        });
        // end::responseListener[]
    }

    public void reset() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        CompletableFuture<Session> sessionCF = http2Client.connect(serverAddress, new Session.Listener.Adapter());
        Session session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP2Client {version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, null, true);

        // tag::reset[]
        // Open a Stream by sending the HEADERS frame.
        CompletableFuture<Stream> streamCF = session.newStream(headersFrame, new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                // The server reset this stream.
            }
        });
        Stream stream = streamCF.get();

        // Reset this stream (for example, the user closed the application).
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        // end::reset[]
    }

    public void push() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        CompletableFuture<Session> sessionCF = http2Client.connect(serverAddress, new Session.Listener.Adapter());
        Session session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP2Client {version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, null, true);

        // tag::push[]
        // Open a Stream by sending the HEADERS frame.
        CompletableFuture<Stream> streamCF = session.newStream(headersFrame, new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream pushedStream, PushPromiseFrame frame)
            {
                // The "request" the client would make for the pushed resource.
                MetaData.Request pushedRequest = frame.getMetaData();
                // The pushed "request" URI.
                HttpURI pushedURI = pushedRequest.getURI();
                // The pushed "request" headers.
                HttpFields pushedRequestHeaders = pushedRequest.getFields();

                // If needed, retrieve the primary stream that triggered the push.
                Stream primaryStream = pushedStream.getSession().getStream(frame.getStreamId());

                // Return a Stream.Listener to listen for the pushed "response" events.
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersFrame frame)
                    {
                        // Handle the pushed stream "response".

                        MetaData metaData = frame.getMetaData();
                        if (metaData.isResponse())
                        {
                            // The pushed "response" headers.
                            HttpFields pushedResponseHeaders = metaData.getFields();
                        }
                    }

                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        // Handle the pushed stream "response" content.

                        // The pushed stream "response" content bytes.
                        ByteBuffer buffer = frame.getData();
                        // Consume the buffer and complete the callback.
                        callback.succeeded();
                    }
                };
            }
        });
        // end::push[]
    }

    public void pushReset() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        CompletableFuture<Session> sessionCF = http2Client.connect(serverAddress, new Session.Listener.Adapter());
        Session session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP2Client {version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, null, true);

        // tag::pushReset[]
        // Open a Stream by sending the HEADERS frame.
        CompletableFuture<Stream> streamCF = session.newStream(headersFrame, new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream pushedStream, PushPromiseFrame frame)
            {
                // Reset the pushed stream to tell the server we are not interested.
                pushedStream.reset(new ResetFrame(pushedStream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);

                // Not interested in listening to pushed response events.
                return null;
            }
        });
        // end::pushReset[]
    }
}
