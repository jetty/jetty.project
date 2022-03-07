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

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResponsePatternRuleTest extends AbstractRuleTest
{
    private void start(ResponsePatternRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
            }
        });
    }

    @Test
    public void testStatusCodeNoMessage() throws Exception
    {
        ResponsePatternRule rule = new ResponsePatternRule("/test", HttpStatus.NO_CONTENT_204, null);
        start(rule);

        String request = """
            GET /test HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(rule.getCode(), response.getStatus());
    }

    @Test
    public void testStatusCodeMessage() throws Exception
    {
        ResponsePatternRule rule = new ResponsePatternRule("/test", HttpStatus.BAD_REQUEST_400, "MESSAGE");

        start(rule);

        String request = """
            GET /test HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(rule.getCode(), response.getStatus());
        assertThat(response.getContent(), containsString(rule.getMessage()));
    }
}
