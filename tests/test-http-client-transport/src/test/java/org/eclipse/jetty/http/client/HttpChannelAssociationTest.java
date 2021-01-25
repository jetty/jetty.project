//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
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
import org.eclipse.jetty.http2.client.http.HttpChannelOverHTTP2;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.client.http.HttpConnectionOverHTTP2;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
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

        scenario.client = new HttpClient(newHttpClientTransport(scenario, exchange -> false), scenario.newClientSslContextFactory());
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
        SslContextFactory sslContextFactory = scenario.newClientSslContextFactory();
        scenario.client = new HttpClient(newHttpClientTransport(scenario, exchange ->
        {
            // We idle timeout just before the association,
            // we must be able to send the request successfully.
            sleep(2 * idleTimeout);
            return true;
        }), sslContextFactory);
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
                return new HttpClientTransportOverHTTP(1)
                {
                    @Override
                    protected HttpConnectionOverHTTP newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
                    {
                        return new HttpConnectionOverHTTP(endPoint, destination, promise)
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
                HTTP2Client http2Client = new HTTP2Client();
                http2Client.setSelectors(1);
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
            case FCGI:
            {
                return new HttpClientTransportOverFCGI(1, false, "")
                {
                    @Override
                    protected HttpConnectionOverFCGI newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
                    {
                        return new HttpConnectionOverFCGI(endPoint, destination, promise, isMultiplexed())
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
            case UNIX_SOCKET:
            {
                return new HttpClientTransportOverUnixSockets(scenario.sockFile.toString())
                {
                    @Override
                    protected HttpConnectionOverHTTP newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
                    {
                        return new HttpConnectionOverHTTP(endPoint, destination, promise)
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
