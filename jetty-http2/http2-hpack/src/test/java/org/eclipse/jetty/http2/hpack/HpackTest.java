//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MetaData.Response;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class HpackTest
{
    static final HttpField ServerJetty = new PreEncodedHttpField(HttpHeader.SERVER, "jetty");
    static final HttpField XPowerJetty = new PreEncodedHttpField(HttpHeader.X_POWERED_BY, "jetty");
    static final HttpField Date = new PreEncodedHttpField(HttpHeader.DATE, DateGenerator.formatDate(System.currentTimeMillis()));

    @Test
    public void encodeDecodeResponseTest() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(8192);
        ByteBuffer buffer = BufferUtil.allocateDirect(16 * 1024);

        HttpFields fields0 = new HttpFields();
        fields0.add(HttpHeader.CONTENT_TYPE, "text/html");
        fields0.add(HttpHeader.CONTENT_LENGTH, "1024");
        fields0.add(new HttpField(HttpHeader.CONTENT_ENCODING, (String)null));
        fields0.add(ServerJetty);
        fields0.add(XPowerJetty);
        fields0.add(Date);
        fields0.add(HttpHeader.SET_COOKIE, "abcdefghijklmnopqrstuvwxyz");
        fields0.add("custom-key", "custom-value");
        Response original0 = new MetaData.Response(HttpVersion.HTTP_2, 200, fields0);

        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, original0);
        BufferUtil.flipToFlush(buffer, 0);
        Response decoded0 = (Response)decoder.decode(buffer);
        original0.getFields().put(new HttpField(HttpHeader.CONTENT_ENCODING, ""));
        assertMetaDataResponseSame(original0, decoded0);

        // Same again?
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, original0);
        BufferUtil.flipToFlush(buffer, 0);
        Response decoded0b = (Response)decoder.decode(buffer);

        assertMetaDataResponseSame(original0, decoded0b);

        HttpFields fields1 = new HttpFields();
        fields1.add(HttpHeader.CONTENT_TYPE, "text/plain");
        fields1.add(HttpHeader.CONTENT_LENGTH, "1234");
        fields1.add(HttpHeader.CONTENT_ENCODING, " ");
        fields1.add(ServerJetty);
        fields1.add(XPowerJetty);
        fields1.add(Date);
        fields1.add("Custom-Key", "Other-Value");
        Response original1 = new MetaData.Response(HttpVersion.HTTP_2, 200, fields1);

        // Same again?
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, original1);
        BufferUtil.flipToFlush(buffer, 0);
        Response decoded1 = (Response)decoder.decode(buffer);

        assertMetaDataResponseSame(original1, decoded1);
        assertEquals("custom-key", decoded1.getFields().getField("Custom-Key").getName());
    }

    @Test
    public void encodeDecodeTooLargeTest() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(164);
        ByteBuffer buffer = BufferUtil.allocateDirect(16 * 1024);

        HttpFields fields0 = new HttpFields();
        fields0.add("1234567890", "1234567890123456789012345678901234567890");
        fields0.add("Cookie", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR");
        MetaData original0 = new MetaData(HttpVersion.HTTP_2, fields0);

        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, original0);
        BufferUtil.flipToFlush(buffer, 0);
        MetaData decoded0 = decoder.decode(buffer);

        assertMetaDataSame(original0, decoded0);

        HttpFields fields1 = new HttpFields();
        fields1.add("1234567890", "1234567890123456789012345678901234567890");
        fields1.add("Cookie", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR");
        fields1.add("x", "y");
        MetaData original1 = new MetaData(HttpVersion.HTTP_2, fields1);

        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, original1);
        BufferUtil.flipToFlush(buffer, 0);
        try
        {
            decoder.decode(buffer);
            fail();
        }
        catch (HpackException.SessionException e)
        {
            assertThat(e.getMessage(), containsString("Header size 198 > 164"));
        }
    }

    @Test
    public void encodeNonAscii() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        ByteBuffer buffer = BufferUtil.allocate(16 * 1024);

        HttpFields fields0 = new HttpFields();
            // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        fields0.add("Cookie", "[\uD842\uDF9F]");
        fields0.add("custom-key", "[\uD842\uDF9F]");
        Response original0 = new MetaData.Response(HttpVersion.HTTP_2, 200, fields0);

        HpackException.SessionException throwable = assertThrows(HpackException.SessionException.class, () ->
        {
            BufferUtil.clearToFill(buffer);
            encoder.encode(buffer, original0);
            BufferUtil.flipToFlush(buffer, 0);
        });

        assertThat(throwable.getMessage(), containsString("Could not hpack encode"));
    }

    @Test
    public void evictReferencedFieldTest() throws Exception
    {
        HpackDecoder decoder = new HpackDecoder(1024);
        decoder.setMaxTableCapacity(200);
        HpackEncoder encoder = new HpackEncoder();
        encoder.setMaxTableCapacity(decoder.getMaxTableCapacity());
        encoder.setTableCapacity(decoder.getMaxTableCapacity());
        ByteBuffer buffer = BufferUtil.allocateDirect(16 * 1024);

        String longEnoughToBeEvicted = "012345678901234567890123456789012345678901234567890";

        HttpFields fields0 = new HttpFields();
        fields0.add(longEnoughToBeEvicted, "value");
        fields0.add("foo", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
        MetaData original0 = new MetaData(HttpVersion.HTTP_2, fields0);

        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, original0);
        BufferUtil.flipToFlush(buffer, 0);
        MetaData decoded0 = decoder.decode(buffer);

        assertEquals(2, encoder.getHpackContext().size());
        assertEquals(2, decoder.getHpackContext().size());
        assertEquals(longEnoughToBeEvicted, encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length + 1).getHttpField().getName());
        assertEquals("foo", encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length).getHttpField().getName());

        assertMetaDataSame(original0, decoded0);

        HttpFields fields1 = new HttpFields();
        fields1.add(longEnoughToBeEvicted, "other_value");
        fields1.add("x", "y");
        MetaData original1 = new MetaData(HttpVersion.HTTP_2, fields1);

        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, original1);
        BufferUtil.flipToFlush(buffer, 0);
        MetaData decoded1 = decoder.decode(buffer);
        assertMetaDataSame(original1, decoded1);

        assertEquals(2, encoder.getHpackContext().size());
        assertEquals(2, decoder.getHpackContext().size());
        assertEquals("x", encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length).getHttpField().getName());
        assertEquals("foo", encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length + 1).getHttpField().getName());
    }

    @Test
    public void testHopHeadersAreRemoved() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(16384);

        HttpFields input = new HttpFields();
        input.put(HttpHeader.ACCEPT, "*");
        input.put(HttpHeader.CONNECTION, "TE, Upgrade, Custom");
        input.put("Custom", "Pizza");
        input.put(HttpHeader.KEEP_ALIVE, "true");
        input.put(HttpHeader.PROXY_CONNECTION, "foo");
        input.put(HttpHeader.TE, "1234567890abcdef");
        input.put(HttpHeader.TRANSFER_ENCODING, "chunked");
        input.put(HttpHeader.UPGRADE, "gold");

        ByteBuffer buffer = BufferUtil.allocate(2048);
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, input));
        BufferUtil.flipToFlush(buffer, 0);
        MetaData metaData = decoder.decode(buffer);
        HttpFields output = metaData.getFields();

        assertEquals(1, output.size());
        assertEquals("*", output.get(HttpHeader.ACCEPT));
    }

    @Test
    public void testTETrailers() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(16384);

        String teValue = "trailers";
        String trailerValue = "Custom";
        HttpFields input = new HttpFields();
        input.add(HttpHeader.CONNECTION, "TE");
        input.add(HttpHeader.TE, teValue);
        input.add(HttpHeader.TRAILER, trailerValue);

        ByteBuffer buffer = BufferUtil.allocate(2048);
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, input));
        BufferUtil.flipToFlush(buffer, 0);
        MetaData metaData = decoder.decode(buffer);
        HttpFields output = metaData.getFields();

        assertEquals(2, output.size());
        assertEquals(teValue, output.get(HttpHeader.TE));
        assertEquals(trailerValue, output.get(HttpHeader.TRAILER));
    }

    @Test
    public void testColonHeaders() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(16384);

        HttpFields input = new HttpFields();
        input.put(":status", "200");
        input.put(":custom", "special");

        ByteBuffer buffer = BufferUtil.allocate(2048);
        BufferUtil.clearToFill(buffer);
        assertThrows(HpackException.StreamException.class, () -> encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, input)));

        encoder.setValidateEncoding(false);
        encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, input));

        BufferUtil.flipToFlush(buffer, 0);
        assertThrows(HpackException.StreamException.class, () -> decoder.decode(buffer));
    }

    private void assertMetaDataResponseSame(MetaData.Response expected, MetaData.Response actual)
    {
        assertThat("Response.status", actual.getStatus(), is(expected.getStatus()));
        assertThat("Response.reason", actual.getReason(), is(expected.getReason()));
        assertMetaDataSame(expected, actual);
    }

    private void assertMetaDataSame(MetaData expected, MetaData actual)
    {
        assertThat("Metadata.contentLength", actual.getContentLength(), is(expected.getContentLength()));
        assertThat("Metadata.version" + ".version", actual.getHttpVersion(), is(expected.getHttpVersion()));
        assertHttpFieldsSame(expected.getFields(), actual.getFields());
    }

    private void assertHttpFieldsSame(HttpFields expected, HttpFields actual)
    {
        assertThat("metaData.fields.size", actual.size(), is(expected.size()));

        for (HttpField actualField : actual)
        {
            if ("DATE".equalsIgnoreCase(actualField.getName()))
            {
                // skip comparison on Date, as these values can often differ by 1 second
                // during testing.
                continue;
            }
            assertThat("metaData.fields.contains(" + actualField + ")", expected.contains(actualField), is(true));
        }
    }
}
