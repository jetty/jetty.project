//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.junit.Ignore;
import org.junit.Test;

@SuppressWarnings("unused")
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
        _request.setURIPathQuery("/valid/uri.html");
        
        _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(200,_response.getStatus());
    }
    
    @Test
    public void testInvalidUrl() throws Exception
    {
        _rule.setCode("404");
        _request.setURIPathQuery("/invalid%0c/uri.html");
        
        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(404,_response.getStatus());
    }

    @Test
    public void testInvalidUrlWithReason() throws Exception
    {
        _rule.setCode("405");
        _rule.setReason("foo");
        _request.setURIPathQuery("/%00/");
        
        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(405,_response.getStatus());
        assertEquals("foo",_response.getReason());
    }
    
    @Test
    public void testInvalidJsp() throws Exception
    {
        _rule.setCode("405");
        _rule.setReason("foo");
        _request.setURIPathQuery("/jsp/bean1.jsp%00");
        
        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(405,_response.getStatus());
        assertEquals("foo",_response.getReason());
    }
    
    @Ignore("Not working in jetty-9")
    @Test
    public void testInvalidShamrock() throws Exception
    {
        _rule.setCode("405");
        _rule.setReason("foo");
        _request.setURIPathQuery("/jsp/shamrock-%00%E2%98%98.jsp");
        
        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(405,_response.getStatus());
        assertEquals("foo",_response.getReason());
    }

    @Ignore("Not working in jetty-9")
    @Test
    public void testValidShamrock() throws Exception
    {
        _rule.setCode("405");
        _rule.setReason("foo");
        _request.setURIPathQuery("/jsp/shamrock-%E2%98%98.jsp");
        
        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(200,_response.getStatus());
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

