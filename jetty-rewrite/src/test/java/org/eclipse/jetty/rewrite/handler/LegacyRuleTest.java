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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LegacyRuleTest extends AbstractRuleTestCase
{
    private String[][] _tests=
    {
            {"/foo/bar","/*","/replace/foo/bar"},
            {"/foo/bar","/foo/*","/replace/bar"},
            {"/foo/bar","/foo/bar","/replace"}
    };
    private LegacyRule _rule;

    @Before
    public void init() throws Exception
    {
        start(false);
        _rule = new LegacyRule();
    }

    @After
    public void destroy()
    {
        _rule = null;
    }

    @Test
    public void testMatchAndApply() throws Exception
    {
        for (String[] _test : _tests)
        {
            _rule.addRewriteRule(_test[1], "/replace");
            String result = _rule.matchAndApply(_test[0], _request, _response);
            assertEquals(_test[1], _test[2], result);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddRewrite()
    {
        _rule.addRewriteRule("*.txt", "/replace");
    }
}
