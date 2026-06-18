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

import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MetaData.Response;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
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
        HpackDecoder decoder = new HpackDecoder(8192, NanoTime::now);
        WritableBuffer wb = WritableBuffer.allocate(16 * 1024, true);

        long contentLength = 1024;
        HttpFields.Mutable fields0 = HttpFields.build()
            .add(HttpHeader.CONTENT_TYPE, "text/html")
            .add(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength))
            .add(new HttpField(HttpHeader.CONTENT_ENCODING, (String)null))
            .add(ServerJetty)
            .add(XPowerJetty)
            .add(Date)
            .add(HttpHeader.SET_COOKIE, "abcdefghijklmnopqrstuvwxyz")
            .add("custom-key", "custom-value");
        Response original0 = new MetaData.Response(200, null, HttpVersion.HTTP_2, fields0, contentLength);

        Response nullToEmpty = new MetaData.Response(200, null, HttpVersion.HTTP_2, fields0.put(new HttpField(HttpHeader.CONTENT_ENCODING, "")), contentLength);
        {
            encoder.encode(wb, original0);
            ReadableBuffer rb = wb.toReadable();
            Response decoded0 = (Response)decoder.decode(rb);
            rb.toWritable();

            assertMetaDataResponseSame(nullToEmpty, decoded0);
        }

        // Same again?
        {
            wb.position(0);
            encoder.encode(wb, original0);
            ReadableBuffer rb = wb.toReadable();
            Response decoded0b = (Response)decoder.decode(rb);
            rb.toWritable();

            assertMetaDataResponseSame(nullToEmpty, decoded0b);
        }

        contentLength = 1234;
        HttpFields.Mutable fields1 = HttpFields.build()
            .add(HttpHeader.CONTENT_TYPE, "text/plain")
            .add(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength))
            .add(HttpHeader.CONTENT_ENCODING, "")
            .add(ServerJetty)
            .add(XPowerJetty)
            .add(Date)
            .add("Custom-Key", "Other-Value");
        Response original1 = new MetaData.Response(200, null, HttpVersion.HTTP_2, fields1, contentLength);

        // Same again?
        {
            wb.position(0);
            encoder.encode(wb, original1);
            ReadableBuffer rb = wb.toReadable();
            Response decoded1 = (Response)decoder.decode(rb);
            rb.toWritable();

            assertMetaDataResponseSame(original1, decoded1);
            assertEquals("custom-key", decoded1.getHttpFields().getField("Custom-Key").getName());
        }
    }

    @Test
    public void encodeDecodeTooLargeTest() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(164, NanoTime::now);
        WritableBuffer wb = WritableBuffer.allocate(16 * 1024, true);

        HttpFields fields0 = HttpFields.build()
            .add("1234567890", "1234567890123456789012345678901234567890")
            .add("Cookie", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR");
        MetaData original0 = new MetaData(HttpVersion.HTTP_2, fields0);

        {
            encoder.encode(wb, original0);
            ReadableBuffer rb = wb.toReadable();
            MetaData decoded0 = decoder.decode(rb);

            assertMetaDataSame(original0, decoded0);
        }

        HttpFields fields1 = HttpFields.build()
            .add("1234567890", "1234567890123456789012345678901234567890")
            .add("Cookie", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR")
            .add("x", "y");
        MetaData original1 = new MetaData(HttpVersion.HTTP_2, fields1);

        wb.position(0);
        encoder.encode(wb, original1);
        ReadableBuffer rb = wb.toReadable();
        try
        {
            decoder.decode(rb);
            rb.toWritable();
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
        WritableBuffer buffer = WritableBuffer.allocate(16 * 1024, false);

        HttpFields fields0 = HttpFields.build()
            // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
            .add("Cookie", "[\uD842\uDF9F]")
            .add("custom-key", "[\uD842\uDF9F]");
        Response original0 = new MetaData.Response(200, null, HttpVersion.HTTP_2, fields0);

        HpackException.StreamException throwable = assertThrows(HpackException.StreamException.class, () -> encoder.encode(buffer, original0));

        assertThat(throwable.getMessage(), containsString("Invalid header value"));
    }

    @Test
    public void evictReferencedFieldTest() throws Exception
    {
        HpackDecoder decoder = new HpackDecoder(1024, NanoTime::now);
        decoder.setMaxTableCapacity(200);
        HpackEncoder encoder = new HpackEncoder();
        encoder.setMaxTableCapacity(decoder.getMaxTableCapacity());
        encoder.setTableCapacity(decoder.getMaxTableCapacity());
        WritableBuffer wb = WritableBuffer.allocate(16 * 1024, true);

        String longEnoughToBeEvicted = "012345678901234567890123456789012345678901234567890";

        HttpFields fields0 = HttpFields.build()
            .add(longEnoughToBeEvicted, "value")
            .add("foo", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
        MetaData original0 = new MetaData(HttpVersion.HTTP_2, fields0);

        {
            encoder.encode(wb, original0);
            ReadableBuffer rb = wb.toReadable();
            MetaData decoded0 = decoder.decode(rb);
            rb.toWritable();

            assertEquals(2, encoder.getHpackContext().size());
            assertEquals(2, decoder.getHpackContext().size());
            assertEquals(longEnoughToBeEvicted, encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length + 1).getHttpField().getName());
            assertEquals("foo", encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length).getHttpField().getName());

            assertMetaDataSame(original0, decoded0);
        }

        HttpFields fields1 = HttpFields.build()
            .add(longEnoughToBeEvicted, "other_value")
            .add("x", "y");
        MetaData original1 = new MetaData(HttpVersion.HTTP_2, fields1);

        {
            wb.position(0);
            encoder.encode(wb, original1);
            ReadableBuffer rb = wb.toReadable();
            MetaData decoded1 = decoder.decode(rb);
            rb.toWritable();
            assertMetaDataSame(original1, decoded1);
        }

        assertEquals(2, encoder.getHpackContext().size());
        assertEquals(2, decoder.getHpackContext().size());
        assertEquals("x", encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length).getHttpField().getName());
        assertEquals("foo", encoder.getHpackContext().get(HpackContext.STATIC_TABLE.length + 1).getHttpField().getName());
    }

    @Test
    public void testHopHeadersAreRemoved() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(16384, NanoTime::now);

        HttpFields input = HttpFields.build()
            .add(HttpHeader.ACCEPT, "*")
            .add(HttpHeader.CONNECTION, "TE, Upgrade, Custom")
            .add("Custom", "Pizza")
            .add(HttpHeader.KEEP_ALIVE, "true")
            .add(HttpHeader.PROXY_CONNECTION, "foo")
            .add(HttpHeader.TE, "1234567890abcdef")
            .add(HttpHeader.TRANSFER_ENCODING, "chunked")
            .add(HttpHeader.UPGRADE, "gold");

        WritableBuffer wb = WritableBuffer.allocate(2048, false);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, input));
        ReadableBuffer rb = wb.toReadable();
        MetaData metaData = decoder.decode(rb);
        HttpFields output = metaData.getHttpFields();

        assertEquals(1, output.size());
        assertEquals("*", output.get(HttpHeader.ACCEPT));
    }

    @Test
    public void testTETrailers() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(16384, NanoTime::now);

        String teValue = "trailers";
        String trailerValue = "Custom";
        HttpFields input = HttpFields.build()
            .add(HttpHeader.CONNECTION, "TE")
            .add(HttpHeader.TE, teValue)
            .add(HttpHeader.TRAILER, trailerValue);

        WritableBuffer wb = WritableBuffer.allocate(2048, false);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, input));
        ReadableBuffer rb = wb.toReadable();
        MetaData metaData = decoder.decode(rb);
        HttpFields output = metaData.getHttpFields();

        assertEquals(2, output.size());
        assertEquals(teValue, output.get(HttpHeader.TE));
        assertEquals(trailerValue, output.get(HttpHeader.TRAILER));
    }

    @Test
    public void testColonHeaders() throws Exception
    {
        HpackEncoder encoder = new HpackEncoder();
        HpackDecoder decoder = new HpackDecoder(16384, NanoTime::now);

        HttpFields input = HttpFields.build()
            .add(":status", "200")
            .add(":custom", "special");

        WritableBuffer wb = WritableBuffer.allocate(2048, false);
        assertThrows(HpackException.StreamException.class, () -> encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, input)));

        encoder.setValidateEncoding(false);
        encoder.encode(wb, new MetaData(HttpVersion.HTTP_2, input));

        ReadableBuffer rb = wb.toReadable();
        assertThrows(HpackException.StreamException.class, () -> decoder.decode(rb));
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
        assertHttpFieldsSame(expected.getHttpFields(), actual.getHttpFields());
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
