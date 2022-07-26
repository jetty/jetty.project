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

package org.eclipse.jetty.docs.programming.client.http3;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class HTTP3ClientDocs
{
    public void start() throws Exception
    {
        // tag::start[]
        // Instantiate HTTP3Client.
        HTTP3Client http3Client = new HTTP3Client();

        // Configure HTTP3Client, for example:
        http3Client.getHTTP3Configuration().setStreamIdleTimeout(15000);

        // Start HTTP3Client.
        http3Client.start();
        // end::start[]
    }

    public void stop() throws Exception
    {
        HTTP3Client http3Client = new HTTP3Client();
        http3Client.start();
        // tag::stop[]
        // Stop HTTP3Client.
        http3Client.stop();
        // end::stop[]
    }

    public void connect() throws Exception
    {
        HTTP3Client http3Client = new HTTP3Client();
        http3Client.start();
        // tag::connect[]
        // Address of the server's port.
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);

        // Connect to the server, the CompletableFuture will be
        // notified when the connection is succeeded (or failed).
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});

        // Block to obtain the Session.
        // Alternatively you can use the CompletableFuture APIs to avoid blocking.
        Session session = sessionCF.get();
        // end::connect[]
    }

    public void configure() throws Exception
    {
        HTTP3Client http3Client = new HTTP3Client();
        http3Client.start();

        // tag::configure[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        http3Client.connect(serverAddress, new Session.Client.Listener()
        {
            @Override
            public Map<Long, Long> onPreface(Session session)
            {
                Map<Long, Long> configuration = new HashMap<>();

                // Add here configuration settings.

                return configuration;
            }
        });
        // end::configure[]
    }

    public void newStream() throws Exception
    {
        HTTP3Client http3Client = new HTTP3Client();
        http3Client.start();
        // tag::newStream[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});
        Session.Client session = sessionCF.get();

        // Configure the request headers.
        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {version}");

        // The request metadata with method, URI and headers.
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8444/path"), HttpVersion.HTTP_3, requestHeaders);

        // The HTTP/3 HEADERS frame, with endStream=true
        // to signal that this request has no content.
        HeadersFrame headersFrame = new HeadersFrame(request, true);

        // Open a Stream by sending the HEADERS frame.
        session.newRequest(headersFrame, new Stream.Client.Listener() {});
        // end::newStream[]
    }

    public void newStreamWithData() throws Exception
    {
        HTTP3Client http3Client = new HTTP3Client();
        http3Client.start();
        // tag::newStreamWithData[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});
        Session.Client session = sessionCF.get();

        // Configure the request headers.
        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.CONTENT_TYPE, "application/json");

        // The request metadata with method, URI and headers.
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost:8444/path"), HttpVersion.HTTP_3, requestHeaders);

        // The HTTP/3 HEADERS frame, with endStream=false to
        // signal that there will be more frames in this stream.
        HeadersFrame headersFrame = new HeadersFrame(request, false);

        // Open a Stream by sending the HEADERS frame.
        CompletableFuture<Stream> streamCF = session.newRequest(headersFrame, new Stream.Client.Listener() {});

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
        CompletableFuture<Stream> dataCF1 = stream.data(new DataFrame(buffer1, false));

        // Only when the first chunk has been sent we can send the second,
        // with endStream=true to signal that there are no more frames.
        dataCF1.thenCompose(s -> s.data(new DataFrame(buffer2, true)));
        // end::newStreamWithData[]
    }

    public void responseListener() throws Exception
    {
        HTTP3Client http3Client = new HTTP3Client();
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});
        Session.Client session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8444/path"), HttpVersion.HTTP_3, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, true);

        // tag::responseListener[]
        // Open a Stream by sending the HEADERS frame.
        session.newRequest(headersFrame, new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData metaData = frame.getMetaData();
                MetaData.Response response = (MetaData.Response)metaData;
                System.getLogger("http3").log(INFO, "Received response {0}", response);
            }

            @Override
            public void onDataAvailable(Stream.Client stream)
            {
                // Read a chunk of the content.
                Stream.Data data = stream.readData();
                if (data == null)
                {
                    // No data available now, demand to be called back.
                    stream.demand();
                }
                else
                {
                    // Process the content.
                    process(data.getByteBuffer());

                    // Notify the implementation that the content has been consumed.
                    data.release();

                    if (!data.isLast())
                    {
                        // Demand to be called back.
                        stream.demand();
                    }
                }
            }
        });
        // end::responseListener[]
    }

    private void process(ByteBuffer byteBuffer)
    {
    }

    public void reset() throws Exception
    {
        HTTP3Client http3Client = new HTTP3Client();
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});
        Session.Client session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, true);

        // tag::reset[]
        // Open a Stream by sending the HEADERS frame.
        CompletableFuture<Stream> streamCF = session.newRequest(headersFrame, new Stream.Client.Listener()
        {
            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                // The server reset this stream.
            }
        });
        Stream stream = streamCF.get();

        // Reset this stream (for example, the user closed the application).
        stream.reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), new ClosedChannelException());
        // end::reset[]
    }

    // TODO: push not yet implemented in HTTP/3
/*
    public void push() throws Exception
    {
        HTTP3Client http3Client = new HTTP3Client();
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        CompletableFuture<Session> sessionCF = http3Client.connect(serverAddress, new Session.Listener());
        Session session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, null, true);

        // tag::push[]
        // Open a Stream by sending the HEADERS frame.
        CompletableFuture<Stream> streamCF = session.newStream(headersFrame, new Stream.Listener()
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
                return new Adapter()
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
        HTTP3Client http3Client = new HTTP3Client();
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        CompletableFuture<Session> sessionCF = http3Client.connect(serverAddress, new Session.Listener());
        Session session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, null, true);

        // tag::pushReset[]
        // Open a Stream by sending the HEADERS frame.
        CompletableFuture<Stream> streamCF = session.newStream(headersFrame, new Stream.Listener()
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
 */
}
