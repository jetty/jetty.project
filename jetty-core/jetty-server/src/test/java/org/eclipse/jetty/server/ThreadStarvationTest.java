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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled // TODO
public class ThreadStarvationTest
{
    static final int BUFFER_SIZE = 1024 * 1024;
    static final int BUFFERS = 64;
    static final int THREADS = 5;
    static final int CLIENTS = THREADS + 2;

    interface ConnectorProvider
    {
        ServerConnector newConnector(Server server, int acceptors, int selectors);
    }

    interface ClientSocketProvider
    {
        Socket newSocket(String host, int port) throws IOException;
    }

    public static Stream<Arguments> scenarios()
    {
        List<Scenario> params = new ArrayList<>();

        // HTTP
        ConnectorProvider http = ServerConnector::new;
        ClientSocketProvider httpClient = Socket::new;
        params.add(new Scenario("http", http, httpClient));

        // HTTPS/SSL/TLS
        ConnectorProvider https = (server, acceptors, selectors) ->
        {
            Path keystorePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStorePath(keystorePath.toString());
            sslContextFactory.setKeyStorePassword("storepwd");
            ByteBufferPool pool = new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged());

            HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
            ServerConnector connector = new ServerConnector(server, null, null, pool, acceptors, selectors,
                AbstractConnectionFactory.getFactories(sslContextFactory, httpConnectionFactory));
            SecureRequestCustomizer secureRequestCustomer = new SecureRequestCustomizer();
            secureRequestCustomer.setSslSessionAttribute("SSL_SESSION");
            httpConnectionFactory.getHttpConfiguration().addCustomizer(secureRequestCustomer);
            return connector;
        };
        ClientSocketProvider httpsClient = new ClientSocketProvider()
        {
            private SSLContext sslContext;

            {
                try
                {
                    HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
                    sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());
                    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            }

            @Override
            public Socket newSocket(String host, int port) throws IOException
            {
                return sslContext.getSocketFactory().createSocket(host, port);
            }
        };
        params.add(new Scenario("https/ssl/tls", https, httpsClient));

        return params.stream().map(Arguments::of);
    }

    private QueuedThreadPool _threadPool;
    private Server _server;
    private ServerConnector _connector;

    private Server prepareServer(Scenario scenario, Handler handler)
    {
        _threadPool = new QueuedThreadPool();
        _threadPool.setMinThreads(THREADS);
        _threadPool.setMaxThreads(THREADS);
        _threadPool.setDetailedDump(true);
        _server = new Server(_threadPool);
        int acceptors = 1;
        int selectors = 1;
        _connector = scenario.connectorProvider.newConnector(_server, acceptors, selectors);
        _server.addConnector(_connector);
        _server.setHandler(handler);
        return _server;
    }

    @AfterEach
    public void dispose() throws Exception
    {
        _server.stop();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testReadInput(Scenario scenario) throws Exception
    {
        prepareServer(scenario, new ReadHandler()).start();

        try (Socket client = scenario.clientSocketProvider.newSocket("localhost", _connector.getLocalPort()))
        {
            client.setSoTimeout(10000);
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            String request =
                "GET / HTTP/1.0\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: 10\r\n" +
                    "\r\n" +
                    "0123456789\r\n";
            os.write(request.getBytes(StandardCharsets.UTF_8));
            os.flush();

            String response = IO.toString(is);
            assertEquals(-1, is.read());
            assertThat(response, containsString("200 OK"));
            assertThat(response, containsString("Read Input 10"));
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testReadStarvation(Scenario scenario) throws Exception
    {
        prepareServer(scenario, new ReadHandler());
        _server.start();

        ExecutorService clientExecutors = Executors.newFixedThreadPool(CLIENTS);

        List<Callable<String>> clientTasks = new ArrayList<>();

        for (int i = 0; i < CLIENTS; i++)
        {
            clientTasks.add(() ->
            {
                try (Socket client = scenario.clientSocketProvider.newSocket("localhost", _connector.getLocalPort());
                     OutputStream out = client.getOutputStream();
                     InputStream in = client.getInputStream())
                {
                    client.setSoTimeout(10000);

                    String request =
                        "PUT / HTTP/1.0\r\n" +
                            "host: localhost\r\n" +
                            "content-length: 10\r\n" +
                            "\r\n" +
                            "1";

                    // Write partial request
                    out.write(request.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    // Finish Request
                    Thread.sleep(1500);
                    out.write(("234567890\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    // Read Response
                    String response = IO.toString(in);
                    assertEquals(-1, in.read());
                    return response;
                }
            });
        }

        try
        {
            List<Future<String>> responses = clientExecutors.invokeAll(clientTasks, 60, TimeUnit.SECONDS);

            for (Future<String> responseFut : responses)
            {
                String response = responseFut.get();
                assertThat(response, containsString("200 OK"));
                assertThat(response, containsString("Read Input 10"));
            }
        }
        finally
        {
            clientExecutors.shutdownNow();
        }
    }

    protected static class ReadHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            /* TODO
            baseRequest.setHandled(true);

            if (request.getDispatcherType() == DispatcherType.REQUEST)
            {
                response.setStatus(200);

                int l = request.getContentLength();
                int r = 0;
                while (r < l)
                {
                    if (request.getInputStream().read() >= 0)
                        r++;
                }

                response.write(true, callback, ByteBuffer.wrap(("Read Input " + r + "\r\n").getBytes()));
            }
            else
            {
                response.sendError(HttpStatus.INTERNAL_SERVER_ERROR_500);
            }

             */
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testWriteStarvation(Scenario scenario) throws Exception
    {
        prepareServer(scenario, new WriteHandler());
        _server.start();

        ExecutorService clientExecutors = Executors.newFixedThreadPool(CLIENTS);

        List<Callable<Long>> clientTasks = new ArrayList<>();

        for (int i = 0; i < CLIENTS; i++)
        {
            clientTasks.add(() ->
            {
                try (Socket client = scenario.clientSocketProvider.newSocket("localhost", _connector.getLocalPort());
                     OutputStream out = client.getOutputStream();
                     InputStream in = client.getInputStream())
                {
                    client.setSoTimeout(30000);

                    String request =
                        "GET / HTTP/1.0\r\n" +
                            "host: localhost\r\n" +
                            "\r\n";

                    // Write GET request
                    out.write(request.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    TimeUnit.MILLISECONDS.sleep(1500);

                    // Read Response
                    long bodyCount = 0;
                    long len;

                    byte[] buf = new byte[1024];

                    try
                    {
                        while ((len = in.read(buf, 0, buf.length)) != -1)
                        {
                            for (int x = 0; x < len; x++)
                            {
                                if (buf[x] == '!')
                                    bodyCount++;
                            }
                        }
                    }
                    catch (Throwable th)
                    {
                        _server.dumpStdErr();
                        throw th;
                    }
                    return bodyCount;
                }
            });
        }

        try
        {
            List<Future<Long>> responses = clientExecutors.invokeAll(clientTasks, 60, TimeUnit.SECONDS);

            long expected = BUFFERS * BUFFER_SIZE;
            for (Future<Long> responseFut : responses)
            {
                Long bodyCount = responseFut.get();
                assertThat(bodyCount, is(expected));
            }
        }
        finally
        {
            clientExecutors.shutdownNow();
        }
    }

    protected static class WriteHandler extends Handler.Processor
    {
        byte[] content = new byte[BUFFER_SIZE];

        {
            // Using a character that will not show up in an HTTP response header
            Arrays.fill(content, (byte)'!');
        }

        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            /* TODO
            baseRequest.setHandled(true);
            response.setStatus(200);

            response.setContentLength(BUFFERS * BUFFER_SIZE);
            OutputStream out = response.getOutputStream();
            for (int i = 0; i < BUFFERS; i++)
            {
                out.write(content);
                out.flush();
            }

             */
        }
    }

    public static class Scenario
    {
        public final String testType;
        public final ConnectorProvider connectorProvider;
        public final ClientSocketProvider clientSocketProvider;

        public Scenario(String testType, ConnectorProvider connectorProvider, ClientSocketProvider clientSocketProvider)
        {
            this.testType = testType;
            this.connectorProvider = connectorProvider;
            this.clientSocketProvider = clientSocketProvider;
        }

        @Override
        public String toString()
        {
            return this.testType;
        }
    }
}
