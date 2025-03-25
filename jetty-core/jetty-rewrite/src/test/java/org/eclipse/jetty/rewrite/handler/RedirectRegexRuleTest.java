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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RedirectRegexRuleTest extends AbstractRuleTest
{
    private void start(RedirectRegexRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        _httpConfig.setUriCompliance(UriCompliance.UNSAFE);
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
    public void testLocationWithReplacementGroupEmpty() throws Exception
    {
        RedirectRegexRule rule = new RedirectRegexRule("/my/dir/file/(.*)$", "http://www.mortbay.org/$1");
        start(rule);

        String request = """
            GET /my/dir/file/ HTTP/1.1
            Host: localhost
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.FOUND_302, response.getStatus());
        assertEquals("http://www.mortbay.org/", response.get(HttpHeader.LOCATION));
    }

    @Test
    public void testLocationWithPathReplacement() throws Exception
    {
        RedirectRegexRule rule = new RedirectRegexRule("/documentation/(.*)$", "/docs/$1");
        start(rule);

        String request = """
            GET /documentation/top.html HTTP/1.1
            Host: localhost
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.FOUND_302, response.getStatus());
        assertEquals("/docs/top.html", response.get(HttpHeader.LOCATION));
    }

    @Test
    public void testLocationWithReplacementGroupSimple() throws Exception
    {
        RedirectRegexRule rule = new RedirectRegexRule("/my/dir/file/(.*)$", "http://www.mortbay.org/$1");
        start(rule);

        String request = """
            GET /my/dir/file/image.png HTTP/1.1
            Host: localhost
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.FOUND_302, response.getStatus());
        assertEquals("http://www.mortbay.org/image.png", response.get(HttpHeader.LOCATION));
    }

    @Test
    public void testLocationWithReplacementGroupWithQuery() throws Exception
    {
        RedirectRegexRule rule = new RedirectRegexRule("/my/dir/file/(.*)$", "http://www.mortbay.org/$1");
        rule.setStatusCode(HttpStatus.MOVED_PERMANENTLY_301);
        start(rule);

        String request = """
            GET /my/dir/file/api/rest/foo?id=100&sort=date HTTP/1.1
            Host: localhost
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_PERMANENTLY_301, response.getStatus());
        assertEquals("http://www.mortbay.org/api/rest/foo?id=100&sort=date", response.get(HttpHeader.LOCATION));
    }

    @Test
    public void testMatchOnlyPathLocationWithoutQuery() throws Exception
    {
        RedirectRegexRule rule = new RedirectRegexRule("/my/dir/file/(.*)/foo$", "http://www.mortbay.org/$1/foo");
        rule.setMatchQuery(false);
        rule.setStatusCode(HttpStatus.MOVED_PERMANENTLY_301);
        start(rule);

        String request = """
            GET /my/dir/file/api/rest/foo?id=100&sort=date HTTP/1.1
            Host: localhost
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_PERMANENTLY_301, response.getStatus());
        // This configuration, with RedirectRegexRule.isAddQueries(false), will drop the input query section
        assertEquals("http://www.mortbay.org/api/rest/foo", response.get(HttpHeader.LOCATION));
    }

    @Test
    public void testMatchOnlyPathLocationWithQueryAddQueries() throws Exception
    {
        RedirectRegexRule rule = new RedirectRegexRule("/my/dir/file/(.*)/foo$", "http://www.mortbay.org/$1/foo?v=old");
        rule.setMatchQuery(false);
        rule.setAddQueries(true);
        rule.setStatusCode(HttpStatus.MOVED_PERMANENTLY_301);
        start(rule);

        String request = """
            GET /my/dir/file/api/rest/foo?id=100&sort=date HTTP/1.1
            Host: localhost
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_PERMANENTLY_301, response.getStatus());
        assertEquals("http://www.mortbay.org/api/rest/foo?id=100&sort=date&v=old", response.get(HttpHeader.LOCATION));
    }

    @Test
    public void testEncodedNewLineInURI() throws Exception
    {
        RedirectRegexRule rule = new RedirectRegexRule("(.+)$", "https://example$1");
        start(rule);

        String request = """
            GET /%0A.evil.com HTTP/1.1
            Host: localhost
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.FOUND_302, response.getStatus());
        assertEquals("https://example/%0A.evil.com", response.get(HttpHeader.LOCATION));
    }
}
