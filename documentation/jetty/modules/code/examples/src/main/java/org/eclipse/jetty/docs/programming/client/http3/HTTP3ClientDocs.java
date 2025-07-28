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
import java.util.concurrent.CompletableFuture;

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
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class HTTP3ClientDocs
{
    public void start() throws Exception
    {
        // tag::start[]
        // Instantiate HTTP3Client.
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));

        // Configure HTTP3Client, for example:
        http3Client.getHTTP3Configuration().setStreamIdleTimeout(15000);

        // Start HTTP3Client.
        http3Client.start();
        // end::start[]
    }

    public void stop() throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
        http3Client.start();
        // tag::stop[]
        // Stop HTTP3Client.
        http3Client.stop();
        // end::stop[]
    }

    public void connect() throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
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
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
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
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
        http3Client.start();
        // tag::newStream[]
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});
        Session.Client session = sessionCF.get();

        // Configure the request headers.
        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {jetty-version}");

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
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
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
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});
        Session.Client session = sessionCF.get();

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
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8444);
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});
        Session.Client session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP3Client {jetty-version}");
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

    public void close() throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});
        Session.Client session = sessionCF.get();

        // tag::close[]
        session.goAway(false);
        // end::close[]
    }

    public void retryStream() throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
        http3Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        CompletableFuture<Session.Client> sessionCF = http3Client.connect(serverAddress, new Session.Client.Listener() {});
        Session.Client session = sessionCF.get();

        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_3, HttpFields.EMPTY);
        HeadersFrame headersFrame = new HeadersFrame(request, true);

        // tag::retryStream[]
        CompletableFuture<Stream> streamCF = session.newRequest(headersFrame, new Stream.Client.Listener()
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
        });
        // end::retryStream[]
    }
}
