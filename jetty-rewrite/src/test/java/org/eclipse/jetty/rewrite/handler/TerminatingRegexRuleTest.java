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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Before;
import org.junit.Test;

public class TerminatingRegexRuleTest extends AbstractRuleTestCase
{
    private RewriteHandler rewriteHandler;

    @Before
    public void init() throws Exception
    {
        rewriteHandler = new RewriteHandler();
        rewriteHandler.setServer(_server);
        rewriteHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                    ServletException
            {
                response.setStatus(HttpStatus.CREATED_201);
                request.setAttribute("target",target);
                request.setAttribute("URI",request.getRequestURI());
                request.setAttribute("info",request.getPathInfo());
            }
        });
        rewriteHandler.start();

        TerminatingRegexRule rule1 = new TerminatingRegexRule();
        rule1.setRegex("^/login.jsp$");
        rewriteHandler.addRule(rule1);
        RedirectRegexRule rule2 = new RedirectRegexRule();
        rule2.setRegex("^/login.*$");
        rule2.setReplacement("http://login.company.com/");
        rewriteHandler.addRule(rule2);

        start(false);
    }

    private void assertIsRedirect(int expectedStatus, String expectedLocation)
    {
        assertThat("Response Status",_response.getStatus(),is(expectedStatus));
        assertThat("Response Location Header",_response.getHeader(HttpHeader.LOCATION.asString()),is(expectedLocation));
    }

    private void assertIsRequest(String expectedRequestPath)
    {
        assertThat("Response Status",_response.getStatus(),is(HttpStatus.CREATED_201));
        assertThat("Request Target",_request.getAttribute("target"),is(expectedRequestPath));
    }

    @Test
    public void testTerminatingEarly() throws IOException, ServletException
    {
        rewriteHandler.handle("/login.jsp",_request,_request,_response);
        assertIsRequest("/login.jsp");
    }

    @Test
    public void testNoTerminationDo() throws IOException, ServletException
    {
        rewriteHandler.handle("/login.do",_request,_request,_response);
        assertIsRedirect(HttpStatus.MOVED_TEMPORARILY_302,"http://login.company.com/");
    }

    @Test
    public void testNoTerminationDir() throws IOException, ServletException
    {
        rewriteHandler.handle("/login/",_request,_request,_response);
        assertIsRedirect(HttpStatus.MOVED_TEMPORARILY_302,"http://login.company.com/");
    }
}
