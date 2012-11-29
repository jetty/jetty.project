//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

public class ValidUrlRuleTest extends AbstractRuleTestCase
{
    private ValidUrlRule _rule;
    
    @Before
    public void init() throws Exception
    {
        start(true);
        _rule = new ValidUrlRule();
    }
    
    @Test
    public void testValidUrl() throws Exception
    {
        _rule.setCode("404");
        _request.setRequestURI("/valid/uri.html");
        
        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(200,_response.getStatus());
    }
    
    @Test
    public void testInvalidUrl() throws Exception
    {
        _rule.setCode("404");
        _request.setRequestURI("/invalid%0c/uri.html");
        
        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(404,_response.getStatus());
    }

    @Test
    public void testInvalidUrl2() throws Exception
    {
        _rule.setCode("404");
        _request.setRequestURI("/%00/");
        
        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(404,_response.getStatus());
    }
    
    @Test
    public void testCharacters() throws Exception
    {
        // space
        Assert.assertTrue( _rule.isValidChar("\u0020".charAt(0)));
        // form feed
        Assert.assertFalse( _rule.isValidChar("\u000c".charAt(0)));
    }
}

