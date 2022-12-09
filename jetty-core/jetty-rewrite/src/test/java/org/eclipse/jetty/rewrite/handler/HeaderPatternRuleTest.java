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

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HeaderPatternRuleTest extends AbstractRuleTest
{
    private void start(HeaderPatternRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
            }
        });
    }

    @Test
    public void testHeaderWithTextValue() throws Exception
    {
        String name = "X-Response";
        String value = "TEXT";
        HeaderPatternRule rule = new HeaderPatternRule("/", name, value);
        start(rule);

        String request = """
            GET / HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
        assertEquals(value, response.get(name));
    }

    @Test
    public void testHeaderWithNumberValues() throws Exception
    {
        String name = "X-Response";
        List<String> values = List.of("1", "-1", "100");

        for (String value : values)
        {
            HeaderPatternRule rule = new HeaderPatternRule("/", name, value);
            start(rule);

            String request = """
                GET / HTTP/1.1
                Host: localhost
                            
                """;

            HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
            assertEquals(200, response.getStatus());
            assertEquals(value, response.get(name));

            stop();
        }
    }
}
