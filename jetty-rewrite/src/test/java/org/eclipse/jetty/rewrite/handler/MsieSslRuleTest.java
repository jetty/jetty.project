//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.rewrite.handler;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MsieSslRuleTest extends AbstractRuleTestCase
{
    private MsieSslRule _rule;

    @BeforeEach
    public void init() throws Exception
    {
        // enable SSL
        start(true);
        _rule = new MsieSslRule();
    }

    @Test
    public void testWin2kSP1WithIE5() throws Exception
    {
        HttpFields.Mutable fields = HttpFields.build(_request.getHttpFields());
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 5.01)");
        _request.setHttpFields(fields);

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 5.01)");
        _request.setHttpFields(fields);
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT 5.01)");
        _request.setHttpFields(fields);
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kSP1WithIE6() throws Exception
    {
        _request.setHttpFields(HttpFields.build(_request.getHttpFields())
            .add("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.01)"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kSP1WithIE7() throws Exception
    {
        _request.setHttpFields(HttpFields.build(_request.getHttpFields())
            .add("User-Agent", "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.01)"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertNull(result);
        assertNull(_response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kWithIE5() throws Exception
    {
        HttpFields.Mutable fields = HttpFields.build(_request.getHttpFields());
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 5.0)");
        _request.setHttpFields(fields);

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 5.0)");
        _request.setHttpFields(fields);
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT 5.0)");
        _request.setHttpFields(fields);
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kWithIE6() throws Exception
    {
        _request.setHttpFields(HttpFields.build(_request.getHttpFields())
            .add("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)")
            .asImmutable());

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWin2kWithIE7() throws Exception
    {
        _request.setHttpFields(HttpFields.build(_request.getHttpFields())
            .add("User-Agent", "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.0)"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertNull(result);
        assertNull(_response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinVistaWithIE5() throws Exception
    {
        HttpFields.Mutable fields = HttpFields.build(_request.getHttpFields());
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 6.0)");
        _request.setHttpFields(fields);

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 6.0)");
        _request.setHttpFields(fields);
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT 6.0)");
        _request.setHttpFields(fields);
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinVistaWithIE6() throws Exception
    {
        _request.setHttpFields(HttpFields.build(_request.getHttpFields())
            .add("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 6.0)"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertNull(result);
        assertNull(_response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinVistaWithIE7() throws Exception
    {
        _request.setHttpFields(HttpFields.build(_request.getHttpFields())
            .add("User-Agent", "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 6.0)"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertNull(result);
        assertNull(_response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinXpWithIE5() throws Exception
    {
        HttpFields.Mutable fields = HttpFields.build(_request.getHttpFields());
        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 5.1)");
        _request.setHttpFields(fields);

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 5.1)");
        _request.setHttpFields(fields);
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));

        fields.add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.5; Windows NT 5.1)");
        _request.setHttpFields(fields);
        result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);
        assertEquals(_request.getRequestURI(), result);
        assertEquals(HttpHeaderValue.CLOSE.asString(), _response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinXpWithIE6() throws Exception
    {
        _request.setHttpFields(HttpFields.build(_request.getHttpFields())
            .add("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1)"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertNull(result);
        assertNull(_response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWinXpWithIE7() throws Exception
    {
        _request.setHttpFields(HttpFields.build(_request.getHttpFields())
            .add("User-Agent", "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.1)"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertNull(result);
        assertNull(_response.getHeader(HttpHeader.CONNECTION.asString()));
    }

    @Test
    public void testWithoutSsl() throws Exception
    {
        // disable SSL
        super.stop();
        super.start(false);

        _request.setHttpFields(HttpFields.build(_request.getHttpFields())
            .add("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT 5.0)"));

        String result = _rule.matchAndApply(_request.getRequestURI(), _request, _response);

        assertNull(result);
        assertNull(_response.getHeader(HttpHeader.CONNECTION.asString()));
    }
}
