//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.encoders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AvailableEncodersTest
{
    private static EndpointConfig testConfig;
    
    @BeforeClass
    public static void initConfig()
    {
        testConfig = new EmptyClientEndpointConfig();
    }
    
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    private AvailableEncoders encoders = new AvailableEncoders(testConfig);
    
    public <T> void assertTextEncoder(Class<T> type, T value, String expectedEncoded) throws IllegalAccessException, InstantiationException, EncodeException
    {
        Encoder.Text<T> encoder = (Encoder.Text<T>) encoders.getInstanceFor(type);
        assertThat("Encoder", encoder, notNullValue());
        String encoded = encoder.encode(value);
        assertThat("Encoded", encoded, is(expectedEncoded));
    }
    
    public <T> void assertTextStreamEncoder(Class<T> type, T value, String expectedEncoded) throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        Encoder.TextStream<T> encoder = (Encoder.TextStream<T>) encoders.getInstanceFor(type);
        assertThat("Encoder", encoder, notNullValue());
        StringWriter writer = new StringWriter();
        encoder.encode(value, writer);
        
        assertThat("Encoded", writer.toString(), is(expectedEncoded));
    }
    
    public <T> void assertBinaryEncoder(Class<T> type, T value, String expectedEncodedHex) throws IllegalAccessException, InstantiationException, EncodeException
    {
        Encoder.Binary<T> encoder = (Encoder.Binary<T>) encoders.getInstanceFor(type);
        assertThat("Encoder", encoder, notNullValue());
        ByteBuffer encoded = encoder.encode(value);
    
        String hexEncoded = Hex.asHex(encoded);
        assertThat("Encoded", hexEncoded, is(expectedEncodedHex));
    }
    
    public <T> void assertBinaryStreamEncoder(Class<T> type, T value, String expectedEncodedHex) throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        Encoder.BinaryStream<T> encoder = (Encoder.BinaryStream<T>) encoders.getInstanceFor(type);
        assertThat("Encoder", encoder, notNullValue());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encoder.encode(value, out);
    
        String hexEncoded = Hex.asHex(out.toByteArray());
        
        assertThat("Encoded", hexEncoded, is(expectedEncodedHex));
    }
    
    @Test
    public void testCoreEncoder_Boolean() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Boolean.class, Boolean.TRUE, "true");
    }
    
    @Test
    public void testCoreEncoder_bool() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Boolean.TYPE, true, "true");
    }
    
    @Test
    public void testCoreEncoder_Byte() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Byte.class, new Byte((byte) 0x21), "33");
    }
    
    @Test
    public void testCoreEncoder_byte() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Byte.TYPE, (byte) 0x21, "33");
    }
    
    @Test
    public void testCoreEncoder_Character() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Character.class, new Character('!'), "!");
    }
    
    @Test
    public void testCoreEncoder_char() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Character.TYPE, '!', "!");
    }
    
    @Test
    public void testCoreEncoder_Double() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Double.class, new Double(123.45), "123.45");
    }
    
    @Test
    public void testCoreEncoder_double() throws IllegalAccessException, InstantiationException, EncodeException
    {
        //noinspection RedundantCast
        assertTextEncoder(Double.TYPE, (double) 123.45, "123.45");
    }
    
    @Test
    public void testCoreEncoder_Float() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Float.class, new Float(123.4567), "123.4567");
    }
    
    @Test
    public void testCoreEncoder_float() throws IllegalAccessException, InstantiationException, EncodeException
    {
        //noinspection RedundantCast
        assertTextEncoder(Float.TYPE, (float) 123.4567, "123.4567");
    }
    
    @Test
    public void testCoreEncoder_Integer() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Integer.class, new Integer(123), "123");
    }
    
    @Test
    public void testCoreEncoder_int() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Integer.TYPE, 123, "123");
    }
    
    @Test
    public void testCoreEncoder_Long() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Long.class, new Long(123_456_789), "123456789");
    }
    
    @Test
    public void testCoreEncoder_long() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(Long.TYPE, 123_456_789L, "123456789");
    }
    
    @Test
    public void testCoreEncoder_String() throws IllegalAccessException, InstantiationException, EncodeException
    {
        assertTextEncoder(String.class, "Hello World", "Hello World");
    }
    
    @Test
    public void testCoreEncoder_ByteBuffer() throws IllegalAccessException, InstantiationException, EncodeException
    {
        ByteBuffer buf = Hex.asByteBuffer("1122334455");
        assertBinaryEncoder(ByteBuffer.class, buf, "1122334455");
    }
    
    @Test
    public void testCoreEncoder_ByteArray() throws IllegalAccessException, InstantiationException, EncodeException
    {
        byte buf[] = Hex.asByteArray("998877665544332211");
        assertBinaryEncoder(byte[].class, buf, "998877665544332211");
    }
    
    @Test
    public void testCustomEncoder_Integer() throws IllegalAccessException, InstantiationException, EncodeException
    {
        encoders.register(IntegerEncoder.class);
        int val = 99887766;
        String expected = "99887766";
        assertTextEncoder(Integer.class, val, expected);
    }
    
    @Test
    public void testCustomEncoder_Time() throws IllegalAccessException, InstantiationException, EncodeException, IOException
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
    public void testCustomEncoder_Date() throws IllegalAccessException, InstantiationException, EncodeException, IOException
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
    public void testCustomEncoder_DateTime() throws IllegalAccessException, InstantiationException, EncodeException, IOException
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
    public void testCustomEncoder_ValidDual_Text() throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        encoders.register(ValidDualEncoder.class);
        assertTextEncoder(Integer.class, 1234567, "[1,234,567]");
    }
    
    @Test
    public void testCustomEncoder_ValidDual_Binary() throws IllegalAccessException, InstantiationException, EncodeException, IOException
    {
        encoders.register(ValidDualEncoder.class);
        long value = 0x112233445566L;
        assertBinaryStreamEncoder(Long.class, value, "5B00001122334455665D");
    }
    
    @Test
    public void testCustomEncoder_Register_Duplicate()
    {
        // has duplicated support for the same target Type
        expectedException.expect(InvalidWebSocketException.class);
        expectedException.expectMessage(containsString("Duplicate"));
        encoders.register(BadDualEncoder.class);
    }
    
    @Test
    public void testCustomEncoder_Register_OtherDuplicate()
    {
        // Register DateEncoder (decodes java.util.Date)
        encoders.register(DateEncoder.class);
        
        // Register TimeEncoder (which also wants to decode java.util.Date)
        expectedException.expect(InvalidWebSocketException.class);
        expectedException.expectMessage(containsString("Duplicate"));
        encoders.register(TimeEncoder.class);
    }
}
