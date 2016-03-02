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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.junit.Before;
import org.junit.Test;

public class MsieSslRuleTest extends AbstractRuleTestCase
{
    private MsieSslRule _rule;

    @Before
    public void init() throws Exception
    {
        // enable SSL
        start(true);
        _rule = new MsieSslRule();
    }

    @Test
    public void testWin2kWithIE5() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 5.0)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));


        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 5.0)");
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT 5.0)");
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kWithIE6() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kWithIE7() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.0)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(null, result);
        assertEquals(null, _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kSP1WithIE5() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 5.01)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 5.01)");
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT 5.01)");
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kSP1WithIE6() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.01)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kSP1WithIE7() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.01)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(null, result);
        assertEquals(null, _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinXpWithIE5() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 5.1)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 5.1)");
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT 5.1)");
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinXpWithIE6() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(null, result);
        assertEquals(null, _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinXpWithIE7() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.1)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(null, result);
        assertEquals(null, _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinVistaWithIE5() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 6.0)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 6.0)");
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT 6.0)");
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinVistaWithIE6() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 6.0)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(null, result);
        assertEquals(null, _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinVistaWithIE7() throws Exception
    {
        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 6.0)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(null, result);
        assertEquals(null, _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWithoutSsl() throws Exception
    {
        // disable SSL
        super.stop();
        super.start(false);

        HttpFields fields = _request.getHttpFields();
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 5.0)");

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(null, result);
        assertEquals(null, _response.getHeader(HttpHeader.CONNECTION.asString()));
    }
}
