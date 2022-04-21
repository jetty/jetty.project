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

import java.util.List;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RewriteHandlerTest extends AbstractRuleTest
{
    @BeforeEach
    public void init() throws Exception
    {
        RewritePatternRule rule1 = new RewritePatternRule("/aaa/*", "/bbb");
        RewritePatternRule rule2 = new RewritePatternRule("/bbb/*", "/ccc");
        RewritePatternRule rule3 = new RewritePatternRule("/ccc/*", "/ddd");
        RewriteRegexRule rule4 = new RewriteRegexRule("/xxx/(.*)", "/$1/zzz");
        _rewriteHandler.setRules(List.of(rule1, rule2, rule3, rule4));
        _rewriteHandler.setOriginalPathAttribute("originalPath");
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.setStatus(HttpStatus.OK_200);
                response.setHeader("X-Path", request.getHttpURI().getPath());
                response.setHeader("X-Original-Path", (String)request.getAttribute(_rewriteHandler.getOriginalPathAttribute()));
                callback.succeeded();
            }
        });
    }

    @Test
    public void testXXXtoBar() throws Exception
    {
        String request = """
            GET /xxx/bar HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/bar/zzz", response.get("X-Path"), "X-Path response value");
    }

    @Test
    public void testFooNoChange() throws Exception
    {
        String request = """
            GET /foo/bar HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/foo/bar", response.get("X-Path"), "X-Path response value");
    }

    @Test
    public void testAAAtoDDD() throws Exception
    {
        String request = """
            GET /aaa/bar HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/ddd/bar", response.get("X-Path"), "X-Path response value");
        assertEquals("/aaa/bar", response.get("X-Original-Path"), "X-Original-Path response value");
    }

    @Test
    public void testEncodedPattern() throws Exception
    {
        String request = """
            GET /ccc/x%20y HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/ddd/x%20y", response.get("X-Path"));
        assertEquals("/ccc/x%20y", response.get("X-Original-Path"));
    }

    @Test
    public void testEncodedRegex() throws Exception
    {
        String request = """
            GET /xxx/x%20y HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/x%20y/zzz", response.get("X-Path"));
        assertEquals("/xxx/x%20y", response.get("X-Original-Path"));
    }
}
