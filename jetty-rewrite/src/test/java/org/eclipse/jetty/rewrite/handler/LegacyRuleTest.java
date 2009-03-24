// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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


public class LegacyRuleTest extends AbstractRuleTestCase
{
    private LegacyRule _rule;
    
    String[][] _tests=
    {
            {"/foo/bar","/*","/replace/foo/bar"},
            {"/foo/bar","/foo/*","/replace/bar"},
            {"/foo/bar","/foo/bar","/replace"}
    };
    
    public void setUp() throws Exception
    {
        super.setUp();
        _rule = new LegacyRule();
    }
    
    public void tearDown()
    {
        _rule = null;
    }
    
    public void testMatchAndApply() throws Exception
    {
        for (int i=0;i<_tests.length;i++)
        {
            _rule.addRewriteRule(_tests[i][1], "/replace");
            
            String result = _rule.matchAndApply(_tests[i][0], _request, _response);
        
            assertEquals(_tests[i][1], _tests[i][2], result);
        }
    }
    
    public void testAddRewrite()
    {
        try
        {
            _rule.addRewriteRule("*.txt", "/replace");
            fail();
        } 
        catch (IllegalArgumentException e)
        {
        }
    }
}
