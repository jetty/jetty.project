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

public class RewriteRegexRuleTest extends AbstractRuleTestCase
{
    private RewriteRegexRule _rule;

    String[][] _tests=
    {       
            {"/foo/bar",".*","/replace","/replace"},
            {"/foo/bar","/xxx.*","/replace",null},       
            {"/foo/bar","/(.*)/(.*)","/$2/$1/xxx","/bar/foo/xxx"},
    };
    
    public void setUp() throws Exception
    {
        super.setUp();
        _rule=new RewriteRegexRule();
    }
    
    public void testRequestUriEnabled() throws IOException
    {
        for (int i=0;i<_tests.length;i++)
        {
            _rule.setRegex(_tests[i][1]);
            _rule.setReplacement(_tests[i][2]);
            
            String result = _rule.matchAndApply(_tests[i][0], _request, _response);
        
            assertEquals(_tests[i][1],_tests[i][3], result);
        }
    }

}
