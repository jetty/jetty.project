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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RedirectPatternRuleTest extends AbstractRuleTest
{
    private void start(RedirectPatternRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
    }

    @Test
    public void testGlobPattern() throws Exception
    {
        String location = "http://eclipse.com";
        RedirectPatternRule rule = new RedirectPatternRule("*", location);
        start(rule);

        String request = """
            GET / HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.FOUND_302, response.getStatus());
        assertEquals(location, response.get(HttpHeader.LOCATION));
    }

    @Test
    public void testPrefixPattern() throws Exception
    {
        String location = "http://api.company.com/";
        RedirectPatternRule rule = new RedirectPatternRule("/api/*", location);
        rule.setStatusCode(HttpStatus.MOVED_PERMANENTLY_301);
        start(rule);

        String request = """
            GET /api/rest?foo=1 HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_PERMANENTLY_301, response.getStatus());
        assertEquals(location, response.get(HttpHeader.LOCATION));
    }
}
