//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.rewrite.handler;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Dispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
public class ValidUrlRuleTest extends AbstractRuleTestCase
{
    private ValidUrlRule _rule;

    @BeforeEach
    public void init() throws Exception
    {
        start(true);
        _rule = new ValidUrlRule();
    }

    @Test
    public void testValidUrl() throws Exception
    {
        _rule.setCode("404");
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/valid/uri.html"));

        _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(200, _response.getStatus());
    }

    @Test
    public void testInvalidUrl() throws Exception
    {
        _rule.setCode("404");
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/invalid%0c/uri.html"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(404, _response.getStatus());
    }

    @Test
    public void testInvalidUrlWithMessage() throws Exception
    {
        _rule.setCode("405");
        _rule.setMessage("foo");
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/%00/"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(405, _response.getStatus());
        assertEquals("foo", _request.getAttribute(Dispatcher.ERROR_MESSAGE));
    }

    @Test
    public void testInvalidJsp() throws Exception
    {
        _rule.setCode("405");
        _rule.setMessage("foo");
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/jsp/bean1.jsp%00"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(405, _response.getStatus());
        assertEquals("foo", _request.getAttribute(Dispatcher.ERROR_MESSAGE));
    }

    @Disabled("Not working in jetty-9")
    @Test
    public void testInvalidShamrock() throws Exception
    {
        _rule.setCode("405");
        _rule.setMessage("foo");
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/jsp/shamrock-%00%E2%98%98.jsp"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(405, _response.getStatus());
        assertEquals("foo", _request.getAttribute(Dispatcher.ERROR_MESSAGE));
    }

    @Disabled("Not working in jetty-9")
    @Test
    public void testValidShamrock() throws Exception
    {
        _rule.setCode("405");
        _rule.setMessage("foo");
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), "/jsp/shamrock-%E2%98%98.jsp"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(200, _response.getStatus());
    }

    @Test
    public void testCharacters() throws Exception
    {
        // space
        assertTrue(_rule.isValidChar("\u0020".charAt(0)));
        // form feed
        //@checkstyle-disable-check : IllegalTokenText
        assertFalse(_rule.isValidChar("\u000c".charAt(0)));
        //@checkstyle-enable-check : IllegalTokenText
    }
}

