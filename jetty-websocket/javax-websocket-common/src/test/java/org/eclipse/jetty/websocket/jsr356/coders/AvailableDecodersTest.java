//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.coders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.websocket.core.InvalidWebSocketException;
import org.eclipse.jetty.websocket.jsr356.BasicEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.decoders.IntegerDecoder;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AvailableDecodersTest
{
    private static EndpointConfig testConfig;

    @BeforeClass
    public static void initConfig()
    {
        testConfig = new BasicEndpointConfig();
    }
    
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    private AvailableDecoders decoders = new AvailableDecoders(testConfig);

    private <T> void assertTextDecoder(Class<T> type, String value, T expectedDecoded) throws IllegalAccessException, InstantiationException, DecodeException
    {
        Decoder.Text<T> decoder = (Decoder.Text<T>) decoders.getInstanceFor(type);
        assertThat("Decoder instance", decoder, notNullValue());
        T decoded = decoder.decode(value);
        assertThat("Decoded", decoded, is(expectedDecoded));
    }

    private <T> void assertBinaryDecoder(Class<T> type, ByteBuffer value, T expectedDecoded) throws IllegalAccessException, InstantiationException, DecodeException
    {
        Decoder.Binary<T> decoder = (Decoder.Binary<T>) decoders.getInstanceFor(type);
        assertThat("Decoder Class", decoder, notNullValue());
        T decoded = decoder.decode(value);
        assertThat("Decoded", decoded, equalTo(expectedDecoded));
    }

    @Test
    public void testCoreDecode_Boolean() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Boolean expected = Boolean.TRUE;
        assertTextDecoder(Boolean.class, "true", expected);
    }

    @Test
    public void testCoreDecode_boolean() throws IllegalAccessException, InstantiationException, DecodeException
    {
        boolean expected = false;
        assertTextDecoder(Boolean.TYPE, "false", expected);
    }

    @Test
    public void testCoreDecode_Byte() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Byte expected = new Byte((byte) 0x21);
        assertTextDecoder(Byte.class, "33", expected);
    }

    @Test
    public void testCoreDecode_byte() throws IllegalAccessException, InstantiationException, DecodeException
    {
        byte expected = 0x21;
        assertTextDecoder(Byte.TYPE, "33", expected);
    }

    @Test
    public void testCoreDecode_Character() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Character expected = new Character('!');
        assertTextDecoder(Character.class, "!", expected);
    }

    @Test
    public void testCoreDecode_char() throws IllegalAccessException, InstantiationException, DecodeException
    {
        char expected = '!';
        assertTextDecoder(Character.TYPE, "!", expected);
    }

    @Test
    public void testCoreDecode_Double() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Double expected = new Double(123.45);
        assertTextDecoder(Double.class, "123.45", expected);
    }

    @Test
    public void testCoreDecode_double() throws IllegalAccessException, InstantiationException, DecodeException
    {
        double expected = 123.45;
        assertTextDecoder(Double.TYPE, "123.45", expected);
    }

    @Test
    public void testCoreDecode_Float() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Float expected = new Float(123.4567);
        assertTextDecoder(Float.class, "123.4567", expected);
    }

    @Test
    public void testCoreDecode_float() throws IllegalAccessException, InstantiationException, DecodeException
    {
        float expected = 123.4567F;
        assertTextDecoder(Float.TYPE, "123.4567", expected);
    }

    @Test
    public void testCoreDecode_Integer() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Integer expected = new Integer(1234);
        assertTextDecoder(Integer.class, "1234", expected);
    }

    @Test
    public void testCoreDecode_int() throws IllegalAccessException, InstantiationException, DecodeException
    {
        int expected = 1234;
        assertTextDecoder(Integer.TYPE, "1234", expected);
    }

    @Test
    public void testCoreDecode_Long() throws IllegalAccessException, InstantiationException, DecodeException
    {
        Long expected = new Long(123_456_789);
        assertTextDecoder(Long.class, "123456789", expected);
    }

    @Test
    public void testCoreDecode_long() throws IllegalAccessException, InstantiationException, DecodeException
    {
        long expected = 123_456_789L;
        assertTextDecoder(Long.TYPE, "123456789", expected);
    }

    @Test
    public void testCoreDecode_String() throws IllegalAccessException, InstantiationException, DecodeException
    {
        String expected = "Hello World";
        assertTextDecoder(String.class, "Hello World", expected);
    }

    @Test
    public void testCoreDecode_ByteBuffer() throws IllegalAccessException, InstantiationException, DecodeException
    {
        ByteBuffer val = Hex.asByteBuffer("112233445566778899");
        ByteBuffer expected = Hex.asByteBuffer("112233445566778899");
        assertBinaryDecoder(ByteBuffer.class, val, expected);
    }

    @Test
    public void testCoreDecode_ByteArray() throws IllegalAccessException, InstantiationException, DecodeException
    {
        ByteBuffer val = Hex.asByteBuffer("112233445566778899");
        byte expected[] = Hex.asByteArray("112233445566778899");
        assertBinaryDecoder(byte[].class, val, expected);
    }
    
    @Test
    public void testCustomDecoder_Integer() throws IllegalAccessException, InstantiationException, DecodeException
    {
        decoders.register(IntegerDecoder.class);
        
        String val = "11223344";
        int expected = 11223344;
        assertTextDecoder(Integer.class, val, expected);
    }

    @Test
    public void testCustomDecoder_Time() throws IllegalAccessException, InstantiationException, DecodeException
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
    public void testCustomDecoder_Date() throws IllegalAccessException, InstantiationException, DecodeException
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
    public void testCustomDecoder_DateTime() throws IllegalAccessException, InstantiationException, DecodeException
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
    public void testCustomDecoder_ValidDual_Text() throws IllegalAccessException, InstantiationException, DecodeException
    {
        decoders.register(ValidDualDecoder.class);
        
        AvailableDecoders.RegisteredDecoder registered = decoders.getRegisteredDecoderFor(Integer.class);
        assertThat("Registered Decoder for Integer", registered.decoder.getName(), is(ValidDualDecoder.class.getName()));

        String val = "[1,234,567]";
        Integer expected = 1234567;

        assertTextDecoder(Integer.class, val, expected);
    }

    @Test
    public void testCustomDecoder_ValidDual_Binary() throws IllegalAccessException, InstantiationException, DecodeException
    {
        decoders.register(ValidDualDecoder.class);
    
        AvailableDecoders.RegisteredDecoder registered = decoders.getRegisteredDecoderFor(Long.class);
        assertThat("Registered Decoder for Long", registered.decoder.getName(), is(ValidDualDecoder.class.getName()));

        ByteBuffer val = ByteBuffer.allocate(16);
        val.put((byte) '[');
        val.putLong(0x112233445566L);
        val.put((byte) ']');
        val.flip();
        Long expected = 0x112233445566L;

        assertBinaryDecoder(Long.class, val, expected);
    }
    
    @Test
    public void testCustomDecoder_Register_Duplicate()
    {
        // has duplicated support for the same target Type
        expectedException.expect(InvalidWebSocketException.class);
        expectedException.expectMessage(containsString("Duplicate"));
        decoders.register(BadDualDecoder.class);
    }
    
    @Test
    public void testCustomDecoder_Register_OtherDuplicate()
    {
        // Register DateDecoder (decodes java.util.Date)
        decoders.register(DateDecoder.class);
    
        // Register TimeDecoder (which also wants to decode java.util.Date)
        expectedException.expect(InvalidWebSocketException.class);
        expectedException.expectMessage(containsString("Duplicate"));
        decoders.register(TimeDecoder.class);
    }
}
