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

import org.eclipse.jetty.http.HttpHeaders;


public class RedirectPatternRuleTest extends AbstractRuleTestCase
{
    private RedirectPatternRule _rule;
    
    public void setUp() throws Exception
    {
        super.setUp();
        _rule = new RedirectPatternRule();
        _rule.setPattern("*");
    }
    
    public void tearDown()
    {
        _rule = null;
    }
    
    public void testLocation() throws IOException
    {
        String location = "http://eclipse.com";
        
        _rule.setLocation(location);
        _rule.apply(null, _request, _response);
        
        assertEquals(location, _response.getHeader(HttpHeaders.LOCATION));
    }
    
}
