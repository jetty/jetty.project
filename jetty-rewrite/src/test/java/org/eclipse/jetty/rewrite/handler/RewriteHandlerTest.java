// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;


public class RewriteHandlerTest extends AbstractRuleTestCase
{   
    RewriteHandler _handler;
    RewritePatternRule _rule1;
    RewritePatternRule _rule2;
    RewritePatternRule _rule3;
    
    
    public void setUp() throws Exception
    {
        _handler=new RewriteHandler();
        _server.setHandler(_handler);
        _handler.setHandler(new AbstractHandler(){

            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(201);
                request.setAttribute("target",target);
                request.setAttribute("URI",request.getRequestURI());
                request.setAttribute("info",request.getPathInfo());
            }
            
        });
        
        _rule1 = new RewritePatternRule();
        _rule1.setPattern("/aaa/*");
        _rule1.setReplacement("/bbb");
        _rule2 = new RewritePatternRule();
        _rule2.setPattern("/bbb/*");
        _rule2.setReplacement("/ccc");
        _rule3 = new RewritePatternRule();
        _rule3.setPattern("/ccc/*");
        _rule3.setReplacement("/ddd");
        
        _handler.setRules(new Rule[]{_rule1,_rule2,_rule3});
        
        super.setUp();
    }    
    
    
    public void test() throws Exception
    {
        _response.setStatus(200);
        _request.setHandled(false);
        _handler.setOriginalPathAttribute("/before");
        _handler.setRewriteRequestURI(false);
        _handler.setRewritePathInfo(false);
        _request.setRequestURI("/foo/bar");
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
        _request.setRequestURI("/aaa/bar");
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
        _request.setRequestURI("/aaa/bar");
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
        _request.setRequestURI("/aaa/bar");
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
        _request.setRequestURI("/aaa/bar");
        _request.setPathInfo("/aaa/bar");
        _handler.handle("/aaa/bar",_request,_request, _response);
        assertEquals(200,_response.getStatus());
        assertEquals(null,_request.getAttribute("target"));
        assertEquals(null,_request.getAttribute("URI"));
        assertEquals(null,_request.getAttribute("info"));
        assertEquals("/aaa/bar",_request.getAttribute("before"));
        assertTrue(_request.isHandled());
        
        
        
    }
}
