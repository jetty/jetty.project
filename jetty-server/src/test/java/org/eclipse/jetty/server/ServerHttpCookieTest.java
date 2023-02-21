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
import java.util.List;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.CookieParser;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.UrlEncoded;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.http.CookieCompliance.RFC2965;
import static org.eclipse.jetty.http.CookieCompliance.RFC2965_LEGACY;
import static org.eclipse.jetty.http.CookieCompliance.RFC6265;
import static org.eclipse.jetty.http.CookieCompliance.RFC6265_LEGACY;
import static org.eclipse.jetty.http.CookieCompliance.RFC6265_STRICT;
import static org.eclipse.jetty.http.CookieCompliance.from;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class ServerHttpCookieTest
{
    private Server _server;
    private LocalConnector _connector;
    private HttpConfiguration _httpConfiguration;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        _server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                HttpConfiguration config = baseRequest.getHttpChannel().getHttpConfiguration();
                baseRequest.setHandled(true);
                String setCookie = baseRequest.getParameter("SetCookie");
                if (setCookie != null)
                {
                    CookieParser parser = CookieParser.newParser((name, value, version, domain, path, comment) ->
                    {
                        Cookie cookie = new Cookie(name, value);
                        if (version > 0)
                            cookie.setVersion(version);
                        if (domain != null)
                            cookie.setDomain(domain);
                        if (path != null)
                            cookie.setPath(path);
                        if (comment != null)
                            cookie.setComment(comment);
                        response.addCookie(cookie);
                    }, RFC2965, null);
                    parser.parseField(setCookie);
                }

                Cookie[] cookies = request.getCookies();
                StringBuilder out = new StringBuilder();
                if (cookies != null)
                {
                    for (Cookie cookie : cookies)
                    {
                        out
                            .append("[")
                            .append(cookie.getName())
                            .append('=')
                            .append(cookie.getValue());

                        if (cookie.getVersion() > 0)
                            out.append(";Version=").append(cookie.getVersion());
                        if (cookie.getPath() != null)
                            out.append(";Path=").append(cookie.getPath());
                        if (cookie.getDomain() != null)
                            out.append(";Domain=").append(cookie.getDomain());
                        out.append("]\n");
                    }
                }
                response.getWriter().println(out);
            }
        });
        _httpConfiguration = _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        _server.start();
    }

    public static Stream<Arguments> requestCases()
    {
        return Stream.of(
            Arguments.of(RFC6265_STRICT, "Cookie: name=value", 200, "Version=", List.of("[name=value]").toArray(new String[0])),


            // Attribute tests
            // TODO $name attributes are ignored because servlet 5.0 Cookie class rejects them.  They are not ignored in servlet 6.0
            Arguments.of(RFC6265_STRICT, "Cookie:  $version=1; name=value", 200, "Version=", List.of("[name=value]").toArray(new String[0])),
            Arguments.of(RFC6265, "Cookie: $version=1; name=value", 200, "Version=", List.of("[name=value]").toArray(new String[0])),
            Arguments.of(RFC6265, "Cookie: name=value;$path=/path", 200, "Path=", List.of("[name=value]").toArray(new String[0])),
            Arguments.of(from("RFC6265,ATTRIBUTES"), "Cookie: name=value;$path=/path", 200, "/path", List.of("name=value").toArray(new String[0])),
            Arguments.of(from("RFC6265_STRICT,ATTRIBUTE_VALUES"), "Cookie: name=value;$path=/path", 200, null, List.of("name=value;Path=/path").toArray(new String[0])),
            Arguments.of(RFC2965, "Cookie: name=value;$path=/path", 200, null, List.of("name=value;Path=/path").toArray(new String[0])),
            Arguments.of(RFC2965, "Cookie: $Version=1;name=value;$path=/path", 200, null, List.of("name=value;Version=1;Path=/path").toArray(new String[0])),
            Arguments.of(RFC2965, "Cookie: $Version=1;name=value;$path=/path;$Domain=host", 200, null, List.of("name=value;Version=1;Path=/path;Domain=host").toArray(new String[0])),

            // multiple cookie tests
            Arguments.of(RFC6265_STRICT, "Cookie: name=value; other=extra", 200, "Version=", List.of("[name=value]", "[other=extra]").toArray(new String[0])),
            Arguments.of(RFC6265_STRICT, "Cookie: name=value, other=extra", 400, null, List.of("BadMessageException", "Comma cookie separator").toArray(new String[0])),
            Arguments.of(from("RFC6265_STRICT,COMMA_SEPARATOR,"), "Cookie: name=value, other=extra", 200, "Version=", List.of("[name=value]", "[other=extra]").toArray(new String[0])),
            Arguments.of(RFC6265, "Cookie: name=value, other=extra", 200, "name=value", null),
            Arguments.of(RFC6265_LEGACY, "Cookie: name=value, other=extra", 200, null, List.of("[name=value, other=extra]").toArray(new String[0])),
            Arguments.of(RFC6265_LEGACY, "Cookie: name=value; other=extra", 200, null, List.of("[name=value]", "other=extra]").toArray(new String[0])),
            Arguments.of(RFC2965, "Cookie: name=value, other=extra", 200, "Version=", List.of("[name=value]", "[other=extra]").toArray(new String[0])),
            Arguments.of(RFC2965_LEGACY, "Cookie: name=value, other=extra", 200, "Version=", List.of("[name=value]", "[other=extra]").toArray(new String[0])),

            // white space
            Arguments.of(RFC6265_STRICT, "Cookie: name =value", 400, null, List.of("BadMessageException", "Bad Cookie name").toArray(new String[0])),
            Arguments.of(from("RFC6265,OPTIONAL_WHITE_SPACE"), "Cookie: name =value", 200, null, List.of("name=value").toArray(new String[0])),

            // bad characters
            Arguments.of(RFC6265_STRICT, "Cookie: name=va\\ue", 400, null, List.of("BadMessageException", "Bad Cookie value").toArray(new String[0])),
            Arguments.of(RFC6265_STRICT, "Cookie: name=\"value\"", 200, "Version=", List.of("[name=value]").toArray(new String[0])),
            Arguments.of(RFC6265_STRICT, "Cookie: name=\"value;other=extra\"", 400, null, List.of("BadMessageException", "Bad Cookie quoted value").toArray(new String[0])),
            Arguments.of(RFC6265, "Cookie: name=\"value;other=extra\"", 200, "name=value", null),
            Arguments.of(RFC6265_LEGACY, "Cookie: name=\"value;other=extra\"", 200, null, List.of("[name=value;other=extra]").toArray(new String[0])),
            Arguments.of(RFC2965, "Cookie: name=\"value;other=extra\"", 200, null, List.of("[name=value;other=extra]").toArray(new String[0])),
            Arguments.of(RFC2965, "Cookie: name=\"value;other=extra", 200, "name=value", null),
            Arguments.of(RFC2965_LEGACY, "Cookie: name=\"value;other=extra\"", 200, null, List.of("[name=value;other=extra]").toArray(new String[0])),
            Arguments.of(RFC2965_LEGACY, "Cookie: name=\"value;other=extra", 200, null, List.of("[name=\"value;other=extra]").toArray(new String[0])),

            // TCK check
            Arguments.of(RFC6265, "Cookie: $Version=1; name1=value1; $Domain=hostname; $Path=/servlet_jsh_cookie_web", 200, null, List.of("name1=value1").toArray(new String[0]))
        );
    }

    @ParameterizedTest
    @MethodSource("requestCases")
    public void testRequestCookies(CookieCompliance compliance, String cookie, int status, String unexpected, String... expectations) throws Exception
    {
        _httpConfiguration.setRequestCookieCompliance(compliance);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("GET / HTTP/1.0\r\n" + cookie + "\r\n\r\n"));

        assertThat(response.getStatus(), equalTo(status));

        if (unexpected != null)
            assertThat(response.getContent(), not(containsString(unexpected)));

        if (expectations != null)
            for (String expected : expectations)
                assertThat(response.getContent(), containsString(expected));
    }

    public static Stream<Arguments> responseCases()
    {
        return Stream.of(
            Arguments.of(RFC6265_STRICT, "name=value", "name=value"),
            Arguments.of(RFC6265, "name=value", "name=value"),
            Arguments.of(RFC6265_LEGACY, "name=value", "name=value"),
            Arguments.of(RFC2965, "name=value", "name=value"),
            Arguments.of(RFC2965_LEGACY, "name=value", "name=value"),

            Arguments.of(RFC6265_STRICT, "name=value;$version=1;$path=/path;$domain=domain", "name=value; Path=/path; Domain=domain"),
            Arguments.of(RFC6265, "name=value;$version=1;$path=/path;$domain=domain", "name=value; Path=/path; Domain=domain"),
            Arguments.of(RFC6265_LEGACY, "name=value;$version=1;$path=/path;$domain=domain", "name=value; Path=/path; Domain=domain"),
            Arguments.of(RFC2965, "name=value;$version=1;$path=/path;$domain=domain", "name=value;Version=1;Path=/path;Domain=domain"),
            Arguments.of(RFC2965_LEGACY, "name=value;$version=1;$path=/path;$domain=domain", "name=value;Version=1;Path=/path;Domain=domain"),

            Arguments.of(RFC6265, "name=value", "name=value")
        );
    }

    @ParameterizedTest
    @MethodSource("responseCases")
    public void testResponseCookies(CookieCompliance compliance, String cookie, String expected) throws Exception
    {
        _httpConfiguration.setResponseCookieCompliance(compliance);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("GET /?SetCookie=" + UrlEncoded.encodeString(cookie) + " HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));

        String setCookie = response.get(HttpHeader.SET_COOKIE);
        if (expected == null)
            assertThat(setCookie, nullValue());
        else
            assertThat(setCookie, equalTo(expected));
    }
}
