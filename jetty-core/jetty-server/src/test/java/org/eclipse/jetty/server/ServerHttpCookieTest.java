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

import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.CookieParser;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
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
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Fields parameters = Request.extractQueryParameters(request);
                Fields.Field setCookie = parameters.get("SetCookie");
                if (setCookie != null)
                {
                    CookieParser parser = CookieParser.newParser(new CookieParser.Handler()
                    {
                        @Override
                        public void addCookie(String name, String value, int version, String domain, String path, String comment)
                        {
                            Response.addCookie(response, HttpCookie.build(name, value, version).domain(domain).path(path).comment(comment).build());
                        }
                    }, RFC2965, null);
                    parser.parseField(setCookie.getValue());
                }

                List<HttpCookie> cookies = Request.getCookies(request);
                StringBuilder out = new StringBuilder();
                for (HttpCookie cookie : cookies)
                    out.append(cookie.toString()).append('\n');
                Content.Sink.write(response, true, out.toString(), callback);
                return true;
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
            Arguments.of(RFC6265_STRICT, "Cookie:  $version=1; name=value", 200, "Version=", List.of("[$version=1]", "[name=value]").toArray(new String[0])),
            Arguments.of(RFC6265, "Cookie: $version=1; name=value", 200, "Version=", List.of("[$version=1]", "[name=value]").toArray(new String[0])),
            Arguments.of(RFC6265, "Cookie: name=value;$path=/path", 200, "Path=", List.of("[name=value]", "[$path=/path]").toArray(new String[0])),
            Arguments.of(from("RFC6265,ATTRIBUTES"), "Cookie: name=value;$path=/path", 200, "/path", List.of("name=value").toArray(new String[0])),
            Arguments.of(from("RFC6265_STRICT,ATTRIBUTE_VALUES"), "Cookie: name=value;$path=/path", 200, null, List.of("name=value; Path=/path").toArray(new String[0])),
            Arguments.of(RFC2965, "Cookie: name=value;$path=/path", 200, null, List.of("name=value; Path=/path").toArray(new String[0])),
            Arguments.of(RFC2965, "Cookie: $Version=1;name=value;$path=/path", 200, null, List.of("name=value; Path=/path").toArray(new String[0])),
            Arguments.of(RFC2965, "Cookie: $Version=1;name=value;$path=/path;$Domain=host", 200, null, List.of("name=value; Domain=host; Path=/path").toArray(new String[0])),

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
            Arguments.of(RFC6265, "Cookie: $Version=1; name1=value1; $Domain=hostname; $Path=/servlet_jsh_cookie_web", 200, null, List.of("$Version=1", "name1=value1", "$Domain=hostname", "$Path=/servlet_jsh_cookie_web").toArray(new String[0]))
        );
    }

    @ParameterizedTest
    @MethodSource("requestCases")
    public void testRequestCookies(CookieCompliance compliance, String cookie, int status, String unexpected, String... expectations) throws Exception
    {
        _httpConfiguration.setRequestCookieCompliance(compliance);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET / HTTP/1.0\r
            %s\r
            \r
            """.formatted(cookie)));

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
            Arguments.of(RFC2965, "name=value;$version=1;$path=/path;$domain=domain", "name=value;Version=1;Domain=domain;Path=/path"),
            Arguments.of(RFC2965_LEGACY, "name=value;$version=1;$path=/path;$domain=domain", "name=value;Version=1;Domain=domain;Path=/path"),

            Arguments.of(RFC6265, "name=value", "name=value")
            );
    }

    @ParameterizedTest
    @MethodSource("responseCases")
    public void testResponseCookies(CookieCompliance compliance, String cookie, String expected) throws Exception
    {
        _httpConfiguration.setResponseCookieCompliance(compliance);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /?SetCookie=%s HTTP/1.0\r
            \r
            """.formatted(UrlEncoded.encodeString(cookie))));

        assertThat(response.getStatus(), equalTo(200));

        String setCookie = response.get(HttpHeader.SET_COOKIE);
        if (expected == null)
            assertThat(setCookie, nullValue());
        else
            assertThat(setCookie, equalTo(expected));
    }
}
