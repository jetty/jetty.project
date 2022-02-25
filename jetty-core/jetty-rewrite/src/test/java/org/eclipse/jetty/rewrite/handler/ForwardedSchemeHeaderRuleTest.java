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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ForwardedSchemeHeaderRuleTest extends AbstractRuleTestCase
{
    private ForwardedSchemeHeaderRule _rule;

    @BeforeEach
    public void init() throws Exception
    {
        start(false);
        _rule = new ForwardedSchemeHeaderRule();
        _request.setHttpURI(HttpURI.build(_request.getRequestURI()).scheme((String)null));
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

        _request.setHttpURI(HttpURI.build(_request.getRequestURI()).scheme("other"));
        // header value doesn't match rule's value
        setRequestHeader("Front-End-Https", "off");
        _rule.matchAndApply("/", _request, _response);
        assertEquals("other", _request.getScheme());

        _request.setHttpURI(HttpURI.build(_request.getRequestURI()).scheme((String)null));
        // header value can be any value
        setRequestHeader("Front-End-Https", "any");
        _rule.setHeaderValue(null);
        _rule.matchAndApply("/", _request, _response);
        assertEquals("https", _request.getScheme());
    }

    private void setRequestHeader(String header, String headerValue)
    {
        _request.setHttpFields(HttpFields.build(_request.getHttpFields()).put(header, headerValue));
    }
}
