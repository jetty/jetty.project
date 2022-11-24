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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TerminatingPatternRuleTest extends AbstractRuleTest
{
    @BeforeEach
    public void init() throws Exception
    {
        TerminatingPatternRule rule1 = new TerminatingPatternRule("/login.jsp");
        _rewriteHandler.addRule(rule1);
        RedirectRegexRule rule2 = new RedirectRegexRule("^/login.*$", "http://login.company.com/");
        rule2.setStatusCode(HttpStatus.SEE_OTHER_303);
        _rewriteHandler.addRule(rule2);
        start(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.setStatus(HttpStatus.CREATED_201);
                callback.succeeded();
            }
        });
    }

    @Test
    public void testTerminatingEarly() throws Exception
    {
        String request = """
            GET /login.jsp HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.CREATED_201, response.getStatus());
        assertNull(response.get(HttpHeader.LOCATION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login.do", "/login/"})
    public void testNonTerminating(String uri) throws Exception
    {
        String request = """
            GET $U HTTP/1.1
            Host: localhost
                        
            """.replace("$U", uri);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        assertEquals("http://login.company.com/", response.get(HttpHeader.LOCATION));
    }
}
