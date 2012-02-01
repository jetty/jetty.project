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
            {"/foo/bar",".*","/replace","/replace",null},
            {"/foo/bar","/xxx.*","/replace",null,null},
            {"/foo/bar","/(.*)/(.*)","/$2/$1/xxx","/bar/foo/xxx",null},
            {"/foo/bar","/(foo)/(.*)(bar)","/$3/$1/xxx$2","/bar/foo/xxx",null},
            {"/foo/$bar",".*","/$replace","/$replace",null},
            {"/foo/$bar","/foo/(.*)","/$1/replace","/$bar/replace",null},
            {"/foo/bar/info","/foo/(NotHere)?([^/]*)/(.*)","/$3/other?p1=$2","/info/other","p1=bar"},
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
            
            _request.setRequestURI(test[0]);
            _request.setQueryString(null);
            _rule.applyURI(_request,test[0],result);

            assertEquals(test[3], _request.getRequestURI());
            assertEquals(test[4], _request.getQueryString());
        }
    }
}
