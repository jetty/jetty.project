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

import java.util.Arrays;
import java.util.Collections;
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

public class VirtualHostRuleContainerTest extends AbstractRuleTest
{
    private VirtualHostRuleContainer _virtualHostRules;

    @BeforeEach
    public void init() throws Exception
    {
        _virtualHostRules = new VirtualHostRuleContainer();
        start(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.getHeaders().put("X-Path", request.getHttpURI().getPath());
                callback.succeeded();
            }
        });
    }

    @Test
    public void testArbitraryHost() throws Exception
    {
        _rewriteHandler.addRule(new RewritePatternRule("/cheese/*", "/rule"));
        _rewriteHandler.addRule(_virtualHostRules);
        _virtualHostRules.setVirtualHosts(List.of("foo.com"));
        _virtualHostRules.addRule(new RewritePatternRule("/cheese/bar/*", "/cheese/fooRule"));

        String request = """
            GET /cheese/bar HTTP/1.1
            Host: cheese.com
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        // VirtualHost rule does not apply, host does not match.
        assertEquals("/rule/bar", response.get("X-Path"));
    }

    @Test
    public void testVirtualHost() throws Exception
    {
        _rewriteHandler.addRule(_virtualHostRules);
        _virtualHostRules.setVirtualHosts(List.of("foo.com"));
        _virtualHostRules.addRule(new RewritePatternRule("/cheese/bar/*", "/cheese/fooRule"));

        String request = """
            GET /cheese/bar HTTP/1.1
            Host: foo.com
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/cheese/fooRule", response.get("X-Path"));
    }

    @Test
    public void testCascadingRules() throws Exception
    {
        RewritePatternRule rule = new RewritePatternRule("/cheese/*", "/rule");
        _rewriteHandler.addRule(rule);
        _rewriteHandler.addRule(_virtualHostRules);
        _virtualHostRules.setVirtualHosts(List.of("foo.com"));
        _virtualHostRules.addRule(new RewritePatternRule("/cheese/bar/*", "/cheese/fooRule"));

        String request = """
            GET /cheese/bar HTTP/1.1
            Host: foo.com
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/rule/bar", response.get("X-Path"));

        _rewriteHandler.setRules(List.of(_virtualHostRules, rule));

        request = """
            GET /cheese/bar HTTP/1.1
            Host: foo.com
                        
            """;

        response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/rule/fooRule", response.get("X-Path"));

        _virtualHostRules.setTerminating(true);

        request = """
            GET /cheese/bar HTTP/1.1
            Host: foo.com
                        
            """;

        response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/cheese/fooRule", response.get("X-Path"));
    }

    @Test
    public void testCaseInsensitiveHostname() throws Exception
    {
        _rewriteHandler.addRule(_virtualHostRules);
        _virtualHostRules.setVirtualHosts(List.of("foo.com"));
        _virtualHostRules.addRule(new RewritePatternRule("/cheese/bar/*", "/cheese/fooRule"));

        String request = """
            GET /cheese/bar HTTP/1.1
            Host: Foo.com
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/cheese/fooRule", response.get("X-Path"));
    }

    @Test
    public void testEmptyVirtualHost() throws Exception
    {
        _rewriteHandler.addRule(_virtualHostRules);
        _virtualHostRules.addRule(new RewritePatternRule("/cheese/bar/*", "/cheese/fooRule"));

        List<List<String>> cases = Arrays.asList(null, List.of(), Collections.singletonList(null));
        for (List<String> virtualHosts : cases)
        {
            _virtualHostRules.setVirtualHosts(virtualHosts);

            String request = """
                GET /cheese/bar HTTP/1.1
                Host: cheese.com
                            
                """;

            HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("/cheese/fooRule", response.get("X-Path"));
        }
    }

    @Test
    public void testMultipleVirtualHosts() throws Exception
    {
        _rewriteHandler.addRule(_virtualHostRules);
        _virtualHostRules.setVirtualHosts(List.of("cheese.com"));
        _virtualHostRules.addRule(new RewritePatternRule("/cheese/bar/*", "/cheese/fooRule"));

        String request = """
                GET /cheese/bar HTTP/1.1
                Host: foo.com
                            
                """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/cheese/bar", response.get("X-Path"));

        _virtualHostRules.addVirtualHost("foo.com");

        request = """
                GET /cheese/bar HTTP/1.1
                Host: foo.com
                            
                """;

        response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("/cheese/fooRule", response.get("X-Path"));
    }

    @Test
    public void testWildcardVirtualHosts() throws Exception
    {
        testWildcardVirtualHost(true, List.of("foo.com", "*.foo.com"), List.of("foo.com", ".foo.com", "vhost.foo.com"));
        testWildcardVirtualHost(false, List.of("foo.com", "*.foo.com"), List.of("badfoo.com", ".badfoo.com", "vhost.badfoo.com"));
        testWildcardVirtualHost(false, List.of("*."), List.of("anything.anything"));
        testWildcardVirtualHost(true, List.of("*.foo.com"), List.of("vhost.foo.com", ".foo.com"));
        testWildcardVirtualHost(false, List.of("*.foo.com"), List.of("vhost.www.foo.com", "foo.com", "www.vhost.foo.com"));
        testWildcardVirtualHost(true, List.of("*.sub.foo.com"), List.of("vhost.sub.foo.com", ".sub.foo.com"));
        testWildcardVirtualHost(false, List.of("*.sub.foo.com"), List.of(".foo.com", "sub.foo.com", "vhost.foo.com"));
        testWildcardVirtualHost(false, List.of("foo.*.com", "foo.com.*"), List.of("foo.vhost.com", "foo.com.vhost", "foo.com"));
    }

    private void testWildcardVirtualHost(boolean succeed, List<String> ruleHosts, List<String> requestHosts) throws Exception
    {
        _rewriteHandler.addRule(_virtualHostRules);
        _virtualHostRules.setVirtualHosts(ruleHosts);
        _virtualHostRules.addRule(new RewritePatternRule("/cheese/bar/*", "/cheese/fooRule"));

        for (String requestHost : requestHosts)
        {
            String request = """
                GET /cheese/bar HTTP/1.1
                Host: $H
                            
                """.replace("$H", requestHost);

            HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
            assertEquals(HttpStatus.OK_200, response.getStatus());
            if (succeed)
                assertEquals("/cheese/fooRule", response.get("X-Path"));
            else
                assertEquals("/cheese/bar", response.get("X-Path"));
        }
    }
}
