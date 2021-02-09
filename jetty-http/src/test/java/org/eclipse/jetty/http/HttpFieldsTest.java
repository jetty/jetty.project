//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
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
import static org.hamcrest.Matchers.is;
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
        HttpFields header = new HttpFields();

        header.add("name0", "wrong");
        header.add(HttpHeader.ACCEPT, "nothing");
        header.add("name0", "still wrong");
        header.add(HttpHeader.ACCEPT, "money");

        header.put("name0", "value0");
        header.put(HttpHeader.ACCEPT, "praise");

        assertEquals(2, header.size());
        assertEquals("value0", header.get("name0"));
        assertEquals("praise", header.get("accept"));
        assertNull(header.get("name2"));

        header.add("name0", "wrong");
        header.add(HttpHeader.ACCEPT, "nothing");

        header.put("name0", (String)null);
        header.put(HttpHeader.ACCEPT, (String)null);
        assertEquals(0, header.size());
    }

    @Test
    public void testPutTo()
    {
        HttpFields header = new HttpFields();

        header.put("name0", "value0");
        header.put("name1", "value:A");
        header.add("name1", "value:B");
        header.add("name2", "");

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
    public void testGet()
    {
        HttpFields header = new HttpFields();

        header.put("name0", "value0");
        header.put("name1", "value1");

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
    public void testGetValuesList()
    {
        HttpFields header = new HttpFields();

        header.add("name0", "value0");
        header.add("name1", "value1a");
        header.add(HttpHeader.ACCEPT, "something");
        header.add("name2", "value2");
        header.add("name1", "value1b");
        header.add(HttpHeader.ACCEPT, "everything");

        assertThat(header.getValuesList("unknown").size(), is(0));
        assertThat(header.getValuesList(HttpHeader.CONNECTION).size(), is(0));
        assertThat(header.getValuesList("name0"), contains("value0"));
        assertThat(header.getValuesList("name1"), contains("value1a", "value1b"));
        assertThat(header.getValuesList(HttpHeader.ACCEPT), contains("something", "everything"));

        assertThat(header.getFields(HttpHeader.CONNECTION).size(), is(0));
        assertThat(header.getFields(HttpHeader.ACCEPT).stream().map(HttpField::getValue).collect(Collectors.toList()),
            contains("something", "everything"));
    }

    @Test
    public void testGetKnown()
    {
        HttpFields header = new HttpFields();

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
        HttpFields header = new HttpFields();

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
        HttpFields header = new HttpFields();

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
        HttpFields header = new HttpFields();

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
    public void testRemovePut()
    {
        HttpFields header = new HttpFields(1);

        header.put("name0", "value0");
        header.put("name1", "value1");
        header.put("name2", "value2");

        assertEquals("value0", header.get("name0"));
        assertEquals("value1", header.get("name1"));
        assertEquals("value2", header.get("name2"));

        header.remove("name1");

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
        HttpFields fields = new HttpFields();

        fields.add("name0", "value0");
        fields.add("name1", "valueA");
        fields.add(HttpHeader.ACCEPT, "everything");

        assertEquals("value0", fields.get("name0"));
        assertEquals("valueA", fields.get("name1"));
        assertEquals("everything", fields.get("accept"));

        fields.add("name1", "valueB");
        fields.add(HttpHeader.ACCEPT, "nothing");
        fields.add("name1", null);
        // fields.add(HttpHeader.ACCEPT, (String)null); TODO this one throws IAE.  Should make the same as the others.

        fields.add("name2", "value2");

        assertEquals("value0", fields.get("name0"));
        assertEquals("valueA", fields.get("name1"));
        assertEquals("value2", fields.get("name2"));
        assertNull(fields.get("name3"));

        assertThat(fields.getValuesList("name1"), contains("valueA", "valueB"));
        assertThat(fields.getValuesList(HttpHeader.ACCEPT), contains("everything", "nothing"));

        fields.add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
        fields.add(HttpHeader.CONNECTION, (HttpHeaderValue)null);
        assertThat(fields.getValuesList("Connection"), contains("close"));
    }

    @Test
    public void testAddAll()
    {
        HttpFields fields0 = new HttpFields();
        assertThat(fields0.size(), is(0));
        HttpFields fields1 = new HttpFields(fields0);
        assertThat(fields1.size(), is(0));

        fields0.add("name0", "value0");
        fields0.add("name1", "valueA");
        fields0.add("name2", "value2");

        fields1.addAll(fields0);
        assertThat(fields1.size(), is(3));
        assertThat(fields0, is(fields1));
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

        HttpFields fields = new HttpFields();
        fields.add(X_XSS_PROTECTION_FIELD);

        assertThat("Fields output", fields.toString(), containsString("X-XSS-Protection: 1; mode=block"));
    }

    @Test
    public void testAddFinalHttpField()
    {
        final HttpField X_XSS_PROTECTION_FIELD = new HttpField("X-XSS-Protection", "1; mode=block");

        HttpFields fields = new HttpFields();
        fields.add(X_XSS_PROTECTION_FIELD);

        assertThat("Fields output", fields.toString(), containsString("X-XSS-Protection: 1; mode=block"));
    }

    @Test
    public void testGetValues()
    {
        HttpFields fields = new HttpFields();

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

        //noinspection deprecation
        e = fields.getValues("name0", ",");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0A");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0B");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0C");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value0D");
        assertFalse(e.hasMoreElements());

        //noinspection deprecation
        e = fields.getValues("name1", ",");
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
    public void testAddCSV()
    {
        HttpFields fields = new HttpFields();
        fields.addCSV(HttpHeader.CONNECTION);
        fields.addCSV("name");
        assertThat(fields.size(), is(0));

        fields.addCSV(HttpHeader.CONNECTION, "one");
        fields.addCSV("name", "one");
        assertThat(fields.getValuesList("name"), contains("one"));
        assertThat(fields.getValuesList(HttpHeader.CONNECTION), contains("one"));

        fields.addCSV(HttpHeader.CONNECTION, "two");
        fields.addCSV("name", "two");
        assertThat(fields.getValuesList("name"), contains("one", "two"));
        assertThat(fields.getValuesList(HttpHeader.CONNECTION), contains("one", "two"));

        fields.addCSV(HttpHeader.CONNECTION, "one", "three", "four");
        fields.addCSV("name", "one", "three", "four");
        assertThat(fields.getValuesList("name"), contains("one", "two", "three, four"));
        assertThat(fields.getValuesList(HttpHeader.CONNECTION), contains("one", "two", "three, four"));
    }

    @Test
    public void testGetCSV()
    {
        HttpFields fields = new HttpFields();

        fields.put(HttpHeader.ACCEPT, "valueA, \"value, B\"");
        fields.add(HttpHeader.ACCEPT, "\"valueC\",valueD");
        fields.put("name1", "value1A, \"value\t, 1B\" ");
        fields.add("name1", "\"value1C\",\tvalue1D");

        assertThat(fields.getCSV(HttpHeader.ACCEPT, false), contains("valueA", "value, B", "valueC", "valueD"));
        assertThat(fields.getCSV(HttpHeader.ACCEPT, true), contains("valueA", "\"value, B\"", "\"valueC\"", "valueD"));
        assertThat(fields.getCSV("name1", false), contains("value1A", "value\t, 1B", "value1C", "value1D"));
        assertThat(fields.getCSV("name1", true), contains("value1A", "\"value\t, 1B\"", "\"value1C\"", "value1D"));
    }

    @Test
    public void testAddQuotedCSV()
    {
        HttpFields fields = new HttpFields();

        fields.put("some", "value");
        fields.add("name", "\"zero\"");
        fields.add("name", "one, \"1 + 1\"");
        fields.put("other", "value");
        fields.add("name", "three");
        fields.add("name", "four, I V");

        List<String> list = fields.getCSV("name", false);
        assertEquals(HttpFields.valueParameters(list.get(0), null), "zero");
        assertEquals(HttpFields.valueParameters(list.get(1), null), "one");
        assertEquals(HttpFields.valueParameters(list.get(2), null), "1 + 1");
        assertEquals(HttpFields.valueParameters(list.get(3), null), "three");
        assertEquals(HttpFields.valueParameters(list.get(4), null), "four");
        assertEquals(HttpFields.valueParameters(list.get(5), null), "I V");

        fields.addCSV("name", "six");
        list = fields.getCSV("name", false);
        assertEquals(HttpFields.valueParameters(list.get(0), null), "zero");
        assertEquals(HttpFields.valueParameters(list.get(1), null), "one");
        assertEquals(HttpFields.valueParameters(list.get(2), null), "1 + 1");
        assertEquals(HttpFields.valueParameters(list.get(3), null), "three");
        assertEquals(HttpFields.valueParameters(list.get(4), null), "four");
        assertEquals(HttpFields.valueParameters(list.get(5), null), "I V");
        assertEquals(HttpFields.valueParameters(list.get(6), null), "six");

        fields.addCSV("name", "1 + 1", "7", "zero");
        list = fields.getCSV("name", false);
        assertEquals(HttpFields.valueParameters(list.get(0), null), "zero");
        assertEquals(HttpFields.valueParameters(list.get(1), null), "one");
        assertEquals(HttpFields.valueParameters(list.get(2), null), "1 + 1");
        assertEquals(HttpFields.valueParameters(list.get(3), null), "three");
        assertEquals(HttpFields.valueParameters(list.get(4), null), "four");
        assertEquals(HttpFields.valueParameters(list.get(5), null), "I V");
        assertEquals(HttpFields.valueParameters(list.get(6), null), "six");
        assertEquals(HttpFields.valueParameters(list.get(7), null), "7");
    }

    @Test
    public void testGetQualityCSV()
    {
        HttpFields fields = new HttpFields();

        fields.put("some", "value");
        fields.add("name", "zero;q=0.9,four;q=0.1");
        fields.put("other", "value");
        fields.add("name", "nothing;q=0");
        fields.add("name", "one;q=0.4");
        fields.add("name", "three;x=y;q=0.2;a=b,two;q=0.3");
        fields.add("name", "first;");

        List<String> list = fields.getQualityCSV("name");
        assertEquals(HttpFields.valueParameters(list.get(0), null), "first");
        assertEquals(HttpFields.valueParameters(list.get(1), null), "zero");
        assertEquals(HttpFields.valueParameters(list.get(2), null), "one");
        assertEquals(HttpFields.valueParameters(list.get(3), null), "two");
        assertEquals(HttpFields.valueParameters(list.get(4), null), "three");
        assertEquals(HttpFields.valueParameters(list.get(5), null), "four");
    }

    @Test
    public void testGetQualityCSVHeader()
    {
        HttpFields fields = new HttpFields();

        fields.put("some", "value");
        fields.add("Accept", "zero;q=0.9,four;q=0.1");
        fields.put("other", "value");
        fields.add("Accept", "nothing;q=0");
        fields.add("Accept", "one;q=0.4");
        fields.add("Accept", "three;x=y;q=0.2;a=b,two;q=0.3");
        fields.add("Accept", "first;");

        List<String> list = fields.getQualityCSV(HttpHeader.ACCEPT);
        assertEquals(HttpFields.valueParameters(list.get(0), null), "first");
        assertEquals(HttpFields.valueParameters(list.get(1), null), "zero");
        assertEquals(HttpFields.valueParameters(list.get(2), null), "one");
        assertEquals(HttpFields.valueParameters(list.get(3), null), "two");
        assertEquals(HttpFields.valueParameters(list.get(4), null), "three");
        assertEquals(HttpFields.valueParameters(list.get(5), null), "four");
    }

    @Test
    public void testDateFields()
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
        HttpFields fields = new HttpFields();

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
        HttpFields header = new HttpFields();

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
        HttpFields header = new HttpFields();

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
            assertTrue(header.containsKey("n" + i));
            assertTrue(header.containsKey("N" + i));
            assertFalse(header.contains("n" + i, "xyz"), "" + i);
            assertEquals(i >= 4, header.contains("n" + i, "def"), "" + i);
        }

        assertTrue(header.contains(new HttpField("N5", "def")));
        assertTrue(header.contains(new HttpField("accept", "abc")));
        assertTrue(header.contains(HttpHeader.ACCEPT));
        assertTrue(header.contains(HttpHeader.ACCEPT, "abc"));
        assertFalse(header.contains(new HttpField("N5", "xyz")));
        assertFalse(header.contains(new HttpField("N8", "def")));
        assertFalse(header.contains(HttpHeader.ACCEPT, "def"));
        assertFalse(header.contains(HttpHeader.AGE));
        assertFalse(header.contains(HttpHeader.AGE, "abc"));

        assertFalse(header.containsKey("n11"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Host", "host", "HOST", "HoSt", "Connection", "CONNECTION", "connection", "CoNnEcTiOn"})
    public void testContainsKeyTrue(String keyName)
    {
        HttpFields fields = new HttpFields();
        fields.put("Host", "localhost");
        HttpField namelessField = new HttpField(HttpHeader.CONNECTION, null, "bogus");
        fields.put(namelessField);

        assertTrue(fields.containsKey(keyName), "containsKey('" + keyName + "')");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Content-Type", "Content-Length", "X-Bogus", ""})
    public void testContainsKeyFalse(String keyName)
    {
        HttpFields fields = new HttpFields();
        fields.add("Host", "localhost");
        HttpField namelessField = new HttpField(HttpHeader.CONNECTION, null, "bogus");
        fields.put(namelessField);

        assertFalse(fields.containsKey(keyName), "containsKey('" + keyName + "')");
    }

    @Test
    public void testPreventNullFieldName()
    {
        HttpFields fields = new HttpFields();
        assertThrows(NullPointerException.class, () ->
        {
            HttpField nullNullField = new HttpField(null, null, "bogus");
            fields.put(nullNullField);
        });
    }

    @Test
    public void testAddNullName()
    {
        HttpFields fields = new HttpFields();
        assertThrows(NullPointerException.class, () -> fields.add((String)null, "bogus"));
        assertThat(fields.size(), is(0));

        assertThrows(NullPointerException.class, () -> fields.add((HttpHeader)null, "bogus"));
        assertThat(fields.size(), is(0));
    }

    @Test
    public void testPutNullName()
    {
        HttpFields fields = new HttpFields();
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
        HttpFields fields = new HttpFields();

        fields.put("name", (List<String>)null);
        assertThat(fields.size(), is(0));
    }

    @Test
    public void testPreventNullField()
    {
        // Attempt various ways that may have put a null field in the array that
        // previously caused a NPE in put.
        HttpFields fields = new HttpFields();
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
    public void testIteration()
    {
        HttpFields header = new HttpFields(5);
        Iterator<HttpField> i = header.iterator();
        assertThat(i.hasNext(), is(false));

        header.put("name1", "valueA");
        header.put("name2", "valueB");
        header.add("name3", "valueC");

        i = header.iterator();
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

        ListIterator<HttpField> l = header.listIterator();
        assertThat(l.hasNext(), is(true));
        assertThat(l.nextIndex(), is(0));
        assertThat(l.previousIndex(), is(-1));

        l.add(new HttpField("name0", "value"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.nextIndex(), is(1));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previousIndex(), is(0));

        assertThat(l.next().getName(), is("name1"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.nextIndex(), is(2));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previousIndex(), is(1));

        l.set(new HttpField("NAME1", "value"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.nextIndex(), is(2));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previousIndex(), is(1));

        assertThat(l.previous().getName(), is("NAME1"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.nextIndex(), is(1));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previousIndex(), is(0));

        assertThat(l.previous().getName(), is("name0"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.nextIndex(), is(0));
        assertThat(l.hasPrevious(), is(false));
        assertThat(l.previousIndex(), is(-1));

        assertThat(l.next().getName(), is("name0"));
        assertThat(l.hasNext(), is(true));
        assertThat(l.nextIndex(), is(1));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previousIndex(), is(0));

        assertThat(l.next().getName(), is("NAME1"));
        l.add(new HttpField("name2", "value"));
        assertThat(l.next().getName(), is("name3"));
        assertThat(l.hasNext(), is(false));
        assertThat(l.nextIndex(), is(4));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previousIndex(), is(3));

        l.add(new HttpField("name4", "value"));
        assertThat(l.hasNext(), is(false));
        assertThat(l.nextIndex(), is(5));
        assertThat(l.hasPrevious(), is(true));
        assertThat(l.previousIndex(), is(4));
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
        HttpFields header = new HttpFields();
        assertThat(header.stream().count(), is(0L));
        header.put("name1", "valueA");
        header.put("name2", "valueB");
        header.add("name3", "valueC");
        assertThat(header.stream().count(), is(3L));
        assertThat(header.stream().map(HttpField::getName).filter("name2"::equalsIgnoreCase).count(), is(1L));
    }

    @Test
    public void testComputeField()
    {
        HttpFields header = new HttpFields();
        assertThat(header.size(), is(0));

        header.computeField("Test", (n, f) -> null);
        assertThat(header.size(), is(0));

        header.add(new HttpField("Before", "value"));
        assertThat(header.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value"));

        header.computeField("Test", (n, f) -> new HttpField(n, "one"));
        assertThat(header.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "Test: one"));

        header.add(new HttpField("After", "value"));
        assertThat(header.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "Test: one", "After: value"));

        header.add(new HttpField("Test", "two"));
        header.add(new HttpField("Test", "three"));
        assertThat(header.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "Test: one", "After: value", "Test: two", "Test: three"));

        header.computeField("Test", (n, f) -> new HttpField("TEST", "count=" + f.size()));
        assertThat(header.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "TEST: count=3", "After: value"));

        header.computeField("TEST", (n, f) -> null);
        assertThat(header.stream().map(HttpField::toString).collect(Collectors.toList()), contains("Before: value", "After: value"));
    }
}
