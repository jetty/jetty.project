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

package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.http2.hpack.internal.Huffman;
import org.eclipse.jetty.http2.hpack.internal.NBitInteger;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class HpackContextTest
{

    @Test
    public void testStaticName()
    {
        HpackContext ctx = new HpackContext(4096);
        Entry entry = ctx.get(":method");
        assertEquals(":method", entry.getHttpField().getName());
        assertTrue(entry.isStatic());
        assertThat(entry.toString(), Matchers.startsWith("{S,2,:method: "));
    }

    @Test
    public void testEmptyAdd()
    {
        HpackContext ctx = new HpackContext(0);
        HttpField field = new HttpField("foo", "bar");
        assertNull(ctx.add(field));
    }

    @Test
    public void testTooBigAdd()
    {
        HpackContext ctx = new HpackContext(37);
        HttpField field = new HttpField("foo", "bar");
        assertNull(ctx.add(field));
    }

    @Test
    public void testJustRight()
    {
        HpackContext ctx = new HpackContext(38);
        HttpField field = new HttpField("foo", "bar");
        Entry entry = ctx.add(field);
        assertNotNull(entry);
        assertThat(entry.toString(), Matchers.startsWith("{D,0,foo: bar,"));
    }

    @Test
    public void testEvictOne()
    {
        HpackContext ctx = new HpackContext(38);
        HttpField field0 = new HttpField("foo", "bar");

        assertEquals(field0, ctx.add(field0).getHttpField());
        assertEquals(field0, ctx.get("foo").getHttpField());

        HttpField field1 = new HttpField("xxx", "yyy");
        assertEquals(field1, ctx.add(field1).getHttpField());

        assertNull(ctx.get(field0));
        assertNull(ctx.get("foo"));
        assertEquals(field1, ctx.get(field1).getHttpField());
        assertEquals(field1, ctx.get("xxx").getHttpField());
    }

    @Test
    public void testEvictNames()
    {
        HpackContext ctx = new HpackContext(38 * 2);
        HttpField[] field =
            {
                new HttpField("name", "v0"),
                new HttpField("name", "v1"),
                new HttpField("name", "v2"),
                new HttpField("name", "v3"),
                new HttpField("name", "v4"),
                new HttpField("name", "v5"),
            };

        Entry[] entry = new Entry[field.length];

        // Add 2 name entries to fill table
        for (int i = 0; i <= 1; i++)
        {
            entry[i] = ctx.add(field[i]);
        }

        // check there is a name reference and it is the most recent added
        assertEquals(entry[1], ctx.get("name"));

        // Add 1 other entry to table and evict 1
        ctx.add(new HttpField("xxx", "yyy"));

        // check the name reference has been not been evicted
        assertEquals(entry[1], ctx.get("name"));

        // Add 1 other entry to table and evict 1
        ctx.add(new HttpField("foo", "bar"));

        // name is evicted
        assertNull(ctx.get("name"));
    }

    @Test
    @SuppressWarnings("ReferenceEquality")
    public void testGetAddStatic()
    {
        HpackContext ctx = new HpackContext(4096);

        // Look for the field.  Should find static version.
        HttpField methodGet = new HttpField(":method", "GET");
        assertEquals(methodGet, ctx.get(methodGet).getHttpField());
        assertTrue(ctx.get(methodGet).isStatic());

        // Add static version to dynamic table
        Entry e0 = ctx.add(ctx.get(methodGet).getHttpField());

        // Look again and should see dynamic version
        assertEquals(methodGet, ctx.get(methodGet).getHttpField());
        assertFalse(methodGet == ctx.get(methodGet).getHttpField());
        assertFalse(ctx.get(methodGet).isStatic());

        // Duplicates allows
        Entry e1 = ctx.add(ctx.get(methodGet).getHttpField());

        // Look again and should see dynamic version
        assertEquals(methodGet, ctx.get(methodGet).getHttpField());
        assertFalse(methodGet == ctx.get(methodGet).getHttpField());
        assertFalse(ctx.get(methodGet).isStatic());
        assertFalse(e0 == e1);
    }

    @Test
    public void testGetAddStaticName()
    {
        HpackContext ctx = new HpackContext(4096);
        HttpField methodOther = new HttpField(":method", "OTHER");

        // Look for the field by name.  Should find static version.
        assertEquals(":method", ctx.get(":method").getHttpField().getName());
        assertTrue(ctx.get(":method").isStatic());

        // Add dynamic entry with method
        ctx.add(methodOther);

        // Look for the field by name.  Should find static version.
        assertEquals(":method", ctx.get(":method").getHttpField().getName());
        assertTrue(ctx.get(":method").isStatic());
    }

    @Test
    public void testIndexes()
    {
        // Only enough space for 5 entries
        HpackContext ctx = new HpackContext(38 * 5);

        HttpField methodPost = new HttpField(":method", "POST");
        HttpField[] field =
            {
                new HttpField("fo0", "b0r"),
                new HttpField("fo1", "b1r"),
                new HttpField("fo2", "b2r"),
                new HttpField("fo3", "b3r"),
                new HttpField("fo4", "b4r"),
                new HttpField("fo5", "b5r"),
                new HttpField("fo6", "b6r"),
                new HttpField("fo7", "b7r"),
                new HttpField("fo8", "b8r"),
                new HttpField("fo9", "b9r"),
                new HttpField("foA", "bAr"),
            };

        Entry[] entry = new Entry[100];

        // Lookup the index of a static field
        assertEquals(0, ctx.size());
        assertEquals(":authority", ctx.get(1).getHttpField().getName());
        assertEquals(3, ctx.index(ctx.get(methodPost)));
        assertEquals(methodPost, ctx.get(3).getHttpField());
        assertEquals("www-authenticate", ctx.get(61).getHttpField().getName());
        assertEquals(null, ctx.get(62));

        // Add a single entry  
        entry[0] = ctx.add(field[0]);

        // Check new entry is 62 
        assertEquals(1, ctx.size());
        assertEquals(62, ctx.index(entry[0]));
        assertEquals(entry[0], ctx.get(62));

        // and statics still OK
        assertEquals(":authority", ctx.get(1).getHttpField().getName());
        assertEquals(3, ctx.index(ctx.get(methodPost)));
        assertEquals(methodPost, ctx.get(3).getHttpField());
        assertEquals("www-authenticate", ctx.get(61).getHttpField().getName());
        assertEquals(null, ctx.get(62 + ctx.size()));

        // Add 4 more entries
        for (int i = 1; i <= 4; i++)
        {
            entry[i] = ctx.add(field[i]);
        }

        // Check newest entry is at 62 oldest at 66
        assertEquals(5, ctx.size());
        int index = 66;
        for (int i = 0; i <= 4; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }

        // and statics still OK
        assertEquals(":authority", ctx.get(1).getHttpField().getName());
        assertEquals(3, ctx.index(ctx.get(methodPost)));
        assertEquals(methodPost, ctx.get(3).getHttpField());
        assertEquals("www-authenticate", ctx.get(61).getHttpField().getName());
        assertEquals(null, ctx.get(62 + ctx.size()));

        // add 1 more entry and this should cause an eviction!
        entry[5] = ctx.add(field[5]);

        // Check newest entry is at 1 oldest at 5
        index = 66;
        for (int i = 1; i <= 5; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }
        // check entry 0 evicted
        assertNull(ctx.get(field[0]));
        assertEquals(0, ctx.index(entry[0]));

        // and statics still OK
        assertEquals(":authority", ctx.get(1).getHttpField().getName());
        assertEquals(3, ctx.index(ctx.get(methodPost)));
        assertEquals(methodPost, ctx.get(3).getHttpField());
        assertEquals("www-authenticate", ctx.get(61).getHttpField().getName());
        assertEquals(null, ctx.get(62 + ctx.size()));

        // Add 4 more entries
        for (int i = 6; i <= 9; i++)
        {
            entry[i] = ctx.add(field[i]);
        }

        // Check newest entry is at 1 oldest at 5
        index = 66;
        for (int i = 5; i <= 9; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }
        // check entry 0-4 evicted
        for (int i = 0; i <= 4; i++)
        {
            assertNull(ctx.get(field[i]));
            assertEquals(0, ctx.index(entry[i]));
        }

        // Add new entries enough so that array queue will wrap
        for (int i = 10; i <= 52; i++)
        {
            entry[i] = ctx.add(new HttpField("n" + i, "v" + i));
        }

        index = 66;
        for (int i = 48; i <= 52; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }
    }

    @Test
    public void testResize()
    {
        // Only enough space for 5 entries
        HpackContext ctx = new HpackContext(38 * 5);

        HttpField[] field =
            {
                new HttpField("fo0", "b0r"),
                new HttpField("fo1", "b1r"),
                new HttpField("fo2", "b2r"),
                new HttpField("fo3", "b3r"),
                new HttpField("fo4", "b4r"),
                new HttpField("fo5", "b5r"),
                new HttpField("fo6", "b6r"),
                new HttpField("fo7", "b7r"),
                new HttpField("fo8", "b8r"),
                new HttpField("fo9", "b9r"),
                new HttpField("foA", "bAr"),
            };
        Entry[] entry = new Entry[field.length];

        // Add 5 entries
        for (int i = 0; i <= 4; i++)
        {
            entry[i] = ctx.add(field[i]);
        }

        assertEquals(5, ctx.size());

        // check indexes
        int index = 66;
        for (int i = 0; i <= 4; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }

        // resize so that only 2 entries may be held
        ctx.resize(38 * 2);
        assertEquals(2, ctx.size());

        // check indexes
        index = 63;
        for (int i = 3; i <= 4; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }

        // resize so that 6.5 entries may be held
        ctx.resize(38 * 6 + 19);
        assertEquals(2, ctx.size());

        // check indexes
        index = 63;
        for (int i = 3; i <= 4; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }

        // Add 5 entries
        for (int i = 5; i <= 9; i++)
        {
            entry[i] = ctx.add(field[i]);
        }

        assertEquals(6, ctx.size());

        // check indexes
        index = 67;
        for (int i = 4; i <= 9; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }

        // resize so that only 100 entries may be held
        ctx.resize(38 * 100);
        assertEquals(6, ctx.size());
        // check indexes
        index = 67;
        for (int i = 4; i <= 9; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }

        // add 50 fields
        for (int i = 0; i < 50; i++)
        {
            ctx.add(new HttpField("n" + i, "v" + i));
        }

        // check indexes
        index = 67 + 50;
        for (int i = 4; i <= 9; i++)
        {
            assertEquals(index, ctx.index(entry[i]));
            assertEquals(entry[i], ctx.get(index));
            index--;
        }
    }

    @Test
    public void testStaticHuffmanValues() throws Exception
    {
        HpackContext ctx = new HpackContext(4096);
        for (int i = 2; i <= 14; i++)
        {
            Entry entry = ctx.get(i);
            assertTrue(entry.isStatic());

            ByteBuffer buffer = ByteBuffer.wrap(entry.getStaticHuffmanValue());
            int huff = 0xff & buffer.get();
            assertTrue((0x80 & huff) == 0x80);

            int len = NBitInteger.decode(buffer, 7);

            assertEquals(len, buffer.remaining());
            String value = Huffman.decode(buffer);

            assertEquals(entry.getHttpField().getValue(), value);
        }
    }

    @Test
    public void testNameInsensitivity()
    {
        HpackContext ctx = new HpackContext(4096);
        assertEquals("content-length", ctx.get("content-length").getHttpField().getName());
        assertEquals("content-length", ctx.get("Content-Length").getHttpField().getName());
        assertTrue(ctx.get("Content-Length").isStatic());
        assertTrue(ctx.get("Content-Type").isStatic());

        ctx.add(new HttpField("Wibble", "Wobble"));
        assertEquals("Wibble", ctx.get("wibble").getHttpField().getName());
        assertEquals("Wibble", ctx.get("Wibble").getHttpField().getName());
    }
}
