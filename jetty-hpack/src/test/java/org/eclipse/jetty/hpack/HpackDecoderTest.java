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


package org.eclipse.jetty.hpack;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.hpack.HpackDecoder.Listener;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.Assert;
import org.junit.Test;


/* ------------------------------------------------------------ */
/**
 */
public class HpackDecoderTest
{

    @Test
    public void testDecodeD_3()
    {
        final HttpFields fields = new HttpFields();
        Listener listener = new Listener()
        {
            
            @Override
            public void endHeaders()
            {
                System.err.println("===");
            }
            
            @Override
            public void emit(HttpField field)
            {
                System.err.println(field);
                fields.add(field);
            }
        };
        
        HpackDecoder decoder = new HpackDecoder(listener);
        
        
        // First request
        String encoded="828786440f7777772e6578616d706c652e636f6d";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        decoder.decode(buffer);
        
        assertEquals(4,fields.size());
        assertEquals("GET",fields.get(":method"));
        assertEquals("http",fields.get(":scheme"));
        assertEquals("/",fields.get(":path"));
        assertEquals("www.example.com",fields.get(":authority"));
        
        
        // Second request
        fields.clear();
        encoded="5c086e6f2d6361636865";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        decoder.decode(buffer);
        
        assertEquals(5,fields.size());
        assertEquals("GET",fields.get(":method"));
        assertEquals("http",fields.get(":scheme"));
        assertEquals("/",fields.get(":path"));
        assertEquals("www.example.com",fields.get(":authority"));
        assertEquals("no-cache",fields.get("cache-control"));

        // Third request
        fields.clear();
        encoded="30858c8b84400a637573746f6d2d6b65790c637573746f6d2d76616c7565";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        decoder.decode(buffer);
        
        assertEquals(5,fields.size());
        assertEquals("GET",fields.get(":method"));
        assertEquals("https",fields.get(":scheme"));
        assertEquals("/index.html",fields.get(":path"));
        assertEquals("www.example.com",fields.get(":authority"));
        assertEquals("custom-value",fields.get("custom-key"));
    }

    @Test
    public void testDecodeD_4()
    {
        final HttpFields fields = new HttpFields();
        Listener listener = new Listener()
        {
            
            @Override
            public void endHeaders()
            {
                System.err.println("===");
            }
            
            @Override
            public void emit(HttpField field)
            {
                System.err.println(field);
                fields.add(field);
            }
        };
        
        HpackDecoder decoder = new HpackDecoder(listener);
        
        
        // First request
        String encoded="828786448ce7cf9bebe89b6fb16fa9b6ff";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        decoder.decode(buffer);
        
        assertEquals(4,fields.size());
        assertEquals("GET",fields.get(":method"));
        assertEquals("http",fields.get(":scheme"));
        assertEquals("/",fields.get(":path"));
        assertEquals("www.example.com",fields.get(":authority"));
        
        
        // Second request
        fields.clear();
        encoded="5c86b9b9949556bf";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        decoder.decode(buffer);
        
        assertEquals(5,fields.size());
        assertEquals("GET",fields.get(":method"));
        assertEquals("http",fields.get(":scheme"));
        assertEquals("/",fields.get(":path"));
        assertEquals("www.example.com",fields.get(":authority"));
        assertEquals("no-cache",fields.get("cache-control"));

        // Third request
        fields.clear();
        encoded="30858c8b844088571c5cdb737b2faf89571c5cdb73724d9c57";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        
        decoder.decode(buffer);
        
        assertEquals(5,fields.size());
        assertEquals("GET",fields.get(":method"));
        assertEquals("https",fields.get(":scheme"));
        assertEquals("/index.html",fields.get(":path"));
        assertEquals("www.example.com",fields.get(":authority"));
        assertEquals("custom-value",fields.get("custom-key"));
    }

}
