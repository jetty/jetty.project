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

import java.util.List;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultipleRulesTest extends AbstractRuleTest
{
    @Test
    public void testMultipleRulesWithSamePattern() throws Exception
    {
        HeaderPatternRule rule1 = new HeaderPatternRule("/*", "name1", "value1");
        RewriteRegexRule rule2 = new RewriteRegexRule("/", "/rewritten");
        HeaderPatternRule rule3 = new HeaderPatternRule("/*", "name2", "value2");
        List.of(rule2, rule1, rule3).forEach(_rewriteHandler::addRule);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String pathInContext = Request.getPathInContext(request);
                assertEquals("/rewritten", pathInContext);
                callback.succeeded();
                return true;
            }
        });

        String request = """
            GET / HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
        assertEquals("value1", response.get("name1"));
        assertEquals("value2", response.get("name2"));
    }

    @Test
    public void testMultipleRulesAppliesAndHandledInOrder() throws Exception
    {
        String requestHeaderName = "X-Request-Header";
        String responseHeaderName = "X-Response-Header";
        Rule rule1 = new Rule()
        {
            @Override
            public Handler matchAndApply(Handler input)
            {
                // First rule, I should only see the client headers.
                String value = input.getHeaders().get(requestHeaderName);
                assertEquals("Request", value);

                HttpFields newFields = HttpFields.build(input.getHeaders()).put(requestHeaderName, String.join(", ", value, "Rule1"));
                return new Handler(input)
                {
                    @Override
                    public HttpFields getHeaders()
                    {
                        return newFields;
                    }

                    @Override
                    protected boolean handle(Response response, Callback callback) throws Exception
                    {
                        // Modify the response.
                        response.getHeaders().put(responseHeaderName, "Rule1");
                        // Chain the rules.
                        return super.handle(response, callback);
                    }
                };
            }
        };
        RewriteRegexRule rule2 = new RewriteRegexRule("/", "/rewritten");
        Rule rule3 = new Rule()
        {
            @Override
            public Handler matchAndApply(Handler input)
            {
                // Third rule, I should see the effects of the previous 2 rules.
                String value = input.getHeaders().get(requestHeaderName);
                assertEquals("Request, Rule1", value);

                String pathInContext = Request.getPathInContext(input);
                assertEquals("/rewritten", pathInContext);

                HttpFields newFields = HttpFields.build(input.getHeaders()).put(requestHeaderName, String.join(", ", "Request", "Rule1", "Rule3"));
                return new Handler(input)
                {
                    @Override
                    public HttpFields getHeaders()
                    {
                        return newFields;
                    }

                    @Override
                    protected boolean handle(Response response, Callback callback) throws Exception
                    {
                        assertEquals("Rule1", response.getHeaders().get(responseHeaderName));
                        // Modify the response.
                        response.getHeaders().put(responseHeaderName, String.join(", ", "Rule1", "Rule3"));
                        // Chain the rules.
                        return super.handle(response, callback);
                    }
                };
            }
        };
        List.of(rule1, rule2, rule3).forEach(_rewriteHandler::addRule);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String pathInContext = Request.getPathInContext(request);
                assertEquals("/rewritten", pathInContext);
                assertEquals("Request, Rule1, Rule3", request.getHeaders().get(requestHeaderName));
                assertEquals("Rule1, Rule3", response.getHeaders().get(responseHeaderName));
                callback.succeeded();
                return true;
            }
        });

        String request = """
            GET / HTTP/1.1
            Host: localhost
            %s: Request
                        
            """.formatted(requestHeaderName);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
        assertEquals("Rule1, Rule3", response.get(responseHeaderName));
    }
}
