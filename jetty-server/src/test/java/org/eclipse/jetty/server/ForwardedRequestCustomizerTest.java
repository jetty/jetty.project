//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.tools.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ForwardedRequestCustomizerTest
{
    private Server _server;
    private LocalConnector _connector;
    private RequestHandler _handler;
    final AtomicBoolean _wasSecure = new AtomicBoolean(false);
    final AtomicReference<String> _sslSession = new AtomicReference<>();
    final AtomicReference<String> _sslCertificate = new AtomicReference<>();
    final AtomicReference<String> _scheme = new AtomicReference<>();
    final AtomicReference<String> _serverName = new AtomicReference<>();
    final AtomicReference<Integer> _serverPort = new AtomicReference<>();
    final AtomicReference<String> _remoteAddr = new AtomicReference<>();
    final AtomicReference<Integer> _remotePort = new AtomicReference<>();
    final AtomicReference<String> _requestURL = new AtomicReference<>();

    ForwardedRequestCustomizer _customizer;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        HttpConnectionFactory http = new HttpConnectionFactory();
        http.setInputBufferSize(1024);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        http.getHttpConfiguration().setResponseHeaderSize(512);
        http.getHttpConfiguration().setOutputBufferSize(2048);
        http.getHttpConfiguration().addCustomizer(_customizer = new ForwardedRequestCustomizer());
        _connector = new LocalConnector(_server, http);
        _server.addConnector(_connector);
        _handler = new RequestHandler();
        _server.setHandler(_handler);

        _handler._checker = (request, response) ->
        {
            _wasSecure.set(request.isSecure());
            _sslSession.set(String.valueOf(request.getAttribute("javax.servlet.request.ssl_session_id")));
            _sslCertificate.set(String.valueOf(request.getAttribute("javax.servlet.request.cipher_suite")));
            _scheme.set(request.getScheme());
            _serverName.set(request.getServerName());
            _serverPort.set(request.getServerPort());
            _remoteAddr.set(request.getRemoteAddr());
            _remotePort.set(request.getRemotePort());
            _requestURL.set(request.getRequestURL().toString());
            return true;
        };

        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testHostIpv4() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: 1.2.3.4:2222\n" +
                    "\n"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("1.2.3.4"));
        assertThat("serverPort", _serverPort.get(), is(2222));
        assertThat("requestURL", _requestURL.get(), is("http://1.2.3.4:2222/"));
    }

    @Test
    public void testHostIpv6() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: [::1]:2222\n" +
                    "\n"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("[::1]"));
        assertThat("serverPort", _serverPort.get(), is(2222));
        assertThat("requestURL", _requestURL.get(), is("http://[::1]:2222/"));
    }

    @Test
    public void testURIIpv4() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET http://1.2.3.4:2222/ HTTP/1.1\n" +
                    "Host: wrong\n" +
                    "\n"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("1.2.3.4"));
        assertThat("serverPort", _serverPort.get(), is(2222));
        assertThat("requestURL", _requestURL.get(), is("http://1.2.3.4:2222/"));
    }

    @Test
    public void testURIIpv6() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET http://[::1]:2222/ HTTP/1.1\n" +
                    "Host: wrong\n" +
                    "\n"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("[::1]"));
        assertThat("serverPort", _serverPort.get(), is(2222));
        assertThat("requestURL", _requestURL.get(), is("http://[::1]:2222/"));
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc7239#section-4">RFC 7239: Section 4</a>
     *
     * Examples of syntax.
     */
    @Test
    public void testRFC7239_Examples_4() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Forwarded: for=\"_gazonk\"\n" +
                    "Forwarded: For=\"[2001:db8:cafe::17]:4711\"\n" +
                    "Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43\n" +
                    "Forwarded: for=192.0.2.43, for=198.51.100.17\n" +
                    "\n"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("[2001:db8:cafe::17]"));
        assertThat("remotePort", _remotePort.get(), is(4711));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc7239#section-7.1">RFC 7239: Section 7.1</a>
     *
     * Examples of syntax with regards to HTTP header fields
     */
    @Test
    public void testRFC7239_Examples_7_1() throws Exception
    {
        // Without spaces
        HttpTester.Response response1 = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Forwarded: for=192.0.2.43,for=\"[2001:db8:cafe::17]\",for=unknown\n" +
                    "\n"));

        assertThat("status", response1.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.0.2.43"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));

        // With spaces
        HttpTester.Response response2 = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Forwarded: for=192.0.2.43, for=\"[2001:db8:cafe::17]\", for=unknown\n" +
                    "\n"));
        assertThat("status", response2.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.0.2.43"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));

        // As multiple headers
        HttpTester.Response response3 = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Forwarded: for=192.0.2.43\n" +
                    "Forwarded: for=\"[2001:db8:cafe::17]\", for=unknown\n" +
                    "\n"));

        assertThat("status", response3.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.0.2.43"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc7239#section-7.4">RFC 7239: Section 7.4</a>
     *
     * Transition
     */
    @Test
    public void testRFC7239_Examples_7_4() throws Exception
    {
        // Old syntax
        HttpTester.Response response1 = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-For: 192.0.2.43, 2001:db8:cafe::17\n" +
                    "\n"));

        assertThat("status", response1.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.0.2.43"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));

        // New syntax
        HttpTester.Response response2 = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Forwarded: for=192.0.2.43, for=\"[2001:db8:cafe::17]\"\n" +
                    "\n"));

        assertThat("status", response2.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.0.2.43"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc7239#section-7.5">RFC 7239: Section 7.5</a>
     *
     * Example Usage
     */
    @Test
    public void testRFC7239_Examples_7_5() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Forwarded: for=192.0.2.43,for=198.51.100.17;by=203.0.113.60;proto=http;host=example.com\n" +
                    "\n"));

        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("example.com"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.0.2.43"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://example.com/"));
    }

    @Test
    public void testRFC7239_IPv6() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Forwarded: for=\"[2001:db8:cafe::1]\";by=\"[2001:db8:cafe::2]\";host=\"[2001:db8:cafe::3]:8888\"\n" +
                    "\n"));

        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("[2001:db8:cafe::3]"));
        assertThat("serverPort", _serverPort.get(), is(8888));
        assertThat("remoteAddr", _remoteAddr.get(), is("[2001:db8:cafe::1]"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://[2001:db8:cafe::3]:8888/"));
    }

    @Test
    public void testProto_OldSyntax() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-Proto: https\n" +
                    "\n"));

        assertTrue(_wasSecure.get(), "wasSecure");
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("https"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(443));
        assertThat("remoteAddr", _remoteAddr.get(), is("0.0.0.0"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("https://myhost/"));
    }

    @Test
    public void testRFC7239_Proto() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Forwarded: proto=https\n" +
                    "\n"));

        assertTrue(_wasSecure.get(), "wasSecure");
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("https"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(443));
        assertThat("remoteAddr", _remoteAddr.get(), is("0.0.0.0"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("https://myhost/"));
    }

    @Test
    public void testFor() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-For: 10.9.8.7,6.5.4.3\n" +
                    "X-Forwarded-For: 8.9.8.7,7.5.4.3\n" +
                    "\n"));

        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("10.9.8.7"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));
    }

    @Test
    public void testForIpv4WithPort() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-For: 10.9.8.7:1111,6.5.4.3:2222\n" +
                    "\n"));

        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("10.9.8.7"));
        assertThat("remotePort", _remotePort.get(), is(1111));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));
    }

    @Test
    public void testForIpv6WithPort() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-For: [2001:db8:cafe::17]:1111,6.5.4.3:2222\n" +
                    "\n"));

        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("[2001:db8:cafe::17]"));
        assertThat("remotePort", _remotePort.get(), is(1111));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));
    }

    @Test
    public void testForIpv6AndPort() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-For: 1:2:3:4:5:6:7:8\n" +
                    "X-Forwarded-Port: 2222\n" +
                    "\n"));

        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(2222));
        assertThat("remoteAddr", _remoteAddr.get(), is("[1:2:3:4:5:6:7:8]"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost:2222/"));
    }

    @Test
    public void testForIpv6AndPort_MultiField() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-Port: 2222\n" +
                    "X-Forwarded-For: 1:2:3:4:5:6:7:8\n" +
                    "X-Forwarded-For: 7:7:7:7:7:7:7:7\n" +
                    "X-Forwarded-Port: 3333\n" +
                    "\n"));

        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(2222));
        assertThat("remoteAddr", _remoteAddr.get(), is("[1:2:3:4:5:6:7:8]"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost:2222/"));
    }

    @Test
    public void testLegacyProto() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Proxied-Https: on\n" +
                    "\n"));
        assertTrue(_wasSecure.get(), "wasSecure");
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("https"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(443));
        assertThat("remoteAddr", _remoteAddr.get(), is("0.0.0.0"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("https://myhost/"));
    }

    @Test
    public void testSslSession() throws Exception
    {
        _customizer.setSslIsSecure(false);
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Proxy-Ssl-Id: Wibble\n" +
                    "\n"));

        assertFalse(_wasSecure.get(), "wasSecure");
        assertThat("sslSession", _sslSession.get(), is("Wibble"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("0.0.0.0"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));

        _customizer.setSslIsSecure(true);
        response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Proxy-Ssl-Id: 0123456789abcdef\n" +
                    "\n"));

        assertTrue(_wasSecure.get(), "wasSecure");
        assertThat("sslSession", _sslSession.get(), is("0123456789abcdef"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("https"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(443));
        assertThat("remoteAddr", _remoteAddr.get(), is("0.0.0.0"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("https://myhost/"));
    }

    @Test
    public void testSslCertificate() throws Exception
    {
        _customizer.setSslIsSecure(false);
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Proxy-auth-cert: Wibble\n" +
                    "\n"));

        assertFalse(_wasSecure.get(), "wasSecure");
        assertThat("sslCertificate", _sslCertificate.get(), is("Wibble"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("0.0.0.0"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));

        _customizer.setSslIsSecure(true);
        response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "Proxy-auth-cert: 0123456789abcdef\n" +
                    "\n"));

        assertTrue(_wasSecure.get(), "wasSecure");
        assertThat("sslCertificate", _sslCertificate.get(), is("0123456789abcdef"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("https"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(443));
        assertThat("remoteAddr", _remoteAddr.get(), is("0.0.0.0"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("https://myhost/"));
    }

    /**
     * Resetting the server port via a forwarding header
     */
    @Test
    public void testPort_For() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-Port: 4444\n" +
                    "X-Forwarded-For: 192.168.1.200\n" +
                    "\n"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(4444));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.168.1.200"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost:4444/"));
    }

    /**
     * Resetting the server port via a forwarding header
     */
    @Test
    public void testRemote_Port_For() throws Exception
    {
        _customizer.setForwardedPortAsAuthority(false);
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-Port: 4444\n" +
                    "X-Forwarded-For: 192.168.1.200\n" +
                    "\n"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.168.1.200"));
        assertThat("remotePort", _remotePort.get(), is(4444));
        assertThat("requestURL", _requestURL.get(), is("http://myhost/"));
    }

    /**
     * Test setting the server Port before the "Host" header has been seen.
     */
    @Test
    public void testPort_For_LateHost() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "X-Forwarded-Port: 4444\n" + // this order is intentional
                    "X-Forwarded-For: 192.168.1.200\n" +
                    "Host: myhost\n" + // leave this as the last header
                    "\n"));
        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("myhost"));
        assertThat("serverPort", _serverPort.get(), is(4444));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.168.1.200"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://myhost:4444/"));
    }

    @Test
    public void testMixed_For_Port_RFC_For() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _connector.getResponse(
                "GET / HTTP/1.1\n" +
                    "Host: myhost\n" +
                    "X-Forwarded-For: 11.9.8.7:1111,8.5.4.3:2222\n" +
                    "X-Forwarded-Port: 3333\n" +
                    "Forwarded: for=192.0.2.43,for=198.51.100.17;by=203.0.113.60;proto=http;host=example.com\n" +
                    "X-Forwarded-For: 11.9.8.7:1111,8.5.4.3:2222\n" +
                    "\n"));

        assertThat("status", response.getStatus(), is(200));
        assertThat("scheme", _scheme.get(), is("http"));
        assertThat("serverName", _serverName.get(), is("example.com"));
        assertThat("serverPort", _serverPort.get(), is(80));
        assertThat("remoteAddr", _remoteAddr.get(), is("192.0.2.43"));
        assertThat("remotePort", _remotePort.get(), is(0));
        assertThat("requestURL", _requestURL.get(), is("http://example.com/"));
    }

    interface RequestTester
    {
        boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException;
    }

    private class RequestHandler extends AbstractHandler
    {
        private RequestTester _checker;

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);

            if (_checker != null && _checker.check(request, response))
                response.setStatus(200);
            else
                response.sendError(500);
        }
    }
}
