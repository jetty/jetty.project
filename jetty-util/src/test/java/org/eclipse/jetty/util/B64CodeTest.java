//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class B64CodeTest
{
    String text = "Man is distinguished, not only by his reason, but by this singular passion " +
        "from other animals, which is a lust of the mind, that by a perseverance of delight in " +
        "the continued and indefatigable generation of knowledge, exceeds the short vehemence " +
        "of any carnal pleasure.";

    @Test
    public void testEncodeRFC1421()
    {
        String expected = "TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24sIGJ1dCBieSB0aGlz" +
            "IHNpbmd1bGFyIHBhc3Npb24gZnJvbSBvdGhlciBhbmltYWxzLCB3aGljaCBpcyBhIGx1c3Qgb2Yg" +
            "dGhlIG1pbmQsIHRoYXQgYnkgYSBwZXJzZXZlcmFuY2Ugb2YgZGVsaWdodCBpbiB0aGUgY29udGlu" +
            "dWVkIGFuZCBpbmRlZmF0aWdhYmxlIGdlbmVyYXRpb24gb2Yga25vd2xlZGdlLCBleGNlZWRzIHRo" +
            "ZSBzaG9ydCB2ZWhlbWVuY2Ugb2YgYW55IGNhcm5hbCBwbGVhc3VyZS4=";

        // Default Encode
        String b64 = B64Code.encode(text, ISO_8859_1);
        assertThat("B64Code.encode(String)", b64, is(expected));

        // Specified RFC Encode
        byte[] rawInputBytes = text.getBytes(ISO_8859_1);
        char[] chars = B64Code.encode(rawInputBytes, false);
        b64 = new String(chars, 0, chars.length);
        assertThat("B64Code.encode(byte[], false)", b64, is(expected));

        // Standard Java Encode
        String javaBase64 = Base64.getEncoder().encodeToString(rawInputBytes);
        assertThat("Base64.getEncoder().encodeToString((byte[])", javaBase64, is(expected));
    }

    @Test
    public void testEncodeRFC2045()
    {
        byte[] rawInputBytes = text.getBytes(ISO_8859_1);

        // Old Jetty way
        char[] chars = B64Code.encode(rawInputBytes, true);
        String b64 = new String(chars, 0, chars.length);

        String expected = "TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24sIGJ1dCBieSB0aGlz\r\n" +
            "IHNpbmd1bGFyIHBhc3Npb24gZnJvbSBvdGhlciBhbmltYWxzLCB3aGljaCBpcyBhIGx1c3Qgb2Yg\r\n" +
            "dGhlIG1pbmQsIHRoYXQgYnkgYSBwZXJzZXZlcmFuY2Ugb2YgZGVsaWdodCBpbiB0aGUgY29udGlu\r\n" +
            "dWVkIGFuZCBpbmRlZmF0aWdhYmxlIGdlbmVyYXRpb24gb2Yga25vd2xlZGdlLCBleGNlZWRzIHRo\r\n" +
            "ZSBzaG9ydCB2ZWhlbWVuY2Ugb2YgYW55IGNhcm5hbCBwbGVhc3VyZS4=\r\n";

        assertThat(b64, is(expected));

        // Standard Java way
        String javaBase64 = Base64.getMimeEncoder().encodeToString(rawInputBytes);
        // NOTE: MIME standard for encoding should not include final "\r\n"
        assertThat(javaBase64 + "\r\n", is(expected));
    }

    @Test
    public void testInteger() throws Exception
    {
        byte[] bytes = text.getBytes(ISO_8859_1);
        int value = (bytes[0] << 24) +
            (bytes[1] << 16) +
            (bytes[2] << 8) +
            (bytes[3]);

        String expected = "TWFuIA";

        // Old Jetty way
        StringBuilder b = new StringBuilder();
        B64Code.encode(value, b);
        assertThat("Old Jetty B64Code", b.toString(), is(expected));

        // Standard Java technique
        byte[] intBytes = new byte[Integer.BYTES];
        for (int i = Integer.BYTES - 1; i >= 0; i--)
        {
            intBytes[i] = (byte)(value & 0xFF);
            value >>= 8;
        }
        assertThat("Standard Java Base64", Base64.getEncoder().withoutPadding().encodeToString(intBytes), is(expected));
    }

    @Test
    public void testLong() throws Exception
    {
        byte[] bytes = text.getBytes(ISO_8859_1);
        long value = ((0xffL & bytes[0]) << 56) +
            ((0xffL & bytes[1]) << 48) +
            ((0xffL & bytes[2]) << 40) +
            ((0xffL & bytes[3]) << 32) +
            ((0xffL & bytes[4]) << 24) +
            ((0xffL & bytes[5]) << 16) +
            ((0xffL & bytes[6]) << 8) +
            (0xffL & bytes[7]);

        String expected = "TWFuIGlzIGQ";

        // Old Jetty way
        StringBuilder b = new StringBuilder();
        B64Code.encode(value, b);
        assertThat("Old Jetty B64Code", b.toString(), is(expected));

        // Standard Java technique
        byte[] longBytes = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--)
        {
            longBytes[i] = (byte)(value & 0xFF);
            value >>= 8;
        }
        assertThat("Standard Java Base64", Base64.getEncoder().withoutPadding().encodeToString(longBytes), is(expected));
    }
}
