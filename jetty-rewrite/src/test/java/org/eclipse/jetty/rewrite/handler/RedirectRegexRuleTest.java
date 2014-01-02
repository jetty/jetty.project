//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;

import org.eclipse.jetty.http.HttpHeader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RedirectRegexRuleTest extends AbstractRuleTestCase
{
    private RedirectRegexRule _rule;

    @Before
    public void init() throws Exception
    {
        start(false);
        _rule = new RedirectRegexRule();
    }

    @After
    public void destroy()
    {
        _rule = null;
    }

    @Test
    public void testLocationWithReplacementGroupEmpty() throws IOException
    {
        _rule.setRegex("/my/dir/file/(.*)$");
        _rule.setReplacement("http://www.mortbay.org/$1");

        // Resource is dir
        _rule.matchAndApply("/my/dir/file/", _request, _response);
        assertEquals("http://www.mortbay.org/", _response.getHeader(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void testLocationWithReplacmentGroupSimple() throws IOException
    {
        _rule.setRegex("/my/dir/file/(.*)$");
        _rule.setReplacement("http://www.mortbay.org/$1");

        // Resource is an image
        _rule.matchAndApply("/my/dir/file/image.png", _request, _response);
        assertEquals("http://www.mortbay.org/image.png", _response.getHeader(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void testLocationWithReplacementGroupDeepWithParams() throws IOException
    {
        _rule.setRegex("/my/dir/file/(.*)$");
        _rule.setReplacement("http://www.mortbay.org/$1");

        // Resource is api with parameters
        _rule.matchAndApply("/my/dir/file/api/rest/foo?id=100&sort=date", _request, _response);
        assertEquals("http://www.mortbay.org/api/rest/foo?id=100&sort=date", _response.getHeader(HttpHeader.LOCATION.asString()));
    }
}
