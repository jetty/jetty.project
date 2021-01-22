//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HpackEncoderTest
{
    @Test
    public void testUnknownFieldsContextManagement() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder(38 * 5);
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
        ByteBuffer buffer = BufferUtil.allocate(4096);
        int pos = BufferUtil.flipToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, pos);

        // something was encoded!
        assertThat(buffer.remaining(), Matchers.greaterThan(0));

        // All are in the dynamic table
        assertEquals(4, encoder.getHpackContext().size());

        // encode exact same fields again!
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, 0);

        // All are in the dynamic table
        assertEquals(4, encoder.getHpackContext().size());

        // Add 4 more fields
        for (int i = 4; i <= 7; i++)
        {
            fields.add(field[i]);
        }

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, 0);

        // something was encoded!
        assertThat(buffer.remaining(), Matchers.greaterThan(0));

        // max dynamic table size reached
        assertEquals(5, encoder.getHpackContext().size());

        // remove some fields
        for (int i = 0; i <= 7; i += 2)
        {
            fields.remove(field[i].getName());
        }

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, 0);

        // something was encoded!
        assertThat(buffer.remaining(), Matchers.greaterThan(0));

        // max dynamic table size reached
        assertEquals(5, encoder.getHpackContext().size());

        // remove another fields
        fields.remove(field[1].getName());

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, 0);

        // something was encoded!
        assertThat(buffer.remaining(), Matchers.greaterThan(0));

        // max dynamic table size reached
        assertEquals(5, encoder.getHpackContext().size());

        // re add the field

        fields.add(field[1]);

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, 0);

        // something was encoded!
        assertThat(buffer.remaining(), Matchers.greaterThan(0));

        // max dynamic table size reached
        assertEquals(5, encoder.getHpackContext().size());
    }

    @Test
    public void testLargeFieldsNotIndexed()
    {
        HpackEncoder encoder = new HpackEncoder(38 * 5);
        HpackContext ctx = encoder.getHpackContext();

        ByteBuffer buffer = BufferUtil.allocate(4096);

        // Index little fields
        int pos = BufferUtil.flipToFill(buffer);
        encoder.encode(buffer, new HttpField("Name", "Value"));
        BufferUtil.flipToFlush(buffer, pos);
        int dynamicTableSize = ctx.getDynamicTableSize();
        assertThat(dynamicTableSize, Matchers.greaterThan(0));

        // Do not index big field
        StringBuilder largeName = new StringBuilder("largeName-");
        String filler = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
        while (largeName.length() < ctx.getMaxDynamicTableSize())
            largeName.append(filler, 0, Math.min(filler.length(), ctx.getMaxDynamicTableSize() - largeName.length()));
        pos = BufferUtil.flipToFill(buffer);
        encoder.encode(buffer, new HttpField(largeName.toString(), "Value"));
        BufferUtil.flipToFlush(buffer, pos);
        assertThat(ctx.getDynamicTableSize(), Matchers.is(dynamicTableSize));
    }

    @Test
    public void testIndexContentLength()
    {
        HpackEncoder encoder = new HpackEncoder(38 * 5);
        HpackContext ctx = encoder.getHpackContext();

        ByteBuffer buffer = BufferUtil.allocate(4096);

        // Index zero content length
        int pos = BufferUtil.flipToFill(buffer);
        encoder.encode(buffer, new HttpField(HttpHeader.CONTENT_LENGTH, "0"));
        BufferUtil.flipToFlush(buffer, pos);
        int dynamicTableSize = ctx.getDynamicTableSize();
        assertThat(dynamicTableSize, Matchers.greaterThan(0));

        // Do not index non zero content length
        pos = BufferUtil.flipToFill(buffer);
        encoder.encode(buffer, new HttpField(HttpHeader.CONTENT_LENGTH, "42"));
        BufferUtil.flipToFlush(buffer, pos);
        assertThat(ctx.getDynamicTableSize(), Matchers.is(dynamicTableSize));
    }

    @Test
    public void testNeverIndexSetCookie() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder(38 * 5);
        ByteBuffer buffer = BufferUtil.allocate(4096);

        HttpFields.Mutable fields = HttpFields.build()
            .put("set-cookie", "some cookie value");

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, 0);

        // something was encoded!
        assertThat(buffer.remaining(), Matchers.greaterThan(0));

        // empty dynamic table
        assertEquals(0, encoder.getHpackContext().size());

        // encode again
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, 0);

        // something was encoded!
        assertThat(buffer.remaining(), Matchers.greaterThan(0));

        // empty dynamic table
        assertEquals(0, encoder.getHpackContext().size());
    }

    @Test
    public void testFieldLargerThanTable() throws Exception
    {
        HttpFields.Mutable fields = HttpFields.build();

        HpackEncoder encoder = new HpackEncoder(128);
        ByteBuffer buffer0 = BufferUtil.allocate(4096);
        int pos = BufferUtil.flipToFill(buffer0);
        encoder.encode(buffer0, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer0, pos);

        encoder = new HpackEncoder(128);
        fields.add(new HttpField("user-agent", "jetty/test"));
        ByteBuffer buffer1 = BufferUtil.allocate(4096);
        pos = BufferUtil.flipToFill(buffer1);
        encoder.encode(buffer1, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer1, pos);

        encoder = new HpackEncoder(128);
        encoder.setValidateEncoding(false);
        fields.add(new HttpField(":path",
            "This is a very large field, whose size is larger than the dynamic table so it should not be indexed as it will not fit in the table ever!" +
                "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX " +
                "YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY " +
                "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ "));
        ByteBuffer buffer2 = BufferUtil.allocate(4096);
        pos = BufferUtil.flipToFill(buffer2);
        encoder.encode(buffer2, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer2, pos);

        encoder = new HpackEncoder(128);
        encoder.setValidateEncoding(false);
        fields.add(new HttpField("host", "somehost"));
        ByteBuffer buffer = BufferUtil.allocate(4096);
        pos = BufferUtil.flipToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, pos);

        //System.err.println(BufferUtil.toHexString(buffer0));
        //System.err.println(BufferUtil.toHexString(buffer1));
        //System.err.println(BufferUtil.toHexString(buffer2));
        //System.err.println(BufferUtil.toHexString(buffer));

        // something was encoded!
        assertThat(buffer.remaining(), Matchers.greaterThan(0));

        // check first field is static index name and dynamic index body
        assertThat((buffer.get(buffer0.remaining()) & 0xFF) >> 6, equalTo(1));

        // check first field is static index name and literal body
        assertThat((buffer.get(buffer1.remaining()) & 0xFF) >> 4, equalTo(0));

        // check first field is static index name and dynamic index body
        assertThat((buffer.get(buffer2.remaining()) & 0xFF) >> 6, equalTo(1));

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

        HpackEncoder encoder = new HpackEncoder(4096);

        ByteBuffer buffer = BufferUtil.allocate(4096);
        int pos = BufferUtil.flipToFill(buffer);
        encoder.encodeMaxDynamicTableSize(buffer, 0);
        encoder.setRemoteMaxDynamicTableSize(50);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
        BufferUtil.flipToFlush(buffer, pos);

        HpackContext context = encoder.getHpackContext();

        assertThat(context.getMaxDynamicTableSize(), Matchers.is(50));
        assertThat(context.size(), Matchers.is(1));
    }
}
