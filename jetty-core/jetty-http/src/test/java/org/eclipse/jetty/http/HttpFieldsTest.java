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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpFieldsTest
{
    @Test
    public void testPut()
    {
        HttpFields.Mutable header = HttpFields.build()
            .put("name0", "value:0")
            .put("name1", "value1");

        assertEquals(2, header.size());
        assertEquals("value:0", header.get("name0"));
        assertEquals("value1", header.get("name1"));
        assertNull(header.get("name2"));

        int matches = 0;
        Enumeration<String> e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o = e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
        }
        assertEquals(2, matches);

        e = header.getValues("name0");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value:0");
        assertFalse(e.hasMoreElements());
    }

    @Test
    public void testPutTo()
    {
        HttpFields.Mutable header = HttpFields.build()
            .put("name0", "value0")
            .put("name1", "value:A")
            .add("name1", "value:B")
            .add("name2", "");

        ByteBuffer buffer = BufferUtil.allocate(1024);
        BufferUtil.flipToFill(buffer);
        HttpGenerator.putTo(header, buffer);
        BufferUtil.flipToFlush(buffer, 0);
        String result = BufferUtil.toString(buffer);

        assertThat(result, Matchers.containsString("name0: value0"));
        assertThat(result, Matchers.containsString("name1: value:A"));
        assertThat(result, Matchers.containsString("name1: value:B"));
    }

    @Test
    public void testImmutable()
    {
        HttpFields header = HttpFields.build()
            .put("name0", "value0")
            .put("name1", "value1").asImmutable();

        assertEquals("value0", header.get("name0"));
        assertEquals("value0", header.get("Name0"));
        assertEquals("value1", header.get("name1"));
        assertEquals("value1", header.get("Name1"));
        assertNull(header.get("Name2"));

        assertEquals("value0", header.getField("name0").getValue());
        assertEquals("value0", header.getField("Name0").getValue());
        assertEquals("value1", header.getField("name1").getValue());
        assertEquals("value1", header.getField("Name1").getValue());
        assertNull(header.getField("Name2"));

        assertEquals("value0", header.getField(0).getValue());
        assertEquals("value1", header.getField(1).getValue());
        assertThrows(NoSuchElementException.class, () -> header.getField(2));
    }

    @Test
    public void testMutable()
    {
        HttpFields headers = HttpFields.build()
            .add(HttpHeader.ETAG, "tag")
            .add("name0", "value0")
            .add("name1", "value1").asImmutable();

        headers = HttpFields.build(headers, EnumSet.of(HttpHeader.ETAG, HttpHeader.CONTENT_RANGE))
            .add(new PreEncodedHttpField(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()))
            .addDateField("name2", System.currentTimeMillis()).asImmutable();

        headers = HttpFields.build(headers, new HttpField(HttpHeader.CONNECTION, "open"));

        assertThat(headers.size(), is(4));
        assertThat(headers.getField(0).getValue(), is("value0"));
        assertThat(headers.getField(1).getValue(), is("value1"));
        assertThat(headers.getField(2).getValue(), is("open"));
        assertThat(headers.getField(3).getName(), is("name2"));
    }

    @Test
    public void testMap()
    {
        Map<HttpFields.Immutable, String> map = new HashMap<>();
        map.put(HttpFields.build().add("X", "1").add(HttpHeader.ETAG, "tag").asImmutable(), "1");
        map.put(HttpFields.build().add("X", "2").add(HttpHeader.ETAG, "other").asImmutable(), "2");

        assertThat(map.get(HttpFields.build().add("X", "1").add(HttpHeader.ETAG, "tag").asImmutable()), is("1"));
        assertThat(map.get(HttpFields.build().add("X", "2").add(HttpHeader.ETAG, "other").asImmutable()), is("2"));
        assertThat(map.get(HttpFields.build().add("X", "2").asImmutable()), nullValue());
        assertThat(map.get(HttpFields.build().add("X", "2").add(HttpHeader.ETAG, "tag").asImmutable()), nullValue());
    }

    @Test
    public void testGet()
    {
        HttpFields header = HttpFields.build()
            .put("name0", "value0")
            .put("name1", "value1");

        assertEquals("value0", header.get("name0"));
        assertEquals("value0", header.get("Name0"));
        assertEquals("value1", header.get("name1"));
        assertEquals("value1", header.get("Name1"));
        assertNull(header.get("Name2"));

        assertEquals("value0", header.getField("name0").getValue());
        assertEquals("value0", header.getField("Name0").getValue());
        assertEquals("value1", header.getField("name1").getValue());
        assertEquals("value1", header.getField("Name1").getValue());
        assertNull(header.getField("Name2"));

        assertEquals("value0", header.getField(0).getValue());
        assertEquals("value1", header.getField(1).getValue());
        assertThrows(NoSuchElementException.class, () -> header.getField(2));
    }

    @Test
    public void testGetKnown()
    {
        HttpFields.Mutable header = HttpFields.build();

        header.put("Connection", "value0");
        header.put(HttpHeader.ACCEPT, "value1");

        assertEquals("value0", header.get(HttpHeader.CONNECTION));
        assertEquals("value1", header.get(HttpHeader.ACCEPT));

        assertEquals("value0", header.getField(HttpHeader.CONNECTION).getValue());
        assertEquals("value1", header.getField(HttpHeader.ACCEPT).getValue());

        assertNull(header.getField(HttpHeader.AGE));
        assertNull(header.get(HttpHeader.AGE));
    }

    @Test
    public void testCRLF()
    {
        HttpFields.Mutable header = HttpFields.build();

        header.put("name0", "value\r\n0");
        header.put("name\r\n1", "value1");
        header.put("name:2", "value:\r\n2");

        ByteBuffer buffer = BufferUtil.allocate(1024);
        BufferUtil.flipToFill(buffer);
        HttpGenerator.putTo(header, buffer);
        BufferUtil.flipToFlush(buffer, 0);
        String out = BufferUtil.toString(buffer);
        assertThat(out, containsString("name0: value  0"));
        assertThat(out, containsString("name??1: value1"));
        assertThat(out, containsString("name?2: value:  2"));
    }

    @Test
    public void testCachedPut()
    {
        HttpFields.Mutable header = HttpFields.build();

        header.put("Connection", "Keep-Alive");
        header.put("tRansfer-EncOding", "CHUNKED");
        header.put("CONTENT-ENCODING", "gZIP");

        ByteBuffer buffer = BufferUtil.allocate(1024);
        BufferUtil.flipToFill(buffer);
        HttpGenerator.putTo(header, buffer);
        BufferUtil.flipToFlush(buffer, 0);
        String out = BufferUtil.toString(buffer).toLowerCase(Locale.ENGLISH);

        assertThat(out, Matchers.containsString((HttpHeader.CONNECTION + ": " + HttpHeaderValue.KEEP_ALIVE).toLowerCase(Locale.ENGLISH)));
        assertThat(out, Matchers.containsString((HttpHeader.TRANSFER_ENCODING + ": " + HttpHeaderValue.CHUNKED).toLowerCase(Locale.ENGLISH)));
        assertThat(out, Matchers.containsString((HttpHeader.CONTENT_ENCODING + ": " + HttpHeaderValue.GZIP).toLowerCase(Locale.ENGLISH)));
    }

    @Test
    public void testRePut()
    {
        HttpFields.Mutable header = HttpFields.build();

        header.put("name0", "value0");
        header.put("name1", "xxxxxx");
        header.put("name2", "value2");

        assertEquals("value0", header.get("name0"));
        assertEquals("xxxxxx", header.get("name1"));
        assertEquals("value2", header.get("name2"));

        header.put("name1", "value1");

        assertEquals("value0", header.get("name0"));
        assertEquals("value1", header.get("name1"));
        assertEquals("value2", header.get("name2"));
        assertNull(header.get("name3"));

        int matches = 0;
        Enumeration<String> e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            String o = e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(3, matches);

        e = header.getValues("name1");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value1");
        assertFalse(e.hasMoreElements());
    }

    @Test
    public void testRemove()
    {
        HttpFields.Mutable header = HttpFields.build(1)
            .put("name0", "value0")
            .add(HttpHeader.CONTENT_TYPE, "text")
            .add("name1", "WRONG")
            .add(HttpHeader.EXPECT, "spanish inquisition")
            .put("name1", "value1")
            .add(HttpHeader.ETAG, "tag")
            .put("name2", "value2");

        assertEquals("value0", header.get("name0"));
        assertEquals("text", header.get(HttpHeader.CONTENT_TYPE));
        assertEquals("value1", header.get("name1"));
        assertEquals("spanish inquisition", header.get(HttpHeader.EXPECT));
        assertEquals("tag", header.get(HttpHeader.ETAG));
        assertEquals("value2", header.get("name2"));

        header.remove("name1");
        header.remove(HttpHeader.ETAG);
        header.remove(EnumSet.of(HttpHeader.CONTENT_TYPE, HttpHeader.EXPECT, HttpHeader.EXPIRES));

        assertEquals("value0", header.get("name0"));
        assertNull(header.get("name1"));
        assertEquals("value2", header.get("name2"));
        assertNull(header.get("name3"));

        int matches = 0;
        Enumeration<String> e = header.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o = e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(2, matches);

        e = header.getValues("name1");
        assertFalse(e.hasMoreElements());
    }

    @Test
    public void testAdd()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.add("name0", "value0");
        fields.add("name1", "valueA");
        fields.add("name2", "value2");

        assertEquals("value0", fields.get("name0"));
        assertEquals("valueA", fields.get("name1"));
        assertEquals("value2", fields.get("name2"));

        fields.add("name1", "valueB");

        assertEquals("value0", fields.get("name0"));
        assertEquals("valueA", fields.get("name1"));
        assertEquals("value2", fields.get("name2"));
        assertNull(fields.get("name3"));

        int matches = 0;
        Enumeration<String> e = fields.getFieldNames();
        while (e.hasMoreElements())
        {
            Object o = e.nextElement();
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(3, matches);

        e = fields.getValues("name1");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "valueA");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "valueB");
        assertFalse(e.hasMoreElements());
    }

    @Test
    public void testPreEncodedField()
    {
        ByteBuffer buffer = BufferUtil.allocate(1024);

        PreEncodedHttpField known = new PreEncodedHttpField(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
        BufferUtil.clearToFill(buffer);
        known.putTo(buffer, HttpVersion.HTTP_1_1);
        BufferUtil.flipToFlush(buffer, 0);
        assertThat(BufferUtil.toString(buffer), is("Connection: close\r\n"));

        PreEncodedHttpField unknown = new PreEncodedHttpField(null, "Header", "Value");
        BufferUtil.clearToFill(buffer);
        unknown.putTo(buffer, HttpVersion.HTTP_1_1);
        BufferUtil.flipToFlush(buffer, 0);
        assertThat(BufferUtil.toString(buffer), is("Header: Value\r\n"));
    }

    @Test
    public void testAddPreEncodedField()
    {
        final PreEncodedHttpField X_XSS_PROTECTION_FIELD = new PreEncodedHttpField("X-XSS-Protection", "1; mode=block");

        HttpFields.Mutable fields = HttpFields.build();
        fields.add(X_XSS_PROTECTION_FIELD);

        assertThat("Fields output", fields.toString(), containsString("X-XSS-Protection: 1; mode=block"));
    }

    @Test
    public void testAddFinalHttpField()
    {
        final HttpField X_XSS_PROTECTION_FIELD = new HttpField("X-XSS-Protection", "1; mode=block");

        HttpFields.Mutable fields = HttpFields.build();
        fields.add(X_XSS_PROTECTION_FIELD);

        assertThat("Fields output", fields.toString(), containsString("X-XSS-Protection: 1; mode=block"));
    }

    @Test
    public void testGetValues()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.put("name0", "value0A,value0B");
        fields.add("name0", "value0C,value0D");
        fields.put("name1", "value1A, \"value\t, 1B\" ");
        fields.add("name1", "\"value1C\",\tvalue1D");

        Enumeration<String> e = fields.getValues("name0");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0A,value0B");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0C,value0D");
        assertFalse(e.hasMoreElements());

        e = Collections.enumeration(fields.getCSV("name0", false));
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0A");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0B");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0C");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0D");
        assertFalse(e.hasMoreElements());

        e = Collections.enumeration(fields.getCSV("name1", false));
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value1A");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value\t, 1B");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value1C");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value1D");
        assertFalse(e.hasMoreElements());
    }

    @Test
    public void testGetCSV()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.put("name0", "value0A,value0B");
        fields.add("name0", "value0C,value0D");
        fields.put("name1", "value1A, \"value\t, 1B\" ");
        fields.add("name1", "\"value1C\",\tvalue1D");

        Enumeration<String> e = fields.getValues("name0");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0A,value0B");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0C,value0D");
        assertFalse(e.hasMoreElements());

        e = Collections.enumeration(fields.getCSV("name0", false));
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0A");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0B");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0C");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0D");
        assertFalse(e.hasMoreElements());

        e = Collections.enumeration(fields.getCSV("name1", false));
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value1A");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value\t, 1B");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value1C");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value1D");
        assertFalse(e.hasMoreElements());
    }

    @Test
    public void testAddQuotedCSV()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.put("some", "value");
        fields.add("name", "\"zero\"");
        fields.add("name", "one, \"1 + 1\"");
        fields.put("other", "value");
        fields.add("name", "three");
        fields.add("name", "four, I V");

        List<String> list = fields.getCSV("name", false);
        assertEquals(HttpField.valueParameters(list.get(0), null), "zero");
        assertEquals(HttpField.valueParameters(list.get(1), null), "one");
        assertEquals(HttpField.valueParameters(list.get(2), null), "1 + 1");
        assertEquals(HttpField.valueParameters(list.get(3), null), "three");
        assertEquals(HttpField.valueParameters(list.get(4), null), "four");
        assertEquals(HttpField.valueParameters(list.get(5), null), "I V");

        fields.addCSV("name", "six");
        list = fields.getCSV("name", false);
        assertEquals(HttpField.valueParameters(list.get(0), null), "zero");
        assertEquals(HttpField.valueParameters(list.get(1), null), "one");
        assertEquals(HttpField.valueParameters(list.get(2), null), "1 + 1");
        assertEquals(HttpField.valueParameters(list.get(3), null), "three");
        assertEquals(HttpField.valueParameters(list.get(4), null), "four");
        assertEquals(HttpField.valueParameters(list.get(5), null), "I V");
        assertEquals(HttpField.valueParameters(list.get(6), null), "six");

        fields.addCSV("name", "1 + 1", "7", "zero");
        list = fields.getCSV("name", false);
        assertEquals(HttpField.valueParameters(list.get(0), null), "zero");
        assertEquals(HttpField.valueParameters(list.get(1), null), "one");
        assertEquals(HttpField.valueParameters(list.get(2), null), "1 + 1");
        assertEquals(HttpField.valueParameters(list.get(3), null), "three");
        assertEquals(HttpField.valueParameters(list.get(4), null), "four");
        assertEquals(HttpField.valueParameters(list.get(5), null), "I V");
        assertEquals(HttpField.valueParameters(list.get(6), null), "six");
        assertEquals(HttpField.valueParameters(list.get(7), null), "7");

        fields.addCSV(HttpHeader.ACCEPT, "en", "it");
        list = fields.getCSV(HttpHeader.ACCEPT, false);
        assertEquals(HttpField.valueParameters(list.get(0), null), "en");
        assertEquals(HttpField.valueParameters(list.get(1), null), "it");
        fields.addCSV(HttpHeader.ACCEPT, "en", "it");
    }

    @Test
    public void testGetQualityCSV()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.put("some", "value");
        fields.add("name", "zero;q=0.9,four;q=0.1");
        fields.put("other", "value");
        fields.add("name", "nothing;q=0");
        fields.add("name", "one;q=0.4");
        fields.add("name", "three;x=y;q=0.2;a=b,two;q=0.3");
        fields.add("name", "first;");

        List<String> list = fields.getQualityCSV("name");
        assertEquals(HttpField.valueParameters(list.get(0), null), "first");
        assertEquals(HttpField.valueParameters(list.get(1), null), "zero");
        assertEquals(HttpField.valueParameters(list.get(2), null), "one");
        assertEquals(HttpField.valueParameters(list.get(3), null), "two");
        assertEquals(HttpField.valueParameters(list.get(4), null), "three");
        assertEquals(HttpField.valueParameters(list.get(5), null), "four");
    }

    @Test
    public void testGetQualityCSVHeader()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.put("some", "value");
        fields.add("Accept", "zero;q=0.9,four;q=0.1");
        fields.put("other", "value");
        fields.add("Accept", "nothing;q=0");
        fields.add("Accept", "one;q=0.4");
        fields.add("Accept", "three;x=y;q=0.2;a=b,two;q=0.3");
        fields.add("Accept", "first;");

        List<String> list = fields.getQualityCSV(HttpHeader.ACCEPT);
        assertEquals(HttpField.valueParameters(list.get(0), null), "first");
        assertEquals(HttpField.valueParameters(list.get(1), null), "zero");
        assertEquals(HttpField.valueParameters(list.get(2), null), "one");
        assertEquals(HttpField.valueParameters(list.get(3), null), "two");
        assertEquals(HttpField.valueParameters(list.get(4), null), "three");
        assertEquals(HttpField.valueParameters(list.get(5), null), "four");
    }

    @Test
    public void testDateFields()
    {
        HttpFields.Mutable fields = HttpFields.build();

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
        assertTrue(d0 != -1);
        assertTrue(d1 > 0);
        assertTrue(d2 > 0);
        assertEquals(d1, d2);
        assertEquals(d2, d3);
        assertEquals(d3 + 2000, d4);
        assertEquals(951825600000L, d5);

        d1 = fields.getDateField("D1");
        d2 = fields.getDateField("D2");
        d3 = fields.getDateField("D3");
        d4 = fields.getDateField("D4");
        d5 = fields.getDateField("D5");
        assertTrue(d1 > 0);
        assertTrue(d2 > 0);
        assertEquals(d1, d2);
        assertEquals(d2, d3);
        assertEquals(d3 + 2000, d4);
        assertEquals(951825600000L, d5);

        fields.putDateField("D2", d1);
        assertEquals("Fri, 31 Dec 1999 23:59:59 GMT", fields.get("D2"));
    }

    @Test
    public void testNegDateFields()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.putDateField("Dzero", 0);
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", fields.get("Dzero"));

        fields.putDateField("Dminus", -1);
        assertEquals("Wed, 31 Dec 1969 23:59:59 GMT", fields.get("Dminus"));

        fields.putDateField("Dminus", -1000);
        assertEquals("Wed, 31 Dec 1969 23:59:59 GMT", fields.get("Dminus"));

        fields.putDateField("Dancient", Long.MIN_VALUE);
        assertEquals("Sun, 02 Dec 55 16:47:04 GMT", fields.get("Dancient"));
    }

    @Test
    public void testLongFields()
    {
        HttpFields.Mutable header = HttpFields.build();

        header.put("I1", "42");
        header.put("I2", " 43 99");
        header.put("I3", "-44");
        header.put("I4", " - 45abc");
        header.put("N1", " - ");
        header.put("N2", "xx");

        long i1 = header.getLongField("I1");
        assertThrows(NumberFormatException.class, () -> header.getLongField("I2"));
        long i3 = header.getLongField("I3");

        assertThrows(NumberFormatException.class, () -> header.getLongField("I4"));
        assertThrows(NumberFormatException.class, () -> header.getLongField("N1"));
        assertThrows(NumberFormatException.class, () -> header.getLongField("N2"));

        assertEquals(42, i1);
        assertEquals(-44, i3);

        header.putLongField("I5", 46);
        header.putLongField("I6", -47);
        assertEquals("46", header.get("I5"));
        assertEquals("-47", header.get("I6"));
    }

    @Test
    public void testContains()
    {
        HttpFields.Mutable header = HttpFields.build();

        header.add("n0", "");
        header.add("n1", ",");
        header.add("n2", ",,");
        header.add("N3", "abc");
        header.add("N4", "def");
        header.add("n5", "abc,def,hig");
        header.add("N6", "abc");
        header.add("n6", "def");
        header.add("N6", "hig");
        header.add("n7", "abc ,  def;q=0.9  ,  hig");
        header.add("n8", "abc ,  def;q=0  ,  hig");
        header.add(HttpHeader.ACCEPT, "abc ,  def;q=0  ,  hig");

        for (int i = 0; i < 8; i++)
        {
            assertTrue(header.contains("n" + i));
            assertTrue(header.contains("N" + i));
            assertFalse(header.contains("n" + i, "xyz"), "" + i);
            assertEquals(i >= 4, header.contains("n" + i, "def"), "" + i);
        }

        assertTrue(header.contains(new HttpField("N5", "def")));
        assertTrue(header.contains(new HttpField("accept", "abc")));
        assertTrue(header.contains(HttpHeader.ACCEPT, "abc"));
        assertFalse(header.contains(new HttpField("N5", "xyz")));
        assertFalse(header.contains(new HttpField("N8", "def")));
        assertFalse(header.contains(HttpHeader.ACCEPT, "def"));
        assertFalse(header.contains(HttpHeader.AGE, "abc"));

        assertFalse(header.contains("n11"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Host", "host", "HOST", "HoSt", "Connection", "CONNECTION", "connection", "CoNnEcTiOn"})
    public void testContainsKeyTrue(String keyName)
    {
        HttpFields.Mutable fields = HttpFields.build();
        fields.put("Host", "localhost");
        HttpField namelessField = new HttpField(HttpHeader.CONNECTION, null, "bogus");
        fields.put(namelessField);

        assertTrue(fields.contains(keyName), "containsKey('" + keyName + "')");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Content-Type", "Content-Length", "X-Bogus", ""})
    public void testContainsKeyFalse(String keyName)
    {
        HttpFields.Mutable fields = HttpFields.build();
        fields.add("Host", "localhost");
        HttpField namelessField = new HttpField(HttpHeader.CONNECTION, null, "bogus");
        fields.put(namelessField);

        assertFalse(fields.contains(keyName), "containsKey('" + keyName + "')");
    }

    @Test
    public void testAddNullName()
    {
        HttpFields.Mutable fields = HttpFields.build();
        assertThrows(NullPointerException.class, () -> fields.add((String)null, "bogus"));
        assertThat(fields.size(), is(0));

        assertThrows(NullPointerException.class, () -> fields.add((HttpHeader)null, "bogus"));
        assertThat(fields.size(), is(0));
    }

    @Test
    public void testAddHttpFields()
    {
        HttpFields.Mutable fields = new HttpFields.Mutable(new HttpFields.Mutable());
        fields.add("One", "1");

        fields = new HttpFields.Mutable(fields);

        fields.add(HttpFields.build().add("two", "2").add("three", "3"));
        fields.add(HttpFields.build().add("four", "4").add("five", "5").asImmutable());

        assertThat(fields.size(), is(5));
        assertThat(fields.get("one"), is("1"));
        assertThat(fields.get("two"), is("2"));
        assertThat(fields.get("three"), is("3"));
        assertThat(fields.get("four"), is("4"));
        assertThat(fields.get("five"), is("5"));
    }

    @Test
    public void testPutNullName()
    {
        HttpFields.Mutable fields = HttpFields.build();
        assertThrows(NullPointerException.class, () -> fields.put((String)null, "bogus"));
        assertThat(fields.size(), is(0));

        assertThrows(NullPointerException.class, () -> fields.put(null, (List<String>)null));
        assertThat(fields.size(), is(0));

        List<String> emptyList = new ArrayList<>();
        assertThrows(NullPointerException.class, () -> fields.put(null, emptyList));
        assertThat(fields.size(), is(0));

        assertThrows(NullPointerException.class, () -> fields.put((HttpHeader)null, "bogus"));
        assertThat(fields.size(), is(0));
    }

    @Test
    public void testPutNullValueList()
    {
        HttpFields.Mutable fields = HttpFields.build();

        assertThrows(NullPointerException.class, () -> fields.put("name", (List<String>)null));
        assertThat(fields.size(), is(0));
    }

    @Test
    public void testPreventNullFieldEntry()
    {
        // Attempt various ways that may have put a null field in the array that
        // previously caused a NPE in put.
        HttpFields.Mutable fields = HttpFields.build();
        fields.add((HttpField)null); // should not result in field being added
        assertThat(fields.size(), is(0));
        fields.put(null); // should not result in field being added
        assertThat(fields.size(), is(0));
        fields.put("something", "else");
        assertThat(fields.size(), is(1));
        ListIterator<HttpField> iter = fields.listIterator();
        iter.next();
        iter.set(null); // set field to null - should result in noop
        assertThat(fields.size(), is(0));
        iter.add(null); // attempt to add null entry
        assertThat(fields.size(), is(0));
        fields.put("something", "other");
        assertThat(fields.size(), is(1));
        iter = fields.listIterator();
        iter.next();
        iter.remove(); // remove only entry
        assertThat(fields.size(), is(0));
        fields.put("something", "other");
        assertThat(fields.size(), is(1));
        fields.clear();
    }

    @Test
    public void testPreventNullField()
    {
        HttpFields.Mutable fields = HttpFields.build();
        assertThrows(NullPointerException.class, () ->
        {
            HttpField nullNullField = new HttpField(null, null, "bogus");
            fields.put(nullNullField);
        });
    }

    @Test
    public void testIteration()
    {
        HttpFields.Mutable header = HttpFields.build();
        Iterator<HttpField> i = header.iterator();
        assertThat(i.hasNext(), is(false));

        header.add("REMOVE", "ME")
            .add("name1", "valueA")
            .add("name2", "valueB")
            .add("name3", "valueC");

        i = header.iterator();

        assertThat(i.hasNext(), is(true));
        assertThat(i.next().getName(), is("REMOVE"));
        i.remove();

        assertThat(i.hasNext(), is(true));
        assertThat(i.next().getName(), is("name1"));
        assertThat(i.next().getName(), is("name2"));
        i.remove();
        assertThat(i.next().getName(), is("name3"));
        assertThat(i.hasNext(), is(false));

        i = header.iterator();
        assertThat(i.hasNext(), is(true));
        assertThat(i.next().getName(), is("name1"));
        assertThat(i.next().getName(), is("name3"));
        assertThat(i.hasNext(), is(false));

        header.add("REMOVE", "ME");
        ListIterator<HttpField> l = header.listIterator();
        assertThat(l.hasNext(), is(true));
        l.add(new HttpField("name0", "value"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.next().getName(), is("name1"));
        l.set(new HttpField("NAME1", "value"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previous().getName(), is("NAME1"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previous().getName(), is("name0"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.hasPrevious(), is(false));
        assertThat(l.next().getName(), is("name0"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.next().getName(), is("NAME1"));
        l.add(new HttpField("name2", "value"));
        assertThat(l.next().getName(), is("name3"));

        assertThat(l.hasNext(), is(true));
        assertThat(l.next().getName(), is("REMOVE"));
        l.remove();

        assertThat(l.hasNext(), is(false));
        assertThat(l.hasPrevious(), is(true));
        l.add(new HttpField("name4", "value"));
        assertThat(l.hasNext(), is(false));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previous().getName(), is("name4"));

        i = header.iterator();
        assertThat(i.hasNext(), is(true));
        assertThat(i.next().getName(), is("name0"));
        assertThat(i.next().getName(), is("NAME1"));
        assertThat(i.next().getName(), is("name2"));
        assertThat(i.next().getName(), is("name3"));
        assertThat(i.next().getName(), is("name4"));
        assertThat(i.hasNext(), is(false));
    }

    @Test
    public void testStream()
    {
        HttpFields.Mutable fields = HttpFields.build();
        assertThat(fields.stream().count(), is(0L));
        fields.put("name1", "valueA");
        fields.put("name2", "valueB");
        fields.add("name3", "valueC");
        assertThat(fields.stream().count(), is(3L));
        assertThat(fields.stream().map(HttpField::getName).filter("name2"::equalsIgnoreCase).count(), is(1L));
    }

    @Test
    public void testComputeField()
    {
        HttpFields.Mutable fields = HttpFields.build();
        assertThat(fields.size(), is(0));
        fields.computeField("Test", (n, f) -> null);
        assertThat(fields.size(), is(0));

        fields.add(new HttpField("Before", "value"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value"));

        fields.computeField("Test", (n, f) -> new HttpField(n, "one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "Test: one"));

        fields.add(new HttpField("After", "value"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "Test: one", "After: value"));

        fields.add(new HttpField("Test", "two"));
        fields.add(new HttpField("Test", "three"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "Test: one", "After: value", "Test: two", "Test: three"));

        fields.computeField("Test", (n, f) -> new HttpField("TEST", "count=" + f.size()));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "TEST: count=3", "After: value"));

        fields.computeField("TEST", (n, f) -> null);
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "After: value"));
    }

    @Test
    public void testEnsureSingleValue()
    {
        HttpFields.Mutable fields = HttpFields.build();

        // 0 existing case
        assertThat(fields.size(), is(0));
        fields.ensureField(new PreEncodedHttpField(HttpHeader.VARY, "one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one"));
        assertThat(fields.getField(0), instanceOf(PreEncodedHttpField.class));

        // 1 existing cases
        fields.ensureField(new HttpField(HttpHeader.VARY, "one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one"));
;
        fields.ensureField(new HttpField(HttpHeader.VARY, "two"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two"));

        // many existing cases
        fields.put(new HttpField(HttpHeader.VARY, "one"));
        fields.add(new HttpField(HttpHeader.VARY, "two"));
        fields.ensureField(new HttpField(HttpHeader.VARY, "one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two"));

        fields.put(new HttpField(HttpHeader.VARY, "one"));
        fields.add(new HttpField(HttpHeader.VARY, "two"));
        fields.ensureField(new HttpField(HttpHeader.VARY, "three"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two, three"));
    }

    @Test
    public void testEnsureMultiValue()
    {
        HttpFields.Mutable fields = HttpFields.build();

        // zero existing case
        assertThat(fields.size(), is(0));
        fields.ensureField(new PreEncodedHttpField(HttpHeader.VARY, "one, two"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two"));
        assertThat(fields.getField(0), instanceOf(PreEncodedHttpField.class));

        // one existing cases
        fields.ensureField(new HttpField(HttpHeader.VARY, "two, one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two"));

        fields.ensureField(new HttpField(HttpHeader.VARY, "three, one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two, three"));

        fields.ensureField(new HttpField(HttpHeader.VARY, "four, five"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two, three, four, five"));

        // many existing cases
        fields.put(new HttpField(HttpHeader.VARY, "one"));
        fields.add(new HttpField(HttpHeader.VARY, "two"));
        fields.ensureField(new HttpField(HttpHeader.VARY, "two, one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two"));

        fields.put(new HttpField(HttpHeader.VARY, "one"));
        fields.add(new HttpField(HttpHeader.VARY, "two"));
        fields.ensureField(new HttpField(HttpHeader.VARY, "three, two"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two, three"));

        fields.put(new HttpField(HttpHeader.VARY, "one"));
        fields.add(new HttpField(HttpHeader.VARY, "two"));
        fields.ensureField(new HttpField(HttpHeader.VARY, "three, four"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Vary: one, two, three, four"));
    }

    @Test
    public void testEnsureStringSingleValue()
    {
        HttpFields.Mutable fields = HttpFields.build();

        // 0 existing case
        assertThat(fields.size(), is(0));
        fields.ensureField(new PreEncodedHttpField("Test", "one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one"));
        assertThat(fields.getField(0), instanceOf(PreEncodedHttpField.class));

        // 1 existing cases
        fields.ensureField(new HttpField("Test", "one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one"));
        ;
        fields.ensureField(new HttpField("Test", "two"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two"));

        // many existing cases
        fields.put(new HttpField("Test", "one"));
        fields.add(new HttpField("Test", "two"));
        fields.ensureField(new HttpField("Test", "one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two"));

        fields.put(new HttpField("Test", "one"));
        fields.add(new HttpField("Test", "two"));
        fields.ensureField(new HttpField("Test", "three"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two, three"));
    }

    @Test
    public void testEnsureStringMultiValue()
    {
        HttpFields.Mutable fields = HttpFields.build();

        // zero existing case
        assertThat(fields.size(), is(0));
        fields.ensureField(new PreEncodedHttpField("Test", "one, two"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two"));
        assertThat(fields.getField(0), instanceOf(PreEncodedHttpField.class));

        // one existing cases
        fields.ensureField(new HttpField("Test", "two, one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two"));

        fields.ensureField(new HttpField("Test", "three, one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two, three"));

        fields.ensureField(new HttpField("Test", "four, five"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two, three, four, five"));

        // many existing cases
        fields.put(new HttpField("Test", "one"));
        fields.add(new HttpField("Test", "two"));
        fields.ensureField(new HttpField("Test", "two, one"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two"));

        fields.put(new HttpField("Test", "one"));
        fields.add(new HttpField("Test", "two"));
        fields.ensureField(new HttpField("Test", "three, two"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two, three"));

        fields.put(new HttpField("Test", "one"));
        fields.add(new HttpField("Test", "two"));
        fields.ensureField(new HttpField("Test", "three, four"));
        assertThat(fields.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Test: one, two, three, four"));
    }
}