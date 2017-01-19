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


package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;
import java.util.Iterator;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class HpackDecoderTest
{
    @Test
    public void testDecodeD_3()
    {
        HpackDecoder decoder = new HpackDecoder(4096,8192);

        // First request
        String encoded="828684410f7777772e6578616d706c652e636f6d";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(),request.getURI().getScheme());
        assertEquals("/",request.getURI().getPath());
        assertEquals("www.example.com",request.getURI().getHost());
        assertFalse(request.iterator().hasNext());

        // Second request
        encoded="828684be58086e6f2d6361636865";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(),request.getURI().getScheme());
        assertEquals("/",request.getURI().getPath());
        assertEquals("www.example.com",request.getURI().getHost());
        Iterator<HttpField> iterator=request.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(new HttpField("cache-control","no-cache"),iterator.next());
        assertFalse(iterator.hasNext());

        // Third request
        encoded="828785bf400a637573746f6d2d6b65790c637573746f6d2d76616c7565";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET",request.getMethod());
        assertEquals(HttpScheme.HTTPS.asString(),request.getURI().getScheme());
        assertEquals("/index.html",request.getURI().getPath());
        assertEquals("www.example.com",request.getURI().getHost());
        iterator=request.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(new HttpField("custom-key","custom-value"),iterator.next());
        assertFalse(iterator.hasNext());
    }

    @Test
    public void testDecodeD_4()
    {
        HpackDecoder decoder = new HpackDecoder(4096,8192);

        // First request
        String encoded="828684418cf1e3c2e5f23a6ba0ab90f4ff";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(),request.getURI().getScheme());
        assertEquals("/",request.getURI().getPath());
        assertEquals("www.example.com",request.getURI().getHost());
        assertFalse(request.iterator().hasNext());

        // Second request
        encoded="828684be5886a8eb10649cbf";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(),request.getURI().getScheme());
        assertEquals("/",request.getURI().getPath());
        assertEquals("www.example.com",request.getURI().getHost());
        Iterator<HttpField> iterator=request.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(new HttpField("cache-control","no-cache"),iterator.next());
        assertFalse(iterator.hasNext());
    }

    @Test
    public void testDecodeWithArrayOffset()
    {
        String value = "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==";

        HpackDecoder decoder = new HpackDecoder(4096,8192);
        String encoded = "8682418cF1E3C2E5F23a6bA0Ab90F4Ff841f0822426173696320515778685a475270626a70766347567549484e6c633246745a513d3d";
        byte[] bytes = TypeUtil.fromHexString(encoded);
        byte[] array = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, array, 1, bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(array, 1, bytes.length).slice();

        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(),request.getURI().getScheme());
        assertEquals("/",request.getURI().getPath());
        assertEquals("www.example.com",request.getURI().getHost());
        assertEquals(1,request.getFields().size());
        HttpField field = request.iterator().next();
        assertEquals(HttpHeader.AUTHORIZATION, field.getHeader());
        assertEquals(value, field.getValue());
    }

    @Test
    public void testDecodeHuffmanWithArrayOffset()
    {
        HpackDecoder decoder = new HpackDecoder(4096,8192);

        String encoded="8286418cf1e3c2e5f23a6ba0ab90f4ff84";
        byte[] bytes = TypeUtil.fromHexString(encoded);
        byte[] array = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, array, 1, bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(array, 1, bytes.length).slice();

        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(),request.getURI().getScheme());
        assertEquals("/",request.getURI().getPath());
        assertEquals("www.example.com",request.getURI().getHost());
        assertFalse(request.iterator().hasNext());
    }
    
    @Test
    public void testNghttpx()
    {        
        // Response encoded by nghttpx
        String encoded="886196C361Be940b6a65B6850400B8A00571972e080a62D1Bf5f87497cA589D34d1f9a0f0d0234327690Aa69D29aFcA954D3A5358980Ae112e0f7c880aE152A9A74a6bF3";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        HpackDecoder decoder = new HpackDecoder(4096,8192);
        MetaData.Response response = (MetaData.Response)decoder.decode(buffer);

        assertThat(response.getStatus(),is(200));
        assertThat(response.getFields().size(),is(6));
        assertTrue(response.getFields().contains(new HttpField(HttpHeader.DATE,"Fri, 15 Jul 2016 02:36:20 GMT")));
        assertTrue(response.getFields().contains(new HttpField(HttpHeader.CONTENT_TYPE,"text/html")));
        assertTrue(response.getFields().contains(new HttpField(HttpHeader.CONTENT_ENCODING,"")));
        assertTrue(response.getFields().contains(new HttpField(HttpHeader.CONTENT_LENGTH,"42")));
        assertTrue(response.getFields().contains(new HttpField(HttpHeader.SERVER,"nghttpx nghttp2/1.12.0")));
        assertTrue(response.getFields().contains(new HttpField(HttpHeader.VIA,"1.1 nghttpx")));
    }

    @Test
    public void testTooBigToIndex()
    {
        String encoded = "44FfEc02Df3990A190A0D4Ee5b3d2940Ec98Aa4a62D127D29e273a0aA20dEcAa190a503b262d8a2671D4A2672a927aA874988a2471D05510750c951139EdA2452a3a548cAa1aA90bE4B228342864A9E0D450A5474a92992a1aA513395448E3A0Aa17B96cFe3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f14E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F353F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F54f";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        HpackDecoder decoder = new HpackDecoder(128,8192);
        try
        {
            decoder.decode(buffer);
            Assert.fail();
        }
        catch (BadMessageException e)
        {
            assertThat(e.getCode(),equalTo(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431));
            assertThat(e.getReason(),Matchers.startsWith("Indexed field value too large"));
        }
    }

    @Test
    public void testUnknownIndex()
    {
        String encoded = "BE";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        HpackDecoder decoder = new HpackDecoder(128,8192);
        try
        {
            decoder.decode(buffer);
            Assert.fail();
        }
        catch (BadMessageException e)
        {
            assertThat(e.getCode(),equalTo(HttpStatus.BAD_REQUEST_400));
            assertThat(e.getReason(),Matchers.startsWith("Unknown index"));
        }
    
    }
}
