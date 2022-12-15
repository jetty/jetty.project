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

// JettyTest.java --
//
// Junit test that shows the Jetty SSL bug.
//

package org.eclipse.jetty.server.ssl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.condition.OS.LINUX;

/**
 *
 */
public class SSLEngineTest
{
    // Useful constants
    private static final String HELLO_WORLD = "Hello world. The quick brown fox jumped over the lazy dog. How now brown cow. The rain in spain falls mainly on the plain.\n";
    private static final String JETTY_VERSION = Server.getVersion();
    private static final String PROTOCOL_VERSION = "2.0";

    /**
     * The request.
     */
    private static final String REQUEST0_HEADER = "POST /r0 HTTP/1.1\n" + "Host: localhost\n" + "Content-Type: text/xml\n" + "Content-Length: ";
    private static final String REQUEST1_HEADER = "POST /r1 HTTP/1.1\n" + "Host: localhost\n" + "Content-Type: text/xml\n" + "Connection: close\n" + "Content-Length: ";
    private static final String REQUEST_CONTENT =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<requests xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "        xsi:noNamespaceSchemaLocation=\"commander.xsd\" version=\"" + PROTOCOL_VERSION + "\">\n" +
            "</requests>";

    private static final String REQUEST0 = REQUEST0_HEADER + REQUEST_CONTENT.getBytes().length + "\n\n" + REQUEST_CONTENT;
    private static final String REQUEST1 = REQUEST1_HEADER + REQUEST_CONTENT.getBytes().length + "\n\n" + REQUEST_CONTENT;

    /**
     * The expected response.
     */
    private static final String RESPONSE0 = "HTTP/1.1 200 OK\n" +
        "Server: Jetty(" + JETTY_VERSION + ")\n" +
        "Content-Length: " + HELLO_WORLD.length() + "\n" +
        '\n' +
        HELLO_WORLD;

    private static final String RESPONSE1 = "HTTP/1.1 200 OK\n" +
        "Server: Jetty(" + JETTY_VERSION + ")\n" +
        "Content-Length: " + HELLO_WORLD.length() + "\n" +
        "Connection: close\n" +
        '\n' +
        HELLO_WORLD;

    private Server server;
    private ServerConnector connector;
    private SslContextFactory.Server sslContextFactory;

    @BeforeEach
    public void startServer() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        server = new Server();
        HttpConnectionFactory http = new HttpConnectionFactory();
        http.setInputBufferSize(512);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        connector = new ServerConnector(server, sslContextFactory, http);
        connector.setPort(0);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);

        server.addConnector(connector);
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testHelloWorld() throws Exception
    {
        server.setHandler(new TestHandler());
        server.start();

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());

        int port = connector.getLocalPort();

        Socket client = ctx.getSocketFactory().createSocket("localhost", port);
        OutputStream os = client.getOutputStream();

        String request =
            "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        os.write(request.getBytes());
        os.flush();

        String response = IO.toString(client.getInputStream());

        assertThat(response, Matchers.containsString("200 OK"));
        assertThat(response, Matchers.containsString(HELLO_WORLD));
    }

    @Test
    public void testBigResponse() throws Exception
    {
        server.setHandler(new TestHandler());
        server.start();

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());

        int port = connector.getLocalPort();

        Socket client = ctx.getSocketFactory().createSocket("localhost", port);
        OutputStream os = client.getOutputStream();

        String request =
            "GET /?dump=102400 HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        os.write(request.getBytes());
        os.flush();

        String response = IO.toString(client.getInputStream());

        assertThat(response.length(), greaterThan(102400));
    }

    @Test
    @EnabledOnOs(LINUX) // this test always fails on MacOS/amd64 - assume it only works on Linux
    public void testInvalidLargeTLSFrame() throws Exception
    {
        AtomicLong unwraps = new AtomicLong();
        ConnectionFactory http = connector.getConnectionFactory(HttpConnectionFactory.class);
        ConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol())
        {
            @Override
            protected SslConnection newSslConnection(Connector connector, EndPoint endPoint, SSLEngine engine)
            {
                return new SslConnection(connector.getByteBufferPool(), connector.getExecutor(), endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                {
                    @Override
                    protected SSLEngineResult unwrap(SSLEngine sslEngine, ByteBuffer input, ByteBuffer output) throws SSLException
                    {
                        unwraps.incrementAndGet();
                        return super.unwrap(sslEngine, input, output);
                    }
                };
            }
        };
        ServerConnector tlsConnector = new ServerConnector(server, 1, 1, ssl, http);
        server.addConnector(tlsConnector);
        server.setHandler(new TestHandler());
        server.start();

        // Create raw TLS record.
        byte[] bytes = new byte[20005];
        Arrays.fill(bytes, (byte)1);

        bytes[0] = 22; // record type
        bytes[1] = 3;  // major version
        bytes[2] = 3;  // minor version
        bytes[3] = 78; // record length 2 bytes / 0x4E20 / decimal 20,000
        bytes[4] = 32; // record length
        bytes[5] = 1;  // message type
        bytes[6] = 0;  // message length 3 bytes / 0x004E17 / decimal 19,991
        bytes[7] = 78;
        bytes[8] = 23;

        SocketFactory socketFactory = SocketFactory.getDefault();
        try (Socket client = socketFactory.createSocket("localhost", tlsConnector.getLocalPort()))
        {
            client.getOutputStream().write(bytes);

            // Sleep to see if the server spins.
            Thread.sleep(1000);
            assertThat(unwraps.get(), lessThan(128L));

            // Read until -1 or read timeout.
            client.setSoTimeout(1000);
            IO.readBytes(client.getInputStream());
        }
    }

    @Test
    public void testRequestJettyHttps() throws Exception
    {
        server.setHandler(new TestHandler());
        server.start();

        final int loops = 2;
        final int numConns = 2;

        Socket[] client = new Socket[numConns];

        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());

        int port = connector.getLocalPort();

        try
        {
            for (int l = 0; l < loops; l++)
            {
                // System.err.print('.');
                try
                {
                    for (int i = 0; i < numConns; ++i)
                    {
                        // System.err.println("write:"+i);
                        client[i] = ctx.getSocketFactory().createSocket("localhost", port);
                        OutputStream os = client[i].getOutputStream();

                        os.write(REQUEST0.getBytes());
                        os.write(REQUEST0.getBytes());
                        os.flush();
                    }

                    for (int i = 0; i < numConns; ++i)
                    {
                        // System.err.println("flush:"+i);
                        OutputStream os = client[i].getOutputStream();
                        os.write(REQUEST1.getBytes());
                        os.flush();
                    }

                    for (int i = 0; i < numConns; ++i)
                    {
                        // System.err.println("read:"+i);
                        // Read the response.
                        String responses = readResponse(client[i]);
                        // Check the responses
                        assertThat(String.format("responses loop=%d connection=%d", l, i), RESPONSE0 + RESPONSE0 + RESPONSE1, is(responses));
                    }
                }
                finally
                {
                    for (int i = 0; i < numConns; ++i)
                    {
                        if (client[i] != null)
                        {
                            try
                            {
                                assertThat("Client should read EOF", client[i].getInputStream().read(), is(-1));
                            }
                            catch (SocketException e)
                            {
                                // no op
                            }
                        }
                    }
                }
            }
        }
        finally
        {
            // System.err.println();
        }
    }

    /**
     * Reads entire response from the client. Close the output.
     *
     * @param client Open client socket.
     * @return The response string.
     * @throws IOException in case of I/O errors
     */
    private static String readResponse(Socket client) throws IOException
    {
        BufferedReader br = null;
        StringBuilder sb = new StringBuilder(1000);

        try
        {
            client.setSoTimeout(5000);
            br = new BufferedReader(new InputStreamReader(client.getInputStream()));

            String line;

            while ((line = br.readLine()) != null)
            {
                sb.append(line);
                sb.append('\n');
            }
        }
        catch (SocketTimeoutException e)
        {
            System.err.println("Test timedout: " + e.toString());
            e.printStackTrace(); // added to see if we can get more info from failures on CI
        }
        finally
        {
            if (br != null)
            {
                br.close();
            }
        }
        return sb.toString();
    }

    private static class TestHandler extends Handler.Abstract
    {
        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            // System.err.println("HANDLE "+request.getRequestURI());
            SecureRequestCustomizer.SslSessionData sslData = (SecureRequestCustomizer.SslSessionData)
                request.getAttribute(SecureRequestCustomizer.DEFAULT_SSL_SESSION_DATA_ATTRIBUTE);
            String sslId = sslData.sessionId();
            assertNotNull(sslId);

            Fields fields = Request.extractQueryParameters(request);
            Fields.Field dumpField = fields.get("dump");
            if (dumpField != null)
            {
                byte[] buf = new byte[dumpField.getValueAsInt()];
                // System.err.println("DUMP "+buf.length);
                for (int i = 0; i < buf.length; i++)
                {
                    buf[i] = (byte)('0' + (i % 10));
                }
                response.write(true, BufferUtil.toBuffer(buf), callback);
            }
            else
            {
                response.write(true, BufferUtil.toBuffer(HELLO_WORLD), callback);
            }
            return true;
        }
    }
}
