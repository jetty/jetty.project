//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


/* ------------------------------------------------------------ */
/**
 */
public class HpackDecoderTest
{

    @Test
    public void testDecodeD_3()
    {        
        HpackDecoder decoder = new HpackDecoder(4096,8192);
           
        // First request
        String encoded="828786440f7777772e6578616d706c652e636f6d";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);
        
        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP,request.getScheme());
        assertEquals("/",request.getURI());
        assertEquals("www.example.com",request.getHost());
        assertFalse(request.iterator().hasNext());
        
        
        // Second request
        encoded="5c086e6f2d6361636865";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP,request.getScheme());
        assertEquals("/",request.getURI());
        assertEquals("www.example.com",request.getHost());
        Iterator<HttpField> iterator=request.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(new HttpField("cache-control","no-cache"),iterator.next());
        assertFalse(iterator.hasNext());
        

        // Third request
        encoded="30858c8b84400a637573746f6d2d6b65790c637573746f6d2d76616c7565";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        request = (MetaData.Request)decoder.decode(buffer);
        
        assertEquals("GET",request.getMethod());
        assertEquals(HttpScheme.HTTPS,request.getScheme());
        assertEquals("/index.html",request.getURI());
        assertEquals("www.example.com",request.getHost());
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
        String encoded="828786448ce7cf9bebe89b6fb16fa9b6ff";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);
        
        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP,request.getScheme());
        assertEquals("/",request.getURI());
        assertEquals("www.example.com",request.getHost());
        assertFalse(request.iterator().hasNext());
        
        
        // Second request
        encoded="5c86b9b9949556bf";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP,request.getScheme());
        assertEquals("/",request.getURI());
        assertEquals("www.example.com",request.getHost());
        Iterator<HttpField> iterator=request.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(new HttpField("cache-control","no-cache"),iterator.next());
        assertFalse(iterator.hasNext());

        // Third request
        encoded="30858c8b844088571c5cdb737b2faf89571c5cdb73724d9c57";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        request = (MetaData.Request)decoder.decode(buffer);
        
        assertEquals("GET",request.getMethod());
        assertEquals(HttpScheme.HTTPS,request.getScheme());
        assertEquals("/index.html",request.getURI());
        assertEquals("www.example.com",request.getHost());
        iterator=request.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(new HttpField("custom-key","custom-value"),iterator.next());
        assertFalse(iterator.hasNext());
    }

}
