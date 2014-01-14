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

public class RedirectPatternRuleTest extends AbstractRuleTestCase
{
    private RedirectPatternRule _rule;

    @Before
    public void init() throws Exception
    {
        start(false);
        _rule = new RedirectPatternRule();
        _rule.setPattern("*");
    }

    @After
    public void destroy()
    {
        _rule = null;
    }

    @Test
    public void testLocation() throws IOException
    {
        String location = "http://eclipse.com";
        _rule.setLocation(location);
        _rule.apply(null, _request, _response);
        assertEquals(location, _response.getHeader(HttpHeader.LOCATION.asString()));
    }
}
