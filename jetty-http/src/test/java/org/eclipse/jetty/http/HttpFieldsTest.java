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

package org.eclipse.jetty.http;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.Enumeration;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class HttpFieldsTest
{
    @Test
    public void testPut() throws Exception
    {
        HttpFields header = new HttpFields();

        header.put("name0", "value:0");
        header.put("name1", "value1");

        assertEquals("value:0",header.getStringField("name0"));
        assertEquals("value1",header.getStringField("name1"));
        assertNull(header.getStringField("name2"));

        int matches=0;
        Enumeration<String> e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o=e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
        }
        assertEquals(2, matches);

        e = header.getValues("name0");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value:0");
        assertEquals(false, e.hasMoreElements());
    }

    @Test
    public void testPutTo() throws Exception
    {
        HttpFields header = new HttpFields();

        header.put("name0", "value0");
        header.put("name1", "value:A");
        header.add("name1", "value:B");
        header.add("name2", "");

        ByteBuffer buffer=BufferUtil.allocate(1024);
        BufferUtil.flipToFill(buffer);
        HttpGenerator.putTo(header,buffer);
        BufferUtil.flipToFlush(buffer,0);
        String result=BufferUtil.toString(buffer);

        assertThat(result,Matchers.containsString("name0: value0"));
        assertThat(result,Matchers.containsString("name1: value:A"));
        assertThat(result,Matchers.containsString("name1: value:B"));
    }

    @Test
    public void testGet() throws Exception
    {
        HttpFields header = new HttpFields();

        header.put("name0", "value0");
        header.put("name1", "value1");

        assertEquals("value0",header.getStringField("name0"));
        assertEquals("value0",header.getStringField("Name0"));
        assertEquals("value1",header.getStringField("name1"));
        assertEquals("value1",header.getStringField("Name1"));
    }

    @Test
    public void testCRLF() throws Exception
    {
        HttpFields header = new HttpFields();

        header.put("name0", "value\r\n0");
        header.put("name\r\n1", "value1");
        header.put("name:2", "value:\r\n2");

        ByteBuffer buffer = BufferUtil.allocate(1024);
        BufferUtil.flipToFill(buffer);
        HttpGenerator.putTo(header,buffer);
        BufferUtil.flipToFlush(buffer,0);
        String out = BufferUtil.toString(buffer);
        assertThat(out,containsString("name0: value??0"));
        assertThat(out,containsString("name??1: value1"));
        assertThat(out,containsString("name?2: value:??2"));
    }

    @Test
    public void testCachedPut() throws Exception
    {
        HttpFields header = new HttpFields();

        header.put("Connection", "Keep-Alive");
        header.put("tRansfer-EncOding", "CHUNKED");
        header.put("CONTENT-ENCODING", "gZIP");

        ByteBuffer buffer = BufferUtil.allocate(1024);
        BufferUtil.flipToFill(buffer);
        HttpGenerator.putTo(header,buffer);
        BufferUtil.flipToFlush(buffer,0);
        String out = BufferUtil.toString(buffer).toLowerCase();

        Assert.assertThat(out,Matchers.containsString((HttpHeader.CONNECTION+": "+HttpHeaderValue.KEEP_ALIVE).toLowerCase()));
        Assert.assertThat(out,Matchers.containsString((HttpHeader.TRANSFER_ENCODING+": "+HttpHeaderValue.CHUNKED).toLowerCase()));
        Assert.assertThat(out,Matchers.containsString((HttpHeader.CONTENT_ENCODING+": "+HttpHeaderValue.GZIP).toLowerCase()));
    }

    @Test
    public void testRePut() throws Exception
    {
        HttpFields header = new HttpFields();

        header.put("name0", "value0");
        header.put("name1", "xxxxxx");
        header.put("name2", "value2");

        assertEquals("value0",header.getStringField("name0"));
        assertEquals("xxxxxx",header.getStringField("name1"));
        assertEquals("value2",header.getStringField("name2"));

        header.put("name1", "value1");

        assertEquals("value0",header.getStringField("name0"));
        assertEquals("value1",header.getStringField("name1"));
        assertEquals("value2",header.getStringField("name2"));
        assertNull(header.getStringField("name3"));

        int matches=0;
        Enumeration<String> e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            String o=e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(3, matches);


        e = header.getValues("name1");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value1");
        assertEquals(false, e.hasMoreElements());
    }

    @Test
    public void testRemovePut() throws Exception
    {
        HttpFields header = new HttpFields();

        header.put("name0", "value0");
        header.put("name1", "value1");
        header.put("name2", "value2");

        assertEquals("value0",header.getStringField("name0"));
        assertEquals("value1",header.getStringField("name1"));
        assertEquals("value2",header.getStringField("name2"));

        header.remove("name1");

        assertEquals("value0",header.getStringField("name0"));
        assertNull(header.getStringField("name1"));
        assertEquals("value2",header.getStringField("name2"));
        assertNull(header.getStringField("name3"));

        int matches=0;
        Enumeration<String> e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o=e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(2, matches);

        e = header.getValues("name1");
        assertEquals(false, e.hasMoreElements());
    }

    @Test
    public void testAdd() throws Exception
    {
        HttpFields fields = new HttpFields();

        fields.add("name0", "value0");
        fields.add("name1", "valueA");
        fields.add("name2", "value2");

        assertEquals("value0",fields.getStringField("name0"));
        assertEquals("valueA",fields.getStringField("name1"));
        assertEquals("value2",fields.getStringField("name2"));

        fields.add("name1", "valueB");

        assertEquals("value0",fields.getStringField("name0"));
        assertEquals("valueA",fields.getStringField("name1"));
        assertEquals("value2",fields.getStringField("name2"));
        assertNull(fields.getStringField("name3"));

        int matches=0;
        Enumeration<String> e = fields.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o=e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(3, matches);

        e = fields.getValues("name1");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "valueA");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "valueB");
        assertEquals(false, e.hasMoreElements());
    }


    @Test
    public void testGetValues() throws Exception
    {
        HttpFields fields = new HttpFields();

        fields.put("name0", "value0A,value0B");
        fields.add("name0", "value0C,value0D");
        fields.put("name1", "value1A, \"value\t, 1B\" ");
        fields.add("name1", "\"value1C\",\tvalue1D");

        Enumeration<String> e = fields.getValues("name0");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value0A,value0B");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value0C,value0D");
        assertEquals(false, e.hasMoreElements());

        e = fields.getValues("name0",",");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value0A");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value0B");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value0C");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value0D");
        assertEquals(false, e.hasMoreElements());
        
        e = fields.getValues("name1",",");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value1A");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value\t, 1B");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value1C");
        assertEquals(true, e.hasMoreElements());
        assertEquals(e.nextElement(), "value1D");
        assertEquals(false, e.hasMoreElements());
        
    }

    @Test
    public void testDateFields() throws Exception
    {
        HttpFields fields = new HttpFields();

        fields.put("D0", "Wed, 31 Dec 1969 23:59:59 GMT");
        fields.put("D1", "Fri, 31 Dec 1999 23:59:59 GMT");
        fields.put("D2", "Friday, 31-Dec-99 23:59:59 GMT");
        fields.put("D3", "Fri Dec 31 23:59:59 1999");
        fields.put("D4", "Mon Jan 1 2000 00:00:01");
        fields.put("D5", "Tue Feb 29 2000 12:00:00");

        long d1 = fields.getDateField("D1");
        long d0 = fields.getDateField("D0");
        long d2 = fields.getDateField("D2");
        long d3 = fields.getDateField("D3");
        long d4 = fields.getDateField("D4");
        long d5 = fields.getDateField("D5");
        assertTrue(d0!=-1);
        assertTrue(d1>0);
        assertTrue(d2>0);
        assertEquals(d1,d2);
        assertEquals(d2,d3);
        assertEquals(d3+2000,d4);
        assertEquals(951825600000L,d5);

        d1 = fields.getDateField("D1");
        d2 = fields.getDateField("D2");
        d3 = fields.getDateField("D3");
        d4 = fields.getDateField("D4");
        d5 = fields.getDateField("D5");
        assertTrue(d1>0);
        assertTrue(d2>0);
        assertEquals(d1,d2);
        assertEquals(d2,d3);
        assertEquals(d3+2000,d4);
        assertEquals(951825600000L,d5);

        fields.putDateField("D2",d1);
        assertEquals("Fri, 31 Dec 1999 23:59:59 GMT",fields.getStringField("D2"));
    }

    @Test
    public void testNegDateFields() throws Exception
    {
        HttpFields fields = new HttpFields();

        fields.putDateField("Dzero",0);
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT",fields.getStringField("Dzero"));

        fields.putDateField("Dminus",-1);
        assertEquals("Wed, 31 Dec 1969 23:59:59 GMT",fields.getStringField("Dminus"));

        fields.putDateField("Dminus",-1000);
        assertEquals("Wed, 31 Dec 1969 23:59:59 GMT",fields.getStringField("Dminus"));

        fields.putDateField("Dancient",Long.MIN_VALUE);
        assertEquals("Sun, 02 Dec 55 16:47:04 GMT",fields.getStringField("Dancient"));
    }

    @Test
    public void testLongFields() throws Exception
    {
        HttpFields header = new HttpFields();

        header.put("I1", "42");
        header.put("I2", " 43 99");
        header.put("I3", "-44;");
        header.put("I4", " - 45abc");
        header.put("N1", " - ");
        header.put("N2", "xx");

        long i1=header.getLongField("I1");
        long i2=header.getLongField("I2");
        long i3=header.getLongField("I3");
        long i4=header.getLongField("I4");

        try{
            header.getLongField("N1");
            assertTrue(false);
        }
        catch(NumberFormatException e)
        {
            assertTrue(true);
        }

        try{
            header.getLongField("N2");
            assertTrue(false);
        }
        catch(NumberFormatException e)
        {
            assertTrue(true);
        }

        assertEquals(42,i1);
        assertEquals(43,i2);
        assertEquals(-44,i3);
        assertEquals(-45,i4);

        header.putLongField("I5", 46);
        header.putLongField("I6",-47);
        assertEquals("46",header.getStringField("I5"));
        assertEquals("-47",header.getStringField("I6"));

    }

    @Test
    public void testContains() throws Exception
    {
        HttpFields header = new HttpFields();

        header.add("0", "");
        header.add("1", ",");
        header.add("2", ",,");
        header.add("3", "abc");
        header.add("4", "def");
        header.add("5", "abc,def,hig");
        header.add("6", "abc");
        header.add("6", "def");
        header.add("6", "hig");

        for (int i=0;i<7;i++)
        {
            assertFalse(""+i,header.contains(""+i,"xyz"));
            assertEquals(""+i,i>=4,header.contains(""+i,"def"));
        }
    }
}
