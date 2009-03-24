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

public class ResponsePatternRuleTest extends AbstractRuleTestCase
{
    private ResponsePatternRule _rule;
    
    public void setUp() throws Exception
    {
        super.setUp();
        _rule = new ResponsePatternRule();
        _rule.setPattern("/test");
    }
    
    public void testStatusCodeNoReason() throws IOException
    {
        for (int i = 1; i < 400; i++)
        {
            _rule.setCode("" + i);
            _rule.apply(null, _request, _response);
            
            assertEquals(i, _response.getStatus());
        }
    }
    
    public void testStatusCodeWithReason() throws IOException
    {
        for (int i = 1; i < 400; i++)
        {
            _rule.setCode("" + i);
            _rule.setReason("reason" + i);
            _rule.apply(null, _request, _response);
            
            assertEquals(i, _response.getStatus());
            assertEquals(null, _response.getReason());
        }
    }
    
    public void testErrorStatusNoReason() throws IOException
    {
        for (int i = 400; i < 600; i++)
        {
            _rule.setCode("" + i);
            _rule.apply(null, _request, _response);
            
            assertEquals(i, _response.getStatus());
            assertEquals("", _response.getReason());
            super.reset();
        }
    }
    
    public void testErrorStatusWithReason() throws IOException
    {
        for (int i = 400; i < 600; i++)
        {
            _rule.setCode("" + i);
            _rule.setReason("reason-" + i);
            _rule.apply(null, _request, _response);
            
            assertEquals(i, _response.getStatus());
            assertEquals("reason-" + i, _response.getReason());
            super.reset();
        }
    }
}
