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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ProxyConnectionTest
{
    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testBadCRLF(RequestProcessor p) throws Exception
    {
        String request = "PROXY TCP 1.2.3.4 5.6.7.8 111 222\r \n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = p.sendRequestWaitingForResponse(request);
        assertNull(response);
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testBadChar(RequestProcessor p) throws Exception
    {
        String request = "PROXY\tTCP 1.2.3.4 5.6.7.8 111 222\r\n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = p.sendRequestWaitingForResponse(request);
        assertNull(response);
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testBadPort(RequestProcessor p) throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(ProxyConnectionFactory.class))
        {
            String request = "PROXY TCP 1.2.3.4 5.6.7.8 9999999999999 222\r\n" +
                "GET /path HTTP/1.1\n" +
                "Host: server:80\n" +
                "Connection: close\n" +
                "\n";
            String response = p.sendRequestWaitingForResponse(request);
            assertNull(response);
        }
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testHttp(RequestProcessor p) throws Exception
    {
        String request =
            "GET /path HTTP/1.1\n" +
                "Host: server:80\n" +
                "Connection: close\n" +
                "\n";
        String response = p.sendRequestWaitingForResponse(request);
        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testIPv6(RequestProcessor p) throws Exception
    {
        String request = "PROXY TCP6 eeee:eeee:eeee:eeee:eeee:eeee:eeee:eeee ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff 65535 65535\r\n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";

        String response = p.sendRequestWaitingForResponse(request);

        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
        assertThat(response, Matchers.containsString("pathInfo=/path"));
        assertThat(response, Matchers.containsString("remote=[eeee:eeee:eeee:eeee:eeee:eeee:eeee:eeee]:65535"));
        assertThat(response, Matchers.containsString("local=[ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]:65535"));
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testIPv6V2(RequestProcessor p) throws Exception
    {

        String proxy =
            // Preamble
            "0D0A0D0A000D0A515549540A" +

                // V2, PROXY
                "21" +

                // 0x1 : AF_INET6    0x1 : STREAM.
                "21" +

                // Address length is 2*16 + 2*2 = 36 bytes.
                // length of remaining header (16+16+2+2 = 36)
                "0024" +

                // uint8_t src_addr[16]; uint8_t  dst_addr[16]; uint16_t src_port; uint16_t dst_port;
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" + // ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
                "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE" + // eeee:eeee:eeee:eeee:eeee:eeee:eeee:eeee
                "3039" + // 12345
                "1F90"; // 8080
        String http = "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";

        String response = p.sendRequestWaitingForResponse(TypeUtil.fromHexString(proxy), http.getBytes(StandardCharsets.US_ASCII));

        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
        assertThat(response, Matchers.containsString("pathInfo=/path"));
        assertThat(response, Matchers.containsString("local=[eeee:eeee:eeee:eeee:eeee:eeee:eeee:eeee]:8080"));
        assertThat(response, Matchers.containsString("remote=[ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]:12345"));
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testLocalV2(RequestProcessor p) throws Exception
    {
        String proxy =
            // Preamble
            "0D0A0D0A000D0A515549540A" +

                // V2, LOCAL
                "20" +

                // 0x1 : AF_INET    0x1 : STREAM.
                "11" +

                // Address length is 16.
                "0010" +

                // gibberish
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
            ;
        String http = "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";

        String response = p.sendRequestWaitingForResponse(TypeUtil.fromHexString(proxy), http.getBytes(StandardCharsets.US_ASCII));

        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
        assertThat(response, Matchers.containsString("pathInfo=/path"));
        assertThat(response, Matchers.containsString("local=0.0.0.0:0"));
        assertThat(response, Matchers.containsString("remote=0.0.0.0:0"));
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testMissingField(RequestProcessor p) throws Exception
    {
        String request = "PROXY TCP 1.2.3.4 5.6.7.8 222\r\n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";
        String response = p.sendRequestWaitingForResponse(request);
        assertNull(response);
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testNotComplete(RequestProcessor p) throws Exception
    {
        String response = p.customize(connector -> connector.setIdleTimeout(100)).sendRequestWaitingForResponse("PROXY TIMEOUT");
        assertNull(response);
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testTooLong(RequestProcessor p) throws Exception
    {
        String request = "PROXY TOOLONG!!! eeee:eeee:eeee:eeee:0000:0000:0000:0000 ffff:ffff:ffff:ffff:0000:0000:0000:0000 65535 65535\r\n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";

        String response = p.sendRequestWaitingForResponse(request);

        assertNull(response);
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testSimple(RequestProcessor p) throws Exception
    {
        String request = "PROXY TCP 1.2.3.4 5.6.7.8 111 222\r\n" +
            "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";

        String response = p.sendRequestWaitingForResponse(request);

        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
        assertThat(response, Matchers.containsString("pathInfo=/path"));
        assertThat(response, Matchers.containsString("local=5.6.7.8:222"));
        assertThat(response, Matchers.containsString("remote=1.2.3.4:111"));
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testSimpleV2(RequestProcessor p) throws Exception
    {
        String proxy =
            // Preamble
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
        String http = "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";

        String response = p.sendRequestWaitingForResponse(TypeUtil.fromHexString(proxy), http.getBytes(StandardCharsets.US_ASCII));

        assertThat(response, Matchers.containsString("HTTP/1.1 200"));
        assertThat(response, Matchers.containsString("pathInfo=/path"));
        assertThat(response, Matchers.containsString("local=127.0.0.1:8080"));
        assertThat(response, Matchers.containsString("remote=192.168.0.1:12345"));
    }

    @ParameterizedTest
    @MethodSource("requestProcessors")
    public void testMaxHeaderLengthV2(RequestProcessor p) throws Exception
    {
        p.customize((connector) ->
        {
            ProxyConnectionFactory factory = (ProxyConnectionFactory)connector.getConnectionFactory("[proxy]");
            factory.setMaxProxyHeader(11); // just one byte short
        });
        String proxy =
            // Preamble
            "0D0A0D0A000D0A515549540A" +

                // V2, PROXY
                "21" +

                // 0x1 : AF_INET    0x1 : STREAM.
                "11" +

                // Address length is 2*4 + 2*2 = 12 bytes.
                // length of remaining header (4+4+2+2 = 12)
                "000C" +

                // uint32_t src_addr; uint32_t dst_addr; uint16_t src_port; uint16_t dst_port;
                "C0A80001" +
                "7f000001" +
                "3039" +
                "1F90";
        String http = "GET /path HTTP/1.1\n" +
            "Host: server:80\n" +
            "Connection: close\n" +
            "\n";

        String response = p.sendRequestWaitingForResponse(TypeUtil.fromHexString(proxy), http.getBytes(StandardCharsets.US_ASCII));

        assertThat(response, Matchers.is(Matchers.nullValue()));
    }

    abstract static class RequestProcessor
    {
        protected LocalConnector _connector;
        private Server _server;

        public RequestProcessor()
        {
            _server = new Server();
            HttpConnectionFactory http = new HttpConnectionFactory();
            http.getHttpConfiguration().setRequestHeaderSize(1024);
            http.getHttpConfiguration().setResponseHeaderSize(1024);
            ProxyConnectionFactory proxy = new ProxyConnectionFactory(HttpVersion.HTTP_1_1.asString());

            _connector = new LocalConnector(_server, null, null, null, 1, proxy, http);
            _connector.setIdleTimeout(1000);
            _server.addConnector(_connector);
            _server.setHandler(new DumpHandler());
            ErrorHandler eh = new ErrorHandler();
            eh.setServer(_server);
            _server.addBean(eh);
        }

        public RequestProcessor customize(Consumer<LocalConnector> consumer)
        {
            consumer.accept(_connector);
            return this;
        }

        public final String sendRequestWaitingForResponse(String request) throws Exception
        {
            return sendRequestWaitingForResponse(request.getBytes(StandardCharsets.US_ASCII));
        }

        public final String sendRequestWaitingForResponse(byte[]... requests) throws Exception
        {
            try
            {
                _server.start();
                return process(requests);
            }
            finally
            {
                destroy();
            }
        }

        protected abstract String process(byte[]... requests) throws Exception;

        private void destroy() throws Exception
        {
            _server.stop();
            _server.join();
        }
    }

    static Stream<Arguments> requestProcessors()
    {
        return Stream.of(
            Arguments.of(new RequestProcessor()
            {
                @Override
                public String process(byte[]... requests) throws Exception
                {
                    LocalConnector.LocalEndPoint endPoint = _connector.connect();
                    for (byte[] request : requests)
                    {
                        endPoint.addInput(ByteBuffer.wrap(request));
                    }
                    return endPoint.getResponse();
                }

                @Override
                public String toString()
                {
                    return "All bytes at once";
                }
            }),
            Arguments.of(new RequestProcessor()
            {
                @Override
                public String process(byte[]... requests) throws Exception
                {
                    LocalConnector.LocalEndPoint endPoint = _connector.connect();
                    for (byte[] request : requests)
                    {
                        for (byte b : request)
                        {
                            endPoint.addInput(ByteBuffer.wrap(new byte[]{b}));
                        }
                    }
                    return endPoint.getResponse();
                }

                @Override
                public String toString()
                {
                    return "Byte by byte";
                }
            })
        );
    }

}
