//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.HttpFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ForwardedSchemeHeaderRuleTest extends AbstractRuleTestCase
{
    private ForwardedSchemeHeaderRule _rule;
    private HttpFields _requestHeaderFields;

    @BeforeEach
    public void init() throws Exception
    {
        start(false);
        _rule = new ForwardedSchemeHeaderRule();
        _requestHeaderFields = _request.getHttpFields();
        _request.setScheme(null);
    }

    @Test
    public void testDefaultScheme() throws Exception
    {
        setRequestHeader("X-Forwarded-Scheme", "https");
        _rule.setHeader("X-Forwarded-Scheme");
        _rule.setHeaderValue("https");
        _rule.matchAndApply("/", _request, _response);
        assertEquals("https", _request.getScheme());
    }

    @Test
    public void testScheme() throws Exception
    {
        setRequestHeader("X-Forwarded-Scheme", "https");
        _rule.setHeader("X-Forwarded-Scheme");
        _rule.setHeaderValue("https");
        _rule.setScheme("https");
        _rule.matchAndApply("/", _request, _response);
        assertEquals("https", _request.getScheme());

        _rule.setScheme("http");
        _rule.matchAndApply("/", _request, _response);
        assertEquals("http", _request.getScheme());
    }

    @Test
    public void testHeaderValue() throws Exception
    {
        setRequestHeader("Front-End-Https", "on");
        _rule.setHeader("Front-End-Https");
        _rule.setHeaderValue("on");
        _rule.setScheme("https");
        _rule.matchAndApply("/", _request, _response);
        assertEquals("https", _request.getScheme());

        _request.setScheme("other");
        // header value doesn't match rule's value
        setRequestHeader("Front-End-Https", "off");
        _rule.matchAndApply("/", _request, _response);
        assertEquals("other", _request.getScheme());

        _request.setScheme(null);
        // header value can be any value
        setRequestHeader("Front-End-Https", "any");
        _rule.setHeaderValue(null);
        _rule.matchAndApply("/", _request, _response);
        assertEquals("https", _request.getScheme());
    }

    private void setRequestHeader(String header, String headerValue)
    {
        _requestHeaderFields.put(header, headerValue);
    }
}
