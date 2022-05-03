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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.coders;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.TimeZone;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.AvailableDecoders;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.IntegerDecoder;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AvailableDecodersTest
{
    private AvailableDecoders availableDecoders;
    private WebSocketComponents components = new WebSocketComponents();

    @SafeVarargs
    public final void init(Class<? extends Decoder>... decoder)
    {
        EndpointConfig testConfig = ClientEndpointConfig.Builder.create()
            .decoders(Arrays.asList(decoder))
            .build();
        this.availableDecoders = new AvailableDecoders(testConfig, components);
    }

    public <T extends Decoder> T getInstanceFor(Class<?> type)
    {
        try
        {
            RegisteredDecoder registeredDecoder = availableDecoders.getFirstRegisteredDecoder(type);
            return registeredDecoder.getInstance();
        }
        catch (NoSuchElementException e)
        {
            throw new InvalidWebSocketException("No Decoder found for type " + type);
        }
    }

    private <T> void assertTextDecoder(Class<T> type, String value, T expectedDecoded) throws DecodeException
    {
        Decoder.Text<T> decoder = getInstanceFor(type);
        assertThat("Decoder instance", decoder, notNullValue());
        T decoded = decoder.decode(value);
        assertThat("Decoded", decoded, is(expectedDecoded));
    }

    private <T> void assertBinaryDecoder(Class<T> type, ByteBuffer value, T expectedDecoded)
        throws DecodeException
    {
        Decoder.Binary<T> decoder = getInstanceFor(type);
        assertThat("Decoder Class", decoder, notNullValue());
        T decoded = decoder.decode(value);
        assertThat("Decoded", decoded, equalTo(expectedDecoded));
    }

    @Test
    public void testCoreDecodeBoolean() throws DecodeException
    {
        init();
        Boolean expected = Boolean.TRUE;
        assertTextDecoder(Boolean.class, "true", expected);
    }

    @Test
    public void testCoreDecodeboolean() throws DecodeException
    {
        init();
        boolean expected = false;
        assertTextDecoder(Boolean.TYPE, "false", expected);
    }

    @Test
    public void testCoreDecodeByte() throws DecodeException
    {
        init();
        Byte expected = (byte)0x21;
        assertTextDecoder(Byte.class, "33", expected);
    }

    @Test
    public void testCoreDecodebyte() throws DecodeException
    {
        init();
        byte expected = 0x21;
        assertTextDecoder(Byte.TYPE, "33", expected);
    }

    @Test
    public void testCoreDecodeCharacter() throws DecodeException
    {
        init();
        Character expected = '!';
        assertTextDecoder(Character.class, "!", expected);
    }

    @Test
    public void testCoreDecodechar() throws DecodeException
    {
        init();
        char expected = '!';
        assertTextDecoder(Character.TYPE, "!", expected);
    }

    @Test
    public void testCoreDecodeDouble() throws DecodeException
    {
        init();
        Double expected = 123.45D;
        assertTextDecoder(Double.class, "123.45", expected);
    }

    @Test
    public void testCoreDecodedouble() throws DecodeException
    {
        init();
        double expected = 123.45D;
        assertTextDecoder(Double.TYPE, "123.45", expected);
    }

    @Test
    public void testCoreDecodeFloat() throws DecodeException
    {
        init();
        Float expected = 123.4567F;
        assertTextDecoder(Float.class, "123.4567", expected);
    }

    @Test
    public void testCoreDecodefloat() throws DecodeException
    {
        init();
        float expected = 123.4567F;
        assertTextDecoder(Float.TYPE, "123.4567", expected);
    }

    @Test
    public void testCoreDecodeInteger() throws DecodeException
    {
        init();
        Integer expected = 1234;
        assertTextDecoder(Integer.class, "1234", expected);
    }

    @Test
    public void testCoreDecodeint() throws DecodeException
    {
        init();
        int expected = 1234;
        assertTextDecoder(Integer.TYPE, "1234", expected);
    }

    @Test
    public void testCoreDecodeLong() throws DecodeException
    {
        init();
        Long expected = 123_456_789L;
        assertTextDecoder(Long.class, "123456789", expected);
    }

    @Test
    public void testCoreDecodelong() throws DecodeException
    {
        init();
        long expected = 123_456_789L;
        assertTextDecoder(Long.TYPE, "123456789", expected);
    }

    @Test
    public void testCoreDecodeString() throws DecodeException
    {
        init();
        String expected = "Hello World";
        assertTextDecoder(String.class, "Hello World", expected);
    }

    @Test
    public void testCoreDecodeByteBuffer() throws DecodeException
    {
        init();
        ByteBuffer val = Hex.asByteBuffer("112233445566778899");
        ByteBuffer expected = Hex.asByteBuffer("112233445566778899");
        assertBinaryDecoder(ByteBuffer.class, val, expected);
    }

    @Test
    public void testCoreDecodeByteArray() throws DecodeException
    {
        init();
        ByteBuffer val = Hex.asByteBuffer("112233445566778899");
        byte[] expected = Hex.asByteArray("112233445566778899");
        assertBinaryDecoder(byte[].class, val, expected);
    }

    @Test
    public void testCustomDecoderInteger() throws DecodeException
    {
        init(IntegerDecoder.class);
        String val = "11223344";
        int expected = 11223344;
        assertTextDecoder(Integer.class, val, expected);
    }

    @Test
    public void testCustomDecoderTime() throws DecodeException
    {
        init(TimeDecoder.class);
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
    public void testCustomDecoderDate() throws DecodeException
    {
        init(DateDecoder.class);
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
    public void testCustomDecoderDateTime() throws DecodeException
    {
        init(DateTimeDecoder.class);
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
    public void testCustomDecoderValidDualText() throws DecodeException
    {
        init(ValidDualDecoder.class);
        RegisteredDecoder registered = availableDecoders.getFirstRegisteredDecoder(Integer.class);
        assertThat("Registered Decoder for Integer", registered.decoder.getName(), is(ValidDualDecoder.class.getName()));

        String val = "[1,234,567]";
        Integer expected = 1234567;

        assertTextDecoder(Integer.class, val, expected);
    }

    @Test
    public void testCustomDecoderValidDualBinary() throws DecodeException
    {
        init(ValidDualDecoder.class);
        RegisteredDecoder registered = availableDecoders.getFirstRegisteredDecoder(Long.class);
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
        Exception e = assertThrows(InvalidWebSocketException.class, () -> init(BadDualDecoder.class));
        assertThat(e.getMessage(), containsString("Multiple decoders with different interface types"));
    }

    @Test
    public void testCustomDecoderRegisterOtherDuplicate()
    {
        // This duplicate of decoders is of the same interface type so will form a decoder list.
        // Register DateDecoder (decodes java.util.Date)
        // Register TimeDecoder (which also wants to decode java.util.Date)
        init(DateDecoder.class, TimeDecoder.class);
    }
}
