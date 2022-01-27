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

package org.eclipse.jetty.websocket.jakarta.tests.coders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.jakarta.client.internal.BasicClientEndpointConfig;
import org.eclipse.jetty.websocket.jakarta.common.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jakarta.common.encoders.IntegerEncoder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AvailableEncodersTest
{
    private static EndpointConfig testConfig;
    private final WebSocketComponents components = new WebSocketComponents();

    @BeforeAll
    public static void initConfig()
    {
        testConfig = new BasicClientEndpointConfig();
    }

    private final AvailableEncoders encoders = new AvailableEncoders(testConfig, components);

    public <T> void assertTextEncoder(Class<T> type, T value, String expectedEncoded) throws IllegalAccessException, InstantiationException, EncodeException
    {
        Encoder.Text<T> encoder = (Encoder.Text<T>)encoders.getInstanceFor(type);
        assertThat("Encoder", encoder, notNullValue());
        String encoded = encoder.encode(value);
        assertThat("Encoded", encoded, is(expectedEncoded));
    }

    public <T> void assertTextStreamEncoder(Class<T> type, T value, String expectedEncoded)
        throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        Encoder.TextStream<T> encoder = (Encoder.TextStream<T>)encoders.getInstanceFor(type);
        assertThat("Encoder", encoder, notNullValue());
        StringWriter writer = new StringWriter();
        encoder.encode(value, writer);

        assertThat("Encoded", writer.toString(), is(expectedEncoded));
    }

    public <T> void assertBinaryEncoder(Class<T> type, T value, String expectedEncodedHex)
        throws IllegalAccessException, InstantiationException, EncodeException
    {
        Encoder.Binary<T> encoder = (Encoder.Binary<T>)encoders.getInstanceFor(type);
        assertThat("Encoder", encoder, notNullValue());
        ByteBuffer encoded = encoder.encode(value);

        String hexEncoded = Hex.asHex(encoded);
        assertThat("Encoded", hexEncoded, is(expectedEncodedHex));
    }

    public <T> void assertBinaryStreamEncoder(Class<T> type, T value, String expectedEncodedHex)
        throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        Encoder.BinaryStream<T> encoder = (Encoder.BinaryStream<T>)encoders.getInstanceFor(type);
        assertThat("Encoder", encoder, notNullValue());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encoder.encode(value, out);

        String hexEncoded = Hex.asHex(out.toByteArray());

        assertThat("Encoded", hexEncoded, is(expectedEncodedHex));
    }

    @Test
    public void testCoreEncoderBoolean() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Boolean.class, Boolean.TRUE, "true");
    }

    @Test
    public void testCoreEncoderbool() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Boolean.TYPE, true, "true");
    }

    @Test
    public void testCoreEncoderByte() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Byte.class, (byte)0x21, "33");
    }

    @Test
    public void testCoreEncoderbyte() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Byte.TYPE, (byte)0x21, "33");
    }

    @Test
    public void testCoreEncoderCharacter() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Character.class, '!', "!");
    }

    @Test
    public void testCoreEncoderchar() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Character.TYPE, '!', "!");
    }

    @Test
    public void testCoreEncoderDouble() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Double.class, 123.45D, "123.45");
    }

    @Test
    public void testCoreEncoderdouble() throws IllegalAccessException, InstantiationException, EncodeException
    {
        //noinspection RedundantCast
        assertTextEncoder(Double.TYPE, 123.45D, "123.45");
    }

    @Test
    public void testCoreEncoderFloat() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Float.class, 123.4567f, "123.4567");
    }

    @Test
    public void testCoreEncoderfloat() throws IllegalAccessException, InstantiationException, EncodeException
    {
        //noinspection RedundantCast
        assertTextEncoder(Float.TYPE, 123.4567F, "123.4567");
    }

    @Test
    public void testCoreEncoderInteger() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Integer.class, 123, "123");
    }

    @Test
    public void testCoreEncoderint() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Integer.TYPE, 123, "123");
    }

    @Test
    public void testCoreEncoderLong() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Long.class, 123_456_789L, "123456789");
    }

    @Test
    public void testCoreEncoderlong() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Long.TYPE, 123_456_789L, "123456789");
    }

    @Test
    public void testCoreEncoderString() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(String.class, "Hello World", "Hello World");
    }

    @Test
    public void testCoreEncoderByteBuffer() throws IllegalAccessException, InstantiationException, EncodeException
    {
        ByteBuffer buf = Hex.asByteBuffer("1122334455");
        assertBinaryEncoder(ByteBuffer.class, buf, "1122334455");
    }

    @Test
    public void testCoreEncoderByteArray() throws IllegalAccessException, InstantiationException, EncodeException
    {
        byte[] buf = Hex.asByteArray("998877665544332211");
        assertBinaryEncoder(byte[].class, buf, "998877665544332211");
    }

    @Test
    public void testCustomEncoderInteger() throws IllegalAccessException, InstantiationException, EncodeException
    {
        encoders.register(IntegerEncoder.class);
        int val = 99887766;
        String expected = "99887766";
        assertTextEncoder(Integer.class, val, expected);
    }

    @Test
    public void testCustomEncoderTime() throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        encoders.register(TimeEncoder.class);

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 34);
        calendar.set(Calendar.SECOND, 56);

        Date val = calendar.getTime();
        assertTextEncoder(Date.class, val, "12:34:56 GMT");
    }

    @Test
    public void testCustomEncoderDate() throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        encoders.register(DateEncoder.class);

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

        calendar.set(Calendar.YEAR, 2016);
        calendar.set(Calendar.MONTH, Calendar.AUGUST);
        calendar.set(Calendar.DAY_OF_MONTH, 22);

        Date val = calendar.getTime();
        assertTextEncoder(Date.class, val, "2016.08.22");
    }

    @Test
    public void testCustomEncoderDateTime() throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        encoders.register(DateTimeEncoder.class);

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

        calendar.set(Calendar.YEAR, 2016);
        calendar.set(Calendar.MONTH, Calendar.AUGUST);
        calendar.set(Calendar.DAY_OF_MONTH, 22);

        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 34);
        calendar.set(Calendar.SECOND, 56);

        Date val = calendar.getTime();
        assertTextEncoder(Date.class, val, "2016.08.22 AD at 12:34:56 GMT");
    }

    @Test
    public void testCustomEncoderValidDualText() throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        encoders.register(ValidDualEncoder.class);
        assertTextEncoder(Integer.class, 1234567, "[1,234,567]");
    }

    @Test
    public void testCustomEncoderValidDualBinary() throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        encoders.register(ValidDualEncoder.class);
        long value = 0x112233445566L;
        assertBinaryStreamEncoder(Long.class, value, "5B00001122334455665D");
    }

    @Test
    public void testCustomEncoderRegisterDuplicate()
    {
        // has duplicated support for the same target Type
        Exception e = assertThrows(InvalidWebSocketException.class, () -> encoders.register(BadDualEncoder.class));
        assertThat(e.getMessage(), containsString("Duplicate"));
    }

    @Test
    public void testCustomEncoderRegisterOtherDuplicate()
    {
        // Register DateEncoder (decodes java.util.Date)
        encoders.register(DateEncoder.class);

        // Register TimeEncoder (which also wants to decode java.util.Date)
        Exception e = assertThrows(InvalidWebSocketException.class, () -> encoders.register(TimeEncoder.class));
        assertThat(e.getMessage(), containsString("Duplicate"));
    }
}
