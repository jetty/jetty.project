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
import java.util.List;
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
public class RewriteLanguageRuleTest extends AbstractRuleTest
{
    public WorkDir _workDir;
    protected ContextHandler _contextHandler;
    Path _docRoot;

    public static Stream<String> languages()
    {
        return Stream.of(
            "en",
            "en-UK",
            "en-AU",
            "fr");
    }

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _docRoot = _workDir.getEmptyPathDir();
        Files.writeString(Files.createFile(_docRoot.resolve("all.html")), "all", StandardCharsets.UTF_8);
        Files.writeString(Files.createFile(_docRoot.resolve("only.html")), "only", StandardCharsets.UTF_8);
        for (String language : languages().toArray(String[]::new))
        {
            Path languageDir = _docRoot.resolve(language);
            Files.createDirectory(languageDir);
            Files.writeString(Files.createFile(languageDir.resolve("all.html")), language, StandardCharsets.UTF_8);
        }
        Files.writeString(_docRoot.resolve("en-AU/en-AU-only.html"), "en-AU-only", StandardCharsets.UTF_8);

        _contextHandler = new ContextHandler();
        _contextHandler.setBaseResourceAsPath(_docRoot);
        _contextHandler.setContextPath("/ctx");
        _server.setHandler(_contextHandler);
        _rewriteHandler.addRule(new RewriteLanguageRule(List.of("xx", "en-AU", "en-UK")));

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        start(resourceHandler);
    }

    @Test
    public void testNoLanguage() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), nullValue());
        assertThat(response.get(HttpHeader.ETAG), not(nullValue()));
        assertThat(response.get(HttpHeader.ETAG), not(containsString("-")));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("all", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), nullValue());
        assertThat(response.get(HttpHeader.ETAG), not(nullValue()));
        assertThat(response.get(HttpHeader.ETAG), not(containsString("-")));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/en-AU-only.html HTTP/1.0\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
    }

    @Test
    public void testOneLanguage() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            Accept-Language: en-AU\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            Accept-Language: en-AU\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), nullValue());

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Language: en-AU\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("en-AU", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), is("en-AU"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-en-AU\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/en-AU-only.html HTTP/1.0\r
            Accept-Language: en-AU\r
            \r
            """));
        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("en-AU-only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), is("en-AU"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-en-AU\""));
    }

    @Test
    public void testQualityLanguage() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            Accept-Language: fr,en-AU;q=0.5\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            Accept-Language: fr,en-AU;q=0.5
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), nullValue());

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Language: fr,en-AU;q=0.5
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("fr", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), is("fr"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-fr\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/en-AU-only.html HTTP/1.0\r
            Accept-Language: fr,en-AU;q=0.5\r
            \r
            """));
        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("en-AU-only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), is("en-AU"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-en-AU\""));
    }

    @Test
    public void testUnknownQualityLanguage() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            Accept-Language: xx,fr,en-AU;q=0.5\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            Accept-Language: xx,fr,en-AU;q=0.5
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), nullValue());

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Language: xx,fr,en-AU;q=0.5
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("fr", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), is("fr"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-fr\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/en-AU-only.html HTTP/1.0\r
            Accept-Language: xx,fr,en-AU;q=0.5\r
            \r
            """));
        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("en-AU-only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), is("en-AU"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-en-AU\""));
    }

    @Test
    public void testWildLanguage() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/unknown.html HTTP/1.0\r
            Accept-Language: fr,*;q=0.5\r
            \r
            """));

        assertEquals(404, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/only.html HTTP/1.0\r
            Accept-Language: fr,*;q=0.5\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), nullValue());

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Language: fr,*;q=0.5\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("fr", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), is("fr"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-fr\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/en-AU-only.html HTTP/1.0\r
            Accept-Language: fr,*;q=0.5\r
            \r
            """));
        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("en-AU-only", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), is("en-AU"));
        assertThat(response.get(HttpHeader.ETAG), containsString("-en-AU\""));
    }

    @Test
    public void testEtags() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Language: en\r
            \r
            """));

        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.VARY), containsString("Accept-Language"));
        assertEquals("en", response.getContent());
        assertThat(response.get(HttpHeader.CONTENT_LANGUAGE), is("en"));

        String etag = response.get(HttpHeader.ETAG);
        assertThat(etag, containsString("-en\""));

        response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/all.html HTTP/1.0\r
            Accept-Language: en\r
            If-None-Match: w/"abc"\r
            If-None-Match: w/"xyz", %s, w/"pqy"\r
            If-None-Match: w/"123"\r
            \r
            """.formatted(etag)));

        assertEquals(304, response.getStatus());
    }
}
