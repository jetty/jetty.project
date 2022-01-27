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

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RewriteHandlerTest extends AbstractRuleTestCase
{
    private RewriteHandler _handler;
    private RewritePatternRule _rule1;
    private RewritePatternRule _rule2;
    private RewritePatternRule _rule3;
    private RewriteRegexRule _rule4;

    @BeforeEach
    public void init() throws Exception
    {
        _handler = new RewriteHandler();
        _handler.setServer(_server);
        _handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(201);
                request.setAttribute("target", target);
                request.setAttribute("URI", request.getRequestURI());
                request.setAttribute("info", request.getPathInfo());
            }
        });
        _handler.start();

        _rule1 = new RewritePatternRule();
        _rule1.setPattern("/aaa/*");
        _rule1.setReplacement("/bbb");
        _rule2 = new RewritePatternRule();
        _rule2.setPattern("/bbb/*");
        _rule2.setReplacement("/ccc");
        _rule3 = new RewritePatternRule();
        _rule3.setPattern("/ccc/*");
        _rule3.setReplacement("/ddd");
        _rule4 = new RewriteRegexRule();
        _rule4.setRegex("/xxx/(.*)");
        _rule4.setReplacement("/$1/zzz");

        _handler.setRules(new Rule[]{_rule1, _rule2, _rule3, _rule4});

        start(false);
    }

    @Test
    public void test() throws Exception
    {
        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute("/before");
        _handler.setRewriteRequestURI(true);
        _handler.setRewritePathInfo(true);
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/xxx/bar"));
        _request.setContext(_request.getContext(), "/xxx/bar");
        _handler.handle("/xxx/bar", _request, _request, _response);
        assertEquals(201, _response.getStatus());
        assertEquals("/bar/zzz", _request.getAttribute("target"));
        assertEquals("/bar/zzz", _request.getAttribute("URI"));
        assertEquals("/bar/zzz", _request.getAttribute("info"));
        assertEquals(null, _request.getAttribute("before"));

        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute("/before");
        _handler.setRewriteRequestURI(false);
        _handler.setRewritePathInfo(false);
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/foo/bar"));
        _request.setContext(_request.getContext(), "/foo/bar");

        _handler.handle("/foo/bar", _request, _request, _response);
        assertEquals(201, _response.getStatus());
        assertEquals("/foo/bar", _request.getAttribute("target"));
        assertEquals("/foo/bar", _request.getAttribute("URI"));
        assertEquals("/foo/bar", _request.getAttribute("info"));
        assertEquals(null, _request.getAttribute("before"));

        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute(null);
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/aaa/bar"));
        _request.setContext(_request.getContext(), "/aaa/bar");
        _handler.handle("/aaa/bar", _request, _request, _response);
        assertEquals(201, _response.getStatus());
        assertEquals("/ddd/bar", _request.getAttribute("target"));
        assertEquals("/aaa/bar", _request.getAttribute("URI"));
        assertEquals("/aaa/bar", _request.getAttribute("info"));
        assertEquals(null, _request.getAttribute("before"));

        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute("before");
        _handler.setRewriteRequestURI(true);
        _handler.setRewritePathInfo(true);
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/aaa/bar"));
        _request.setContext(_request.getContext(), "/aaa/bar");
        _handler.handle("/aaa/bar", _request, _request, _response);
        assertEquals(201, _response.getStatus());
        assertEquals("/ddd/bar", _request.getAttribute("target"));
        assertEquals("/ddd/bar", _request.getAttribute("URI"));
        assertEquals("/ddd/bar", _request.getAttribute("info"));
        assertEquals("/aaa/bar", _request.getAttribute("before"));

        _response.setStatus(200);
        _request.setHandled(false);
        _rule2.setTerminating(true);
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/aaa/bar"));
        _request.setContext(_request.getContext(), "/aaa/bar");
        _handler.handle("/aaa/bar", _request, _request, _response);
        assertEquals(201, _response.getStatus());
        assertEquals("/ccc/bar", _request.getAttribute("target"));
        assertEquals("/ccc/bar", _request.getAttribute("URI"));
        assertEquals("/ccc/bar", _request.getAttribute("info"));
        assertEquals("/aaa/bar", _request.getAttribute("before"));

        _response.setStatus(200);
        _request.setHandled(false);
        _rule2.setHandling(true);
        _request.setAttribute("before", null);
        _request.setAttribute("target", null);
        _request.setAttribute("URI", null);
        _request.setAttribute("info", null);
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/aaa/bar"));
        _request.setContext(_request.getContext(), "/aaa/bar");
        _handler.handle("/aaa/bar", _request, _request, _response);
        assertEquals(200, _response.getStatus());
        assertEquals(null, _request.getAttribute("target"));
        assertEquals(null, _request.getAttribute("URI"));
        assertEquals(null, _request.getAttribute("info"));
        assertEquals("/aaa/bar", _request.getAttribute("before"));
        assertTrue(_request.isHandled());
    }

    @Test
    public void testEncodedPattern() throws Exception
    {
        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute("/before");
        _handler.setRewriteRequestURI(true);
        _handler.setRewritePathInfo(false);
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/ccc/x%20y"));
        _request.setContext(_request.getContext(), "/ccc/x y");
        _handler.handle("/ccc/x y", _request, _request, _response);
        assertEquals(201, _response.getStatus());
        assertEquals("/ddd/x y", _request.getAttribute("target"));
        assertEquals("/ddd/x%20y", _request.getAttribute("URI"));
        assertEquals("/ccc/x y", _request.getAttribute("info"));
    }

    @Test
    public void testEncodedRegex() throws Exception
    {
        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute("/before");
        _handler.setRewriteRequestURI(true);
        _handler.setRewritePathInfo(false);
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/xxx/x%20y"));
        _request.setContext(_request.getContext(), "/xxx/x y");
        _handler.handle("/xxx/x y", _request, _request, _response);
        assertEquals(201, _response.getStatus());
        assertEquals("/x y/zzz", _request.getAttribute("target"));
        assertEquals("/x%20y/zzz", _request.getAttribute("URI"));
        assertEquals("/xxx/x y", _request.getAttribute("info"));
    }
}
