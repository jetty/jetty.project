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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ForwardedRequestCustomizerTest
{
    private Server server;
    private LocalConnector connector;
    private LocalConnector connectorAlt;
    private LocalConnector connectorConfigured;
    private ForwardedRequestCustomizer customizer;
    private ForwardedRequestCustomizer customizerAlt;
    private ForwardedRequestCustomizer customizerConfigured;

    private static class Actual
    {
        final AtomicReference<String> scheme = new AtomicReference<>();
        final AtomicBoolean wasSecure = new AtomicBoolean(false);
        final AtomicReference<String> serverName = new AtomicReference<>();
        final AtomicReference<Integer> serverPort = new AtomicReference<>();
        final AtomicReference<String> requestURI = new AtomicReference<>();
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
        http.getHttpConfiguration().setSecurePort(443);
        customizer = new ForwardedRequestCustomizer();
        http.getHttpConfiguration().addCustomizer(customizer);
        connector = new LocalConnector(server, http);
        server.addConnector(connector);

        // Alternate behavior Connector
        HttpConnectionFactory httpAlt = new HttpConnectionFactory();
        httpAlt.getHttpConfiguration().setSecurePort(8443);
        customizerAlt = new ForwardedRequestCustomizer();
        httpAlt.getHttpConfiguration().addCustomizer(customizerAlt);
        connectorAlt = new LocalConnector(server, httpAlt);
        server.addConnector(connectorAlt);

        // Configured behavior Connector
        http = new HttpConnectionFactory();
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

        RequestHandler handler = new RequestHandler();
        server.setHandler(handler);

        handler.requestTester = (request, response) ->
        {
            actual = new Actual();
            actual.wasSecure.set(request.isSecure());
            actual.sslSession.set(String.valueOf(request.getAttribute("jakarta.servlet.request.ssl_session_id")));
            actual.sslCertificate.set(String.valueOf(request.getAttribute("jakarta.servlet.request.cipher_suite")));
            actual.scheme.set(request.getHttpURI().getScheme());
            actual.serverName.set(Request.getServerName(request));
            actual.serverPort.set(Request.getServerPort(request));
            actual.remoteAddr.set(Request.getRemoteAddr(request));
            actual.remotePort.set(Request.getRemotePort(request));
            actual.requestURI.set(request.getHttpURI().toString());
            return true;
        };

        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
    }

    public static Stream<Arguments> cases2()
    {
        return Stream.of(
            Arguments.of(new TestRequest("https initial authority, X-Forwarded-Proto on http, Proxy-Ssl-Id exists (setSslIsSecure==false)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(false))
                    .headers(
                        "GET https://alt.example.net/foo HTTP/1.1",
                        "Host: alt.example.net",
                        "X-Forwarded-Proto: http",
                        "Proxy-Ssl-Id: Wibble"
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(80)
                    .secure(false)
                    .requestURL("http://alt.example.net/foo")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
            )
        );
    }

    public static Stream<Arguments> cases()
    {
        return Stream.of(
            // HTTP 1.0
            Arguments.of(
                new TestRequest("HTTP/1.0 - no Host header")
                    .headers(
                        "GET /example HTTP/1.0"
                    ),
                new Expectations()
                    .scheme("http").serverName("0.0.0.0").serverPort(80)
                    .secure(false)
                    .requestURL("http://0.0.0.0/example")
            ),
            Arguments.of(
                new TestRequest("HTTP/1.0 - Empty Host header")
                    .headers(
                        "GET scheme:///example HTTP/1.0",
                        "Host:"
                    ),
                new Expectations()
                    .scheme("scheme").serverName(null).serverPort(-1)
                    .secure(false)
                    .requestURL("scheme:///example")
            ),
            Arguments.of(
                new TestRequest("HTTP/1.0 - No Host header, with X-Forwarded-Host")
                    .headers(
                        "GET /example HTTP/1.0",
                        "X-Forwarded-Host: alt.example.net:7070"
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(7070)
                    .secure(false)
                    .requestURL("http://alt.example.net:7070/example")
            ),
            Arguments.of(
                new TestRequest("HTTP/1.0 - Empty Host header, with X-Forwarded-Host")
                    .headers(
                        "GET http:///example HTTP/1.0",
                        "Host:",
                        "X-Forwarded-Host: alt.example.net:7070"
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(7070)
                    .secure(false)
                    .requestURL("http://alt.example.net:7070/example")
            ),
            // Host IPv4
            Arguments.of(
                new TestRequest("IPv4 Host Only")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: 1.2.3.4:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("1.2.3.4").serverPort(2222)
                    .secure(false)
                    .requestURL("http://1.2.3.4:2222/")
            ),
            Arguments.of(new TestRequest("IPv6 Host Only")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: [::1]:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("[::1]").serverPort(2222)
                    .secure(false)
                    .requestURL("http://[::1]:2222/")
            ),
            Arguments.of(new TestRequest("IPv4 in Request line only")
                    .headers(
                        "GET https://1.2.3.4:2222/ HTTP/1.0"
                    ),
                new Expectations()
                    .scheme("https").serverName("1.2.3.4").serverPort(2222)
                    .secure(true)
                    .requestURL("https://1.2.3.4:2222/")
            ),
            Arguments.of(new TestRequest("IPv6 in Request line only")
                    .headers(
                        "GET http://[::1]:2222/ HTTP/1.0"
                    ),
                new Expectations()
                    .scheme("http").serverName("[::1]").serverPort(2222)
                    .secure(false)
                    .requestURL("http://[::1]:2222/")
            ),
            Arguments.of(new TestRequest("IPv4 in Request Line")
                    .headers(
                        "GET https://1.2.3.4:2222/ HTTP/1.1",
                        "Host: 1.2.3.4:2222"
                    ),
                new Expectations()
                    .scheme("https").serverName("1.2.3.4").serverPort(2222)
                    .secure(true)
                    .requestURL("https://1.2.3.4:2222/")
            ),
            Arguments.of(new TestRequest("IPv6 in Request Line")
                    .headers(
                        "GET http://[::1]:2222/ HTTP/1.1",
                        "Host: [::1]:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("[::1]").serverPort(2222)
                    .secure(false)
                    .requestURL("http://[::1]:2222/")
            ),

            // =================================================================
            // https://tools.ietf.org/html/rfc7239#section-4  - examples of syntax
            Arguments.of(new TestRequest("RFC7239 Examples: Section 4")
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
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("[2001:db8:cafe::17]").remotePort(4711)
            ),

            // https://tools.ietf.org/html/rfc7239#section-7  - Examples of syntax with regards to HTTP header fields
            Arguments.of(new TestRequest("RFC7239 Examples: Section 7")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43,for=\"[2001:db8:cafe::17]\",for=unknown"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // (same as above, but with spaces, as shown in RFC section 7.1)
            Arguments.of(new TestRequest("RFC7239 Examples: Section 7 (spaced)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43, for=\"[2001:db8:cafe::17]\", for=unknown"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // (same as above, but as multiple headers, as shown in RFC section 7.1)
            Arguments.of(new TestRequest("RFC7239 Examples: Section 7 (multi header)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43",
                        "Forwarded: for=\"[2001:db8:cafe::17]\", for=unknown"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // https://tools.ietf.org/html/rfc7239#section-7.4  - Transition
            Arguments.of(new TestRequest("RFC7239 Examples: Section 7.4 (old syntax)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 192.0.2.43, 2001:db8:cafe::17"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),
            Arguments.of(new TestRequest("RFC7239 Examples: Section 7.4 (new syntax)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43, for=\"[2001:db8:cafe::17]\""
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // https://tools.ietf.org/html/rfc7239#section-7.5  - Example Usage
            Arguments.of(new TestRequest("RFC7239 Examples: Section 7.5")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: for=192.0.2.43,for=198.51.100.17;by=203.0.113.60;proto=http;host=example.com"
                    ),
                new Expectations()
                    .scheme("http").serverName("example.com").serverPort(80)
                    .secure(false)
                    .requestURL("http://example.com/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),

            // Forwarded, proto only
            Arguments.of(new TestRequest("RFC7239: Forwarded proto only")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Forwarded: proto=https"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .secure(true)
                    .requestURL("https://myhost/")
            ),

            // =================================================================
            // ProxyPass usages
            Arguments.of(new TestRequest("ProxyPass (example.com:80 to localhost:8080)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: localhost:8080",
                        "X-Forwarded-For: 10.20.30.40",
                        "X-Forwarded-Host: example.com"
                    ),
                new Expectations()
                    .scheme("http").serverName("example.com").serverPort(80)
                    .secure(false)
                    .remoteAddr("10.20.30.40")
                    .requestURL("http://example.com/")
            ),
            Arguments.of(new TestRequest("ProxyPass (example.com:81 to localhost:8080)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: localhost:8080",
                        "X-Forwarded-For: 10.20.30.40",
                        "X-Forwarded-Host: example.com:81",
                        "X-Forwarded-Server: example.com",
                        "X-Forwarded-Proto: https"
                    ),
                new Expectations()
                    .scheme("https").serverName("example.com").serverPort(81)
                    .secure(true)
                    .remoteAddr("10.20.30.40")
                    .requestURL("https://example.com:81/")
            ),
            Arguments.of(new TestRequest("ProxyPass (example.com:443 to localhost:8443)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: localhost:8443",
                        "X-Forwarded-Host: example.com",
                        "X-Forwarded-Proto: https"
                    ),
                new Expectations()
                    .scheme("https").serverName("example.com").serverPort(443)
                    .secure(true)
                    .requestURL("https://example.com/")
            ),
            Arguments.of(new TestRequest("ProxyPass (IPv6 from [::1]:80 to localhost:8080)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: localhost:8080",
                        "X-Forwarded-For: 10.20.30.40",
                        "X-Forwarded-Host: [::1]"
                    ),
                new Expectations()
                    .scheme("http").serverName("[::1]").serverPort(80)
                    .secure(false)
                    .remoteAddr("10.20.30.40")
                    .requestURL("http://[::1]/")
            ),
            Arguments.of(new TestRequest("ProxyPass (IPv6 from [::1]:8888 to localhost:8080)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: localhost:8080",
                        "X-Forwarded-For: 10.20.30.40",
                        "X-Forwarded-Host: [::1]:8888"
                    ),
                new Expectations()
                    .scheme("http").serverName("[::1]").serverPort(8888)
                    .secure(false)
                    .remoteAddr("10.20.30.40")
                    .requestURL("http://[::1]:8888/")
            ),
            Arguments.of(new TestRequest("Multiple ProxyPass (example.com:80 to rp.example.com:82 to localhost:8080)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: localhost:8080",
                        "X-Forwarded-For: 10.20.30.40, 10.0.0.1",
                        "X-Forwarded-Host: example.com, rp.example.com:82",
                        "X-Forwarded-Server: example.com, rp.example.com",
                        "X-Forwarded-Proto: https, http"
                    ),
                new Expectations()
                    .scheme("https").serverName("example.com").serverPort(443)
                    .secure(true)
                    .remoteAddr("10.20.30.40")
                    .requestURL("https://example.com/")
            ),
            // =================================================================
            // X-Forwarded-* usages
            Arguments.of(new TestRequest("X-Forwarded-Proto (old syntax)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Proto: https"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .secure(true)
                    .requestURL("https://myhost/")
            ),
            Arguments.of(new TestRequest("X-Forwarded-For (multiple headers)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 10.9.8.7,6.5.4.3",
                        "X-Forwarded-For: 8.9.8.7,7.5.4.3"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("10.9.8.7").remotePort(0)
            ),
            Arguments.of(new TestRequest("X-Forwarded-For (IPv4 with port)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 10.9.8.7:1111,6.5.4.3:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("10.9.8.7").remotePort(1111)
            ),
            Arguments.of(new TestRequest("X-Forwarded-For (IPv6 without port)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 2001:db8:cafe::17"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("[2001:db8:cafe::17]").remotePort(0)
            ),
            Arguments.of(new TestRequest("X-Forwarded-For (IPv6 with port)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: [2001:db8:cafe::17]:1111,6.5.4.3:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("[2001:db8:cafe::17]").remotePort(1111)
            ),
            Arguments.of(new TestRequest("X-Forwarded-For and X-Forwarded-Port (once)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 1:2:3:4:5:6:7:8",
                        "X-Forwarded-Port: 2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(2222)
                    .secure(false)
                    .requestURL("http://myhost:2222/")
                    .remoteAddr("[1:2:3:4:5:6:7:8]").remotePort(0)
            ),
            Arguments.of(new TestRequest("X-Forwarded-For and X-Forwarded-Port (multiple times)")
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
                    .secure(false)
                    .requestURL("http://myhost:2222/")
                    .remoteAddr("[1:2:3:4:5:6:7:8]").remotePort(0)
            ),
            Arguments.of(new TestRequest("X-Forwarded-For and X-Forwarded-Port (multiple times combined)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Port: 2222, 3333",
                        "X-Forwarded-For: 1:2:3:4:5:6:7:8, 7:7:7:7:7:7:7:7"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(2222)
                    .secure(false)
                    .requestURL("http://myhost:2222/")
                    .remoteAddr("[1:2:3:4:5:6:7:8]").remotePort(0)
            ),
            Arguments.of(new TestRequest("X-Forwarded-Port")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Port: 4444", // resets server port
                        "X-Forwarded-For: 192.168.1.200"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(4444)
                    .secure(false)
                    .requestURL("http://myhost:4444/")
                    .remoteAddr("192.168.1.200").remotePort(0)
            ),
            Arguments.of(new TestRequest("X-Forwarded-Port (ForwardedPortAsAuthority==false)")
                    .configureCustomizer((customizer) -> customizer.setForwardedPortAsAuthority(false))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Port: 4444",
                        "X-Forwarded-For: 192.168.1.200"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("192.168.1.200").remotePort(4444)
            ),
            Arguments.of(new TestRequest("X-Forwarded-Port for Late Host header")
                    .headers(
                        "GET / HTTP/1.1",
                        "X-Forwarded-Port: 4444", // this order is intentional
                        "X-Forwarded-For: 192.168.1.200",
                        "Host: myhost" // leave this as last header
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(4444)
                    .secure(false)
                    .requestURL("http://myhost:4444/")
                    .remoteAddr("192.168.1.200").remotePort(0)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (all headers except server)")
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
                    .secure(true)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (all headers except server, port first)")
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
                    .secure(true)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (all headers)")
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
                    .secure(true)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (Server before Host)")
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
                    .secure(true)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (all headers reversed)")
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
                    .secure(true)
                    .requestURL("https://www.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (Server and Port)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Server: fw.example.com",
                        "X-Forwarded-Port: 4333",
                        "X-Forwarded-For: 8.5.4.3:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("fw.example.com").serverPort(4333)
                    .secure(false)
                    .requestURL("http://fw.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (Port and Server)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Port: 4333",
                        "X-Forwarded-For: 8.5.4.3:2222",
                        "X-Forwarded-Server: fw.example.com"
                    ),
                new Expectations()
                    .scheme("http").serverName("fw.example.com").serverPort(4333)
                    .secure(false)
                    .requestURL("http://fw.example.com:4333/")
                    .remoteAddr("8.5.4.3").remotePort(2222)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (Multiple Ports)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost:10001",
                        "X-Forwarded-For: 127.0.0.1:8888,127.0.0.2:9999",
                        "X-Forwarded-Port: 10002",
                        "X-Forwarded-Proto: https",
                        "X-Forwarded-Host: sub1.example.com:10003",
                        "X-Forwarded-Server: sub2.example.com"
                    ),
                new Expectations()
                    .scheme("https").serverName("sub1.example.com").serverPort(10003)
                    .secure(true)
                    .requestURL("https://sub1.example.com:10003/")
                    .remoteAddr("127.0.0.1").remotePort(8888)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (Multiple Ports - Server First)")
                    .headers(
                        "GET / HTTP/1.1",
                        "X-Forwarded-Server: sub2.example.com:10007",
                        "Host: myhost:10001",
                        "X-Forwarded-For: 127.0.0.1:8888,127.0.0.2:9999",
                        "X-Forwarded-Proto: https",
                        "X-Forwarded-Port: 10002",
                        "X-Forwarded-Host: sub1.example.com:10003"
                    ),
                new Expectations()
                    .scheme("https").serverName("sub1.example.com").serverPort(10003)
                    .secure(true)
                    .requestURL("https://sub1.example.com:10003/")
                    .remoteAddr("127.0.0.1").remotePort(8888)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (Multiple Ports - setForwardedPortAsAuthority = false)")
                    .configureCustomizer((customizer) -> customizer.setForwardedPortAsAuthority(false))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost:10001",
                        "X-Forwarded-For: 127.0.0.1:8888,127.0.0.2:9999",
                        "X-Forwarded-Port: 10002",
                        "X-Forwarded-Proto: https",
                        "X-Forwarded-Host: sub1.example.com:10003",
                        "X-Forwarded-Server: sub2.example.com"
                    ),
                new Expectations()
                    .scheme("https").serverName("sub1.example.com").serverPort(10003)
                    .secure(true)
                    .requestURL("https://sub1.example.com:10003/")
                    .remoteAddr("127.0.0.1").remotePort(8888)
            ),
            Arguments.of(new TestRequest("X-Forwarded-* (Multiple Ports Alt Order)")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost:10001",
                        "X-Forwarded-For: 127.0.0.1:8888,127.0.0.2:9999",
                        "X-Forwarded-Proto: https",
                        "X-Forwarded-Host: sub1.example.com:10003",
                        "X-Forwarded-Port: 10002",
                        "X-Forwarded-Server: sub2.example.com"
                    ),
                new Expectations()
                    .scheme("https").serverName("sub1.example.com").serverPort(10003)
                    .secure(true)
                    .requestURL("https://sub1.example.com:10003/")
                    .remoteAddr("127.0.0.1").remotePort(8888)
            ),
            // =================================================================
            // Mixed Behavior
            Arguments.of(new TestRequest("RFC7239 mixed with X-Forwarded-* headers")
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
                    .secure(false)
                    .requestURL("http://example.com/")
                    .remoteAddr("192.0.2.43").remotePort(0)
            ),
            Arguments.of(
                new TestRequest("RFC7239 - mixed with HTTP/1.0 - No Host header")
                    .headers(
                        "GET /example HTTP/1.0",
                        "Forwarded: for=1.1.1.1:6060,proto=http;host=alt.example.net:7070"
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(7070)
                    .secure(false)
                    .requestURL("http://alt.example.net:7070/example")
                    .remoteAddr("1.1.1.1").remotePort(6060)
            ),
            Arguments.of(
                new TestRequest("RFC7239 - mixed with HTTP/1.0 - Empty Host header")
                    .headers(
                        "GET http:///example HTTP/1.0",
                        "Host:",
                        "Forwarded: for=1.1.1.1:6060,proto=http;host=alt.example.net:7070"
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(7070)
                    .secure(false)
                    .requestURL("http://alt.example.net:7070/example")
                    .remoteAddr("1.1.1.1").remotePort(6060)
            ),
            // =================================================================
            // Forced Behavior
            Arguments.of(new TestRequest("Forced Host (no port)")
                    .configureCustomizer((customizer) -> customizer.setForcedHost("always.example.com"))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 11.9.8.7:1111",
                        "X-Forwarded-Host: example.com:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("always.example.com").serverPort(80)
                    .secure(false)
                    .requestURL("http://always.example.com/")
                    .remoteAddr("11.9.8.7").remotePort(1111)
            ),
            Arguments.of(new TestRequest("Forced Host with port")
                    .configureCustomizer((customizer) -> customizer.setForcedHost("always.example.com:9090"))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 11.9.8.7:1111",
                        "X-Forwarded-Host: example.com:2222"
                    ),
                new Expectations()
                    .scheme("http").serverName("always.example.com").serverPort(9090)
                    .secure(false)
                    .requestURL("http://always.example.com:9090/")
                    .remoteAddr("11.9.8.7").remotePort(1111)
            ),
            // =================================================================
            // Legacy Headers
            Arguments.of(new TestRequest("X-Proxied-Https")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Proxied-Https: on"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .secure(true)
                    .requestURL("https://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
            ),
            Arguments.of(new TestRequest("Proxy-Ssl-Id (setSslIsSecure==false)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(false))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Proxy-Ssl-Id: Wibble"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
            ),
            Arguments.of(new TestRequest("Proxy-Ssl-Id (setSslIsSecure==true)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(true))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Proxy-Ssl-Id: 0123456789abcdef"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .secure(true)
                    .requestURL("https://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("0123456789abcdef")
            ),
            Arguments.of(new TestRequest("Proxy-Auth-Cert (setSslIsSecure==false)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(false))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Proxy-auth-cert: Wibble"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslCertificate("Wibble")
            ),
            Arguments.of(new TestRequest("Proxy-Auth-Cert (setSslIsSecure==true)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(true))
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "Proxy-auth-cert: 0123456789abcdef"
                    ),
                new Expectations()
                    .scheme("https").serverName("myhost").serverPort(443)
                    .secure(true)
                    .requestURL("https://myhost/")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslCertificate("0123456789abcdef")
            ),
            // =================================================================
            // Complicated scenarios
            Arguments.of(new TestRequest("No initial authority, X-Forwarded-Proto on http, Proxy-Ssl-Id exists (setSslIsSecure==true)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(true))
                    .headers(
                        "GET /foo HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-Proto: http",
                        "Proxy-Ssl-Id: Wibble"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(true)
                    .requestURL("http://myhost/foo")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
            ),
            Arguments.of(new TestRequest("https initial authority, X-Forwarded-Proto on http, Proxy-Ssl-Id exists (setSslIsSecure==false)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(false))
                    .headers(
                        "GET https://alt.example.net/foo HTTP/1.1",
                        "Host: alt.example.net",
                        "X-Forwarded-Proto: http",
                        "Proxy-Ssl-Id: Wibble"
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(80)
                    .secure(false)
                    .requestURL("http://alt.example.net/foo")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
            ),
            Arguments.of(new TestRequest("No initial authority, X-Proxied-Https off, Proxy-Ssl-Id exists (setSslIsSecure==true)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(true))
                    .headers(
                        "GET /foo HTTP/1.1",
                        "Host: myhost",
                        "X-Proxied-Https: off", // this wins for scheme and secure
                        "Proxy-Ssl-Id: Wibble"
                    ),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/foo")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
            ),
            Arguments.of(new TestRequest("Https initial authority, X-Proxied-Https off, Proxy-Ssl-Id exists (setSslIsSecure==true)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(true))
                    .headers(
                        "GET https://alt.example.net/foo HTTP/1.1",
                        "Host: alt.example.net",
                        "X-Proxied-Https: off", // this wins for scheme and secure
                        "Proxy-Ssl-Id: Wibble"
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(80)
                    .secure(false)
                    .requestURL("http://alt.example.net/foo")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
            ),
            Arguments.of(new TestRequest("Https initial authority, X-Proxied-Https off, Proxy-Ssl-Id exists (setSslIsSecure==true) (alt order)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(true))
                    .headers(
                        "GET https://alt.example.net/foo HTTP/1.1",
                        "Host: alt.example.net",
                        "Proxy-Ssl-Id: Wibble",
                        "X-Proxied-Https: off" // this wins for scheme and secure
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(80)
                    .secure(false)
                    .requestURL("http://alt.example.net/foo")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
            ),
            Arguments.of(new TestRequest("Http initial authority, X-Proxied-Https off, Proxy-Ssl-Id exists (setSslIsSecure==false)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(false))
                    .headers(
                        "GET https://alt.example.net/foo HTTP/1.1",
                        "Host: alt.example.net",
                        "X-Proxied-Https: off",
                        "Proxy-Ssl-Id: Wibble",
                        "Proxy-auth-cert: 0123456789abcdef"
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(80)
                    .secure(false)
                    .requestURL("http://alt.example.net/foo")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
                    .sslCertificate("0123456789abcdef")
            ),
            Arguments.of(new TestRequest("Http initial authority, X-Proxied-Https off, Proxy-Ssl-Id exists (setSslIsSecure==false) (alt)")
                    .configureCustomizer((customizer) -> customizer.setSslIsSecure(false))
                    .headers(
                        "GET https://alt.example.net/foo HTTP/1.1",
                        "Host: alt.example.net",
                        "Proxy-Ssl-Id: Wibble",
                        "Proxy-auth-cert: 0123456789abcdef",
                        "X-Proxied-Https: off"
                    ),
                new Expectations()
                    .scheme("http").serverName("alt.example.net").serverPort(80)
                    .secure(false)
                    .requestURL("http://alt.example.net/foo")
                    .remoteAddr("0.0.0.0").remotePort(0)
                    .sslSession("Wibble")
                    .sslCertificate("0123456789abcdef")
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    public void testDefaultBehavior(TestRequest request, Expectations expectations) throws Exception
    {
        request.configure(customizer);

        String rawRequest = request.getRawRequest((header) -> header);
//        System.out.println(rawRequest);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat("status", response.getStatus(), is(200));

        expectations.accept(actual);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    public void testConfiguredBehavior(TestRequest request, Expectations expectations) throws Exception
    {
        request.configure(customizerConfigured);

        String rawRequest = request.getRawRequest((header) -> header
            .replaceFirst("X-Forwarded-", "Jetty-Forwarded-")
            .replaceFirst("Forwarded:", "Jetty-Forwarded:")
            .replaceFirst("X-Proxied-Https:", "Jetty-Proxied-Https:")
            .replaceFirst("Proxy-Ssl-Id:", "Jetty-Proxy-Ssl-Id:")
            .replaceFirst("Proxy-auth-cert:", "Jetty-Proxy-Auth-Cert:"));
        // System.out.println(rawRequest);

        HttpTester.Response response = HttpTester.parseResponse(connectorConfigured.getResponse(rawRequest));
        assertThat("status", response.getStatus(), is(200));

        expectations.accept(actual);
    }

    public static Stream<Arguments> nonStandardPortCases()
    {
        return Stream.of(
            // RFC7239 Tests with https.
            Arguments.of(new TestRequest("RFC7239 with https and h2")
                    .headers(
                        "GET /test/forwarded.jsp HTTP/1.1",
                        "Host: web.example.net",
                        "Forwarded: for=192.168.2.6;host=web.example.net;proto=https;proto-version=h2"
                    ),
                new Expectations()
                    .scheme("https").serverName("web.example.net").serverPort(443)
                    .requestURL("https://web.example.net/test/forwarded.jsp")
                    .remoteAddr("192.168.2.6").remotePort(0)
            ),
            // RFC7239 Tests with https and proxy provided port
            Arguments.of(new TestRequest("RFC7239 with proxy provided port on https and h2")
                    .headers(
                        "GET /test/forwarded.jsp HTTP/1.1",
                        "Host: web.example.net:9443",
                        "Forwarded: for=192.168.2.6;host=web.example.net:9443;proto=https;proto-version=h2"
                    ),
                new Expectations()
                    .scheme("https").serverName("web.example.net").serverPort(9443)
                    .requestURL("https://web.example.net:9443/test/forwarded.jsp")
                    .remoteAddr("192.168.2.6").remotePort(0)
            ),
            // RFC7239 Tests with https, no port in Host, but proxy provided port
            Arguments.of(new TestRequest("RFC7239 with client provided host and different proxy provided port on https and h2")
                    .headers(
                        "GET /test/forwarded.jsp HTTP/1.1",
                        "Host: web.example.net",
                        "Forwarded: for=192.168.2.6;host=new.example.net:7443;proto=https;proto-version=h2"
                        // Client: https://web.example.net/test/forwarded.jsp
                        // Proxy Requests: https://new.example.net/test/forwarded.jsp
                    ),
                new Expectations()
                    .scheme("https").serverName("new.example.net").serverPort(7443)
                    .requestURL("https://new.example.net:7443/test/forwarded.jsp")
                    .remoteAddr("192.168.2.6").remotePort(0)
            )
        );
    }

    /**
     * Tests against a Connector with a HttpConfiguration on non-standard ports.
     * HttpConfiguration is set to securePort of 8443
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonStandardPortCases")
    public void testNonStandardPortBehavior(TestRequest request, Expectations expectations) throws Exception
    {
        request.configure(customizerAlt);

        String rawRequest = request.getRawRequest((header) -> header);
        // System.out.println(rawRequest);

        HttpTester.Response response = HttpTester.parseResponse(connectorAlt.getResponse(rawRequest));
        assertThat("status", response.getStatus(), is(200));

        expectations.accept(actual);
    }

    public static Stream<TestRequest> badRequestCases()
    {
        return Stream.of(
            new TestRequest("Bad port value")
                .headers(
                    "GET / HTTP/1.1",
                    "Host: myhost",
                    "X-Forwarded-Port: "
                ),
            new TestRequest("Invalid X-Proxied-Https value")
                .headers(
                    "GET / HTTP/1.1",
                    "Host: myhost",
                    "X-Proxied-Https: foo"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("badRequestCases")
    public void testBadInput(TestRequest request) throws Exception
    {
        request.configure(customizer);

        String rawRequest = request.getRawRequest((header) -> header);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat("status", response.getStatus(), is(400));
    }

    public static Stream<Arguments> customHeaderNameRequestCases()
    {
        return Stream.of(
            Arguments.of(new TestRequest("Old name then new name")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Forwarded-For: 1.1.1.1",
                        "X-Custom-For: 2.2.2.2"
                    )
                    .configureCustomizer((forwardedRequestCustomizer) ->
                        forwardedRequestCustomizer.setForwardedForHeader("X-Custom-For")),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("2.2.2.2").remotePort(0)
            ),
            Arguments.of(new TestRequest("New name then old name")
                    .headers(
                        "GET / HTTP/1.1",
                        "Host: myhost",
                        "X-Custom-For: 2.2.2.2",
                        "X-Forwarded-For: 1.1.1.1"
                    )
                    .configureCustomizer((forwardedRequestCustomizer) ->
                        forwardedRequestCustomizer.setForwardedForHeader("X-Custom-For")),
                new Expectations()
                    .scheme("http").serverName("myhost").serverPort(80)
                    .secure(false)
                    .requestURL("http://myhost/")
                    .remoteAddr("2.2.2.2").remotePort(0)
            )
        );
    }

    @ParameterizedTest
    @MethodSource("customHeaderNameRequestCases")
    public void testCustomHeaderName(TestRequest request, Expectations expectations) throws Exception
    {
        request.configure(customizer);

        String rawRequest = request.getRawRequest((header) -> header);
        // System.out.println(rawRequest);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat("status", response.getStatus(), is(200));

        expectations.accept(actual);
    }

    private static class TestRequest
    {
        String description;
        String[] requestHeaders;
        Consumer<ForwardedRequestCustomizer> forwardedRequestCustomizerConsumer;

        public TestRequest(String description)
        {
            this.description = description;
        }

        public TestRequest headers(String... headers)
        {
            this.requestHeaders = headers;
            return this;
        }

        public TestRequest configureCustomizer(Consumer<ForwardedRequestCustomizer> forwardedRequestCustomizerConsumer)
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
        Boolean secure;

        @Override
        public void accept(Actual actual)
        {
            assertThat("scheme", actual.scheme.get(), is(expectedScheme));
            if (secure != null && secure)
            {
                assertTrue(actual.wasSecure.get(), "wasSecure");
            }
            assertThat("serverName", actual.serverName.get(), is(expectedServerName));
            assertThat("serverPort", actual.serverPort.get(), is(expectedServerPort));
            assertThat("requestURL", actual.requestURI.get(), is(expectedRequestURL));
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

        public Expectations secure(boolean flag)
        {
            this.secure = flag;
            return this;
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
        boolean check(Request request, Response response) throws IOException;
    }

    private static class RequestHandler extends Handler.Processor
    {
        private RequestTester requestTester;

        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            if (requestTester != null && requestTester.check(request, response))
            {
                response.setStatus(200);
                callback.succeeded();
            }
            else
            {
                Response.writeError(request, response, callback, 500, "failed");
            }
        }
    }
}
