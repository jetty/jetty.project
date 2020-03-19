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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ForwardedRequestCustomizerTest
{
    private Server server;
    private RequestHandler handler;
    private LocalConnector connector;
    private LocalConnector connectorConfigured;
    private ForwardedRequestCustomizer customizer;
    private ForwardedRequestCustomizer customizerConfigured;

    private static class Actual
    {
        final AtomicReference<String> scheme = new AtomicReference<>();
        final AtomicBoolean wasSecure = new AtomicBoolean(false);
        final AtomicReference<String> serverName = new AtomicReference<>();
        final AtomicReference<Integer> serverPort = new AtomicReference<>();
        final AtomicReference<String> requestURL = new AtomicReference<>();
        final AtomicReference<String> remoteAddr = new AtomicReference<>();
        final AtomicReference<Integer> remotePort = new AtomicReference<>();
        final AtomicReference<String> sslSession = new AtomicReference<>();
        final AtomicReference<String> sslCertificate = new AtomicReference<>();
    }

    private Actual actual;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();

        // Default behavior Connector
        HttpConnectionFactory http = new HttpConnectionFactory();
        http.setInputBufferSize(1024);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        http.getHttpConfiguration().setResponseHeaderSize(512);
        http.getHttpConfiguration().setOutputBufferSize(2048);
        customizer = new ForwardedRequestCustomizer();
        http.getHttpConfiguration().addCustomizer(customizer);
        connector = new LocalConnector(server, http);
        server.addConnector(connector);

        // Configured behavior Connector
        http = new HttpConnectionFactory();
        http.setInputBufferSize(1024);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        http.getHttpConfiguration().setResponseHeaderSize(512);
        http.getHttpConfiguration().setOutputBufferSize(2048);
        customizerConfigured = new ForwardedRequestCustomizer();
        customizerConfigured.setForwardedHeader("Jetty-Forwarded");
        customizerConfigured.setForwardedHostHeader("Jetty-Forwarded-Host");
        customizerConfigured.setForwardedServerHeader("Jetty-Forwarded-Server");
        customizerConfigured.setForwardedProtoHeader("Jetty-Forwarded-Proto");
        customizerConfigured.setForwardedForHeader("Jetty-Forwarded-For");
        customizerConfigured.setForwardedPortHeader("Jetty-Forwarded-Port");
        customizerConfigured.setForwardedHttpsHeader("Jetty-Proxied-Https");
        customizerConfigured.setForwardedCipherSuiteHeader("Jetty-Proxy-Auth-Cert");
        customizerConfigured.setForwardedSslSessionIdHeader("Jetty-Proxy-Ssl-Id");

        http.getHttpConfiguration().addCustomizer(customizerConfigured);
        connectorConfigured = new LocalConnector(server, http);
        server.addConnector(connectorConfigured);

        handler = new RequestHandler();
        server.setHandler(handler);

        handler.requestTester = (request, response) ->
        {
            actual = new Actual();
            actual.wasSecure.set(request.isSecure());
            actual.sslSession.set(String.valueOf(request.getAttribute("javax.servlet.request.ssl_session_id")));
            actual.sslCertificate.set(String.valueOf(request.getAttribute("javax.servlet.request.cipher_suite")));
            actual.scheme.set(request.getScheme());
            actual.serverName.set(request.getServerName());
            actual.serverPort.set(request.getServerPort());
            actual.remoteAddr.set(request.getRemoteAddr());
            actual.remotePort.set(request.getRemotePort());
            actual.requestURL.set(request.getRequestURL().toString());
            return true;
        };

        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
    }

    public static Stream<Arguments> cases()
    {
        return Stream.of(
            // Host IPv4
            Arguments.of(
                new Request("IPv4 Host Only")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: 1.2.3.4:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("1.2.3.4").serverPort(2222)
                    .requestURL("http://1.2.3.4:2222/")
            ),
            Arguments.of(new Request("IPv6 Host Only")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: [::1]:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("[::1]").serverPort(2222)
                    .requestURL("http://[::1]:2222/")
            ),
            Arguments.of(new Request("IPv4 in Request Line")
                    .headers(
                        "GET http://1.2.3.4:2222/ HTTP/1.1",
                        "Host: wrong"
                    ),
                new Expectations()
                    .scheme("http").serverName("1.2.3.4").serverPort(2222)
                    .requestURL("http://1.2.3.4:2222/")
            ),
            Arguments.of(new Request("IPv6 in Request Line")
                    .headers(
                        "GET http://[::1]:2222/ HTTP/1.1",
                        "Host: wrong"
                    ),
                new Expectations()
                    .scheme("http").serverName("[::1]").serverPort(2222)
                    .requestURL("http://[::1]:2222/")
            ),

            // =================================================================
            // https://tools.ietf.org/html/rfc7239#section-4  - examples of syntax
            Arguments.of(new Request("RFC7239 Examples: Section 4")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=\"_gazonk\"",
                        "Forwarded: For=\"[2001:db8:cafe::17]:4711\"",
                        "Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43",
                        "Forwarded: for=192.0.2.43, for=198.51.100.17"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("[2001:db8:cafe::17]").remotePort(4711)
            ),

            // https://tools.ietf.org/html/rfc7239#section-7  - Examples of syntax with regards to HTTP header fields
            Arguments.of(new Request("RFC7239 Examples: Section 7")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43,for=\"[2001:db8:cafe::17]\",for=unknown"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // (same as above, but with spaces, as shown in RFC section 7.1)
            Arguments.of(new Request("RFC7239 Examples: Section 7 (spaced)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43, for=\"[2001:db8:cafe::17]\", for=unknown"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // (same as above, but as multiple headers, as shown in RFC section 7.1)
            Arguments.of(new Request("RFC7239 Examples: Section 7 (multi header)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43",
                        "Forwarded: for=\"[2001:db8:cafe::17]\", for=unknown"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // https://tools.ietf.org/html/rfc7239#section-7.4  - Transition
            Arguments.of(new Request("RFC7239 Examples: Section 7.4 (old syntax)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 192.0.2.43, 2001:db8:cafe::17"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),
            Arguments.of(new Request("RFC7239 Examples: Section 7.4 (new syntax)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43, for=\"[2001:db8:cafe::17]\""
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // https://tools.ietf.org/html/rfc7239#section-7.5  - Example Usage
            Arguments.of(new Request("RFC7239 Examples: Section 7.5")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43,for=198.51.100.17;by=203.0.113.60;proto=http;host=example.com"
                    ),
                new Expectations()
                    .scheme("http").serverName("example.com").serverPort(80)
                    .requestURL("http://example.com/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // Forwarded, proto only
            Arguments.of(new Request("RFC7239: Forwarded proto only")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: proto=https"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .requestURL("https://myhost/")
            ),

            // =================================================================
            // X-Forwarded-* usages
            Arguments.of(new Request("X-Forwarded-Proto (old syntax)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Proto: https"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .requestURL("https://myhost/")
            ),
            Arguments.of(new Request("X-Forwarded-For (multiple headers)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 10.9.8.7,6.5.4.3",
                        "X-Forwarded-For: 8.9.8.7,7.5.4.3"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("10.9.8.7").remotePort(0)
            ),
            Arguments.of(new Request("X-Forwarded-For (IPv4 with port)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 10.9.8.7:1111,6.5.4.3:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("10.9.8.7").remotePort(1111)
            ),
            Arguments.of(new Request("X-Forwarded-For (IPv6 with port)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: [2001:db8:cafe::17]:1111,6.5.4.3:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("[2001:db8:cafe::17]").remotePort(1111)
            ),
            Arguments.of(new Request("X-Forwarded-For and X-Forwarded-Port (once)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 1:2:3:4:5:6:7:8",
                        "X-Forwarded-Port: 2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(2222)
                    .requestURL("http://myhost:2222/")
                    .remoteAddr("[1:2:3:4:5:6:7:8]").remotePort(0)
            ),
            Arguments.of(new Request("X-Forwarded-For and X-Forwarded-Port (multiple times)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Port: 2222", // this wins
                        "X-Forwarded-For: 1:2:3:4:5:6:7:8", // this wins
                        "X-Forwarded-For: 7:7:7:7:7:7:7:7", // ignored
                        "X-Forwarded-Port: 3333" // ignored
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(2222)
                    .requestURL("http://myhost:2222/")
                    .remoteAddr("[1:2:3:4:5:6:7:8]").remotePort(0)
            ),
            Arguments.of(new Request("X-Forwarded-For and X-Forwarded-Port (multiple times combined)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Port: 2222, 3333",
                        "X-Forwarded-For: 1:2:3:4:5:6:7:8, 7:7:7:7:7:7:7:7"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(2222)
                    .requestURL("http://myhost:2222/")
                    .remoteAddr("[1:2:3:4:5:6:7:8]").remotePort(0)
            ),
            Arguments.of(new Request("X-Forwarded-Port")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Port: 4444", // resets server port
                        "X-Forwarded-For: 192.168.1.200"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(4444)
                    .requestURL("http://myhost:4444/")
                    .remoteAddr("192.168.1.200").remotePort(0)
            ),
            Arguments.of(new Request("X-Forwarded-Port (ForwardedPortAsAuthority==false)")
                    .configureCustomizer((customizer) -> customizer.setForwardedPortAsAuthority(false))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Port: 4444",
                        "X-Forwarded-For: 192.168.1.200"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.168.1.200").remotePort(4444)
            ),
            Arguments.of(new Request("X-Forwarded-Port for Late Host header")
                    .headers(
                        "GET / HTTP/1.1",
                        "X-Forwarded-Port: 4444", // this order is intentional
                        "X-Forwarded-For: 192.168.1.200",
                        "Host: myhost" // leave this as last header
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(4444)
                    .requestURL("http://myhost:4444/")
                    .remoteAddr("192.168.1.200").remotePort(0)
            ),
            Arguments.of(new Request("X-Forwarded-* (all headers except server)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Proto: https",
                        "X-Forwarded-Host: www.example.com",
                        "X-Forwarded-Port: 4333",
                        "X-Forwarded-For: 8.5.4.3:2222"
                    ),
                new Expectations()
                    .scheme("https").serverName("www.example.com").serverPort(4333)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new Request("X-Forwarded-* (all headers except server, port first)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Proto: https",
                        "X-Forwarded-Port: 4333",
                        "X-Forwarded-Host: www.example.com",
                        "X-Forwarded-For: 8.5.4.3:2222"
                        ),
                new Expectations()
                    .scheme("https").serverName("www.example.com").serverPort(4333)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new Request("X-Forwarded-* (all headers)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Proto: https",
                        "X-Forwarded-Host: www.example.com",
                        "X-Forwarded-Port: 4333",
                        "X-Forwarded-For: 8.5.4.3:2222",
                        "X-Forwarded-Server: fw.example.com"
                    ),
                new Expectations()
                    .scheme("https").serverName("www.example.com").serverPort(4333)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new Request("X-Forwarded-* (Server before Host)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Proto: https",
                        "X-Forwarded-Server: fw.example.com",
                        "X-Forwarded-Host: www.example.com",
                        "X-Forwarded-Port: 4333",
                        "X-Forwarded-For: 8.5.4.3:2222"
                    ),
                new Expectations()
                    .scheme("https").serverName("www.example.com").serverPort(4333)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new Request("X-Forwarded-* (all headers reversed)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Server: fw.example.com",
                        "X-Forwarded-For: 8.5.4.3:2222",
                        "X-Forwarded-Port: 4333",
                        "X-Forwarded-Host: www.example.com",
                        "X-Forwarded-Proto: https"
                        ),
                new Expectations()
                    .scheme("https").serverName("www.example.com").serverPort(4333)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new Request("X-Forwarded-* (Server and Port)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Server: fw.example.com",
                        "X-Forwarded-Port: 4333",
                        "X-Forwarded-For: 8.5.4.3:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("fw.example.com").serverPort(4333)
                    .requestURL("http://fw.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new Request("X-Forwarded-* (Port and Server)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Port: 4333",
                        "X-Forwarded-For: 8.5.4.3:2222",
                        "X-Forwarded-Server: fw.example.com"
                    ),
                new Expectations()
                    .scheme("http").serverName("fw.example.com").serverPort(4333)
                    .requestURL("http://fw.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),

            // =================================================================
            // Mixed Behavior
            Arguments.of(new Request("RFC7239 mixed with X-Forwarded-* headers")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 11.9.8.7:1111,8.5.4.3:2222",
                        "X-Forwarded-Port: 3333",
                        "Forwarded: for=192.0.2.43,for=198.51.100.17;by=203.0.113.60;proto=http;host=example.com",
                        "X-Forwarded-For: 11.9.8.7:1111,8.5.4.3:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("example.com").serverPort(80)
                    .requestURL("http://example.com/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // =================================================================
            // Legacy Headers
            Arguments.of(new Request("X-Proxied-Https")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Proxied-Https: on"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .requestURL("https://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
            ),
            Arguments.of(new Request("Proxy-Ssl-Id (setSslIsSecure==false)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(false))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Proxy-Ssl-Id: Wibble"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
            ),
            Arguments.of(new Request("Proxy-Ssl-Id (setSslIsSecure==true)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(true))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Proxy-Ssl-Id: 0123456789abcdef"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .requestURL("https://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("0123456789abcdef")
            ),
            Arguments.of(new Request("Proxy-Auth-Cert (setSslIsSecure==false)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(false))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Proxy-auth-cert: Wibble"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .requestURL("http://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslCertificate("Wibble")
            ),
            Arguments.of(new Request("Proxy-Auth-Cert (setSslIsSecure==true)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(true))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Proxy-auth-cert: 0123456789abcdef"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .requestURL("https://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslCertificate("0123456789abcdef")
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @SuppressWarnings("unused")
    public void testDefaultBehavior(Request request, Expectations expectations) throws Exception
    {
        request.configure(customizer);

        String rawRequest = request.getRawRequest((header) -> header);
        System.out.println(rawRequest);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat("status", response.getStatus(), is(200));

        expectations.accept(actual);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @SuppressWarnings("unused")
    public void testConfiguredBehavior(Request request, Expectations expectations) throws Exception
    {
        request.configure(customizerConfigured);

        String rawRequest = request.getRawRequest((header) -> header
            .replaceFirst("X-Forwarded-", "Jetty-Forwarded-")
            .replaceFirst("Forwarded:", "Jetty-Forwarded:")
            .replaceFirst("X-Proxied-Https:", "Jetty-Proxied-Https:")
            .replaceFirst("Proxy-Ssl-Id:", "Jetty-Proxy-Ssl-Id:")
            .replaceFirst("Proxy-auth-cert:", "Jetty-Proxy-Auth-Cert:"));
        System.out.println(rawRequest);

        HttpTester.Response response = HttpTester.parseResponse(connectorConfigured.getResponse(rawRequest));
        assertThat("status", response.getStatus(), is(200));

        expectations.accept(actual);
    }

    @Test
    public void testBadInput() throws Exception
    {
        Request request = new Request("Bad port value")
            .headers(
                "GET / HTTP/1.1",
                "Host: myhost",
                "X-Forwarded-Port: "
            );

        request.configure(customizer);

        String rawRequest = request.getRawRequest((header) -> header);
        System.out.println(rawRequest);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat("status", response.getStatus(), is(400));
    }

    private static class Request
    {
        String description;
        String[] requestHeaders;
        Consumer<ForwardedRequestCustomizer> forwardedRequestCustomizerConsumer;

        public Request(String description)
        {
            this.description = description;
        }

        public Request headers(String... headers)
        {
            this.requestHeaders = headers;
            return this;
        }

        public Request configureCustomizer(Consumer<ForwardedRequestCustomizer> forwardedRequestCustomizerConsumer)
        {
            this.forwardedRequestCustomizerConsumer = forwardedRequestCustomizerConsumer;
            return this;
        }

        public void configure(ForwardedRequestCustomizer customizer)
        {
            if (forwardedRequestCustomizerConsumer != null)
            {
                forwardedRequestCustomizerConsumer.accept(customizer);
            }
        }

        public String getRawRequest(Function<String, String> headerManip)
        {
            StringBuilder request = new StringBuilder();
            for (String header : requestHeaders)
            {
                request.append(headerManip.apply(header)).append('\n');
            }
            request.append('\n');
            return request.toString();
        }

        @Override
        public String toString()
        {
            return this.description;
        }
    }

    private static class Expectations implements Consumer<Actual>
    {
        String expectedScheme;
        String expectedServerName;
        int expectedServerPort;
        String expectedRequestURL;
        String expectedRemoteAddr = "0.0.0.0";
        int expectedRemotePort = 0;
        String expectedSslSession;
        String expectedSslCertificate;

        @Override
        public void accept(Actual actual)
        {
            assertThat("scheme", actual.scheme.get(), is(expectedScheme));
            if (actual.scheme.equals("https"))
            {
                assertTrue(actual.wasSecure.get(), "wasSecure");
            }
            assertThat("serverName", actual.serverName.get(), is(expectedServerName));
            assertThat("serverPort", actual.serverPort.get(), is(expectedServerPort));
            assertThat("requestURL", actual.requestURL.get(), is(expectedRequestURL));
            if (expectedRemoteAddr != null)
            {
                assertThat("remoteAddr", actual.remoteAddr.get(), is(expectedRemoteAddr));
                assertThat("remotePort", actual.remotePort.get(), is(expectedRemotePort));
            }
            if (expectedSslSession != null)
            {
                assertThat("sslSession", actual.sslSession.get(), is(expectedSslSession));
            }
            if (expectedSslCertificate != null)
            {
                assertThat("sslCertificate", actual.sslCertificate.get(), is(expectedSslCertificate));
            }
        }

        public Expectations scheme(String scheme)
        {
            this.expectedScheme = scheme;
            return this;
        }

        public Expectations serverName(String name)
        {
            this.expectedServerName = name;
            return this;
        }

        public Expectations serverPort(int port)
        {
            this.expectedServerPort = port;
            return this;
        }

        public Expectations requestURL(String requestURL)
        {
            this.expectedRequestURL = requestURL;
            return this;
        }

        public Expectations remoteAddr(String remoteAddr)
        {
            this.expectedRemoteAddr = remoteAddr;
            return this;
        }

        public Expectations remotePort(int remotePort)
        {
            this.expectedRemotePort = remotePort;
            return this;
        }

        public Expectations sslSession(String sslSession)
        {
            this.expectedSslSession = sslSession;
            return this;
        }

        public Expectations sslCertificate(String sslCertificate)
        {
            this.expectedSslCertificate = sslCertificate;
            return this;
        }
    }

    interface RequestTester
    {
        boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException;
    }

    private class RequestHandler extends AbstractHandler
    {
        private RequestTester requestTester;

        @Override
        public void handle(String target, org.eclipse.jetty.server.Request baseRequest,
                           HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);

            if (requestTester != null && requestTester.check(request, response))
                response.setStatus(200);
            else
                response.sendError(500);
        }
    }
}
