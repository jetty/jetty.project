//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

public class ProxyCustomizerTest
{
    private Server server;

    private ProxyResponse sendProxyRequest(String proxyAsHexString, String rawHttp) throws IOException
    {
        try (Socket socket = new Socket(server.getURI().getHost(), server.getURI().getPort()))
        {
            OutputStream output = socket.getOutputStream();
            output.write(TypeUtil.fromHexString(proxyAsHexString));
            output.write(rawHttp.getBytes(StandardCharsets.UTF_8));
            output.flush();
            socket.shutdownOutput();

            StringBuilder sb = new StringBuilder();

            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            while (true)
            {
                String line = reader.readLine();
                if (line == null)
                    break;
                sb.append(line).append("\r\n");
            }

            return new ProxyResponse((InetSocketAddress)socket.getLocalSocketAddress(), (InetSocketAddress)socket.getRemoteSocketAddress(), sb.toString());
        }
    }

    private static class ProxyResponse
    {
        private final InetSocketAddress localSocketAddress;
        private final InetSocketAddress remoteSocketAddress;
        private final String httpResponse;

        public ProxyResponse(InetSocketAddress localSocketAddress, InetSocketAddress remoteSocketAddress, String httpResponse)
        {
            this.localSocketAddress = localSocketAddress;
            this.remoteSocketAddress = remoteSocketAddress;
            this.httpResponse = httpResponse;
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                response.addHeader("preexisting.attribute", request.getAttribute("some.attribute").toString());
                ArrayList<String> attributeNames = Collections.list(request.getAttributeNames());
                Collections.sort(attributeNames);
                response.addHeader("attributeNames", String.join(",", attributeNames));

                response.addHeader("localAddress", request.getLocalAddr() + ":" + request.getLocalPort());
                response.addHeader("remoteAddress", request.getRemoteAddr() + ":" + request.getRemotePort());
                Object localAddress = request.getAttribute(ProxyCustomizer.LOCAL_ADDRESS_ATTRIBUTE_NAME);
                if (localAddress != null)
                    response.addHeader("proxyLocalAddress", localAddress.toString() + ":" + request.getAttribute(ProxyCustomizer.LOCAL_PORT_ATTRIBUTE_NAME));
                Object remoteAddress = request.getAttribute(ProxyCustomizer.REMOTE_ADDRESS_ATTRIBUTE_NAME);
                if (remoteAddress != null)
                    response.addHeader("proxyRemoteAddress", remoteAddress.toString() + ":" + request.getAttribute(ProxyCustomizer.REMOTE_PORT_ATTRIBUTE_NAME));

                baseRequest.setHandled(true);
            }
        };

        server = new Server();
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.addCustomizer((connector, channelConfig, request) -> request.setAttribute("some.attribute", "some value"));
        httpConfiguration.addCustomizer(new ProxyCustomizer());
        ServerConnector connector = new ServerConnector(server, new ProxyConnectionFactory(), new HttpConnectionFactory(httpConfiguration));
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        server.stop();
        server = null;
    }

    @Test
    public void testProxyCustomizerWithProxyData() throws Exception
    {
        String proxy =
            // Preamble
            "0D0A0D0A000D0A515549540A" +
                // V2, PROXY
                "21" +
                // 0x1 : AF_INET    0x1 : STREAM.  Address length is 2*4 + 2*2 = 12 bytes.
                "11" +
                // length of remaining header (4+4+2+2 = 12)
                "000C" +
                // uint32_t src_addr; uint32_t dst_addr; uint16_t src_port; uint16_t dst_port;
                "01010001" +
                "010100FE" +
                "3039" +
                "1F90";
        String http = "GET /1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";

        ProxyResponse response = sendProxyRequest(proxy, http);

        assertThat(response.httpResponse, Matchers.containsString("localAddress: 1.1.0.254:8080"));
        assertThat(response.httpResponse, Matchers.containsString("remoteAddress: 1.1.0.1:12345"));
        assertThat(response.httpResponse, Matchers.containsString("proxyLocalAddress: " + response.remoteSocketAddress.getAddress().getHostAddress() + ":" + response.remoteSocketAddress.getPort()));
        assertThat(response.httpResponse, Matchers.containsString("proxyRemoteAddress: " + response.localSocketAddress.getAddress().getHostAddress() + ":" + response.localSocketAddress.getPort()));
        assertThat(response.httpResponse, Matchers.containsString("preexisting.attribute: some value"));
        assertThat(response.httpResponse, Matchers.containsString("attributeNames: org.eclipse.jetty.proxy.local.address,org.eclipse.jetty.proxy.local.port,org.eclipse.jetty.proxy.remote.address,org.eclipse.jetty.proxy.remote.port,some.attribute"));
    }

    @Test
    public void testProxyCustomizerWithoutProxyData() throws Exception
    {
        String proxy = "";
        String http = "GET /1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";

        ProxyResponse response = sendProxyRequest(proxy, http);

        assertThat(response.httpResponse, Matchers.containsString("localAddress: " + response.remoteSocketAddress.getAddress().getHostAddress() + ":" + response.remoteSocketAddress.getPort()));
        assertThat(response.httpResponse, Matchers.containsString("remoteAddress: " + response.localSocketAddress.getAddress().getHostAddress() + ":" + response.localSocketAddress.getPort()));
        assertThat(response.httpResponse, Matchers.not(Matchers.containsString("proxyLocalAddress: ")));
        assertThat(response.httpResponse, Matchers.not(Matchers.containsString("proxyRemoteAddress: ")));
        assertThat(response.httpResponse, Matchers.containsString("preexisting.attribute: some value"));
        assertThat(response.httpResponse, Matchers.containsString("attributeNames: some.attribute"));
    }
}
