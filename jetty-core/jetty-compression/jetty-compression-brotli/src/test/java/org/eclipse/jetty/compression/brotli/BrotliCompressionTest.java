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

package org.eclipse.jetty.compression.brotli;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import org.eclipse.jetty.toolchain.test.Hex;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class BrotliCompressionTest extends AbstractBrotliTest
{
    @Test
    public void testStripSuffixes() throws Exception
    {
        startBrotli();
        assertThat(brotli.stripSuffixes("12345"), is("12345"));
        assertThat(brotli.stripSuffixes("12345, 666" + brotli.getEtagSuffix()), is("12345, 666"));
        assertThat(brotli.stripSuffixes("12345, 666" + brotli.getEtagSuffix() + ",W/\"9999" + brotli.getEtagSuffix() + "\""),
            is("12345, 666,W/\"9999\""));
    }

    @Test
    public void testEncodeBehavior() throws Exception
    {
        startBrotli();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream encodingStream = new BrotliOutputStream(baos))
        {
            encodingStream.write("Hello".getBytes(StandardCharsets.UTF_8));
            encodingStream.write("World".getBytes(StandardCharsets.UTF_8));
            encodingStream.flush();
            encodingStream.close();
            System.out.println("Hex: " + Hex.asHex(baos.toByteArray()));
        }
    }
}
