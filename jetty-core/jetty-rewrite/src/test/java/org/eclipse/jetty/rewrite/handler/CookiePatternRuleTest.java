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

package org.eclipse.jetty.rewrite.handler;

import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.fail;

public class CookiePatternRuleTest extends AbstractRuleTest
{
    private void start(CookiePatternRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, false, Callback.NOOP, "pathInContext=%s%n".formatted(request.getPathInContext()));
                Content.Sink.write(response, false, Callback.NOOP, "path=%s%n".formatted(request.getHttpURI().getPath()));
                Content.Sink.write(response, false, Callback.NOOP, "query=%s%n".formatted(request.getHttpURI().getQuery()));
                Request original = Request.unWrap(request);
                Content.Sink.write(response, false, Callback.NOOP, "originalPath=%s%n".formatted(original.getHttpURI().getPath()));
                Content.Sink.write(response, false, Callback.NOOP, "originalQuery=%s%n".formatted(original.getHttpURI().getQuery()));
                callback.succeeded();
            }
        });
    }

    @Test
    public void testRule() throws Exception
    {
        CookiePatternRule rule = new CookiePatternRule();
        rule.setPattern("*");
        rule.setName("cookie");
        rule.setValue("value");

        start(rule);

        String rawRequest = """
            GET / HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = _connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        HttpField setCookieField = response.getField(HttpHeader.SET_COOKIE);
        assertThat("response should have Set-Cookie", setCookieField, notNullValue());
        for (String value : setCookieField.getValues())
        {
            String[] result = value.split("=");
            assertThat(result[0], is("cookie"));
            assertThat(result[1], is("value"));
        }
    }

    @Test
    public void testCookieAlreadySet() throws Exception
    {
        CookiePatternRule rule = new CookiePatternRule();
        rule.setPattern("*");
        rule.setName("set");
        rule.setValue("already");

        start(rule);

        // Cookie already present on the request.
        String rawRequest = """
            GET / HTTP/1.1
            Host: local
            Connection: close
            Cookie: set=already
            
            """;

        String rawResponse = _connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("response should not have Set-Cookie", response.getField(HttpHeader.SET_COOKIE), nullValue());
    }

    @Test
    public void testPathQuery() throws Exception
    {
        CookiePatternRule rule = new CookiePatternRule();
        rule.setPattern("*");
        rule.setName("fruit");
        rule.setValue("banana");

        start(rule);

        String rawRequest = """
            GET /other?fruit=apple HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = _connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String responseContent = response.getContent();
        assertResponseContentLine(responseContent, "path=", "/other");
        assertResponseContentLine(responseContent, "query=", "fruit=apple");

        HttpField setCookieField = response.getField(HttpHeader.SET_COOKIE);
        assertThat("response should have Set-Cookie", setCookieField, notNullValue());
        for (String value : setCookieField.getValues())
        {
            String[] result = value.split("=");
            assertThat(result[0], is("fruit"));
            assertThat(result[1], is("banana"));
        }
    }

    @Test
    public void testPathParameter() throws Exception
    {
        CookiePatternRule rule = new CookiePatternRule();
        rule.setPattern("*");
        rule.setName("fruit");
        rule.setValue("banana");

        start(rule);

        String rawRequest = """
            GET /other;fruit=apple HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = _connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String responseContent = response.getContent();
        assertResponseContentLine(responseContent, "path=", "/other;fruit=apple");

        HttpField setCookieField = response.getField(HttpHeader.SET_COOKIE);
        assertThat("response should have Set-Cookie", setCookieField, notNullValue());
        for (String value : setCookieField.getValues())
        {
            String[] result = value.split("=");
            assertThat(result[0], is("fruit"));
            assertThat(result[1], is("banana"));
        }
    }

    private void assertResponseContentLine(String responseContent, String linePrefix, String expectedEquals)
    {
        List<String> matches = Arrays.stream(responseContent.split("\n"))
            .filter(line -> line.startsWith(linePrefix))
            .map(line -> line.substring(linePrefix.length()))
            .filter(line -> line.equals(expectedEquals))
            .toList();

        if (matches.size() == 0)
            fail("Unable to find line prefixed with: " + linePrefix);
        if (matches.size() > 1)
            fail("Found multiple lines prefixed with: " + linePrefix);
    }
}
