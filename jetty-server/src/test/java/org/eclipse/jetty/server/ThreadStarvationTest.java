//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.ExecuteProduceConsume;
import org.eclipse.jetty.util.thread.strategy.ProduceExecuteConsume;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ThreadStarvationTest
{
    @Rule
    public TestTracker tracker = new TestTracker();
    
    interface ConnectorProvider {
        ServerConnector newConnector(Server server, int acceptors, int selectors);
    }
    
    interface ClientSocketProvider {
        Socket newSocket(String host, int port) throws IOException;
    }
    
    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> params()
    {
        List<Object[]> params = new ArrayList<>();
        
        // HTTP
        ConnectorProvider http = (server, acceptors, selectors) -> new ServerConnector(server, acceptors, selectors);
        ClientSocketProvider httpClient = (host, port) -> new Socket(host, port);
        params.add(new Object[]{ "http", http, httpClient });
        
        // HTTPS/SSL/TLS
        ConnectorProvider https = (server, acceptors, selectors) -> {
            Path keystorePath = MavenTestingUtils.getTestResourcePath("keystore");
            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStorePath(keystorePath.toString());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
            sslContextFactory.setTrustStorePath(keystorePath.toString());
            sslContextFactory.setTrustStorePassword("storepwd");
            ByteBufferPool pool = new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged());
    
            HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
            ServerConnector connector = new ServerConnector(server,(Executor)null,(Scheduler)null,
                    pool, acceptors, selectors,
                    AbstractConnectionFactory.getFactories(sslContextFactory,httpConnectionFactory));
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
                    HttpsURLConnection.setDefaultHostnameVerifier((hostname, session)-> true);
                    sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());
                    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            }
            
            @Override
            public Socket newSocket(String host, int port) throws IOException
            {
                return sslContext.getSocketFactory().createSocket(host,port);
            }
        };
        params.add(new Object[]{ "https/ssl/tls", https, httpsClient });
        
        return params;
    }
    
    private final ConnectorProvider connectorProvider;
    private final ClientSocketProvider clientSocketProvider;
    private QueuedThreadPool _threadPool;
    private Server _server;
    private ServerConnector _connector;
    private int _availableThreads;
    
    public ThreadStarvationTest(String testType, ConnectorProvider connectorProvider, ClientSocketProvider clientSocketProvider)
    {
        this.connectorProvider = connectorProvider;
        this.clientSocketProvider = clientSocketProvider;
    }
    
    private Server prepareServer(Handler handler)
    {
        int threads = 4;
        _threadPool = new QueuedThreadPool();
        _threadPool.setMinThreads(threads);
        _threadPool.setMaxThreads(threads);
        _threadPool.setDetailedDump(true);
        _server = new Server(_threadPool);
        int acceptors = 1;
        int selectors = 1;
        _connector = connectorProvider.newConnector(_server, acceptors, selectors);
        _server.addConnector(_connector);
        _server.setHandler(handler);
        _availableThreads = threads - acceptors - selectors;
        return _server;
    }

    @After
    public void dispose() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testReadInput() throws Exception
    {
        prepareServer(new ReadHandler()).start();

        Socket client = clientSocketProvider.newSocket("localhost", _connector.getLocalPort());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        String request = "" +
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

    @Test
    public void testEPCStarvation() throws Exception
    {
        testStarvation(new ExecuteProduceConsume.Factory());
    }

    @Test
    public void testPECStarvation() throws Exception
    {
        testStarvation(new ProduceExecuteConsume.Factory());
    }

    private void testStarvation(ExecutionStrategy.Factory executionFactory) throws Exception
    {
        prepareServer(new ReadHandler());
        _connector.setExecutionStrategyFactory(executionFactory);
        _server.start();

        Socket[] client = new Socket[_availableThreads + 1];
        OutputStream[] os = new OutputStream[client.length];
        InputStream[] is = new InputStream[client.length];

        for (int i = 0; i < client.length; i++)
        {
            client[i] = clientSocketProvider.newSocket("localhost", _connector.getLocalPort());
            client[i].setSoTimeout(10000);

            os[i] = client[i].getOutputStream();
            is[i] = client[i].getInputStream();

            String request = "" +
                    "PUT / HTTP/1.0\r\n" +
                    "host: localhost\r\n" +
                    "content-length: 10\r\n" +
                    "\r\n" +
                    "1";
            os[i].write(request.getBytes(StandardCharsets.UTF_8));
            os[i].flush();
        }

        Thread.sleep(500);

        for (int i = 0; i < client.length; i++)
        {
            os[i].write(("234567890\r\n").getBytes(StandardCharsets.UTF_8));
            os[i].flush();
        }

        Thread.sleep(500);

        for (int i = 0; i < client.length; i++)
        {
            String response = IO.toString(is[i]);
            assertEquals(-1, is[i].read());
            assertThat(response, containsString("200 OK"));
            assertThat(response, containsString("Read Input 10"));
        }
    }

    @Test
    public void testEPCExitsLowThreadsMode() throws Exception
    {
        prepareServer(new ReadHandler());
        _threadPool.setMaxThreads(5);
        _connector.setExecutionStrategyFactory(new ExecuteProduceConsume.Factory());
        _server.start();

        // Three idle threads in the pool here.
        // The server will accept the socket in normal mode.
        Socket client = clientSocketProvider.newSocket("localhost", _connector.getLocalPort());
        client.setSoTimeout(10000);

        Thread.sleep(500);

        // Now steal two threads.
        CountDownLatch[] latches = new CountDownLatch[2];
        for (int i = 0; i < latches.length; ++i)
        {
            CountDownLatch latch = latches[i] = new CountDownLatch(1);
            _threadPool.execute(() ->
            {
                try
                {
                    latch.await();
                }
                catch (InterruptedException ignored)
                {
                }
            });
        }

        InputStream is = client.getInputStream();
        OutputStream os = client.getOutputStream();

        String request = "" +
                "PUT / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "1";
        os.write(request.getBytes(StandardCharsets.UTF_8));
        os.flush();

        Thread.sleep(500);

        // Request did not send the whole body, Handler
        // is blocked reading, zero idle threads here,
        // EPC is in low threads mode.
        for (ManagedSelector selector : _connector.getSelectorManager().getBeans(ManagedSelector.class))
        {
            ExecuteProduceConsume executionStrategy = (ExecuteProduceConsume)selector.getExecutionStrategy();
            assertTrue(executionStrategy.isLowOnThreads());
        }

        // Release the stolen threads.
        for (CountDownLatch latch : latches)
            latch.countDown();
        Thread.sleep(500);

        // Send the rest of the body to unblock the reader thread.
        // This will be run directly by the selector thread,
        // which then will exit the low threads mode.
        os.write("234567890".getBytes(StandardCharsets.UTF_8));
        os.flush();

        Thread.sleep(500);

        for (ManagedSelector selector : _connector.getSelectorManager().getBeans(ManagedSelector.class))
        {
            ExecuteProduceConsume executionStrategy = (ExecuteProduceConsume)selector.getExecutionStrategy();
            assertFalse(executionStrategy.isLowOnThreads());
        }
    }

    protected static class ReadHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);

            int l = request.getContentLength();
            int r = 0;
            while (r < l)
            {
                if (request.getInputStream().read() >= 0)
                    r++;
            }

            response.getOutputStream().write(("Read Input " + r + "\r\n").getBytes());
        }
    }
}
