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
import java.util.Iterator;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackException.CompressionException;
import org.eclipse.jetty.http2.hpack.HpackException.SessionException;
import org.eclipse.jetty.http2.hpack.HpackException.StreamException;
import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeaderValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class HpackDecoderTest
{
    /*
      0   1   2   3   4   5   6   7
     +---+---+---+---+---+---+---+---+
     | 0 | 0 | 0 | 0 |       0       |
     +---+---+-----------------------+
     | H |     Name Length (7+)      |
     +---+---------------------------+
     |  Name String (Length octets)  |
     +---+---------------------------+
     | H |     Value Length (7+)     |
     +---+---------------------------+
     | Value String (Length octets)  |
     +-------------------------------+
     */

    @Test
    public void testDecodeD3() throws Exception
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        // First request
        String encoded = "828684410f7777772e6578616d706c652e636f6d";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(), request.getURI().getScheme());
        assertEquals("/", request.getURI().getPath());
        assertEquals("www.example.com", request.getURI().getHost());
        assertFalse(request.iterator().hasNext());

        // Second request
        encoded = "828684be58086e6f2d6361636865";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(), request.getURI().getScheme());
        assertEquals("/", request.getURI().getPath());
        assertEquals("www.example.com", request.getURI().getHost());
        Iterator<HttpField> iterator = request.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(new HttpField("cache-control", "no-cache"), iterator.next());
        assertFalse(iterator.hasNext());

        // Third request
        encoded = "828785bf400a637573746f6d2d6b65790c637573746f6d2d76616c7565";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTPS.asString(), request.getURI().getScheme());
        assertEquals("/index.html", request.getURI().getPath());
        assertEquals("www.example.com", request.getURI().getHost());
        iterator = request.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(new HttpField("custom-key", "custom-value"), iterator.next());
        assertFalse(iterator.hasNext());
    }

    @Test
    public void testDecodeD4() throws Exception
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        // First request
        String encoded = "828684418cf1e3c2e5f23a6ba0ab90f4ff";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(), request.getURI().getScheme());
        assertEquals("/", request.getURI().getPath());
        assertEquals("www.example.com", request.getURI().getHost());
        assertFalse(request.iterator().hasNext());

        // Second request
        encoded = "828684be5886a8eb10649cbf";
        buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(), request.getURI().getScheme());
        assertEquals("/", request.getURI().getPath());
        assertEquals("www.example.com", request.getURI().getHost());
        Iterator<HttpField> iterator = request.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(new HttpField("cache-control", "no-cache"), iterator.next());
        assertFalse(iterator.hasNext());
    }

    @Test
    public void testDecodeWithArrayOffset() throws Exception
    {
        String value = "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==";

        HpackDecoder decoder = new HpackDecoder(4096, 8192);
        String encoded = "8682418cF1E3C2E5F23a6bA0Ab90F4Ff841f0822426173696320515778685a475270626a70766347567549484e6c633246745a513d3d";
        byte[] bytes = TypeUtil.fromHexString(encoded);
        byte[] array = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, array, 1, bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(array, 1, bytes.length).slice();

        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(), request.getURI().getScheme());
        assertEquals("/", request.getURI().getPath());
        assertEquals("www.example.com", request.getURI().getHost());
        assertEquals(1, request.getFields().size());
        HttpField field = request.iterator().next();
        assertEquals(HttpHeader.AUTHORIZATION, field.getHeader());
        assertEquals(value, field.getValue());
    }

    @Test
    public void testDecodeHuffmanWithArrayOffset() throws Exception
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "8286418cf1e3c2e5f23a6ba0ab90f4ff84";
        byte[] bytes = TypeUtil.fromHexString(encoded);
        byte[] array = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, array, 1, bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(array, 1, bytes.length).slice();

        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(), request.getURI().getScheme());
        assertEquals("/", request.getURI().getPath());
        assertEquals("www.example.com", request.getURI().getHost());
        assertFalse(request.iterator().hasNext());
    }

    @Test
    public void testNghttpx() throws Exception
    {
        // Response encoded by nghttpx
        String encoded = "886196C361Be940b6a65B6850400B8A00571972e080a62D1Bf5f87497cA589D34d1f9a0f0d0234327690Aa69D29aFcA954D3A5358980Ae112e0f7c880aE152A9A74a6bF3";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        HpackDecoder decoder = new HpackDecoder(4096, 8192);
        MetaData.Response response = (MetaData.Response)decoder.decode(buffer);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getFields().size(), is(6));
        assertThat(response.getFields(), containsHeaderValue(HttpHeader.DATE, "Fri, 15 Jul 2016 02:36:20 GMT"));
        assertThat(response.getFields(), containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/html"));
        assertThat(response.getFields(), containsHeaderValue(HttpHeader.CONTENT_ENCODING, ""));
        assertThat(response.getFields(), containsHeaderValue(HttpHeader.CONTENT_LENGTH, "42"));
        assertThat(response.getFields(), containsHeaderValue(HttpHeader.SERVER, "nghttpx nghttp2/1.12.0"));
        assertThat(response.getFields(), containsHeaderValue(HttpHeader.VIA, "1.1 nghttpx"));
    }

    @Test
    public void testResize() throws Exception
    {
        String encoded = "203f136687A0E41d139d090760881c6490B2Cd39Ba7f";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        HpackDecoder decoder = new HpackDecoder(4096, 8192);
        MetaData metaData = decoder.decode(buffer);
        assertThat(metaData.getFields().get(HttpHeader.HOST), is("localhost0"));
        assertThat(metaData.getFields().get(HttpHeader.COOKIE), is("abcdefghij"));
        assertThat(decoder.getHpackContext().getMaxDynamicTableSize(), is(50));
        assertThat(decoder.getHpackContext().size(), is(1));
    }

    @Test
    public void testBadResize() throws Exception
    {
        /*
        4. Dynamic Table Management
        4.2. Maximum Table Size
          × 1: Sends a dynamic table size update at the end of header block
            -> The endpoint MUST treat this as a decoding error.
               Expected: GOAWAY Frame (Error Code: COMPRESSION_ERROR)
                         Connection closed
        */

        String encoded = "203f136687A0E41d139d090760881c6490B2Cd39Ba7f20";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        HpackDecoder decoder = new HpackDecoder(4096, 8192);
        try
        {
            decoder.decode(buffer);
            fail();
        }
        catch (CompressionException e)
        {
            assertThat(e.getMessage(), Matchers.containsString("Dynamic table resize after fields"));
        }
    }

    @Test
    public void testTooBigToIndex() throws Exception
    {
        String encoded = "3f610f17FfEc02Df3990A190A0D4Ee5b3d2940Ec98Aa4a62D127D29e273a0aA20dEcAa190a503b262d8a2671D4A2672a927aA874988a2471D05510750c951139EdA2452a3a548cAa1aA90bE4B228342864A9E0D450A5474a92992a1aA513395448E3A0Aa17B96cFe3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f14E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F3E7Cf9f3e7cF9F353F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F7F54f";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        HpackDecoder decoder = new HpackDecoder(128, 8192);
        MetaData metaData = decoder.decode(buffer);

        assertThat(decoder.getHpackContext().getDynamicTableSize(), is(0));
        assertThat(metaData.getFields().get("host"), Matchers.startsWith("This is a very large field"));
    }

    @Test
    public void testUnknownIndex() throws Exception
    {
        String encoded = "BE";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        HpackDecoder decoder = new HpackDecoder(128, 8192);

        try
        {
            decoder.decode(buffer);
            fail();
        }
        catch (SessionException e)
        {
            assertThat(e.getMessage(), Matchers.startsWith("Unknown index"));
        }
    }

    /* 8.1.2.1. Pseudo-Header Fields */
    @Test
    public void test8121PseudoHeaderFields() throws Exception
    {
        // 1:Sends a HEADERS frame that contains a unknown pseudo-header field
        MetaDataBuilder mdb = new MetaDataBuilder(4096);
        mdb.emit(new HttpField(":unknown", "value"));
        try
        {
            mdb.build();
            fail();
        }
        catch (StreamException ex)
        {
            assertThat(ex.getMessage(), Matchers.containsString("Unknown pseudo header"));
        }

        // 2: Sends a HEADERS frame that contains the pseudo-header field defined for response
        mdb = new MetaDataBuilder(4096);
        mdb.emit(new HttpField(HttpHeader.C_SCHEME, "http"));
        mdb.emit(new HttpField(HttpHeader.C_METHOD, "GET"));
        mdb.emit(new HttpField(HttpHeader.C_PATH, "/path"));
        mdb.emit(new HttpField(HttpHeader.C_STATUS, "100"));
        try
        {
            mdb.build();
            fail();
        }
        catch (StreamException ex)
        {
            assertThat(ex.getMessage(), Matchers.containsString("Request and Response headers"));
        }

        // 3: Sends a HEADERS frame that contains a pseudo-header field as trailers

        // 4: Sends a HEADERS frame that contains a pseudo-header field that appears in a header block after a regular header field
        mdb = new MetaDataBuilder(4096);
        mdb.emit(new HttpField(HttpHeader.C_SCHEME, "http"));
        mdb.emit(new HttpField(HttpHeader.C_METHOD, "GET"));
        mdb.emit(new HttpField(HttpHeader.C_PATH, "/path"));
        mdb.emit(new HttpField("Accept", "No Compromise"));
        mdb.emit(new HttpField(HttpHeader.C_AUTHORITY, "localhost"));
        try
        {
            mdb.build();
            fail();
        }
        catch (StreamException ex)
        {
            assertThat(ex.getMessage(), Matchers.containsString("Pseudo header :authority after fields"));
        }
    }

    @Test
    public void test8122ConnectionSpecificHeaderFields() throws Exception
    {
        MetaDataBuilder mdb;

        // 1: Sends a HEADERS frame that contains the connection-specific header field
        mdb = new MetaDataBuilder(4096);
        mdb.emit(new HttpField(HttpHeader.CONNECTION, "value"));
        try
        {
            mdb.build();
            fail();
        }
        catch (StreamException ex)
        {
            assertThat(ex.getMessage(), Matchers.containsString("Connection specific field 'Connection'"));
        }

        // 2: Sends a HEADERS frame that contains the TE header field with any value other than "trailers"
        mdb = new MetaDataBuilder(4096);
        mdb.emit(new HttpField(HttpHeader.TE, "not_trailers"));
        try
        {
            mdb.build();
            fail();
        }
        catch (StreamException ex)
        {
            assertThat(ex.getMessage(), Matchers.containsString("Unsupported TE value 'not_trailers'"));
        }

        mdb = new MetaDataBuilder(4096);
        mdb.emit(new HttpField(HttpHeader.CONNECTION, "TE"));
        mdb.emit(new HttpField(HttpHeader.TE, "trailers"));
        assertNotNull(mdb.build());
    }

    @Test
    public void test8123RequestPseudoHeaderFields() throws Exception
    {
        {
            MetaDataBuilder mdb = new MetaDataBuilder(4096);
            mdb.emit(new HttpField(HttpHeader.C_METHOD, "GET"));
            mdb.emit(new HttpField(HttpHeader.C_SCHEME, "http"));
            mdb.emit(new HttpField(HttpHeader.C_AUTHORITY, "localhost:8080"));
            mdb.emit(new HttpField(HttpHeader.C_PATH, "/"));
            assertThat(mdb.build(), Matchers.instanceOf(MetaData.Request.class));
        }

        {
            // 1: Sends a HEADERS frame with empty ":path" pseudo-header field
            final MetaDataBuilder mdb = new MetaDataBuilder(4096);
            mdb.emit(new HttpField(HttpHeader.C_METHOD, "GET"));
            mdb.emit(new HttpField(HttpHeader.C_SCHEME, "http"));
            mdb.emit(new HttpField(HttpHeader.C_AUTHORITY, "localhost:8080"));
            mdb.emit(new HttpField(HttpHeader.C_PATH, ""));
            StreamException ex = assertThrows(StreamException.class, mdb::build);
            assertThat(ex.getMessage(), Matchers.containsString("No Path"));
        }

        {
            // 2: Sends a HEADERS frame that omits ":method" pseudo-header field
            final MetaDataBuilder mdb = new MetaDataBuilder(4096);
            mdb.emit(new HttpField(HttpHeader.C_SCHEME, "http"));
            mdb.emit(new HttpField(HttpHeader.C_AUTHORITY, "localhost:8080"));
            mdb.emit(new HttpField(HttpHeader.C_PATH, "/"));
            StreamException ex = assertThrows(StreamException.class, mdb::build);
            assertThat(ex.getMessage(), Matchers.containsString("No Method"));
        }

        {
            // 3: Sends a HEADERS frame that omits ":scheme" pseudo-header field
            final MetaDataBuilder mdb = new MetaDataBuilder(4096);
            mdb.emit(new HttpField(HttpHeader.C_METHOD, "GET"));
            mdb.emit(new HttpField(HttpHeader.C_AUTHORITY, "localhost:8080"));
            mdb.emit(new HttpField(HttpHeader.C_PATH, "/"));
            StreamException ex = assertThrows(StreamException.class, mdb::build);
            assertThat(ex.getMessage(), Matchers.containsString("No Scheme"));
        }

        {
            // 4: Sends a HEADERS frame that omits ":path" pseudo-header field
            final MetaDataBuilder mdb = new MetaDataBuilder(4096);
            mdb.emit(new HttpField(HttpHeader.C_METHOD, "GET"));
            mdb.emit(new HttpField(HttpHeader.C_SCHEME, "http"));
            mdb.emit(new HttpField(HttpHeader.C_AUTHORITY, "localhost:8080"));
            StreamException ex = assertThrows(StreamException.class, mdb::build);
            assertThat(ex.getMessage(), Matchers.containsString("No Path"));
        }

        {
            // 5: Sends a HEADERS frame with duplicated ":method" pseudo-header field
            final MetaDataBuilder mdb = new MetaDataBuilder(4096);
            mdb.emit(new HttpField(HttpHeader.C_METHOD, "GET"));
            mdb.emit(new HttpField(HttpHeader.C_METHOD, "GET"));
            mdb.emit(new HttpField(HttpHeader.C_SCHEME, "http"));
            mdb.emit(new HttpField(HttpHeader.C_AUTHORITY, "localhost:8080"));
            mdb.emit(new HttpField(HttpHeader.C_PATH, "/"));
            StreamException ex = assertThrows(StreamException.class, mdb::build);
            assertThat(ex.getMessage(), Matchers.containsString("Duplicate"));
        }

        {
            // 6: Sends a HEADERS frame with duplicated ":scheme" pseudo-header field
            final MetaDataBuilder mdb = new MetaDataBuilder(4096);
            mdb.emit(new HttpField(HttpHeader.C_METHOD, "GET"));
            mdb.emit(new HttpField(HttpHeader.C_SCHEME, "http"));
            mdb.emit(new HttpField(HttpHeader.C_SCHEME, "http"));
            mdb.emit(new HttpField(HttpHeader.C_AUTHORITY, "localhost:8080"));
            mdb.emit(new HttpField(HttpHeader.C_PATH, "/"));

            StreamException ex = assertThrows(StreamException.class, mdb::build);
            assertThat(ex.getMessage(), Matchers.containsString("Duplicate"));
        }
    }

    @Test
    public void testHuffmanEncodedStandard() throws Exception
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "82868441" + "83" + "49509F";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        MetaData.Request request = (MetaData.Request)decoder.decode(buffer);

        assertEquals("GET", request.getMethod());
        assertEquals(HttpScheme.HTTP.asString(), request.getURI().getScheme());
        assertEquals("/", request.getURI().getPath());
        assertEquals("test", request.getURI().getHost());
        assertFalse(request.iterator().hasNext());
    }

    /* 5.2.1: Sends a Huffman-encoded string literal representation with padding longer than 7 bits */
    @Test
    public void testHuffmanEncodedExtraPadding()
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "82868441" + "84" + "49509FFF";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        CompressionException ex = assertThrows(CompressionException.class, () -> decoder.decode(buffer));
        assertThat(ex.getMessage(), Matchers.containsString("Bad termination"));
    }

    /* 5.2.2: Sends a Huffman-encoded string literal representation padded by zero */
    @Test
    public void testHuffmanEncodedZeroPadding()
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "82868441" + "83" + "495090";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        CompressionException ex = assertThrows(CompressionException.class, () -> decoder.decode(buffer));
        assertThat(ex.getMessage(), Matchers.containsString("Incorrect padding"));
    }

    /* 5.2.3: Sends a Huffman-encoded string literal representation containing the EOS symbol */
    @Test
    public void testHuffmanEncodedWithEOS()
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "82868441" + "87" + "497FFFFFFF427F";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        CompressionException ex = assertThrows(CompressionException.class, () -> decoder.decode(buffer));
        assertThat(ex.getMessage(), Matchers.containsString("EOS in content"));
    }

    @Test
    public void testHuffmanEncodedOneIncompleteOctet()
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "82868441" + "81" + "FE";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        CompressionException ex = assertThrows(CompressionException.class, () -> decoder.decode(buffer));
        assertThat(ex.getMessage(), Matchers.containsString("Bad termination"));
    }

    @Test
    public void testHuffmanEncodedTwoIncompleteOctet()
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "82868441" + "82" + "FFFE";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));

        CompressionException ex = assertThrows(CompressionException.class, () -> decoder.decode(buffer));
        assertThat(ex.getMessage(), Matchers.containsString("Bad termination"));
    }

    @Test
    public void testZeroLengthName()
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "00000130";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        SessionException ex = assertThrows(SessionException.class, () -> decoder.decode(buffer));
        assertThat(ex.getMessage(), Matchers.containsString("Header size 0"));
    }

    @Test
    public void testZeroLengthValue() throws Exception
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "00016800";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        MetaData metaData = decoder.decode(buffer);
        assertThat(metaData.getFields().size(), is(1));
        assertThat(metaData.getFields().get("h"), is(""));
    }

    @Test
    public void testUpperCaseName()
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "0001480130";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        StreamException ex = assertThrows(StreamException.class, () -> decoder.decode(buffer));
        assertThat(ex.getMessage(), Matchers.containsString("Uppercase header"));
    }

    @Test
    public void testWhiteSpaceName()
    {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        String encoded = "0001200130";
        ByteBuffer buffer = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        StreamException ex = assertThrows(StreamException.class, () -> decoder.decode(buffer));
        assertThat(ex.getMessage(), Matchers.containsString("Illegal header"));
    }
}
