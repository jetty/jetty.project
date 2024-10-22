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

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResponseStatusHeaderRegexRuleTest extends AbstractRuleTest
{
    private void start(ResponseStatusHeaderRegexRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
    }

    @Test
    public void testNoMatch() throws Exception
    {
        ResponseStatusHeaderRegexRule rule = new ResponseStatusHeaderRegexRule();
        rule.setHeaderName("Regex-Test");
        start(rule);

        String request = """
            GET /test HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testMatchNoRegex() throws Exception
    {
        ResponseStatusHeaderRegexRule rule = new ResponseStatusHeaderRegexRule();
        rule.setHeaderName("Regex-Test");
        rule.setCode(403);
        start(rule);

        String request = """
            GET /test HTTP/1.1
            Host: localhost
            Regex-Test: random stuff
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(403, response.getStatus());
    }

    @Test
    public void testMatchStaticRegexNoMessage() throws Exception
    {
        ResponseStatusHeaderRegexRule rule = new ResponseStatusHeaderRegexRule();
        rule.setHeaderName("Regex-Test");
        rule.setHeaderRegex("value");
        rule.setCode(403);
        start(rule);

        String request = """
            GET /test HTTP/1.1
            Host: localhost
            Regex-Test: value
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(403, response.getStatus());
    }

    @Test
    public void testMatchRegexNoMessage() throws Exception
    {
        ResponseStatusHeaderRegexRule rule = new ResponseStatusHeaderRegexRule();
        rule.setHeaderName("Regex-Test");
        rule.setHeaderRegex(".*value.*");
        rule.setCode(403);
        start(rule);

        String request = """
            GET /test HTTP/1.1
            Host: localhost
            Regex-Test: this is the value found
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(403, response.getStatus());
    }

    @Test
    public void testMatchRegexMessage() throws Exception
    {
        ResponseStatusHeaderRegexRule rule = new ResponseStatusHeaderRegexRule();
        rule.setHeaderName("Regex-Test");
        rule.setCode(403);
        rule.setMessage("Matched");
        start(rule);

        String request = """
            GET /test HTTP/1.1
            Host: localhost
            Regex-Test: random stuff
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(403, response.getStatus());
        assertThat(response.getContent(), containsString(rule.getMessage()));
    }
}
