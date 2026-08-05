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

package org.eclipse.jetty.http2.hpack;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.compression.NBitIntegerDecoder;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HpackEncoderTest
{
    @Test
    public void testUnknownFieldsContextManagement() throws Exception
    {
        HpackEncoder encoder = newHpackEncoder(38 * 5);
        HttpFields.Mutable fields = HttpFields.build();

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

        // Add 4 entries
        for (int i = 0; i <= 3; i++)
        {
            fields.add(field[i]);
        }

        // encode them
        WritableBuffer wb = WritableBuffer.allocate(4096, false);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, fields));

        // something was encoded!
        {
            ReadableBuffer rb = wb.toReadable();
            assertThat(rb.remaining(), Matchers.greaterThan(0L));
            rb.toWritable();
        }

        // All are in the dynamic table
        assertEquals(4, encoder.getHpackContext().size());

        // encode exact same fields again!
        wb.position(0);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, fields));

        // All are in the dynamic table
        assertEquals(4, encoder.getHpackContext().size());

        // Add 4 more fields
        for (int i = 4; i <= 7; i++)
        {
            fields.add(field[i]);
        }

        // encode
        wb.position(0);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, fields));

        // something was encoded!
        {
            ReadableBuffer rb = wb.toReadable();
            assertThat(rb.remaining(), Matchers.greaterThan(0L));
            rb.toWritable();
        }

        // max dynamic table size reached
        assertEquals(5, encoder.getHpackContext().size());

        // remove some fields
        for (int i = 0; i <= 7; i += 2)
        {
            fields.remove(field[i].getName());
        }

        // encode
        wb.position(0);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, fields));

        // something was encoded!
        {
            ReadableBuffer rb = wb.toReadable();
            assertThat(rb.remaining(), Matchers.greaterThan(0L));
            rb.toWritable();
        }

        // max dynamic table size reached
        assertEquals(5, encoder.getHpackContext().size());

        // remove another fields
        fields.remove(field[1].getName());

        // encode
        wb.position(0);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, fields));

        // something was encoded!
        {
            ReadableBuffer rb = wb.toReadable();
            assertThat(rb.remaining(), Matchers.greaterThan(0L));
            rb.toWritable();
        }

        // max dynamic table size reached
        assertEquals(5, encoder.getHpackContext().size());

        // re add the field

        fields.add(field[1]);

        // encode
        wb.position(0);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, fields));

        // something was encoded!
        {
            ReadableBuffer rb = wb.toReadable();
            assertThat(rb.remaining(), Matchers.greaterThan(0L));
            rb.toWritable();
        }

        // max dynamic table size reached
        assertEquals(5, encoder.getHpackContext().size());
    }

    @Test
    public void testLargeFieldsNotIndexed()
    {
        HpackEncoder encoder = newHpackEncoder(38 * 5);
        HpackContext ctx = encoder.getHpackContext();
        ctx.resize(encoder.getMaxTableCapacity());

        WritableBuffer wb = WritableBuffer.allocate(4096, false);

        // Index little fields
        encoder.encode(wb, new HttpField("Name", "Value"));
        int dynamicTableSize = ctx.getDynamicTableSize();
        assertThat(dynamicTableSize, Matchers.greaterThan(0));

        // Do not index big field
        StringBuilder largeName = new StringBuilder("largeName-");
        String filler = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
        while (largeName.length() < ctx.getMaxDynamicTableSize())
            largeName.append(filler, 0, Math.min(filler.length(), ctx.getMaxDynamicTableSize() - largeName.length()));
        encoder.encode(wb, new HttpField(largeName.toString(), "Value"));
        assertThat(ctx.getDynamicTableSize(), Matchers.is(dynamicTableSize));
    }

    @Test
    public void testIndexContentLength()
    {
        HpackEncoder encoder = newHpackEncoder(38 * 5);
        HpackContext ctx = encoder.getHpackContext();
        ctx.resize(encoder.getMaxTableCapacity());

        WritableBuffer buffer = WritableBuffer.allocate(4096, false);

        // Index zero content length
        encoder.encode(buffer, HttpFields.CONTENT_LENGTH_0);
        int dynamicTableSize = ctx.getDynamicTableSize();
        assertThat(dynamicTableSize, Matchers.greaterThan(0));

        // Do not index non zero content length
        encoder.encode(buffer, new HttpField(HttpHeader.CONTENT_LENGTH, "42"));
        assertThat(ctx.getDynamicTableSize(), Matchers.is(dynamicTableSize));
    }

    @Test
    public void testNeverIndexSetCookie() throws Exception
    {
        HpackEncoder encoder = newHpackEncoder(38 * 5);
        WritableBuffer wb = WritableBuffer.allocate(4096, false);

        HttpFields.Mutable fields = HttpFields.build()
            .put("set-cookie", "some cookie value");

        // encode
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, fields));

        // something was encoded!
        {
            ReadableBuffer rb = wb.toReadable();
            assertThat(rb.remaining(), Matchers.greaterThan(0L));
            rb.toWritable();
        }

        // empty dynamic table
        assertEquals(0, encoder.getHpackContext().size());

        // encode again
        wb.position(0);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, fields));

        // something was encoded!
        {
            ReadableBuffer rb = wb.toReadable();
            assertThat(rb.remaining(), Matchers.greaterThan(0L));
            rb.toWritable();
        }

        // empty dynamic table
        assertEquals(0, encoder.getHpackContext().size());
    }

    @Test
    public void testFieldLargerThanTable() throws Exception
    {
        HttpFields.Mutable fields = HttpFields.build();

        HpackEncoder encoder = newHpackEncoder(128);
        WritableBuffer buffer0 = WritableBuffer.allocate(4096, false);
        encoder.encode(buffer0, new MetaData(HttpVersion.HTTP_2, fields));

        encoder = newHpackEncoder(128);
        fields.add(new HttpField("user-agent", "jetty/test"));
        WritableBuffer buffer1 = WritableBuffer.allocate(4096, false);
        encoder.encode(buffer1, new MetaData(HttpVersion.HTTP_2, fields));

        encoder = newHpackEncoder(128);
        encoder.setValidateEncoding(false);
        fields.add(new HttpField(":path",
            "This is a very large field, whose size is larger than the dynamic table so it should not be indexed as it will not fit in the table ever!" +
                "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX " +
                "YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY " +
                "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ "));
        WritableBuffer buffer2 = WritableBuffer.allocate(4096, false);
        encoder.encode(buffer2, new MetaData(HttpVersion.HTTP_2, fields));

        encoder = newHpackEncoder(128);
        encoder.setValidateEncoding(false);
        fields.add(new HttpField("host", "somehost"));
        WritableBuffer buffer = WritableBuffer.allocate(4096, false);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));

        //System.err.println(BufferUtil.toHexString(buffer0));
        //System.err.println(BufferUtil.toHexString(buffer1));
        //System.err.println(BufferUtil.toHexString(buffer2));
        //System.err.println(BufferUtil.toHexString(buffer));

        // something was encoded!
        {
            ReadableBuffer rb = buffer.toReadable();
            assertThat(rb.remaining(), Matchers.greaterThan(0L));

            // check first field is static index name and dynamic index body
            assertThat((rb.get(buffer0.toReadable().remaining()) & 0xFF) >> 6, equalTo(1));

            // check first field is static index name and literal body
            assertThat((rb.get(buffer1.toReadable().remaining()) & 0xFF) >> 4, equalTo(0));

            // check first field is static index name and dynamic index body
            assertThat((rb.get(buffer2.toReadable().remaining()) & 0xFF) >> 6, equalTo(1));
        }

        // Only first and third fields are put in the table
        HpackContext context = encoder.getHpackContext();
        assertThat(context.size(), equalTo(2));
        assertThat(context.get(HpackContext.STATIC_SIZE + 1).getHttpField().getName(), equalTo("host"));
        assertThat(context.get(HpackContext.STATIC_SIZE + 2).getHttpField().getName(), equalTo("user-agent"));
        assertThat(context.getDynamicTableSize(), equalTo(
            context.get(HpackContext.STATIC_SIZE + 1).getSize() + context.get(HpackContext.STATIC_SIZE + 2).getSize()));
    }

    @Test
    public void testResize() throws Exception
    {
        HttpFields fields = HttpFields.build()
            .add("host", "localhost0")
            .add("cookie", "abcdefghij");

        HpackEncoder encoder = newHpackEncoder(4096);

        WritableBuffer buffer = WritableBuffer.allocate(4096, false);
        encoder.encodeMaxDynamicTableSize(buffer, 0);
        encoder.setTableCapacity(50);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));

        HpackContext context = encoder.getHpackContext();

        assertThat(context.getMaxDynamicTableSize(), Matchers.is(50));
        assertThat(context.size(), Matchers.is(1));
    }

    private static HpackEncoder newHpackEncoder(int tableCapacity)
    {
        HpackEncoder encoder = new HpackEncoder();
        encoder.setMaxTableCapacity(tableCapacity);
        encoder.setTableCapacity(tableCapacity);
        return encoder;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, HpackContext.DEFAULT_MAX_TABLE_CAPACITY})
    public void testAlwaysSendInitialSize(int size) throws Exception
    {
        HpackEncoder encoder = newHpackEncoder(size);
        WritableBuffer buffer = WritableBuffer.allocate(4096, false);

        // Index zero content length
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, HttpFields.EMPTY));

        ReadableBuffer rb = buffer.toReadable();

        byte b = rb.get(buffer.position());
        byte f = (byte)((b & 0xF0) >> 4);
        assertThat((int)f, Matchers.either(is(2)).or(is(3)));

        NBitIntegerDecoder decoder = new NBitIntegerDecoder();
        decoder.setPrefix(5);
        int s = decoder.decodeInt(rb);

        assertThat(s, is(size));
    }
}
