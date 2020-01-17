//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

// JettyTest.java --
//
// Junit test that shows the Jetty SSL bug.
//

package org.eclipse.jetty.server.ssl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        "Content-Length: " + HELLO_WORLD.length() + "\n" +
        "Server: Jetty(" + JETTY_VERSION + ")\n" +
        '\n' +
        HELLO_WORLD;

    private static final String RESPONSE1 = "HTTP/1.1 200 OK\n" +
        "Connection: close\n" +
        "Content-Length: " + HELLO_WORLD.length() + "\n" +
        "Server: Jetty(" + JETTY_VERSION + ")\n" +
        '\n' +
        HELLO_WORLD;

    private static final int BODY_SIZE = 300;

    private Server server;
    private ServerConnector connector;

    @BeforeEach
    public void startServer() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");

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
        server.setHandler(new HelloWorldHandler());
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
        server.setHandler(new HelloWorldHandler());
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
    public void testRequestJettyHttps() throws Exception
    {
        server.setHandler(new HelloWorldHandler());
        server.start();

        final int loops = 10;
        final int numConns = 20;

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

    @Test
    public void testURLConnectionChunkedPost() throws Exception
    {
        StreamHandler handler = new StreamHandler();
        server.setHandler(handler);
        server.start();

        SSLContext context = SSLContext.getInstance("SSL");
        context.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());

        URL url = new URL("https://localhost:" + connector.getLocalPort() + "/test");

        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        if (conn instanceof HttpsURLConnection)
        {
            ((HttpsURLConnection)conn).setHostnameVerifier(new HostnameVerifier()
            {
                @Override
                public boolean verify(String urlHostName, SSLSession session)
                {
                    return true;
                }
            });
        }

        conn.setConnectTimeout(10000);
        conn.setReadTimeout(100000);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setChunkedStreamingMode(128);
        conn.connect();
        byte[] b = new byte[BODY_SIZE];
        for (int i = 0; i < BODY_SIZE; i++)
        {
            b[i] = 'x';
        }
        OutputStream os = conn.getOutputStream();
        os.write(b);
        os.flush();

        int len = 0;
        InputStream is = conn.getInputStream();
        int bytes = 0;
        while ((len = is.read(b)) > -1)
        {
            bytes += len;
        }
        is.close();

        assertEquals(BODY_SIZE, handler.bytes);
        assertEquals(BODY_SIZE, bytes);
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

    private static class HelloWorldHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            // System.err.println("HANDLE "+request.getRequestURI());
            String sslId = (String)request.getAttribute("javax.servlet.request.ssl_session_id");
            assertNotNull(sslId);

            if (request.getParameter("dump") != null)
            {
                ServletOutputStream out = response.getOutputStream();
                byte[] buf = new byte[Integer.parseInt(request.getParameter("dump"))];
                // System.err.println("DUMP "+buf.length);
                for (int i = 0; i < buf.length; i++)
                {
                    buf[i] = (byte)('0' + (i % 10));
                }
                out.write(buf);
                out.close();
            }
            else
            {
                PrintWriter out = response.getWriter();
                out.print(HELLO_WORLD);
                out.close();
            }
        }
    }

    private static class StreamHandler extends AbstractHandler
    {
        private int bytes = 0;

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.setContentType("text/plain");
            response.setBufferSize(128);
            byte[] b = new byte[BODY_SIZE];
            int len = 0;
            InputStream is = request.getInputStream();
            while ((len = is.read(b)) > -1)
            {
                bytes += len;
            }

            OutputStream os = response.getOutputStream();
            for (int i = 0; i < BODY_SIZE; i++)
            {
                b[i] = 'x';
            }
            os.write(b);
            response.flushBuffer();
        }
    }
}
