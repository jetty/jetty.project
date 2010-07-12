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

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RewriteRegexRuleTest extends AbstractRuleTestCase
{
    private String[][] _tests=
    {
            {"/foo/bar",".*","/replace","/replace"},
            {"/foo/bar","/xxx.*","/replace",null},
            {"/foo/bar","/(.*)/(.*)","/$2/$1/xxx","/bar/foo/xxx"},
    };
    private RewriteRegexRule _rule;

    @Before
    public void init() throws Exception
    {
        start(false);
        _rule=new RewriteRegexRule();
    }

    @Test
    public void testRequestUriEnabled() throws IOException
    {
        for (String[] test : _tests)
        {
            _rule.setRegex(test[1]);
            _rule.setReplacement(test[2]);
            String result = _rule.matchAndApply(test[0], _request, _response);
            assertEquals(test[1], test[3], result);
        }
    }
}
