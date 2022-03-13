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

package org.eclipse.jetty.ee10.http.client;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpChannelOverHTTP;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.fcgi.client.http.HttpChannelOverFCGI;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.client.http.HttpConnectionOverFCGI;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.client.http.internal.HttpChannelOverHTTP2;
import org.eclipse.jetty.http2.client.http.internal.HttpConnectionOverHTTP2;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.http.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.http3.client.http.internal.HttpChannelOverHTTP3;
import org.eclipse.jetty.http3.client.http.internal.HttpConnectionOverHTTP3;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpChannelAssociationTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAssociationFailedAbortsRequest(Transport transport) throws Exception
    {
        init(transport);
        scenario.startServer(new EmptyServerHandler());

        scenario.client = new HttpClient(newHttpClientTransport(scenario, exchange -> false));
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        scenario.client.setExecutor(clientThreads);
        scenario.client.start();

        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIdleTimeoutJustBeforeAssociation(Transport transport) throws Exception
    {
        init(transport);
        scenario.startServer(new EmptyServerHandler());

        long idleTimeout = 1000;
        scenario.client = new HttpClient(newHttpClientTransport(scenario, exchange ->
        {
            // We idle timeout just before the association,
            // we must be able to send the request successfully.
            sleep(2 * idleTimeout);
            return true;
        }));
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        scenario.client.setExecutor(clientThreads);
        scenario.client.setIdleTimeout(idleTimeout);
        scenario.client.start();

        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });

        assertTrue(latch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    private HttpClientTransport newHttpClientTransport(TransportScenario scenario, Predicate<HttpExchange> code)
    {
        switch (scenario.transport)
        {
            case HTTP:
            case HTTPS:
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(scenario.newClientSslContextFactory());
                return new HttpClientTransportOverHTTP(clientConnector)
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
                clientConnector.setSslContextFactory(scenario.newClientSslContextFactory());
                HTTP2Client http2Client = new HTTP2Client(clientConnector);
                return new HttpClientTransportOverHTTP2(http2Client)
                {
                    @Override
                    protected HttpConnectionOverHTTP2 newHttpConnection(HttpDestination destination, Session session)
                    {
                        return new HttpConnectionOverHTTP2(destination, session)
                        {
                            @Override
                            protected HttpChannelOverHTTP2 newHttpChannel()
                            {
                                return new HttpChannelOverHTTP2(getHttpDestination(), this, getSession())
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
                http3Client.getClientConnector().setSslContextFactory(scenario.newClientSslContextFactory());
                http3Client.getQuicConfiguration().setVerifyPeerCertificates(false);
                return new HttpClientTransportOverHTTP3(http3Client)
                {
                    @Override
                    protected HttpConnection newHttpConnection(HttpDestination destination, HTTP3SessionClient session)
                    {
                        return new HttpConnectionOverHTTP3(destination, session)
                        {
                            @Override
                            protected HttpChannelOverHTTP3 newHttpChannel()
                            {
                                return new HttpChannelOverHTTP3(getHttpDestination(), this, getSession())
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
                clientConnector.setSslContextFactory(scenario.newClientSslContextFactory());
                return new HttpClientTransportOverFCGI(clientConnector, "")
                {
                    @Override
                    protected org.eclipse.jetty.io.Connection newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
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
                ClientConnector clientConnector = ClientConnector.forUnixDomain(scenario.unixDomainPath);
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(scenario.newClientSslContextFactory());
                return new HttpClientTransportOverHTTP(clientConnector)
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
            default:
            {
                throw new IllegalArgumentException();
            }
        }
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
