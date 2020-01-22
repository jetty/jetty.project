//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests.coders;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.websocket.javax.client.BasicClientEndpointConfig;
import org.eclipse.jetty.websocket.javax.common.InvalidWebSocketException;
import org.eclipse.jetty.websocket.javax.common.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.javax.common.decoders.IntegerDecoder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AvailableDecodersTest
{
    private static EndpointConfig testConfig;

    @BeforeAll
    public static void initConfig()
    {
        testConfig = new BasicClientEndpointConfig();
    }

    private AvailableDecoders decoders = new AvailableDecoders(testConfig);

    private <T> void assertTextDecoder(Class<T> type, String value, T expectedDecoded) throws IllegalAccessException, InstantiationException, DecodeException
    {
        Decoder.Text<T> decoder = (Decoder.Text<T>)decoders.getInstanceFor(type);
        assertThat("Decoder instance", decoder, notNullValue());
        T decoded = decoder.decode(value);
        assertThat("Decoded", decoded, is(expectedDecoded));
    }

    private <T> void assertBinaryDecoder(Class<T> type, ByteBuffer value, T expectedDecoded)
        throws IllegalAccessException, InstantiationException, DecodeException
    {
        Decoder.Binary<T> decoder = (Decoder.Binary<T>)decoders.getInstanceFor(type);
        assertThat("Decoder Class", decoder, notNullValue());
        T decoded = decoder.decode(value);
        assertThat("Decoded", decoded, equalTo(expectedDecoded));
    }

    @Test
    public void testCoreDecodeBoolean() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Boolean expected = Boolean.TRUE;
        assertTextDecoder(Boolean.class, "true", expected);
    }

    @Test
    public void testCoreDecodeboolean() throws IllegalAccessException, InstantiationException, DecodeException
    {
        boolean expected = false;
        assertTextDecoder(Boolean.TYPE, "false", expected);
    }

    @Test
    public void testCoreDecodeByte() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Byte expected = (byte)0x21;
        assertTextDecoder(Byte.class, "33", expected);
    }

    @Test
    public void testCoreDecodebyte() throws IllegalAccessException, InstantiationException, DecodeException
    {
        byte expected = 0x21;
        assertTextDecoder(Byte.TYPE, "33", expected);
    }

    @Test
    public void testCoreDecodeCharacter() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Character expected = '!';
        assertTextDecoder(Character.class, "!", expected);
    }

    @Test
    public void testCoreDecodechar() throws IllegalAccessException, InstantiationException, DecodeException
    {
        char expected = '!';
        assertTextDecoder(Character.TYPE, "!", expected);
    }

    @Test
    public void testCoreDecodeDouble() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Double expected = 123.45D;
        assertTextDecoder(Double.class, "123.45", expected);
    }

    @Test
    public void testCoreDecodedouble() throws IllegalAccessException, InstantiationException, DecodeException
    {
        double expected = 123.45D;
        assertTextDecoder(Double.TYPE, "123.45", expected);
    }

    @Test
    public void testCoreDecodeFloat() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Float expected = 123.4567F;
        assertTextDecoder(Float.class, "123.4567", expected);
    }

    @Test
    public void testCoreDecodefloat() throws IllegalAccessException, InstantiationException, DecodeException
    {
        float expected = 123.4567F;
        assertTextDecoder(Float.TYPE, "123.4567", expected);
    }

    @Test
    public void testCoreDecodeInteger() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Integer expected = 1234;
        assertTextDecoder(Integer.class, "1234", expected);
    }

    @Test
    public void testCoreDecodeint() throws IllegalAccessException, InstantiationException, DecodeException
    {
        int expected = 1234;
        assertTextDecoder(Integer.TYPE, "1234", expected);
    }

    @Test
    public void testCoreDecodeLong() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Long expected = 123_456_789L;
        assertTextDecoder(Long.class, "123456789", expected);
    }

    @Test
    public void testCoreDecodelong() throws IllegalAccessException, InstantiationException, DecodeException
    {
        long expected = 123_456_789L;
        assertTextDecoder(Long.TYPE, "123456789", expected);
    }

    @Test
    public void testCoreDecodeString() throws IllegalAccessException, InstantiationException, DecodeException
    {
        String expected = "Hello World";
        assertTextDecoder(String.class, "Hello World", expected);
    }

    @Test
    public void testCoreDecodeByteBuffer() throws IllegalAccessException, InstantiationException, DecodeException
    {
        ByteBuffer val = Hex.asByteBuffer("112233445566778899");
        ByteBuffer expected = Hex.asByteBuffer("112233445566778899");
        assertBinaryDecoder(ByteBuffer.class, val, expected);
    }

    @Test
    public void testCoreDecodeByteArray() throws IllegalAccessException, InstantiationException, DecodeException
    {
        ByteBuffer val = Hex.asByteBuffer("112233445566778899");
        byte[] expected = Hex.asByteArray("112233445566778899");
        assertBinaryDecoder(byte[].class, val, expected);
    }

    @Test
    public void testCustomDecoderInteger() throws IllegalAccessException, InstantiationException, DecodeException
    {
        decoders.register(IntegerDecoder.class);

        String val = "11223344";
        int expected = 11223344;
        assertTextDecoder(Integer.class, val, expected);
    }

    @Test
    public void testCustomDecoderTime() throws IllegalAccessException, InstantiationException, DecodeException
    {
        decoders.register(TimeDecoder.class);

        String val = "12:34:56 GMT";

        Date epoch = Date.from(Instant.EPOCH);
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        calendar.setTime(epoch);

        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 34);
        calendar.set(Calendar.SECOND, 56);

        Date expected = calendar.getTime();
        assertTextDecoder(Date.class, val, expected);
    }

    @Test
    public void testCustomDecoderDate() throws IllegalAccessException, InstantiationException, DecodeException
    {
        decoders.register(DateDecoder.class);

        String val = "2016.08.22";

        Date epoch = Date.from(Instant.EPOCH);
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        calendar.setTime(epoch);

        calendar.set(Calendar.YEAR, 2016);
        calendar.set(Calendar.MONTH, Calendar.AUGUST);
        calendar.set(Calendar.DAY_OF_MONTH, 22);

        Date expected = calendar.getTime();
        assertTextDecoder(Date.class, val, expected);
    }

    @Test
    public void testCustomDecoderDateTime() throws IllegalAccessException, InstantiationException, DecodeException
    {
        decoders.register(DateTimeDecoder.class);

        String val = "2016.08.22 AD at 12:34:56 GMT";

        Date epoch = Date.from(Instant.EPOCH);
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        calendar.setTime(epoch);

        calendar.set(Calendar.YEAR, 2016);
        calendar.set(Calendar.MONTH, Calendar.AUGUST);
        calendar.set(Calendar.DAY_OF_MONTH, 22);

        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 34);
        calendar.set(Calendar.SECOND, 56);

        Date expected = calendar.getTime();
        assertTextDecoder(Date.class, val, expected);
    }

    @Test
    public void testCustomDecoderValidDualText() throws IllegalAccessException, InstantiationException, DecodeException
    {
        decoders.register(ValidDualDecoder.class);

        AvailableDecoders.RegisteredDecoder registered = decoders.getRegisteredDecoderFor(Integer.class);
        assertThat("Registered Decoder for Integer", registered.decoder.getName(), is(ValidDualDecoder.class.getName()));

        String val = "[1,234,567]";
        Integer expected = 1234567;

        assertTextDecoder(Integer.class, val, expected);
    }

    @Test
    public void testCustomDecoderValidDualBinary() throws IllegalAccessException, InstantiationException, DecodeException
    {
        decoders.register(ValidDualDecoder.class);

        AvailableDecoders.RegisteredDecoder registered = decoders.getRegisteredDecoderFor(Long.class);
        assertThat("Registered Decoder for Long", registered.decoder.getName(), is(ValidDualDecoder.class.getName()));

        ByteBuffer val = ByteBuffer.allocate(16);
        val.put((byte)'[');
        val.putLong(0x112233445566L);
        val.put((byte)']');
        val.flip();
        Long expected = 0x112233445566L;

        assertBinaryDecoder(Long.class, val, expected);
    }

    @Test
    public void testCustomDecoderRegisterDuplicate()
    {
        // has duplicated support for the same target Type
        Exception e = assertThrows(InvalidWebSocketException.class, () -> decoders.register(BadDualDecoder.class));
        assertThat(e.getMessage(), containsString("Duplicate"));
    }

    @Test
    public void testCustomDecoderRegisterOtherDuplicate()
    {
        // Register DateDecoder (decodes java.util.Date)
        decoders.register(DateDecoder.class);

        // Register TimeDecoder (which also wants to decode java.util.Date)
        Exception e = assertThrows(InvalidWebSocketException.class, () -> decoders.register(TimeDecoder.class));
        assertThat(e.getMessage(), containsString("Duplicate"));
    }
}
