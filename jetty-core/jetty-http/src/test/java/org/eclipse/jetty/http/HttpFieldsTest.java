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
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    public static Stream<HttpFields.Mutable> mutables()
    {
        return Stream.of(
            HttpFields.build(),
            HttpFields.build(0),
            new HttpFields.Mutable.Wrapper(HttpFields.build()),
            new HttpFields.Mutable()
            {
                private final HttpFields.Mutable fields = HttpFields.build();

                @Override
                public ListIterator<HttpField> listIterator(int index)
                {
                    return fields.listIterator(index);
                }

                @Override
                public String toString()
                {
                    return "DefaultMutableMethods";
                }
            },
            new HttpFields.Mutable()
            {
                private final HttpFields.Mutable fields = HttpFields.build();

                @Override
                public ListIterator<HttpField> listIterator(int index)
                {
                    return fields.listIterator(index);
                }

                @Override
                public String toString()
                {
                    return "DefaultMutableMethods";
                }

                @Override
                public HttpFields asImmutable()
                {
                    return fields.asImmutable();
                }
            }
        );
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testPut(HttpFields.Mutable header)
    {
        header
            .put("name0", "value:0")
            .put("name1", "value1");

        assertEquals(2, header.size());
        assertEquals("value:0", header.get("name0"));
        assertEquals("value1", header.get("name1"));
        assertNull(header.get("name2"));

        int matches = 0;
        for (String o : header.getFieldNamesCollection())
        {
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
        }
        assertEquals(2, matches);

        Enumeration<String> values = header.getValues("name0");
        assertTrue(values.hasMoreElements());
        assertEquals(values.nextElement(), "value:0");
        assertFalse(values.hasMoreElements());

        header.add("name0", "extra0");
        header.add("name1", "extra1");
        header.put(new HttpField("name0", "ZERO"));
        header.put(new HttpField("name1", "ONE"));
        assertThat(header.stream().map(HttpField::getValue).collect(Collectors.toList()), Matchers.contains("ZERO", "ONE"));

        header.put("name0", (String)null);
        assertThat(header.stream().map(HttpField::getValue).collect(Collectors.toList()), Matchers.contains("ONE"));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testPutTo(HttpFields.Mutable header)
    {
        header
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

    public static Stream<Arguments> afterAsImmutable()
    {
        return Stream.of(
            Arguments.of(
                (Consumer<HttpFields.Mutable>)m -> m.remove("name0"),
                (Consumer<HttpFields.Mutable>)m ->
                {
                    assertThat(m.size(), is(1));
                    assertThat(m.get("name1"), is("value1"));
                }
            ),
            Arguments.of(
                (Consumer<HttpFields.Mutable>)m -> m.remove("name1"),
                (Consumer<HttpFields.Mutable>)m ->
                {
                    assertThat(m.size(), is(1));
                    assertThat(m.get("name0"), is("value0"));
                }
            ),
            Arguments.of(
                (Consumer<HttpFields.Mutable>)m ->
                {
                    ListIterator<HttpField> i = m.listIterator();
                    i.next();
                    i.remove();
                },
                (Consumer<HttpFields.Mutable>)m ->
                {
                    assertThat(m.size(), is(1));
                    assertThat(m.get("name1"), is("value1"));
                }
            ),
            Arguments.of(
                (Consumer<HttpFields.Mutable>)m -> m.remove("name2"),
                (Consumer<HttpFields.Mutable>)m ->
                {
                    assertThat(m.size(), is(2));
                    assertThat(m.get("name0"), is("value0"));
                    assertThat(m.get("name1"), is("value1"));
                }
            ),
            Arguments.of(
                (Consumer<HttpFields.Mutable>)m -> m.add("name2", "value2"),
                (Consumer<HttpFields.Mutable>)m ->
                {
                    assertThat(m.size(), is(3));
                    assertThat(m.get("name0"), is("value0"));
                    assertThat(m.get("name1"), is("value1"));
                    assertThat(m.get("name2"), is("value2"));
                }
            ),
            Arguments.of(
                (Consumer<HttpFields.Mutable>)m -> m.put("name2", "value2"),
                (Consumer<HttpFields.Mutable>)m ->
                {
                    assertThat(m.size(), is(3));
                    assertThat(m.get("name0"), is("value0"));
                    assertThat(m.get("name1"), is("value1"));
                    assertThat(m.get("name2"), is("value2"));
                }
            ),
            Arguments.of(
                (Consumer<HttpFields.Mutable>)m -> m.put("name1", "ONE"),
                (Consumer<HttpFields.Mutable>)m ->
                {
                    assertThat(m.size(), is(2));
                    assertThat(m.get("name0"), is("value0"));
                    assertThat(m.get("name1"), is("ONE"));
                }
            )
        );
    }

    @ParameterizedTest
    @MethodSource("afterAsImmutable")
    public void testMutationAfterAsImmutable(Consumer<HttpFields.Mutable> mutation, Consumer<HttpFields.Mutable> check)
    {
        HttpFields.Mutable mutable = HttpFields.build();
        HttpFields immutable = mutable
            .put("name0", "value0")
            .put("name1", "value1").asImmutable();

        assertThat(immutable.size(), is(2));
        assertThat(immutable.get("name0"), is("value0"));
        assertThat(immutable.get("name1"), is("value1"));

        mutation.accept(mutable);

        assertThat(immutable.size(), is(2));
        assertThat(immutable.get("name0"), is("value0"));
        assertThat(immutable.get("name1"), is("value1"));

        check.accept(mutable);
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testMutable(HttpFields.Mutable mutable)
    {
        HttpFields headers = mutable
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
        Map<HttpFields, String> map = new HashMap<>();
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
    public void testCaseInsensitive()
    {
        HttpFields header = HttpFields.build()
            .add("expect", "100")
            .add("RaNdOm", "value")
            .add("Accept-Charset", "UTF-8")
            .add("accept-charset", "UTF-16")
            .add("foo-bar", "one")
            .add("Foo-Bar", "two")
            .asImmutable();

        assertThat(header.get("expect"), is("100"));
        assertThat(header.get("Expect"), is("100"));
        assertThat(header.get("EXPECT"), is("100"));
        assertThat(header.get("eXpEcT"), is("100"));
        assertThat(header.get(HttpHeader.EXPECT), is("100"));
        assertTrue(header.contains("expect"));
        assertTrue(header.contains("Expect"));
        assertTrue(header.contains("EXPECT"));
        assertTrue(header.contains("eXpEcT"));

        assertThat(header.get("random"), is("value"));
        assertThat(header.get("Random"), is("value"));
        assertThat(header.get("RANDOM"), is("value"));
        assertThat(header.get("rAnDoM"), is("value"));
        assertThat(header.get("RaNdOm"), is("value"));
        assertTrue(header.contains("random"));
        assertTrue(header.contains("Random"));
        assertTrue(header.contains("RANDOM"));
        assertTrue(header.contains("rAnDoM"));
        assertTrue(header.contains("RaNdOm"));

        assertThat(header.get("Accept-Charset"), is("UTF-8"));
        assertThat(header.get("accept-charset"), is("UTF-8"));
        assertThat(header.get(HttpHeader.ACCEPT_CHARSET), is("UTF-8"));
        assertTrue(header.contains("Accept-Charset"));
        assertTrue(header.contains("accept-charset"));

        assertThat(header.getValuesList("Accept-Charset"), contains("UTF-8", "UTF-16"));
        assertThat(header.getValuesList("accept-charset"), contains("UTF-8", "UTF-16"));
        assertThat(header.getValuesList(HttpHeader.ACCEPT_CHARSET), contains("UTF-8", "UTF-16"));

        assertThat(header.get("foo-bar"), is("one"));
        assertThat(header.get("Foo-Bar"), is("one"));
        assertThat(header.getValuesList("foo-bar"), contains("one", "two"));
        assertThat(header.getValuesList("Foo-Bar"), contains("one", "two"));
        assertTrue(header.contains("foo-bar"));
        assertTrue(header.contains("Foo-Bar"));

        // We know the order of the set is deterministic
        Set<String> names = header.getFieldNamesCollection();
        assertThat(names, contains("expect", "RaNdOm", "Accept-Charset", "foo-bar"));
        assertTrue(names.contains("expect"));
        assertTrue(names.contains("Expect"));
        assertTrue(names.contains("random"));
        assertTrue(names.contains("accept-charset"));
        assertTrue(names.contains("Accept-Charset"));
        assertTrue(names.contains("foo-bar"));
        assertTrue(names.contains("Foo-Bar"));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testGetKnown(HttpFields.Mutable header)
    {
        header.put("Connection", "value0");
        header.put(HttpHeader.ACCEPT, "value1");

        assertEquals("value0", header.get(HttpHeader.CONNECTION));
        assertEquals("value1", header.get(HttpHeader.ACCEPT));

        assertEquals("value0", header.getField(HttpHeader.CONNECTION).getValue());
        assertEquals("value1", header.getField(HttpHeader.ACCEPT).getValue());

        assertNull(header.getField(HttpHeader.AGE));
        assertNull(header.get(HttpHeader.AGE));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testCRLF(HttpFields.Mutable header)
    {
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

    @ParameterizedTest
    @MethodSource("mutables")
    public void testCachedPut(HttpFields.Mutable header)
    {
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

    @ParameterizedTest
    @MethodSource("mutables")
    public void testRePut(HttpFields.Mutable header)
    {
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
        for (String o : header.getFieldNamesCollection())
        {
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(3, matches);

        Enumeration<String> e = header.getValues("name1");
        assertTrue(e.hasMoreElements());
        assertEquals(e.nextElement(), "value1");
        assertFalse(e.hasMoreElements());
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testRemove(HttpFields.Mutable header)
    {
        header
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
        for (String o : header.getFieldNamesCollection())
        {
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(2, matches);

        Enumeration<String> e = header.getValues("name1");
        assertFalse(e.hasMoreElements());
    }

    /**
     * <p>
     * Test where multiple headers arrive with the same name, but
     * those values are interleaved with other headers names.
     * </p>
     */
    @Test
    public void testRemoveInterleaved()
    {
        HttpFields originalHeaders = HttpFields.build()
            .put(new HostPortHttpField("local"))
            .add("Accept", "images/jpeg")
            .add("Accept-Encoding", "Accept-Encoding: gzip;q=1.0, identity; q=0.5, *;q=0")
            .add("Accept", "text/plain")
            .add("Accept-Charset", "iso-8859-5, unicode-1-1;q=0.8")
            .add("Accept", "*/*")
            .add("Connection", "closed");

        assertEquals(7, originalHeaders.size(), "Size of Original fields");

        assertEquals("Accept-Encoding: gzip;q=1.0, identity; q=0.5, *;q=0", originalHeaders.get(HttpHeader.ACCEPT_ENCODING));
        assertEquals("iso-8859-5, unicode-1-1;q=0.8", originalHeaders.get(HttpHeader.ACCEPT_CHARSET));
        assertEquals("images/jpeg", originalHeaders.get(HttpHeader.ACCEPT), "Should have only gotten the first value?");
        assertEquals("images/jpeg, text/plain, */*", String.join(", ", originalHeaders.getValuesList(HttpHeader.ACCEPT)), "Should have gotten all of the values");

        HttpFields immutable = originalHeaders.asImmutable();

        assertEquals(7, immutable.size(), "Size of (took as) Immutable fields");

        assertEquals("Accept-Encoding: gzip;q=1.0, identity; q=0.5, *;q=0", immutable.get(HttpHeader.ACCEPT_ENCODING));
        assertEquals("iso-8859-5, unicode-1-1;q=0.8", immutable.get(HttpHeader.ACCEPT_CHARSET));
        assertEquals("images/jpeg", immutable.get(HttpHeader.ACCEPT), "Should have only gotten the first value?");
        assertEquals("images/jpeg, text/plain, */*", String.join(", ", immutable.getValuesList(HttpHeader.ACCEPT)), "Should have gotten all of the values");

        // Lets remove "Accept" headers in a copy of the headers
        HttpFields.Mutable headersCopy = HttpFields.build(immutable);

        assertEquals(7, headersCopy.size(), "Size of Mutable fields");

        // Attempt to remove all the "Accept" headers.
        headersCopy.remove("Accept");

        assertEquals("Accept-Encoding: gzip;q=1.0, identity; q=0.5, *;q=0", headersCopy.get(HttpHeader.ACCEPT_ENCODING));
        assertNull(headersCopy.get(HttpHeader.ACCEPT));
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
        for (String o : fields.getFieldNamesCollection())
        {
            if ("name0".equals(o))
                matches++;
            if ("name1".equals(o))
                matches++;
            if ("name2".equals(o))
                matches++;
        }
        assertEquals(3, matches);

        Enumeration<String> e = fields.getValues("name1");
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
        assertEquals(HttpField.getValueParameters(list.get(0), null), "zero");
        assertEquals(HttpField.getValueParameters(list.get(1), null), "one");
        assertEquals(HttpField.getValueParameters(list.get(2), null), "1 + 1");
        assertEquals(HttpField.getValueParameters(list.get(3), null), "three");
        assertEquals(HttpField.getValueParameters(list.get(4), null), "four");
        assertEquals(HttpField.getValueParameters(list.get(5), null), "I V");

        fields.addCSV("name", "six");
        list = fields.getCSV("name", false);
        assertEquals(HttpField.getValueParameters(list.get(0), null), "zero");
        assertEquals(HttpField.getValueParameters(list.get(1), null), "one");
        assertEquals(HttpField.getValueParameters(list.get(2), null), "1 + 1");
        assertEquals(HttpField.getValueParameters(list.get(3), null), "three");
        assertEquals(HttpField.getValueParameters(list.get(4), null), "four");
        assertEquals(HttpField.getValueParameters(list.get(5), null), "I V");
        assertEquals(HttpField.getValueParameters(list.get(6), null), "six");

        fields.addCSV("name", "1 + 1", "7", "zero");
        list = fields.getCSV("name", false);
        assertEquals(HttpField.getValueParameters(list.get(0), null), "zero");
        assertEquals(HttpField.getValueParameters(list.get(1), null), "one");
        assertEquals(HttpField.getValueParameters(list.get(2), null), "1 + 1");
        assertEquals(HttpField.getValueParameters(list.get(3), null), "three");
        assertEquals(HttpField.getValueParameters(list.get(4), null), "four");
        assertEquals(HttpField.getValueParameters(list.get(5), null), "I V");
        assertEquals(HttpField.getValueParameters(list.get(6), null), "six");
        assertEquals(HttpField.getValueParameters(list.get(7), null), "7");

        fields.addCSV(HttpHeader.ACCEPT, "en", "it");
        list = fields.getCSV(HttpHeader.ACCEPT, false);
        assertEquals(HttpField.getValueParameters(list.get(0), null), "en");
        assertEquals(HttpField.getValueParameters(list.get(1), null), "it");
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
        assertEquals(HttpField.getValueParameters(list.get(0), null), "first");
        assertEquals(HttpField.getValueParameters(list.get(1), null), "zero");
        assertEquals(HttpField.getValueParameters(list.get(2), null), "one");
        assertEquals(HttpField.getValueParameters(list.get(3), null), "two");
        assertEquals(HttpField.getValueParameters(list.get(4), null), "three");
        assertEquals(HttpField.getValueParameters(list.get(5), null), "four");
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
        assertEquals(HttpField.getValueParameters(list.get(0), null), "first");
        assertEquals(HttpField.getValueParameters(list.get(1), null), "zero");
        assertEquals(HttpField.getValueParameters(list.get(2), null), "one");
        assertEquals(HttpField.getValueParameters(list.get(3), null), "two");
        assertEquals(HttpField.getValueParameters(list.get(4), null), "three");
        assertEquals(HttpField.getValueParameters(list.get(5), null), "four");
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

        fields.putDate("D2", d1);
        assertEquals("Fri, 31 Dec 1999 23:59:59 GMT", fields.get("D2"));
    }

    @Test
    public void testNegDateFields()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.putDate("Dzero", 0);
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", fields.get("Dzero"));

        fields.putDate("Dminus", -1);
        assertEquals("Wed, 31 Dec 1969 23:59:59 GMT", fields.get("Dminus"));

        fields.putDate("Dminus", -1000);
        assertEquals("Wed, 31 Dec 1969 23:59:59 GMT", fields.get("Dminus"));

        fields.putDate("Dancient", Long.MIN_VALUE);
        assertEquals("Sun, 02 Dec 55 16:47:04 GMT", fields.get("Dancient"));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testLongFields(HttpFields.Mutable header)
    {
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

        header.put("I5", 46);
        header.put("I6", -47);
        assertEquals("46", header.get("I5"));
        assertEquals("-47", header.get("I6"));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testContains(HttpFields.Mutable header)
    {
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
    @MethodSource("mutables")
    public void testContainsLast(HttpFields.Mutable header)
    {
        assertFalse(header.containsLast(HttpHeader.TRANSFER_ENCODING, "gzip"));

        header.add(HttpHeader.TRANSFER_ENCODING, "gzip");
        assertTrue(header.containsLast(HttpHeader.TRANSFER_ENCODING, "gzip"));

        header.add(HttpHeader.TRANSFER_ENCODING, "bz2");
        assertFalse(header.containsLast(HttpHeader.TRANSFER_ENCODING, "gzip"));
        assertTrue(header.containsLast(HttpHeader.TRANSFER_ENCODING, "bz2"));

        header.add(HttpHeader.TRANSFER_ENCODING, "foo, bar");
        assertFalse(header.containsLast(HttpHeader.TRANSFER_ENCODING, "foo"));
        assertTrue(header.containsLast(HttpHeader.TRANSFER_ENCODING, "bar"));

        header.add(HttpHeader.TRANSFER_ENCODING, "\"x\", \"y\"");
        assertFalse(header.containsLast(HttpHeader.TRANSFER_ENCODING, "x"));
        assertTrue(header.containsLast(HttpHeader.TRANSFER_ENCODING, "y"));

        header.add(HttpHeader.TRANSFER_ENCODING, "tom,dick,harry");
        assertFalse(header.containsLast(HttpHeader.TRANSFER_ENCODING, "tom"));
        assertTrue(header.containsLast(HttpHeader.TRANSFER_ENCODING, "harry"));

        header.add(HttpHeader.TRANSFER_ENCODING, "spongebob");
        assertFalse(header.containsLast(HttpHeader.TRANSFER_ENCODING, "sponge"));
        assertFalse(header.containsLast(HttpHeader.TRANSFER_ENCODING, "bob"));
        assertTrue(header.containsLast(HttpHeader.TRANSFER_ENCODING, "spongebob"));

        header.add(HttpHeader.TRANSFER_ENCODING, "sponge bob");
        assertFalse(header.containsLast(HttpHeader.TRANSFER_ENCODING, "sponge"));
        assertFalse(header.containsLast(HttpHeader.TRANSFER_ENCODING, "bob"));
        assertTrue(header.containsLast(HttpHeader.TRANSFER_ENCODING, "sponge bob"));
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
        assertThrows(NullPointerException.class, () -> fields.add((HttpHeader)null, "bogus"));
        assertThat(fields.size(), is(0));
    }

    @Test
    public void testAddHttpFields()
    {
        HttpFields.Mutable fields = HttpFields.build();
        fields.add("One", "1");

        fields = HttpFields.build(fields);

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
    public void testAddNullValueList()
    {
        HttpFields.Mutable fields = HttpFields.build();
        fields.add("name", (List<String>)null);
        assertThat(fields.size(), is(0));
        List<String> list = new ArrayList<>();
        fields.add("name", list);
        assertThat(fields.size(), is(0));

        list.add("Foo");
        list.add(null);
        list.add("Bar");
        assertThrows(IllegalArgumentException.class, () -> fields.add("name", list));

        list.set(1, "");
        assertThrows(IllegalArgumentException.class, () -> fields.add("name", list));

        list.set(1, " ");
        assertThrows(IllegalArgumentException.class, () -> fields.add("name", list));

        list.set(1, "  ");
        assertThrows(IllegalArgumentException.class, () -> fields.add("name", list));

        assertThat(fields.size(), is(0));
    }

    @Test
    public void testAddValueList()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.add("name", "0, 1, 2");
        fields.add("name", List.of("A", "B", "C"));
        assertThat(fields.size(), is(2));
        assertThat(fields.getValuesList("name"), contains("0, 1, 2", "A, B, C"));
        assertThat(fields.getCSV("name", false), contains("0", "1", "2", "A", "B", "C"));
        assertThat(fields.getField("name").getValueList(), contains("0", "1", "2"));
        assertThat(fields.getField(1).getValueList(), contains("A", "B", "C"));
    }

    @Test
    public void testPutNullValueList()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.add("name", "x");
        fields.put("name", (List<String>)null);
        assertThat(fields.size(), is(0));

        List<String> list = new ArrayList<>();
        fields.add("name", "x");
        fields.put("name", list);
        assertThat(fields.size(), is(0));

        fields.add("name", "x");
        list.add("Foo");
        list.add(null);
        list.add("Bar");
        assertThrows(IllegalArgumentException.class, () -> fields.put("name", list));

        list.set(1, "");
        assertThrows(IllegalArgumentException.class, () -> fields.put("name", list));

        list.set(1, " ");
        assertThrows(IllegalArgumentException.class, () -> fields.put("name", list));

        list.set(1, "  ");
        assertThrows(IllegalArgumentException.class, () -> fields.put("name", list));

        assertThat(fields.size(), is(1));
        assertThat(fields.get("name"), is("x"));
    }

    @Test
    public void testPutValueList()
    {
        HttpFields.Mutable fields = HttpFields.build();

        fields.put("name", List.of("A", "B", "C"));
        assertThat(fields.size(), is(1));
        assertThat(fields.get("name"), is("A, B, C"));
        assertThat(fields.getField("name").getValueList(), contains("A", "B", "C"));
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

    @ParameterizedTest
    @MethodSource("mutables")
    public void testIteration(HttpFields.Mutable header)
    {
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

    @Test
    public void testWrapperComputeFieldCallingOnField()
    {
        var wrapper = new HttpFields.Mutable.Wrapper(HttpFields.build())
        {
            final List<String> actions = new ArrayList<>();

            @Override
            public HttpField onAddField(HttpField field)
            {
                actions.add("onAddField");
                return super.onAddField(field);
            }

            @Override
            public boolean onRemoveField(HttpField field)
            {
                actions.add("onRemoveField");
                return super.onRemoveField(field);
            }

            @Override
            public HttpField onReplaceField(HttpField oldField, HttpField newField)
            {
                if (newField.getValueList().contains("removeOnReplace"))
                {
                    actions.add("onReplaceFieldRemove");
                    return null;
                }

                actions.add("onReplaceField");
                return super.onReplaceField(oldField, newField);
            }
        };

        wrapper.computeField("non-existent", (name, httpFields) -> null);
        assertThat(wrapper.size(), is(0));
        assertThat(wrapper.actions, is(List.of()));

        wrapper.computeField("non-existent", (name, httpFields) -> new HttpField("non-existent", "a"));
        wrapper.computeField("non-existent", (name, httpFields) -> new HttpField("non-existent", "b"));
        wrapper.computeField("non-existent", (name, httpFields) -> null);
        assertThat(wrapper.size(), is(0));
        assertThat(wrapper.actions, is(List.of("onAddField", "onReplaceField", "onRemoveField")));
        wrapper.actions.clear();

        wrapper.computeField(HttpHeader.VARY, (name, httpFields) -> null);
        assertThat(wrapper.size(), is(0));
        assertThat(wrapper.actions, is(List.of()));

        wrapper.computeField(HttpHeader.VARY, (name, httpFields) -> new HttpField(HttpHeader.VARY, "a"));
        wrapper.computeField(HttpHeader.VARY, (name, httpFields) -> new HttpField(HttpHeader.VARY, "b"));
        wrapper.computeField(HttpHeader.VARY, (name, httpFields) -> null);
        assertThat(wrapper.size(), is(0));
        assertThat(wrapper.actions, is(List.of("onAddField", "onReplaceField", "onRemoveField")));
        wrapper.actions.clear();

        wrapper.ensureField(new HttpField("ensure", "value0"));
        wrapper.ensureField(new HttpField("ensure", "value1"));
        assertThat(wrapper.actions, is(List.of("onAddField", "onReplaceField")));
        wrapper.ensureField(new HttpField("ensure", "removeOnReplace"));
        assertThat(wrapper.actions, is(List.of("onAddField", "onReplaceField", "onReplaceFieldRemove")));
        assertThat(wrapper.getField("ensure"), nullValue());
    }
}
