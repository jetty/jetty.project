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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.junit.Assert;
import org.junit.Test;
import org.eclipse.jetty.http.MetaData.Request;
import org.eclipse.jetty.http.MetaData.Response;
import org.eclipse.jetty.util.BufferUtil;


public class HpackTest
{
    @Test
    public void encodeDecodeResponseTest()
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder();
        ByteBuffer buffer = BufferUtil.allocate(16*1024);
        
        HttpFields fields0 = new HttpFields();
        fields0.add(HttpHeader.CONTENT_TYPE,"text/html");
        fields0.add(HttpHeader.CONTENT_LENGTH,"1024");
        fields0.add(HttpHeader.SERVER,"jetty");
        fields0.add(HttpHeader.SET_COOKIE,"abcdefghijklmnopqrstuvwxyz");
        fields0.add("custom-key","custom-value");
        Response original0 = new Response(HttpVersion.HTTP_2_0,200,fields0);
        
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original0);
        BufferUtil.flipToFlush(buffer,0);
        Response decoded0 = (Response)decoder.decode(buffer);

        Assert.assertEquals(original0,decoded0);
        
        // Same again?
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original0);
        BufferUtil.flipToFlush(buffer,0);
        Response decoded0b = (Response)decoder.decode(buffer);

        Assert.assertEquals(original0,decoded0b);        

        HttpFields fields1 = new HttpFields();
        fields1.add(HttpHeader.CONTENT_TYPE,"text/plain");
        fields1.add(HttpHeader.CONTENT_LENGTH,"1234");
        fields1.add(HttpHeader.SERVER,"jetty");
        fields1.add("custom-key","other-value");
        Response original1 = new Response(HttpVersion.HTTP_2_0,200,fields1);

        // Same again?
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original1);
        BufferUtil.flipToFlush(buffer,0);
        Response decoded1 = (Response)decoder.decode(buffer);

        Assert.assertEquals(original1,decoded1);
        
        
    }
}
