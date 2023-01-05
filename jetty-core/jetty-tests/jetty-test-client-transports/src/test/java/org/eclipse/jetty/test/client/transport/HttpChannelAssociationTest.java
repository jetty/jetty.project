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

package org.eclipse.jetty.test.client.transport;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.internal.HttpExchange;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.transport.internal.HttpChannelOverHTTP;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.fcgi.client.transport.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.client.transport.internal.HttpChannelOverFCGI;
import org.eclipse.jetty.fcgi.client.transport.internal.HttpConnectionOverFCGI;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.client.transport.internal.HttpChannelOverHTTP2;
import org.eclipse.jetty.http2.client.transport.internal.HttpConnectionOverHTTP2;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.http3.client.transport.internal.HttpChannelOverHTTP3;
import org.eclipse.jetty.http3.client.transport.internal.HttpConnectionOverHTTP3;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpChannelAssociationTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testAssociationFailedAbortsRequest(Transport transport) throws Exception
    {
        startServer(transport, new EmptyServerHandler());

        client = new HttpClient(newHttpClientTransport(transport, exchange -> false));
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client.setExecutor(clientThreads);
        client.start();

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutJustBeforeAssociation(Transport transport) throws Exception
    {
        startServer(transport, new EmptyServerHandler());

        long idleTimeout = 1000;
        client = new HttpClient(newHttpClientTransport(transport, exchange ->
        {
            // We idle timeout just before the association,
            // we must be able to send the request successfully.
            sleep(2 * idleTimeout);
            return true;
        }));
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client.setExecutor(clientThreads);
        client.setIdleTimeout(idleTimeout);
        client.start();

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });

        assertTrue(latch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    private HttpClientTransport newHttpClientTransport(Transport transport, Predicate<HttpExchange> code)
    {
        return switch (transport)
        {
            case HTTP:
            case HTTPS:
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(newSslContextFactoryClient());
                yield new HttpClientTransportOverHTTP(clientConnector)
                {
                    @Override
                    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
                    {
                        return new HttpConnectionOverHTTP(endPoint, context)
                        {
                            @Override
                            protected HttpChannelOverHTTP newHttpChannel()
                            {
                                return new HttpChannelOverHTTP(this)
                                {
                                    @Override
                                    public boolean associate(HttpExchange exchange)
                                    {
                                        return code.test(exchange) && super.associate(exchange);
                                    }
                                };
                            }
                        };
                    }
                };
            }
            case H2C:
            case H2:
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(newSslContextFactoryClient());
                HTTP2Client http2Client = new HTTP2Client(clientConnector);
                yield new HttpClientTransportOverHTTP2(http2Client)
                {
                    @Override
                    protected Connection newConnection(Destination destination, Session session)
                    {
                        return new HttpConnectionOverHTTP2(destination, session)
                        {
                            @Override
                            protected HttpChannelOverHTTP2 newHttpChannel()
                            {
                                return new HttpChannelOverHTTP2(this, getSession())
                                {
                                    @Override
                                    public boolean associate(HttpExchange exchange)
                                    {
                                        return code.test(exchange) && super.associate(exchange);
                                    }
                                };
                            }
                        };
                    }
                };
            }
            case H3:
            {
                HTTP3Client http3Client = new HTTP3Client();
                http3Client.getClientConnector().setSelectors(1);
                http3Client.getClientConnector().setSslContextFactory(newSslContextFactoryClient());
                http3Client.getQuicConfiguration().setVerifyPeerCertificates(false);
                yield new HttpClientTransportOverHTTP3(http3Client)
                {
                    @Override
                    protected org.eclipse.jetty.client.Connection newConnection(Destination destination, HTTP3SessionClient session)
                    {
                        return new HttpConnectionOverHTTP3(destination, session)
                        {
                            @Override
                            protected HttpChannelOverHTTP3 newHttpChannel()
                            {
                                return new HttpChannelOverHTTP3(this, getSession())
                                {
                                    @Override
                                    public boolean associate(HttpExchange exchange)
                                    {
                                        return code.test(exchange) && super.associate(exchange);
                                    }
                                };
                            }
                        };
                    }
                };
            }
            case FCGI:
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(newSslContextFactoryClient());
                yield new HttpClientTransportOverFCGI(clientConnector, "")
                {
                    @Override
                    protected org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Destination destination, Promise<Connection> promise)
                    {
                        return new HttpConnectionOverFCGI(endPoint, destination, promise)
                        {
                            @Override
                            protected HttpChannelOverFCGI newHttpChannel(Request request)
                            {
                                return new HttpChannelOverFCGI(this, getFlusher(), request.getIdleTimeout())
                                {
                                    @Override
                                    public boolean associate(HttpExchange exchange)
                                    {
                                        return code.test(exchange) && super.associate(exchange);
                                    }
                                };
                            }
                        };
                    }
                };
            }
            case UNIX_DOMAIN:
            {
                ClientConnector clientConnector = ClientConnector.forUnixDomain(unixDomainPath);
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(newSslContextFactoryClient());
                yield new HttpClientTransportOverHTTP(clientConnector)
                {
                    @Override
                    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
                    {
                        return new HttpConnectionOverHTTP(endPoint, context)
                        {
                            @Override
                            protected HttpChannelOverHTTP newHttpChannel()
                            {
                                return new HttpChannelOverHTTP(this)
                                {
                                    @Override
                                    public boolean associate(HttpExchange exchange)
                                    {
                                        return code.test(exchange) && super.associate(exchange);
                                    }
                                };
                            }
                        };
                    }
                };
            }
        };
    }

    private void sleep(long time)
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }
}
