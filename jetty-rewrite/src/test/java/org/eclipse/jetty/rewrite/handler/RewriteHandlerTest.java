//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.rewrite.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Before;
import org.junit.Test;

public class RewriteHandlerTest extends AbstractRuleTestCase
{
    private RewriteHandler _handler;
    private RewritePatternRule _rule1;
    private RewritePatternRule _rule2;
    private RewritePatternRule _rule3;
    private RewriteRegexRule _rule4;

    @Before
    public void init() throws Exception
    {
        _handler=new RewriteHandler();
        _handler.setServer(_server);
        _handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(201);
                request.setAttribute("target",target);
                request.setAttribute("URI",request.getRequestURI());
                request.setAttribute("info",request.getPathInfo());
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

        _handler.setRules(new Rule[]{_rule1,_rule2,_rule3,_rule4});

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
        _request.setURIPathQuery("/xxx/bar");
        _request.setPathInfo("/xxx/bar");
        _handler.handle("/xxx/bar",_request,_request, _response);
        assertEquals(201,_response.getStatus());
        assertEquals("/bar/zzz",_request.getAttribute("target"));
        assertEquals("/bar/zzz",_request.getAttribute("URI"));
        assertEquals("/bar/zzz",_request.getAttribute("info"));
        assertEquals(null,_request.getAttribute("before"));
        
        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute("/before");
        _handler.setRewriteRequestURI(false);
        _handler.setRewritePathInfo(false);
        _request.setURIPathQuery("/foo/bar");
        _request.setPathInfo("/foo/bar");
        
        _handler.handle("/foo/bar",_request,_request, _response);
        assertEquals(201,_response.getStatus());
        assertEquals("/foo/bar",_request.getAttribute("target"));
        assertEquals("/foo/bar",_request.getAttribute("URI"));
        assertEquals("/foo/bar",_request.getAttribute("info"));
        assertEquals(null,_request.getAttribute("before"));

        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute(null);
        _request.setURIPathQuery("/aaa/bar");
        _request.setPathInfo("/aaa/bar");
        _handler.handle("/aaa/bar",_request,_request, _response);
        assertEquals(201,_response.getStatus());
        assertEquals("/ddd/bar",_request.getAttribute("target"));
        assertEquals("/aaa/bar",_request.getAttribute("URI"));
        assertEquals("/aaa/bar",_request.getAttribute("info"));
        assertEquals(null,_request.getAttribute("before"));

        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute("before");
        _handler.setRewriteRequestURI(true);
        _handler.setRewritePathInfo(true);
        _request.setURIPathQuery("/aaa/bar");
        _request.setPathInfo("/aaa/bar");
        _handler.handle("/aaa/bar",_request,_request, _response);
        assertEquals(201,_response.getStatus());
        assertEquals("/ddd/bar",_request.getAttribute("target"));
        assertEquals("/ddd/bar",_request.getAttribute("URI"));
        assertEquals("/ddd/bar",_request.getAttribute("info"));
        assertEquals("/aaa/bar",_request.getAttribute("before"));

        _response.setStatus(200);
        _request.setHandled(false);
        _rule2.setTerminating(true);
        _request.setURIPathQuery("/aaa/bar");
        _request.setPathInfo("/aaa/bar");
        _handler.handle("/aaa/bar",_request,_request, _response);
        assertEquals(201,_response.getStatus());
        assertEquals("/ccc/bar",_request.getAttribute("target"));
        assertEquals("/ccc/bar",_request.getAttribute("URI"));
        assertEquals("/ccc/bar",_request.getAttribute("info"));
        assertEquals("/aaa/bar",_request.getAttribute("before"));

        _response.setStatus(200);
        _request.setHandled(false);
        _rule2.setHandling(true);
        _request.setAttribute("before",null);
        _request.setAttribute("target",null);
        _request.setAttribute("URI",null);
        _request.setAttribute("info",null);
        _request.setURIPathQuery("/aaa/bar");
        _request.setPathInfo("/aaa/bar");
        _handler.handle("/aaa/bar",_request,_request, _response);
        assertEquals(200,_response.getStatus());
        assertEquals(null,_request.getAttribute("target"));
        assertEquals(null,_request.getAttribute("URI"));
        assertEquals(null,_request.getAttribute("info"));
        assertEquals("/aaa/bar",_request.getAttribute("before"));
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
        _request.setURIPathQuery("/ccc/x%20y");
        _request.setPathInfo("/ccc/x y");
        _handler.handle("/ccc/x y",_request,_request, _response);
        assertEquals(201,_response.getStatus());
        assertEquals("/ddd/x y",_request.getAttribute("target"));
        assertEquals("/ddd/x%20y",_request.getAttribute("URI"));
        assertEquals("/ccc/x y",_request.getAttribute("info"));

    }
    

    @Test
    public void testEncodedRegex() throws Exception
    {
        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute("/before");
        _handler.setRewriteRequestURI(true);
        _handler.setRewritePathInfo(false);
        _request.setURIPathQuery("/xxx/x%20y");
        _request.setPathInfo("/xxx/x y");
        _handler.handle("/xxx/x y",_request,_request, _response);
        assertEquals(201,_response.getStatus());
        assertEquals("/x y/zzz",_request.getAttribute("target"));
        assertEquals("/x%20y/zzz",_request.getAttribute("URI"));
        assertEquals("/xxx/x y",_request.getAttribute("info"));

    }
}
