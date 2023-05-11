//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpFieldTest
{
    @Test
    public void testContainsSimple()
    {
        HttpField field = new HttpField("name", "SomeValue");
        assertTrue(field.contains("somevalue"));
        assertTrue(field.contains("sOmEvAlUe"));
        assertTrue(field.contains("SomeValue"));
        assertFalse(field.contains("other"));
        assertFalse(field.contains("some"));
        assertFalse(field.contains("Some"));
        assertFalse(field.contains("value"));
        assertFalse(field.contains("v"));
        assertFalse(field.contains(""));
        assertFalse(field.contains(null));
    }

    @Test
    public void testCaseInsensitiveHashcodeKnownField()
    {
        HttpField fieldFoo1 = new HttpField("Cookie", "foo");
        HttpField fieldFoo2 = new HttpField("cookie", "foo");

        assertThat("Field hashcodes are case insensitive", fieldFoo1.hashCode(), is(fieldFoo2.hashCode()));
    }

    @Test
    public void testCaseInsensitiveHashcodeUnknownField()
    {
        HttpField fieldFoo1 = new HttpField("X-Foo", "bar");
        HttpField fieldFoo2 = new HttpField("x-foo", "bar");

        assertThat("Field hashcodes are case insensitive", fieldFoo1.hashCode(), is(fieldFoo2.hashCode()));
    }

    @Test
    public void testContainsList()
    {
        HttpField field = new HttpField("name", ",aaa,Bbb,CCC, ddd , e e, \"\\\"f,f\\\"\", ");
        assertTrue(field.contains("aaa"));
        assertTrue(field.contains("bbb"));
        assertTrue(field.contains("ccc"));
        assertTrue(field.contains("Aaa"));
        assertTrue(field.contains("Bbb"));
        assertTrue(field.contains("Ccc"));
        assertTrue(field.contains("AAA"));
        assertTrue(field.contains("BBB"));
        assertTrue(field.contains("CCC"));
        assertTrue(field.contains("ddd"));
        assertTrue(field.contains("e e"));
        assertTrue(field.contains("\"f,f\""));
        assertFalse(field.contains(""));
        assertFalse(field.contains("aa"));
        assertFalse(field.contains("bb"));
        assertFalse(field.contains("cc"));
        assertFalse(field.contains(null));
    }

    @Test
    public void testQualityContainsList()
    {
        HttpField field;

        field = new HttpField("name", "yes");
        assertTrue(field.contains("yes"));
        assertFalse(field.contains("no"));

        field = new HttpField("name", ",yes,");
        assertTrue(field.contains("yes"));
        assertFalse(field.contains("no"));

        field = new HttpField("name", "other,yes,other");
        assertTrue(field.contains("yes"));
        assertFalse(field.contains("no"));

        field = new HttpField("name", "other,  yes  ,other");
        assertTrue(field.contains("yes"));
        assertFalse(field.contains("no"));

        field = new HttpField("name", "other,  y s  ,other");
        assertTrue(field.contains("y s"));
        assertFalse(field.contains("no"));

        field = new HttpField("name", "other,  \"yes\"  ,other");
        assertTrue(field.contains("yes"));
        assertFalse(field.contains("no"));

        field = new HttpField("name", "other,  \"\\\"yes\\\"\"  ,other");
        assertTrue(field.contains("\"yes\""));
        assertFalse(field.contains("no"));

        field = new HttpField("name", ";no,yes,;no");
        assertTrue(field.contains("yes"));
        assertFalse(field.contains("no"));

        field = new HttpField("name", "no;q=0,yes;q=1,no; q = 0");
        assertTrue(field.contains("yes"));
        assertFalse(field.contains("no"));

        field = new HttpField("name", "no;q=0.0000,yes;q=0.0001,no; q = 0.00000");
        assertTrue(field.contains("yes"));
        assertFalse(field.contains("no"));

        field = new HttpField("name", "no;q=0.0000,Yes;Q=0.0001,no; Q = 0.00000");
        assertTrue(field.contains("yes"));
        assertFalse(field.contains("no"));
    }

    @Test
    public void testValues()
    {
        String[] values = new HttpField("name", "value").getValues();
        assertEquals(1, values.length);
        assertEquals("value", values[0]);

        values = new HttpField("name", "a,b,c").getValues();
        assertEquals(3, values.length);
        assertEquals("a", values[0]);
        assertEquals("b", values[1]);
        assertEquals("c", values[2]);

        values = new HttpField("name", "a,\"x,y,z\",c").getValues();
        assertEquals(3, values.length);
        assertEquals("a", values[0]);
        assertEquals("x,y,z", values[1]);
        assertEquals("c", values[2]);

        values = new HttpField("name", "a,\"x,\\\"p,q\\\",z\",c").getValues();
        assertEquals(3, values.length);
        assertEquals("a", values[0]);
        assertEquals("x,\"p,q\",z", values[1]);
        assertEquals("c", values[2]);
    }

    @Test
    public void testFieldNameNull()
    {
        assertThrows(NullPointerException.class, () -> new HttpField((String)null, null));
    }

    @Test
    public void testCachedField()
    {
        PreEncodedHttpField field = new PreEncodedHttpField(HttpHeader.ACCEPT, "something");
        ByteBuffer buf = BufferUtil.allocate(256);
        BufferUtil.clearToFill(buf);
        field.putTo(buf, HttpVersion.HTTP_1_0);
        BufferUtil.flipToFlush(buf, 0);
        String s = BufferUtil.toString(buf);

        assertEquals("Accept: something\r\n", s);
    }

    @Test
    public void testCachedFieldWithHeaderName()
    {
        PreEncodedHttpField field = new PreEncodedHttpField("X-My-Custom-Header", "something");

        assertNull(field.getHeader());
        assertEquals("X-My-Custom-Header", field.getName());
        assertEquals("something", field.getValue());
    }

    @Test
    public void testGetValueParameters()
    {
        Map<String, String> map = new HashMap<>();
        String value = HttpField.getValueParameters("Value ; p1=v1;p2 = v2 ; p3 = \" v ; 3 = three\"", map);
        assertThat(value, is("Value"));
        assertThat(map.size(), is(3));
        assertThat(map.get("p1"), is("v1"));
        assertThat(map.get("p2"), is("v2"));
        assertThat(map.get("p3"), is(" v ; 3 = three"));
    }
}
