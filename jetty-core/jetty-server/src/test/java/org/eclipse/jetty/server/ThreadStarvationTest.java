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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
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

import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.handler.EagerContentHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

        for (boolean delayed : new boolean[]{false, true})
        {
            // HTTP
            ConnectorProvider http = (server, acceptors, selectors) ->
            {
                ArrayByteBufferPool.Tracking pool = new ArrayByteBufferPool.Tracking();
                HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
                return new ServerConnector(server, null, null, pool, acceptors, selectors, httpConnectionFactory);
            };
            ClientSocketProvider httpClient = Socket::new;
            params.add(new Scenario("http", http, httpClient, delayed));

            // HTTPS/SSL/TLS
            ConnectorProvider https = (server, acceptors, selectors) ->
            {
                Path keystorePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStorePath(keystorePath.toString());
                sslContextFactory.setKeyStorePassword("storepwd");
                ArrayByteBufferPool.Tracking pool = new ArrayByteBufferPool.Tracking();

                HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
                ServerConnector connector = new ServerConnector(server, null, null, pool, acceptors, selectors,
                    AbstractConnectionFactory.getFactories(sslContextFactory, httpConnectionFactory));
                SecureRequestCustomizer secureRequestCustomer = new SecureRequestCustomizer();
                httpConnectionFactory.getHttpConfiguration().addCustomizer(secureRequestCustomer);
                return connector;
            };
            ClientSocketProvider httpsClient = new ClientSocketProvider()
            {
                private final SSLContext sslContext;

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
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public Socket newSocket(String host, int port) throws IOException
                {
                    return sslContext.getSocketFactory().createSocket(host, port);
                }
            };
            params.add(new Scenario("https/ssl/tls", https, httpsClient, delayed));
        }

        return params.stream().map(Arguments::of);
    }

    private Server _server;
    private ServerConnector _connector;

    private void prepareServer(Scenario scenario, Handler handler)
    {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMinThreads(THREADS);
        threadPool.setMaxThreads(THREADS);
        threadPool.setDetailedDump(true);
        _server = new Server(threadPool);
        int acceptors = 1;
        int selectors = 1;
        _connector = scenario.connectorProvider.newConnector(_server, acceptors, selectors);
        _server.addConnector(_connector);
        _server.setHandler(handler);

        if (scenario.delayed)
        {
            _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setDelayDispatchUntilContent(true);
            _server.insertHandler(new EagerContentHandler());
        }
    }

    @AfterEach
    public void dispose() throws Exception
    {
        ArrayByteBufferPool.Tracking byteBufferPool = (ArrayByteBufferPool.Tracking)_server.getConnectors()[0].getByteBufferPool();
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server leaks: " + byteBufferPool.dumpLeaks(), byteBufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            LifeCycle.stop(_server);
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testReadStarvation(Scenario scenario) throws Exception
    {
        prepareServer(scenario, new ReadHandler());
        _server.start();

        ExecutorService clientExecutors = Executors.newFixedThreadPool(CLIENTS);
        try
        {
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

                        String request = """
                            PUT / HTTP/1.0\r
                            host: localhost\r
                            content-length: 10\r
                            \r
                            1""";

                        // Write partial request
                        out.write(request.getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        // Finish Request
                        Thread.sleep(1500);
                        out.write(("234567890").getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        // Read Response
                        String response = IO.toString(in);
                        assertEquals(-1, in.read());
                        return response;
                    }
                });
            }

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

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testFormStarvation(Scenario scenario) throws Exception
    {
        prepareServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Fields fields = FormFields.getFields(request);
                StringBuilder builder = new StringBuilder();
                fields.forEach(field -> builder.append(field.getName()).append('=').append(field.getValue()).append('\n'));
                response.write(true, BufferUtil.toBuffer(builder.toString()), callback);
                return true;
            }
        });
        _server.start();

        ExecutorService clientExecutors = Executors.newFixedThreadPool(CLIENTS);
        try
        {
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

                        String request = """
                            POST / HTTP/1.0\r
                            host: localhost\r
                            content-type: application/x-www-form-urlencoded\r
                            content-length: 11\r
                            \r
                            a=1&b""";

                        // Write partial request
                        out.write(request.getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        // Finish Request
                        Thread.sleep(1500);
                        out.write(("=2&c=3").getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        // Read Response
                        String response = IO.toString(in);
                        assertEquals(-1, in.read());
                        return response;
                    }
                });
            }

            List<Future<String>> responses = clientExecutors.invokeAll(clientTasks, 60, TimeUnit.SECONDS);

            for (Future<String> responseFut : responses)
            {
                String response = responseFut.get();
                assertThat(response, containsString("200 OK"));
                assertThat(response, containsString("a=1"));
                assertThat(response, containsString("b=2"));
                assertThat(response, containsString("c=3"));
            }
        }
        finally
        {
            clientExecutors.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testMultiPartStarvation(Scenario scenario) throws Exception
    {
        MultiPartConfig config = new MultiPartConfig.Builder()
            .maxParts(10)
            .maxMemoryPartSize(Long.MAX_VALUE)
            .maxSize(Long.MAX_VALUE)
            .useFilesForPartsWithoutFileName(false)
            .build();

        prepareServer(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                MultiPartFormData.Parts parts = MultiPartFormData.getParts(request, request, "multipart/form-data; boundary=\"A1B2C3\"", config);
                StringBuilder builder = new StringBuilder();
                parts.forEach(part -> builder.append(part.getName()).append('=').append(part.getContentAsString(StandardCharsets.UTF_8)).append('\n'));
                parts.close();
                response.write(true, BufferUtil.toBuffer(builder.toString()), callback);
                return true;
            }
        });
        _server.start();

        ExecutorService clientExecutors = Executors.newFixedThreadPool(CLIENTS);
        try
        {
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
                        String content = """
                            --A1B2C3
                            Content-Disposition: form-data; name="part1"
                            Content-Type: text/plain; charset="UTF-8"
                            
                            content1
                            --A1B2C3
                            Content-Disposition: form-data; name="part2"
                            Content-Type: text/plain; charset="UTF-8"
                            
                            content2
                            --A1B2C3--
                            """;
                        String header = """
                           POST / HTTP/1.0
                           Host: localhost
                           Content-Type: multipart/form-data; boundary="A1B2C3"
                           Content-Length: %d
                           
                           """.formatted(content.length());

                        // Write partial request
                        out.write(header.getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        // Finish Request
                        Thread.sleep(750);
                        out.write(content.substring(0, 20).getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        // Finish Request
                        Thread.sleep(750);
                        out.write(content.substring(20).getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        // Read Response
                        String response = IO.toString(in);
                        assertEquals(-1, in.read());
                        return response;
                    }
                });
            }

            List<Future<String>> responses = clientExecutors.invokeAll(clientTasks, 60, TimeUnit.SECONDS);

            for (Future<String> responseFut : responses)
            {
                String response = responseFut.get();
                assertThat(response, containsString("200 OK"));
                assertThat(response, containsString("part1=content1"));
                assertThat(response, containsString("part2=content2"));
            }
        }
        finally
        {
            clientExecutors.shutdownNow();
        }
    }

    protected static class ReadHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);
            String string = Content.Source.asString(request);
            response.write(true, ByteBuffer.wrap(("Read Input " + string.length() + "\r\n").getBytes()), callback);
            return true;
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testWriteStarvation(Scenario scenario) throws Exception
    {
        prepareServer(scenario, new WriteHandler());
        _server.start();

        ExecutorService clientExecutors = Executors.newFixedThreadPool(CLIENTS);
        try
        {
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

                        String request = """
                            GET / HTTP/1.0\r
                            host: localhost\r
                            \r
                            """;

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

    protected static class WriteHandler extends Handler.Abstract
    {
        byte[] content = new byte[BUFFER_SIZE];

        {
            // Using a character that will not show up in an HTTP response header
            Arrays.fill(content, (byte)'!');
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);

            try (OutputStream out = Content.Sink.asOutputStream(response))
            {
                for (int i = 0; i < BUFFERS; i++)
                {
                    out.write(content);
                    out.flush();
                }
            }

            return true;
        }
    }

    public static class Scenario
    {
        private final String testType;
        private final ConnectorProvider connectorProvider;
        private final ClientSocketProvider clientSocketProvider;
        private final boolean delayed;

        private Scenario(String testType, ConnectorProvider connectorProvider, ClientSocketProvider clientSocketProvider, boolean delayed)
        {
            this.testType = testType;
            this.connectorProvider = connectorProvider;
            this.clientSocketProvider = clientSocketProvider;
            this.delayed = delayed;
        }

        @Override
        public String toString()
        {
            return "%s|%b".formatted(testType, delayed);
        }
    }
}
