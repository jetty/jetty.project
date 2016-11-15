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

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MetaData.Response;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class HpackTest
{
    final static HttpField ServerJetty = new PreEncodedHttpField(HttpHeader.SERVER,"jetty");
    final static HttpField XPowerJetty = new PreEncodedHttpField(HttpHeader.X_POWERED_BY,"jetty");
    final static HttpField Date = new PreEncodedHttpField(HttpHeader.DATE,DateGenerator.formatDate(System.currentTimeMillis()));
    
    @Test
    public void encodeDecodeResponseTest()
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(4096,8192);
        ByteBuffer buffer = BufferUtil.allocate(16*1024);
        
        HttpFields fields0 = new HttpFields();
        fields0.add(HttpHeader.CONTENT_TYPE,"text/html");
        fields0.add(HttpHeader.CONTENT_LENGTH,"1024");
        fields0.add(new HttpField(HttpHeader.CONTENT_ENCODING,(String)null));
        fields0.add(ServerJetty);
        fields0.add(XPowerJetty);
        fields0.add(Date);
        fields0.add(HttpHeader.SET_COOKIE,"abcdefghijklmnopqrstuvwxyz");
        fields0.add("custom-key","custom-value");
        Response original0 = new MetaData.Response(HttpVersion.HTTP_2,200,fields0);
        
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original0);
        BufferUtil.flipToFlush(buffer,0);
        Response decoded0 = (Response)decoder.decode(buffer);
        original0.getFields().put(new HttpField(HttpHeader.CONTENT_ENCODING,""));
        assertMetadataSame(original0,decoded0);
        
        // Same again?
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original0);
        BufferUtil.flipToFlush(buffer,0);
        Response decoded0b = (Response)decoder.decode(buffer);

        assertMetadataSame(original0,decoded0b);        

        HttpFields fields1 = new HttpFields();
        fields1.add(HttpHeader.CONTENT_TYPE,"text/plain");
        fields1.add(HttpHeader.CONTENT_LENGTH,"1234");
        fields1.add(HttpHeader.CONTENT_ENCODING," ");
        fields1.add(ServerJetty);
        fields1.add(XPowerJetty);
        fields1.add(Date);
        fields1.add("Custom-Key","Other-Value");
        Response original1 = new MetaData.Response(HttpVersion.HTTP_2,200,fields1);

        // Same again?
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original1);
        BufferUtil.flipToFlush(buffer,0);
        Response decoded1 = (Response)decoder.decode(buffer);

        assertMetadataSame(original1,decoded1);
        Assert.assertEquals("custom-key",decoded1.getFields().getField("Custom-Key").getName());
    }
    
    @Test
    public void encodeDecodeTooLargeTest()
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(4096,164);
        ByteBuffer buffer = BufferUtil.allocate(16*1024);
        
        HttpFields fields0 = new HttpFields();
        fields0.add("1234567890","1234567890123456789012345678901234567890");
        fields0.add("Cookie","abcdeffhijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR");
        MetaData original0= new MetaData(HttpVersion.HTTP_2,fields0);
        
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original0);
        BufferUtil.flipToFlush(buffer,0);
        MetaData decoded0 = (MetaData)decoder.decode(buffer);

        assertMetadataSame(original0,decoded0);
               
        HttpFields fields1 = new HttpFields();
        fields1.add("1234567890","1234567890123456789012345678901234567890");
        fields1.add("Cookie","abcdeffhijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR");
        fields1.add("x","y");
        MetaData original1 = new MetaData(HttpVersion.HTTP_2,fields1);

        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original1);
        BufferUtil.flipToFlush(buffer,0);
        try
        {
            decoder.decode(buffer);
            Assert.fail();
        }
        catch(BadMessageException e)
        {
            assertEquals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431,e.getCode());
        }
    }

    @Test
    public void evictReferencedFieldTest()
    {
        HpackEncoder encoder = new HpackEncoder(200,200);
        HpackDecoder decoder = new HpackDecoder(200,1024);
        ByteBuffer buffer = BufferUtil.allocate(16*1024);
        
        HttpFields fields0 = new HttpFields();
        fields0.add("123456789012345678901234567890123456788901234567890","value");
        fields0.add("foo","abcdeffhijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR");
        MetaData original0= new MetaData(HttpVersion.HTTP_2,fields0);

        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original0);
        BufferUtil.flipToFlush(buffer,0);
        MetaData decoded0 = (MetaData)decoder.decode(buffer);

        assertEquals(2,encoder.getHpackContext().size());
        assertEquals(2,decoder.getHpackContext().size());
        assertEquals("123456789012345678901234567890123456788901234567890",encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length+1).getHttpField().getName());
        assertEquals("foo",encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length+0).getHttpField().getName());
        
        assertMetadataSame(original0,decoded0);
               
        HttpFields fields1 = new HttpFields();
        fields1.add("123456789012345678901234567890123456788901234567890","other_value");
        fields1.add("x","y");
        MetaData original1 = new MetaData(HttpVersion.HTTP_2,fields1);

        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,original1);
        BufferUtil.flipToFlush(buffer,0);
        MetaData decoded1 = (MetaData)decoder.decode(buffer);
        assertMetadataSame(original1,decoded1);
        
        assertEquals(2,encoder.getHpackContext().size());
        assertEquals(2,decoder.getHpackContext().size());
        assertEquals("x",encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length+0).getHttpField().getName());
        assertEquals("foo",encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length+1).getHttpField().getName());        
    }
    
    private void assertMetadataSame(MetaData.Response expected, MetaData.Response actual)
    {
        assertThat("Response.status", actual.getStatus(), is(expected.getStatus()));
        assertThat("Response.reason", actual.getReason(), is(expected.getReason()));
        assertMetadataSame((MetaData)expected,(MetaData)actual);
    }

    private void assertMetadataSame(MetaData expected, MetaData actual)
    {
        assertThat("Metadata.contentLength",actual.getContentLength(),is(expected.getContentLength()));
        assertThat("Metadata.version" + ".version", actual.getHttpVersion(),is(expected.getHttpVersion()));
        assertHttpFieldsSame("Metadata.fields",expected.getFields(),actual.getFields());
    }

    private void assertHttpFieldsSame(String msg, HttpFields expected, HttpFields actual)
    {
        assertThat(msg + ".size", actual.size(), is(expected.size()));
        
        for (HttpField actualField : actual)
        {
            if ("DATE".equalsIgnoreCase(actualField.getName()))
            {
                // skip comparison on Date, as these values can often differ by 1 second
                // during testing.
                continue;
            }
            assertThat(msg + ".contains(" + actualField + ")",expected.contains(actualField),is(true));
        }
    }
}
