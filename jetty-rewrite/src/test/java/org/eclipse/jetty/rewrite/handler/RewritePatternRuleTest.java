//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RewritePatternRuleTest extends AbstractRuleTestCase
{
    private String[][] _tests=
    {
            {"/foo/bar","/","/replace"},
            {"/foo/bar","/*","/replace/foo/bar"},
            {"/foo/bar","/foo/*","/replace/bar"},
            {"/foo/bar","/foo/bar","/replace"},
            {"/foo/bar.txt","*.txt","/replace"},
    };
    private RewritePatternRule _rule;

    @Before
    public void init() throws Exception
    {
        start(false);
        _rule = new RewritePatternRule();
        _rule.setReplacement("/replace");
    }

    @Test
    public void testRequestUriEnabled() throws IOException
    {
        for (String[] test : _tests)
        {
            _rule.setPattern(test[1]);
            String result = _rule.matchAndApply(test[0], _request, _response);
            assertEquals(test[1], test[2], result);
        }
    }
}
