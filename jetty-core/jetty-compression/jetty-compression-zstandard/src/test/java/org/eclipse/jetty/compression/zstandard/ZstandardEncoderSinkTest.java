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

package org.eclipse.jetty.compression.zstandard;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ZstandardEncoderSinkTest extends AbstractZstdTest
{
    @ParameterizedTest
    @MethodSource("textResources")
    public void testEncodeText(String textResourceName) throws Exception
    {
        startZstd();
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);
        byte[] compressed = null;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink encoderSink = zstd.newEncoderSink(fileSink);

            Content.Source fileSource = Content.Source.from(sizedPool, uncompressed);
            Callback.Completable callback = new Callback.Completable();
            Content.copy(fileSource, encoderSink, callback);
            callback.get();
            compressed = baos.toByteArray();
        }

        // Verify contents
        String decompressed = new String(decompress(compressed), StandardCharsets.UTF_8);
        String expected = Files.readString(uncompressed, StandardCharsets.UTF_8);
        assertEquals(expected, decompressed);
    }
}
