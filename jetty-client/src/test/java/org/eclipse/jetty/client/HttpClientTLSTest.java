//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpClientTLSTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(SslContextFactory sslContextFactory, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        server.setHandler(handler);
        server.start();
    }

    private void startClient(SslContextFactory sslContextFactory) throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(sslContextFactory);
        client.setExecutor(clientThreads);
        client.start();
    }

    private SslContextFactory createSslContextFactory()
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setEndpointIdentificationAlgorithm("");
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");
        return sslContextFactory;
    }

    @After
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    @Test
    public void testNoCommonTLSProtocol() throws Exception
    {
        SslContextFactory serverTLSFactory = createSslContextFactory();
        serverTLSFactory.setIncludeProtocols("TLSv1.2");
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createSslContextFactory();
        clientTLSFactory.setIncludeProtocols("TLSv1.1");
        startClient(clientTLSFactory);

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected.
        }
    }

    @Test
    public void testNoCommonTLSCiphers() throws Exception
    {
        SslContextFactory serverTLSFactory = createSslContextFactory();
        serverTLSFactory.setIncludeCipherSuites("TLS_RSA_WITH_AES_128_CBC_SHA");
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createSslContextFactory();
        clientTLSFactory.setExcludeCipherSuites(".*_SHA$");
        startClient(clientTLSFactory);

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected.
        }
    }

    @Test
    public void testMismatchBetweenTLSProtocolAndTLSCiphersOnServer() throws Exception
    {
        SslContextFactory serverTLSFactory = createSslContextFactory();
        // TLS 1.1 protocol, but only TLS 1.2 ciphers.
        serverTLSFactory.setIncludeProtocols("TLSv1.1");
        serverTLSFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createSslContextFactory();
        startClient(clientTLSFactory);

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected.
        }
    }

    @Test
    public void testMismatchBetweenTLSProtocolAndTLSCiphersOnClient() throws Exception
    {
        SslContextFactory serverTLSFactory = createSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createSslContextFactory();
        // TLS 1.1 protocol, but only TLS 1.2 ciphers.
        clientTLSFactory.setIncludeProtocols("TLSv1.1");
        clientTLSFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        startClient(clientTLSFactory);

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected.
        }
    }

    @Test
    public void testTLSLargeFragments() throws Exception
    {
        final CountDownLatch serverLatch = new CountDownLatch(1);
        SslContextFactory serverTLSFactory = createSslContextFactory();
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
        SslConnectionFactory ssl = new SslConnectionFactory(serverTLSFactory, http.getProtocol())
        {
            @Override
            protected SslConnection newSslConnection(Connector connector, EndPoint endPoint, SSLEngine engine)
            {
                return new SslConnection(connector.getByteBufferPool(), connector.getExecutor(), endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                {
                    @Override
                    protected SSLEngineResult unwrap(SSLEngine sslEngine, ByteBuffer input, ByteBuffer output) throws SSLException
                    {
                        int inputBytes = input.remaining();
                        SSLEngineResult result = super.unwrap(sslEngine, input, output);
                        if (inputBytes == 5)
                            serverLatch.countDown();
                        return result;
                    }
                };
            }
        };
        connector = new ServerConnector(server, 1, 1, ssl, http);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        long idleTimeout = 2000;

        final CountDownLatch clientLatch = new CountDownLatch(1);
        final SslContextFactory clientTLSFactory = createSslContextFactory();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(
            new HttpClientTransportOverHTTP()
            {
                @Override
                public HttpDestination newHttpDestination(Origin origin)
                {
                    return new HttpDestinationOverHTTP(getHttpClient(), origin)
                    {
                        @Override
                        protected ClientConnectionFactory newSslClientConnectionFactory(ClientConnectionFactory connectionFactory)
                        {
                            SslContextFactory sslContextFactory = getHttpClient().getSslContextFactory();
                            ByteBufferPool bufferPool = getHttpClient().getByteBufferPool();
                            Executor executor = getHttpClient().getExecutor();
                            return new SslClientConnectionFactory(sslContextFactory, bufferPool, executor, connectionFactory)
                            {
                                @Override
                                protected SslConnection newSslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
                                {
                                    return new SslConnection(byteBufferPool, executor, endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                                    {
                                        @Override
                                        protected SSLEngineResult wrap(SSLEngine sslEngine, ByteBuffer[] input, ByteBuffer output) throws SSLException
                                        {
                                            try
                                            {
                                                clientLatch.countDown();
                                                assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
                                                return super.wrap(sslEngine, input, output);
                                            }
                                            catch (InterruptedException x)
                                            {
                                                throw new SSLException(x);
                                            }
                                        }
                                    };
                                }
                            };
                        }
                    };
                }
            }, clientTLSFactory);
        client.setIdleTimeout(idleTimeout);
        client.setExecutor(clientThreads);
        client.start();

        String host = "localhost";
        int port = connector.getLocalPort();

        final CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest(host, port)
            .scheme(HttpScheme.HTTPS.asString())
            .send(new Response.CompleteListener()
                  {
                      public void onComplete(Result result)
                      {
                          assertTrue(result.isSucceeded());
                          assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                          responseLatch.countDown();
                      }
                  }
            );
        // Wait for the TLS buffers to be acquired by the client, then the
        // HTTP request will be paused waiting for the TLS buffer to be expanded.
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        // Send the large frame bytes that will enlarge the TLS buffers.
        try (Socket socket = new Socket(host, port))
        {
            OutputStream output = socket.getOutputStream();
            byte[] largeFrameBytes = new byte[5];
            largeFrameBytes[0] = 22; // Type = handshake
            largeFrameBytes[1] = 3; // Major TLS version
            largeFrameBytes[2] = 3; // Minor TLS version
            // Frame length is 0x7FFF == 32767, i.e. a "large fragment".
            // Maximum allowed by RFC 8446 is 16384, but SSLEngine supports up to 33093.
            largeFrameBytes[3] = 0x7F; // Length hi byte
            largeFrameBytes[4] = (byte)0xFF; // Length lo byte
            output.write(largeFrameBytes);
            output.flush();
            // Just close the connection now, the large frame
            // length was enough to trigger the buffer expansion.
        }

        // The HTTP request will resume and be forced to handle the TLS buffer expansion.
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }
}
