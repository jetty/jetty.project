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

package org.eclipse.jetty.rewrite.handler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(WorkDirExtension.class)
public class RewriteEncodingRuleTest extends AbstractRuleTest
{
    public WorkDir _workDir;
    protected ContextHandler _contextHandler;
    Path _docRoot;

    public static Map<String, String> __encodingToExtension = Map.of("br", ".br", "gzip", ".gz");
    
    public static Stream<String> encodings()
    {
        return __encodingToExtension.keySet().stream();
    }

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _docRoot = _workDir.getEmptyPathDir();
        Files.writeString(Files.createFile(_docRoot.resolve("all.html")), "all", StandardCharsets.UTF_8);
        Files.writeString(Files.createFile(_docRoot.resolve("only.html")), "only", StandardCharsets.UTF_8);
        for (String encoding : encodings().toArray(String[]::new))
        {
            Path encoded = _docRoot.resolve("all.html" + __encodingToExtension.get(encoding));
            Files.writeString(Files.createFile(encoded), encoding, StandardCharsets.UTF_8);
        }
        Files.writeString(_docRoot.resolve("br-only.html.br"), "br-only", StandardCharsets.UTF_8);

        _contextHandler = new ContextHandler();
        _contextHandler.setBaseResourceAsPath(_docRoot);
        _contextHandler.setContextPath("/ctx");
        _server.setHandler(_contextHandler);
        _rewriteHandler.addRule(new RewriteEncodingRule());

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        start(resourceHandler);
    }

    @Test
    public void testNoEncoding() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), nullValue());
        assertThat(response.get(HttpHeader.ETAG), not(nullValue()));
        assertThat(response.get(HttpHeader.ETAG), not(containsString("-")));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("all", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), nullValue());
        assertThat(response.get(HttpHeader.ETAG), not(nullValue()));
        assertThat(response.get(HttpHeader.ETAG), not(containsString("-")));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/br-only.html HTTP/1.0\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
    }

    @Test
    public void testOneEncoding() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            Accept-Encoding: br\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            Accept-Encoding: br\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), nullValue());

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Encoding: br\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("br", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), is("br"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-br\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/br-only.html HTTP/1.0\r
            Accept-Encoding: br\r
            \r
            """));
        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("br-only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), is("br"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-br\""));
    }

    @Test
    public void testQualityEncoding() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            Accept-Encoding: gzip,br;q=0.5\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            Accept-Encoding: gzip,br;q=0.5
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), nullValue());

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Encoding: gzip,br;q=0.5
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("gzip", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), is("gzip"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-gzip\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/br-only.html HTTP/1.0\r
            Accept-Encoding: gzip,br;q=0.5\r
            \r
            """));
        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("br-only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), is("br"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-br\""));
    }

    @Test
    public void testUnknownQualityEncoding() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            Accept-Encoding: xx,gzip,br;q=0.5\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            Accept-Encoding: xx,gzip,br;q=0.5
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), nullValue());

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Encoding: xx,gzip,br;q=0.5
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("gzip", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), is("gzip"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-gzip\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/br-only.html HTTP/1.0\r
            Accept-Encoding: xx,gzip,br;q=0.5\r
            \r
            """));
        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("br-only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), is("br"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-br\""));
    }

    @Test
    public void testWildEncoding() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            Accept-Encoding: gzip,*;q=0.5\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            Accept-Encoding: gzip,*;q=0.5\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), nullValue());

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Encoding: gzip,*;q=0.5\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("gzip", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), is("gzip"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-gzip\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/br-only.html HTTP/1.0\r
            Accept-Encoding: gzip,*;q=0.5\r
            \r
            """));
        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("br-only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), is("br"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-br\""));
    }

    @Test
    public void testEtags() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Encoding: br\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Encoding"));
        assertEquals("br", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_ENCODING), is("br"));

        String etag = response.get(HttpHeader.ETAG);
        assertThat(etag, containsString("-br\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Encoding: br\r
            If-None-Match: w/"abc"\r
            If-None-Match: w/"xyz", %s, w/"pqy"\r
            If-None-Match: w/"123"\r
            \r
            """.formatted(etag)));

        assertEquals(304, response.getStatus());
    }
}
