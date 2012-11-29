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
        _request.setRequestURI("/invalid\u000c/uri.html");
        
        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(404,_response.getStatus());
    }

    @Test
    public void testCharacters() throws Exception
    {
        // space
        Assert.assertTrue( _rule.isPrintableChar("\u0020".charAt(0)));
        // form feed
        Assert.assertFalse( _rule.isPrintableChar("\u000c".charAt(0)));
    }
}

