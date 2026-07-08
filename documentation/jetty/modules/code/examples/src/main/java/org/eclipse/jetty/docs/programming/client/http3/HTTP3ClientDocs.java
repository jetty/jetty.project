//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.RetryableStreamException;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientQuicConfiguration;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.client.QuicClient;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.client.QuicTransport;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Promise;

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class HTTP3ClientDocs
{
    public void start() throws Exception
    {
        // tag::start[]
        // Create a QUIC configuration suitable for HTTP/3.
        ClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        // Instantiate HTTP3Client.
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);

        // Configure HTTP/3 features, for example:
        http3Client.getHTTP3Configuration().setStreamIdleTimeout(15000);

        // Start HTTP3Client.
        http3Client.start();
        // end::start[]
    }

    public void stop() throws Exception
    {
        ClientQuicConfiguration quicConfiguration = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(quicConfiguration);
        http3Client.start();
        // tag::stop[]
        // Stop HTTP3Client.
        http3Client.stop();
        // end::stop[]
    }

    public void connect() throws Exception
    {
        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
        http3Client.start();
        // tag::connect[]
        // Address of the server's port.
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);

        // Connect to the server, the Promise will be
        // notified when the connection is succeeded (or failed).
        http3Client.connect(new QuicTransport(new QuicClient(clientQuicConfig)), serverAddress, new Session.Client.Listener() {}, new Promise.Invocable.NonBlocking<>()
        {
            @Override
            public void succeeded(Session.Client result)
            {
                // Connected successfully.
            }

            @Override
            public void failed(Throwable x)
            {
                // Failed to connect.
            }
        });
        // end::connect[]
    }

    public void configure() throws Exception
    {
        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
        http3Client.start();

        // tag::configure[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        http3Client.connect(new QuicTransport(new QuicClient(clientQuicConfig)), serverAddress, new Session.Client.Listener()
        {
            @Override
            public Map<Long, Long> onPreface(Session session)
            {
                Map<Long, Long> configuration = new HashMap<>();

                // Add here configuration settings.

                return configuration;
            }
        }, Promise.Invocable.noop());
        // end::configure[]
    }

    public void newStream() throws Exception
    {
        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
        http3Client.start();
        // tag::newStream[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        Session.Client session = Blocker.blockWithPromise(p -> http3Client.connect(new QuicTransport(new QuicClient(clientQuicConfig)), serverAddress, new Session.Client.Listener() {}, p));

        // Configure the request headers.
        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {jetty-version}");

        // The request metadata with method, URI and headers.
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8444/path"), HttpVersion.HTTP_3, requestHeaders);

        // The HTTP/3 HEADERS frame, with last=true to signal
        // that there will be no more frames in this stream.
        HeadersFrame headersFrame = new HeadersFrame(request, true);

        // Open a stream by sending the HEADERS frame.
        session.newRequest(headersFrame, new Stream.Client.Listener() {}, Promise.Invocable.noop());
        // end::newStream[]
    }

    public void newStreamWithData() throws Exception
    {
        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
        http3Client.start();
        // tag::newStreamWithData[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        Session.Client session = Blocker.blockWithPromise(p -> http3Client.connect(new QuicTransport(new QuicClient(clientQuicConfig)), serverAddress, new Session.Client.Listener() {}, p));

        // Configure the request headers.
        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.CONTENT_TYPE, "application/json");

        // The request metadata with method, URI and headers.
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost:8444/path"), HttpVersion.HTTP_3, requestHeaders);

        // The HTTP/3 HEADERS frame, with last=false to
        // signal that there will be more frames in this stream.
        HeadersFrame headersFrame = new HeadersFrame(request, false);

        // Open a Stream by sending the HEADERS frame.
        // Block to obtain the Stream (or use a Promise).
        Stream stream = Blocker.blockWithPromise(p -> session.newRequest(headersFrame, new Stream.Client.Listener() {}, p));

        // The request content, in two chunks.
        String content1 = "{\"greet\": \"hello world\"}";
        ByteBuffer buffer1 = StandardCharsets.UTF_8.encode(content1);
        String content2 = "{\"user\": \"jetty\"}";
        ByteBuffer buffer2 = StandardCharsets.UTF_8.encode(content2);

        // Send the first DATA frame on the stream, with last=false
        // to signal that there are more frames in this stream.
        stream.data(new DataFrame(buffer1, false), new Promise.Invocable.NonBlocking<>()
        {
            @Override
            public void succeeded(Stream result)
            {
                // Only when the first chunk has been sent we can send the second,
                // with last=true to signal that there will be no more frames.
                result.data(new DataFrame(buffer2, true), Promise.Invocable.noop());
            }
        });
        // end::newStreamWithData[]
    }

    public void responseListener() throws Exception
    {
        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        Session.Client session = Blocker.blockWithPromise(p -> http3Client.connect(new QuicTransport(new QuicClient(clientQuicConfig)), serverAddress, new Session.Client.Listener() {}, p));

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {jetty-version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8444/path"), HttpVersion.HTTP_3, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, true);

        // tag::responseListener[]
        // Open a Stream by sending the HEADERS frame.
        session.newRequest(headersFrame, new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                // Process the response status code and headers, if necessary.
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                System.getLogger("http3").log(INFO, "Received response {0}", response);

                if (!frame.isLast())
                {
                    // There will be content, so call demand() to have
                    // onDataAvailable() be called when the content is available.
                    stream.demand();
                }
            }

            @Override
            public void onDataAvailable(Stream.Client stream)
            {
                // Read a chunk of the content.
                Content.Chunk chunk = stream.read();
                if (chunk == null)
                {
                    // No data available now, demand to be called back.
                    stream.demand();
                }
                else
                {
                    // Process the content.
                    process(chunk.getByteBuffer());

                    // Notify the implementation that the content has been consumed.
                    chunk.release();

                    if (!chunk.isLast())
                    {
                        // Demand to be called back.
                        stream.demand();
                    }
                }
            }
        }, Promise.Invocable.noop());
        // end::responseListener[]
    }

    private void process(ByteBuffer byteBuffer)
    {
    }

    public void terminate() throws Exception
    {
        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        Session.Client session = Blocker.blockWithPromise(p -> http3Client.connect(new QuicTransport(new QuicClient(clientQuicConfig)), serverAddress, new Session.Client.Listener() {}, p));

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {jetty-version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, true);

        // tag::terminate[]
        // Open a Stream by sending the HEADERS frame.
        Stream stream = Blocker.blockWithPromise(p -> session.newRequest(headersFrame, new Stream.Client.Listener()
        {
            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                // The server terminated this stream.
            }
        }, p));

        // The client terminates this stream (for example, the user closed the application).
        stream.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), new ClosedChannelException(), Promise.Invocable.noop());
        // end::terminate[]
    }

    public void close() throws Exception
    {
        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        Session.Client session = Blocker.blockWithPromise(p -> http3Client.connect(new QuicTransport(new QuicClient(clientQuicConfig)), serverAddress, new Session.Client.Listener() {}, p));

        // tag::close[]
        session.goAway(false, Promise.Invocable.noop());
        // end::close[]
    }

    public void retryStream() throws Exception
    {
        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        Session.Client session = Blocker.blockWithPromise(p -> http3Client.connect(new QuicTransport(new QuicClient(clientQuicConfig)), serverAddress, new Session.Client.Listener() {}, p));

        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_3, HttpFields.EMPTY);
        HeadersFrame headersFrame = new HeadersFrame(request, true);

        // tag::retryStream[]
        session.newRequest(headersFrame, new Stream.Client.Listener()
        {
            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                if (failure instanceof RetryableStreamException)
                {
                    // The request may be retried.
                }
                else
                {
                    // The request failed.
                }
            }
        }, Promise.Invocable.noop());
        // end::retryStream[]
    }
}
