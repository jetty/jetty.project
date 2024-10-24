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

package org.eclipse.jetty.compression.gzip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ConfiguredGzipOutputStreamTest extends AbstractGzipTest
{
    @Test
    public void testGzipOutputStreamParts() throws IOException
    {
        Deflater deflater = new Deflater();
        deflater.setLevel(9);

        GzipEncoderConfig config = new GzipEncoderConfig();
        config.setCompressionLevel(Deflater.BEST_COMPRESSION);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ConfiguredGzipOutputStream gzipOutputStream = new ConfiguredGzipOutputStream(baos, config))
        {
            List<String> entries = List.of("Hello", " World", "!");
            for (String entry : entries)
            {
                gzipOutputStream.write(entry.getBytes(UTF_8));
            }
            gzipOutputStream.close();

            byte[] compressed = baos.toByteArray();
            String actual = new String(decompress(compressed), UTF_8);
            assertThat(actual, is("Hello World!"));
        }
    }
}
