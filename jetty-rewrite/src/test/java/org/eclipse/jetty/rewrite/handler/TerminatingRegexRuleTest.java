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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class TerminatingRegexRuleTest extends AbstractRuleTestCase
{
    private RewriteHandler rewriteHandler;

    @BeforeEach
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
                request.setAttribute("target", target);
                request.setAttribute("URI", request.getRequestURI());
                request.setAttribute("info", request.getPathInfo());
            }
        });
        rewriteHandler.start();

        TerminatingRegexRule rule1 = new TerminatingRegexRule();
        rule1.setRegex("^/login.jsp$");
        rewriteHandler.addRule(rule1);
        RedirectRegexRule rule2 = new RedirectRegexRule("^/login.*$", "http://login.company.com/");
        rewriteHandler.addRule(rule2);

        start(false);
    }

    private void assertIsRedirect(int expectedStatus, String expectedLocation)
    {
        assertThat("Response Status", _response.getStatus(), is(expectedStatus));
        assertThat("Response Location Header", _response.getHeader(HttpHeader.LOCATION.asString()), is(expectedLocation));
    }

    private void assertIsRequest(String expectedRequestPath)
    {
        assertThat("Response Status", _response.getStatus(), is(HttpStatus.CREATED_201));
        assertThat("Request Target", _request.getAttribute("target"), is(expectedRequestPath));
    }

    @Test
    public void testTerminatingEarly() throws IOException, ServletException
    {
        rewriteHandler.handle("/login.jsp", _request, _request, _response);
        assertIsRequest("/login.jsp");
    }

    @Test
    public void testNoTerminationDo() throws IOException, ServletException
    {
        rewriteHandler.handle("/login.do", _request, _request, _response);
        assertIsRedirect(HttpStatus.MOVED_TEMPORARILY_302, "http://login.company.com/");
    }

    @Test
    public void testNoTerminationDir() throws IOException, ServletException
    {
        rewriteHandler.handle("/login/", _request, _request, _response);
        assertIsRedirect(HttpStatus.MOVED_TEMPORARILY_302, "http://login.company.com/");
    }
}
