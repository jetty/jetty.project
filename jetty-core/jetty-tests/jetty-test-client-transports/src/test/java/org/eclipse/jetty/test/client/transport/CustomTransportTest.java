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

package org.eclipse.jetty.test.client.transport;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.ContentSourceRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MemoryEndPointPipe;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * <p>Tests a proxy scenario where the proxy HttpClient wants to send the request bytes to an in-memory gateway,
 * and receive response bytes from it. The in-memory gateway sends the request bytes over the network
 * for example via SSH, and then receives the response bytes from SSH, which should be relayed back to HttpClient.</p>
 * <p>Simulates the following flows:</p>
 * {@code
 * Client -> Proxy -> HttpClient.newRequest() -> Local MemoryEndPoint -> Request Bytes -> In-Memory Gateway (SSH) - -> Remote Server
 * |
 * Remote Server (SSH) - - > In-Memory Gateway -> Response Bytes -> Remote MemoryEndPoint -> HttpClient -> Proxy -> Client
 * }
 */
public class CustomTransportTest
{
    private static final Logger LOG = LoggerFactory.getLogger(CustomTransportTest.class);

    private static final String CONTENT = "CONTENT";

    private Server server;
    private HttpClient httpClient;

    @BeforeEach
    public void prepare()
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        serverThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSelectors(1);
        httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        server.addBean(httpClient);
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testCustomTransport() throws Exception
    {
        Gateway gateway = new Gateway();

        ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                var gatewayRequest = httpClient.newRequest("http://localhost/")
                    .transport(new GatewayTransport(httpClient.getScheduler(), gateway))
                    .method(request.getMethod())
                    .path(request.getHttpURI().getPathQuery())
                    .timeout(5, TimeUnit.SECONDS);

                // Copy some of the headers.
                String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
                if (contentType != null)
                    gatewayRequest.headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, contentType));

                // Copy the request content.
                if (request.getLength() != 0)
                    gatewayRequest.body(new ContentSourceRequestContent(request));

                // Send the request.
                // It will be serialized into bytes and sent to the Gateway.
                CompletableFuture<ContentResponse> completable = new CompletableResponseListener(gatewayRequest).send();
                completable.whenComplete((r, x) ->
                {
                    if (x == null)
                    {
                        // Copy the response headers.
                        response.getHeaders().add(r.getHeaders());
                        // Remove Content-Encoding, as the content has already been decoded.
                        response.getHeaders().remove(HttpHeader.CONTENT_ENCODING);
                        // Copy the response content.
                        response.write(true, RetainableByteBuffer.wrap(r.getContent()), callback);
                    }
                    else
                    {
                        Response.writeError(request, response, callback, x);
                    }
                });

                return true;
            }
        });
        server.start();

        // Make a request to the server, it will be forwarded to the external system in bytes.
        var request = httpClient.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .body(new StringRequestContent("REQUEST"))
            .timeout(5, TimeUnit.SECONDS);

        if (LOG.isDebugEnabled())
            LOG.debug("Sending request {}", request);

        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send();

        // After a while, simulate that the Gateway sends back data on Channel 1.
        Thread.sleep(500);
        gateway.onData(1);

        ContentResponse response = completable.get(5, TimeUnit.SECONDS);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is(CONTENT));
    }

    private static class GatewayTransport implements Transport
    {
        private final Scheduler scheduler;
        private final Gateway gateway;

        private GatewayTransport(Scheduler scheduler, Gateway gateway)
        {
            this.scheduler = scheduler;
            this.gateway = gateway;
        }

        @Override
        public void connect(SocketAddress socketAddress, Map<String, Object> context)
        {
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
            try
            {
                // Create the Pipe to connect client and server.
                MemoryEndPointPipe pipe = new MemoryEndPointPipe(scheduler, Runnable::run, socketAddress);
                EndPoint localEndPoint = pipe.getLocalEndPoint();
                EndPoint remoteEndPoint = pipe.getRemoteEndPoint();
                if (LOG.isDebugEnabled())
                    LOG.debug("Connected to {}, local={}, remote={}", socketAddress, localEndPoint, remoteEndPoint);

                // Set up the server-side.
                gateway.onConnect(remoteEndPoint);

                // Set up the client-side.
                ClientConnector clientConnector = (ClientConnector)context.get(ClientConnector.CONTEXT_KEY);
                localEndPoint.setIdleTimeout(clientConnector.getIdleTimeout().toMillis());
                Transport transport = (Transport)context.get(Transport.CONTEXT_KEY);
                Connection connection = transport.newConnection(localEndPoint, context);
                localEndPoint.setConnection(connection);

                localEndPoint.onOpen();
                connection.onOpen();
            }
            catch (Throwable x)
            {
                promise.failed(x);
            }
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(gateway);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj instanceof GatewayTransport that)
                return Objects.equals(gateway, that.gateway);
            return false;
        }
    }

    private static class Gateway
    {
        private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();

        public void onConnect(EndPoint endPoint)
        {
            // For every new connection, generate a new Channel,
            // and associate the Channel with the EndPoint.
            Channel channel = new Channel(endPoint);
            channels.put(channel.id, channel);

            // Register for read interest with the EndPoint.
            RemoteFillCallback remoteFillCallback = new RemoteFillCallback(channel);
            endPoint.fillInterested(Callback.from(remoteFillCallback::iterate));
        }

        // Called when there is data to read from the Gateway on the given Channel.
        public void onData(int id)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("received S->G data on channel {}", id);

            Channel channel = channels.get(id);
            // Simulate the data to read.
            channel.data = RetainableByteBuffer.wrap("""
                HTTP/1.1 200 OK
                Content-Length: %d
                
                """.formatted(CONTENT.length()) + CONTENT, StandardCharsets.UTF_8);
            new ChannelToEndPointCallback(channel).iterate();
        }

        private class Channel
        {
            // Channels should have different ids,
            // hard-coding the id just for the test.
            private final int id = 1;
            private final EndPoint endPoint;
            private RetainableByteBuffer data;

            public Channel(EndPoint endPoint)
            {
                this.endPoint = endPoint;
            }

            public void close(Throwable failure)
            {
                // Close the Gateway Channel, possibly due to a failure.
                channels.remove(id);
                endPoint.close(failure);
            }

            public void demand()
            {
                // Demands to be notified by calling Gateway.onData()
                // when there is data to read from the Gateway.
            }

            public long read(RetainableByteBuffer.Mutable buffer)
            {
                // This simulates response data arriving from the Gateway.
                if (data == null)
                    return 0;
                RetainableByteBuffer received = data;
                data = null;
                long length = received.remaining();
                buffer.put(received);
                return length;
            }

            public void write(RetainableByteBuffer buffer, Callback callback)
            {
                // Consume the buffer and simulate that the write operation succeeded.
                buffer.consume(buffer.remaining());
                callback.succeeded();
            }
        }

        // Reads from the EndPoint, and writes to the Gateway Channel.
        private static class RemoteFillCallback extends IteratingCallback
        {
            private final Channel channel;

            private RemoteFillCallback(Channel channel)
            {
                this.channel = channel;
            }

            @Override
            protected Action process() throws Throwable
            {
                EndPoint endPoint = channel.endPoint;
                RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(1024, false);
                int filled = endPoint.fill(buffer);

                if (LOG.isDebugEnabled())
                    LOG.debug("filled C->G {} bytes from {}", filled, endPoint);

                if (filled < 0)
                    return Action.SUCCEEDED;
                if (filled == 0)
                {
                    endPoint.fillInterested(this);
                    return Action.IDLE;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("writing G->S {} bytes on channel {}", filled, channel.id);

                channel.write(buffer, Callback.from(this::iterate));
                return Action.SCHEDULED;
            }

            @Override
            protected void onCompleteSuccess()
            {
                // Nothing more to read, close the Gateway Channel.
                channel.close(null);
            }

            @Override
            protected void onFailure(Throwable cause)
            {
                // There was a write error, close the Gateway Channel.
                channel.close(cause);
            }
        }

        // Reads from the Gateway Channel, and writes to the EndPoint.
        private static class ChannelToEndPointCallback extends IteratingCallback
        {
            private final Channel channel;

            private ChannelToEndPointCallback(Channel channel)
            {
                this.channel = channel;
            }

            @Override
            protected Action process()
            {
                RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(1024, false);
                // Read from the Gateway Channel.
                long read = channel.read(buffer);

                if (LOG.isDebugEnabled())
                    LOG.debug("read S->G {} bytes from channel {}", read, channel.id);

                if (read < 0)
                    return Action.SUCCEEDED;
                if (read == 0)
                {
                    channel.demand();
                    return Action.IDLE;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("writing G->C {} bytes to {}", read, channel.endPoint);

                // Write to the EndPoint.
                channel.endPoint.write(buffer, this);
                return Action.SCHEDULED;
            }

            @Override
            protected void onCompleteSuccess()
            {
                // Nothing more to read, close the Gateway Channel.
                channel.close(null);
            }

            @Override
            protected void onFailure(Throwable cause)
            {
                // There was a write error, close the Gateway Channel.
                channel.close(cause);
            }
        }
    }
}
