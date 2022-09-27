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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DetectorConnectionTest
{
    private Server _server;

    private static String inputStreamToString(InputStream is) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.US_ASCII));

        while (true)
        {
            String line = reader.readLine();
            if (line == null)
            {
                // remove the last '\n'
                if (sb.length() != 0)
                    sb.deleteCharAt(sb.length() - 1);
                break;
            }
            sb.append(line).append('\n');
        }

        return sb.length() == 0 ? null : sb.toString();
    }

    private String getResponse(String request) throws Exception
    {
        return getResponse(request.getBytes(StandardCharsets.US_ASCII));
    }

    private String getResponse(byte[]... requests) throws Exception
    {
        try (Socket socket = new Socket(_server.getURI().getHost(), _server.getURI().getPort()))
        {
            for (byte[] request : requests)
            {
                socket.getOutputStream().write(request);
            }
            return inputStreamToString(socket.getInputStream());
        }
    }

    private String getResponseOverSsl(String request) throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.start();

        SSLSocketFactory socketFactory = sslContextFactory.getSslContext().getSocketFactory();
        try (Socket socket = socketFactory.createSocket(_server.getURI().getHost(), _server.getURI().getPort()))
        {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return inputStreamToString(socket.getInputStream());
        }
        finally
        {
            sslContextFactory.stop();
        }
    }

    private void start(ConnectionFactory... connectionFactories) throws Exception
    {
        _server = new Server();
        _server.addConnector(new ServerConnector(_server, 1, 1, connectionFactories));
        _server.setHandler(new DumpHandler());
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    @Test
    public void testConnectionClosedDuringDetection() throws Exception
    {
        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(proxy);

        start(detector, http);

        try (Socket socket = new Socket(_server.getURI().getHost(), _server.getURI().getPort()))
        {
            socket.getOutputStream().write("PR".getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(100); // make sure the onFillable callback gets called
            socket.getOutputStream().write("OX".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().close();

            assertThrows(SocketException.class, () -> socket.getInputStream().read());
        }
    }

    @Test
    public void testConnectionClosedDuringProxyV1Handling() throws Exception
    {
        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(proxy);

        start(detector, http);

        try (Socket socket = new Socket(_server.getURI().getHost(), _server.getURI().getPort()))
        {
            socket.getOutputStream().write("PROXY".getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(100); // make sure the onFillable callback gets called
            socket.getOutputStream().write(" ".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().close();

            assertThrows(SocketException.class, () -> socket.getInputStream().read());
        }
    }

    @Test
    public void testConnectionClosedDuringProxyV2HandlingFixedLengthPart() throws Exception
    {
        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(proxy);

        start(detector, http);

        try (Socket socket = new Socket(_server.getURI().getHost(), _server.getURI().getPort()))
        {
            socket.getOutputStream().write(TypeUtil.fromHexString("0D0A0D0A000D0A515549540A")); // proxy V2 Preamble
            Thread.sleep(100); // make sure the onFillable callback gets called
            socket.getOutputStream().write(TypeUtil.fromHexString("21")); // V2, PROXY
            socket.getOutputStream().close();

            assertThrows(SocketException.class, () -> socket.getInputStream().read());
        }
    }

    @Test
    public void testConnectionClosedDuringProxyV2HandlingDynamicLengthPart() throws Exception
    {
        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(proxy);

        start(detector, http);

        try (Socket socket = new Socket(_server.getURI().getHost(), _server.getURI().getPort()))
        {
            socket.getOutputStream().write(TypeUtil.fromHexString(
                // proxy V2 Preamble
                "0D0A0D0A000D0A515549540A" +
                    // V2, PROXY
                    "21" +
                    // 0x1 : AF_INET    0x1 : STREAM.
                    "11" +
                    // Address length is 2*4 + 2*2 = 12 bytes.
                    // length of remaining header (4+4+2+2 = 12)
                    "000C"
            ));
            Thread.sleep(100); // make sure the onFillable callback gets called
            socket.getOutputStream().write(TypeUtil.fromHexString(
                // uint32_t src_addr; uint32_t dst_addr; uint16_t src_port; uint16_t dst_port;
                "C0A80001" // 8080
            ));
            socket.getOutputStream().close();

            assertThrows(SocketException.class, () -> socket.getInputStream().read());
        }
    }

    @Test
    @Disabled // TODO
    public void testDetectingSslProxyToHttpNoSslWithProxy() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(ssl, proxy);

        start(detector, http);

        String request = "PROXY TCP 1.2.3.4 5.6.7.8 111 222\r\n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = getResponse(request);

        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
        assertThat(response, Matchers.containsString("pathInContext=/path"));
        assertThat(response, Matchers.containsString("servername=server"));
        assertThat(response, Matchers.containsString("serverport=80"));
        assertThat(response, Matchers.containsString("localname=5.6.7.8"));
        assertThat(response, Matchers.containsString("local=5.6.7.8:222"));
        assertThat(response, Matchers.containsString("remote=1.2.3.4:111"));
    }

    @Test
    public void testDetectingSslProxyToHttpWithSslNoProxy() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(ssl, proxy);

        start(detector, http);

        String request = "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = getResponseOverSsl(request);

        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
    }

    @Test
    public void testDetectingSslProxyToHttpWithSslWithProxy() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(ssl, proxy);

        start(detector, http);

        String request = "PROXY TCP 1.2.3.4 5.6.7.8 111 222\r\n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = getResponseOverSsl(request);

        // SSL matched, so the upgrade was made to HTTP which does not understand the proxy request
        assertThat(response, Matchers.containsString("HTTP/1.1 400"));
    }

    @Test
    public void testDetectionUnsuccessfulUpgradesToNextProtocol() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(ssl, proxy);

        start(detector, http);

        String request = "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = getResponse(request);

        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
    }

    @Test
    public void testDetectorToNextDetector() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        DetectorConnectionFactory proxyDetector = new DetectorConnectionFactory(proxy);
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, proxyDetector.getProtocol());
        DetectorConnectionFactory sslDetector = new DetectorConnectionFactory(ssl);

        start(sslDetector, proxyDetector, http);

        String request = "PROXY TCP 1.2.3.4 5.6.7.8 111 222\r\n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = getResponseOverSsl(request);

        // SSL matched, so the upgrade was made to proxy which itself upgraded to HTTP
        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
        assertThat(response, Matchers.containsString("pathInContext=/path"));
        assertThat(response, Matchers.containsString("local=5.6.7.8:222"));
        assertThat(response, Matchers.containsString("remote=1.2.3.4:111"));
    }

    @Test
    public void testDetectorWithDetectionUnsuccessful() throws Exception
    {
        AtomicBoolean detectionSuccessful = new AtomicBoolean(true);
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(HttpVersion.HTTP_1_1.asString());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(proxy)
        {
            @Override
            protected void nextProtocol(Connector connector, EndPoint endPoint, ByteBuffer buffer)
            {
                if (!detectionSuccessful.compareAndSet(true, false))
                    throw new AssertionError("DetectionUnsuccessful callback should only have been called once");

                // omitting this will leak the buffer
                connector.getByteBufferPool().release(buffer);

                Callback.Completable.with(c -> endPoint.write(c, ByteBuffer.wrap("No upgrade for you".getBytes(StandardCharsets.US_ASCII))))
                    .whenComplete((r, x) -> endPoint.close());
            }
        };
        HttpConnectionFactory http = new HttpConnectionFactory();

        start(detector, http);

        String request = "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = getResponse(request);

        assertEquals("No upgrade for you", response);
        assertFalse(detectionSuccessful.get());
    }

    @Test
    public void testDetectorWithProxyThatHasNoNextProto() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory();
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(ssl, proxy);

        start(detector, http);

        String request = "PROXY TCP 1.2.3.4 5.6.7.8 111 222\r\n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = getResponse(request);

        // ProxyConnectionFactory has no next protocol -> it cannot upgrade
        assertThat(response, Matchers.nullValue());
    }

    @Test
    public void testOptionalSsl() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConnectionFactory http = new HttpConnectionFactory();
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(ssl);

        start(detector, http);

        String request =
            "GET /path HTTP/1.1\n" +
                "Host: server:80\n" +
                "Connection: close\n" +
                "\n";
        String clearTextResponse = getResponse(request);
        String sslResponse = getResponseOverSsl(request);

        // both clear text and SSL can be responded to just fine
        assertThat(clearTextResponse, Matchers.containsString("HTTP/1.1 200"));
        assertThat(sslResponse, Matchers.containsString("HTTP/1.1 200"));
    }

    @Test
    public void testDetectorThatHasNoConfiguredNextProto() throws Exception
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(ssl);

        start(detector);

        String request =
            "GET /path HTTP/1.1\n" +
                "Host: server:80\n" +
                "Connection: close\n" +
                "\n";
        String response = getResponse(request);

        assertThat(response, Matchers.nullValue());
    }

    @Test
    public void testDetectorWithNextProtocolThatDoesNotExist() throws Exception
    {
        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory("does-not-exist");
        DetectorConnectionFactory detector = new DetectorConnectionFactory(proxy);

        start(detector, http);

        String proxyReq =
            // proxy V2 Preamble
            "0D0A0D0A000D0A515549540A" +
                // V2, PROXY
                "21" +
                // 0x1 : AF_INET    0x1 : STREAM.
                "11" +
                // Address length is 2*4 + 2*2 = 12 bytes.
                // length of remaining header (4+4+2+2 = 12)
                "000C" +
                // uint32_t src_addr; uint32_t dst_addr; uint16_t src_port; uint16_t dst_port;
                "C0A80001" + // 192.168.0.1
                "7f000001" + // 127.0.0.1
                "3039" + // 12345
                "1F90"; // 8080

        String httpReq = """
                GET /path HTTP/1.1
                Host: server:80
                Connection: close

                """;
        try (StacklessLogging ignore = new StacklessLogging(DetectorConnectionFactory.class))
        {
            String response = getResponse(StringUtil.fromHexString(proxyReq), httpReq.getBytes(StandardCharsets.US_ASCII));
            assertThat(response, Matchers.nullValue());
        }
    }

    @Test
    public void testDetectingWithNextProtocolThatDoesNotImplementUpgradeTo() throws Exception
    {
        ConnectionFactory.Detecting noUpgradeTo = new ConnectionFactory.Detecting()
        {
            @Override
            public Detection detect(ByteBuffer buffer)
            {
                return Detection.RECOGNIZED;
            }

            @Override
            public String getProtocol()
            {
                return "noUpgradeTo";
            }

            @Override
            public List<String> getProtocols()
            {
                return Collections.singletonList(getProtocol());
            }

            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return new AbstractConnection(null, connector.getExecutor())
                {
                    @Override
                    public void onFillable()
                    {
                    }
                };
            }
        };

        HttpConnectionFactory http = new HttpConnectionFactory();
        DetectorConnectionFactory detector = new DetectorConnectionFactory(noUpgradeTo);

        start(detector, http);

        String proxyReq =
            // proxy V2 Preamble
            "0D0A0D0A000D0A515549540A" +
                // V2, PROXY
                "21" +
                // 0x1 : AF_INET    0x1 : STREAM.
                "11" +
                // Address length is 2*4 + 2*2 = 12 bytes.
                // length of remaining header (4+4+2+2 = 12)
                "000C" +
                // uint32_t src_addr; uint32_t dst_addr; uint16_t src_port; uint16_t dst_port;
                "C0A80001" + // 192.168.0.1
                "7f000001" + // 127.0.0.1
                "3039" + // 12345
                "1F90"; // 8080

        String httpReq =
            "GET /path HTTP/1.1\n" +
                "Host: server:80\n" +
                "Connection: close\n" +
                "\n";
        String response = getResponse(TypeUtil.fromHexString(proxyReq), httpReq.getBytes(StandardCharsets.US_ASCII));

        assertThat(response, Matchers.nullValue());
    }

    @Test
    public void testDetectorWithNextProtocolThatDoesNotImplementUpgradeTo() throws Exception
    {
        ConnectionFactory noUpgradeTo = new ConnectionFactory()
        {
            @Override
            public String getProtocol()
            {
                return "noUpgradeTo";
            }

            @Override
            public List<String> getProtocols()
            {
                return Collections.singletonList(getProtocol());
            }

            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return new AbstractConnection(null, connector.getExecutor())
                {
                    @Override
                    public void onFillable()
                    {
                    }
                };
            }
        };

        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        DetectorConnectionFactory detector = new DetectorConnectionFactory(proxy);

        start(detector, noUpgradeTo);

        String request =
            "GET /path HTTP/1.1\n" +
                "Host: server:80\n" +
                "Connection: close\n" +
                "\n";
        String response = getResponse(request);

        assertThat(response, Matchers.nullValue());
    }

    @Test
    public void testGeneratedProtocolNames()
    {
        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        ProxyConnectionFactory proxy = new ProxyConnectionFactory(HttpVersion.HTTP_1_1.asString());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString());

        assertEquals("[SSL|[proxy]]", new DetectorConnectionFactory(ssl, proxy).getProtocol());
        assertEquals("[[proxy]|SSL]", new DetectorConnectionFactory(proxy, ssl).getProtocol());
    }

    @Test
    public void testDetectorWithNoDetectingFails()
    {
        assertThrows(IllegalArgumentException.class, DetectorConnectionFactory::new);
    }

    @Test
    public void testExerciseDetectorNotEnoughBytes() throws Exception
    {
        ConnectionFactory.Detecting detectingNeverRecognizes = new ConnectionFactory.Detecting()
        {
            @Override
            public Detection detect(ByteBuffer buffer)
            {
                return Detection.NOT_RECOGNIZED;
            }

            @Override
            public String getProtocol()
            {
                return "nevergood";
            }

            @Override
            public List<String> getProtocols()
            {
                throw new AssertionError();
            }

            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                throw new AssertionError();
            }
        };

        ConnectionFactory.Detecting detectingAlwaysNeedMoreBytes = new ConnectionFactory.Detecting()
        {
            @Override
            public Detection detect(ByteBuffer buffer)
            {
                return Detection.NEED_MORE_BYTES;
            }

            @Override
            public String getProtocol()
            {
                return "neverenough";
            }

            @Override
            public List<String> getProtocols()
            {
                throw new AssertionError();
            }

            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                throw new AssertionError();
            }
        };

        DetectorConnectionFactory detector = new DetectorConnectionFactory(detectingNeverRecognizes, detectingAlwaysNeedMoreBytes);
        HttpConnectionFactory http = new HttpConnectionFactory();

        start(detector, http);

        String request = "AAAA".repeat(32768);

        try
        {
            String response = getResponse(request);
            assertThat(response, Matchers.nullValue());
        }
        catch (SocketException expected)
        {
            // The test may fail writing the "request"
            // bytes as the server sends back a TCP RST.
        }
    }
}
