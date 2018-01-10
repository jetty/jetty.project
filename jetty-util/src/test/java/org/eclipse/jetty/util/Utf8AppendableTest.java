//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;



@RunWith(value = Parameterized.class)
public class Utf8AppendableTest
{

    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        Object[][] data = new Object[][]{
            {Utf8StringBuilder.class},
            {Utf8StringBuffer.class},
        };
        return Arrays.asList(data);
    }
    
    private final Class<Utf8Appendable> test;
    
    public Utf8AppendableTest(Class<Utf8Appendable> test)
    {
        this.test = test;
    }

    Utf8Appendable newBuffer()
    {
        try
        {
            return test.getConstructor().newInstance();
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    
    @Test
    public void testUtf() throws Exception
    {
        String source = "abcd012345\n\r\u0000\u00a4\u10fb\ufffdjetty";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        Utf8Appendable buffer = newBuffer();
        for (byte aByte : bytes)
            buffer.append(aByte);
        assertEquals(source,buffer.toString());
        assertTrue(buffer.toString().endsWith("jetty"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUtf8WithMissingByte() throws Exception
    {
        String source = "abc\u10fb";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        Utf8Appendable buffer = newBuffer();
        for (int i = 0; i < bytes.length - 1; i++)
            buffer.append(bytes[i]);
        buffer.toString();
    }

    @Test(expected = Utf8Appendable.NotUtf8Exception.class)
    public void testUtf8WithAdditionalByte() throws Exception
    {
        String source = "abcXX";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        bytes[3] = (byte)0xc0;
        bytes[4] = (byte)0x00;

        Utf8Appendable buffer = newBuffer();
        for (byte aByte : bytes)
            buffer.append(aByte);
    }


    @Test
    public void testUTF32codes() throws Exception
    {
        String source = "\uD842\uDF9F";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

        String jvmcheck = new String(bytes,0,bytes.length,StandardCharsets.UTF_8);
        assertEquals(source,jvmcheck);

        Utf8Appendable buffer = newBuffer();
        buffer.append(bytes,0,bytes.length);
        String result = buffer.toString();
        assertEquals(source,result);
    }
    @Test
    public void testGermanUmlauts() throws Exception
    {
        byte[] bytes = new byte[6];
        bytes[0] = (byte)0xC3;
        bytes[1] = (byte)0xBC;
        bytes[2] = (byte)0xC3;
        bytes[3] = (byte)0xB6;
        bytes[4] = (byte)0xC3;
        bytes[5] = (byte)0xA4;

        Utf8Appendable buffer = newBuffer();
        for (int i = 0; i < bytes.length; i++)
            buffer.append(bytes[i]);

        assertEquals("\u00FC\u00F6\u00E4",buffer.toString());
    }

    @Test(expected = Utf8Appendable.NotUtf8Exception.class)
    public void testInvalidUTF8() throws UnsupportedEncodingException
    {
        Utf8Appendable buffer = newBuffer();
        buffer.append((byte)0xC2);
        buffer.append((byte)0xC2);
    }
    

    @Test
    public void testFastFail_1() throws Exception
    {
        byte[] part1 = TypeUtil.fromHexString("cebae1bdb9cf83cebcceb5");
        byte[] part2 = TypeUtil.fromHexString("f4908080"); // INVALID
        // Here for test tracking reasons, not needed to satisfy test
        // byte[] part3 = TypeUtil.fromHexString("656469746564");

        Utf8Appendable buffer = newBuffer();
        // Part 1 is valid
        buffer.append(part1,0,part1.length);
        try
        {
            // Part 2 is invalid
            buffer.append(part2,0,part2.length);
            Assert.fail("Should have thrown a NotUtf8Exception");
        }
        catch (Utf8Appendable.NotUtf8Exception e)
        {
            // expected path
        }
    }

    @Test
    public void testFastFail_2() throws Exception
    {
        byte[] part1 = TypeUtil.fromHexString("cebae1bdb9cf83cebcceb5f4");
        byte[] part2 = TypeUtil.fromHexString("90"); // INVALID
        // Here for test search/tracking reasons, not needed to satisfy test
        // byte[] part3 = TypeUtil.fromHexString("8080656469746564");

        Utf8Appendable buffer = newBuffer();
        // Part 1 is valid
        buffer.append(part1,0,part1.length);
        try
        {
            // Part 2 is invalid
            buffer.append(part2,0,part2.length);
            Assert.fail("Should have thrown a NotUtf8Exception");
        }
        catch (Utf8Appendable.NotUtf8Exception e)
        {
            // expected path
        }
    }

    @Test
    public void testPartial_UnsplitCodepoint()
    {
        Utf8Appendable utf8 = newBuffer();

        String seq1 = "Hello-\uC2B5@\uC39F\uC3A4";
        String seq2 = "\uC3BC\uC3A0\uC3A1-UTF-8!!";

        utf8.append(BufferUtil.toBuffer(seq1,StandardCharsets.UTF_8));
        String ret1 = utf8.takePartialString();

        utf8.append(BufferUtil.toBuffer(seq2,StandardCharsets.UTF_8));
        String ret2 = utf8.takePartialString();

        assertThat("Seq1",ret1,is(seq1));
        assertThat("Seq2",ret2,is(seq2));
    }
    
    @Test
    public void testPartial_SplitCodepoint()
    {
        Utf8Appendable utf8 = newBuffer();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(TypeUtil.fromHexString(seq1));
        String ret1 = utf8.takePartialString();

        utf8.append(TypeUtil.fromHexString(seq2));
        String ret2 = utf8.takePartialString();

        assertThat("Seq1",ret1,is("Hello-\uC2B5@\uC39F"));
        assertThat("Seq2",ret2,is("\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!"));
    }
    
    @Test
    public void testPartial_SplitCodepoint_WithNoBuf()
    {
        Utf8Appendable utf8 = newBuffer();

        String seq1 = "48656C6C6F2DEC8AB540EC8E9FEC8E";
        String seq2 = "A4EC8EBCEC8EA0EC8EA12D5554462D382121";

        utf8.append(TypeUtil.fromHexString(seq1));
        String ret1 = utf8.takePartialString();
        
        String ret2 = utf8.takePartialString();

        utf8.append(TypeUtil.fromHexString(seq2));
        String ret3 = utf8.takePartialString();
        
        assertThat("Seq1",ret1,is("Hello-\uC2B5@\uC39F"));
        assertThat("Seq2",ret2,is(""));
        assertThat("Seq3",ret3,is("\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!"));
    }
    
    @Test
    public void testBadUtf8()
    {
        List<String> data = new ArrayList<>();
        data.add("c0af");
        data.add("EDA080");
        data.add("f08080af");
        data.add("f8808080af");
        data.add("e080af");
        data.add("F4908080");
        data.add("fbbfbfbfbf");
        data.add("10FFFF");
        data.add("CeBaE1BdB9Cf83CeBcCeB5EdA080656469746564");
        // use of UTF-16 High Surrogates (in codepoint form)
        data.add("da07");
        data.add("d807");
        // decoded UTF-16 High Surrogate "\ud807" (in UTF-8 form)
        data.add("EDA087");
        
        data.forEach(s->
        {
            try
            {
                Utf8Appendable utf8 = newBuffer();
                utf8.append(TypeUtil.fromHexString(s));
                Assert.fail();
            }
            catch(NotUtf8Exception e)
            {
                // expected
            }
        });
        
    }
}
