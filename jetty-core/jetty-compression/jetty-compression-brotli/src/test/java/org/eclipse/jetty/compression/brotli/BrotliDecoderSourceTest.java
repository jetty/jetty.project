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

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.compression.brotli.internal.BrotliDecoderSource;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BrotliDecoderSourceTest extends AbstractBrotliTest
{
    @ParameterizedTest
    @MethodSource("textResources")
    public void testDecodeText(String textResourceName) throws Exception
    {
        startBrotli();
        String compressedName = String.format("%s.%s", textResourceName, brotli.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);

        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source decoderSource = brotli.newDecoderSource(fileSource);
        assertFalse(((BrotliDecoderSource)decoderSource).isReleased());

        String result = Content.Source.asString(decoderSource);
        String expected = Files.readString(uncompressed);
        assertEquals(expected, result);
        assertEquals("DONE", ((BrotliDecoderSource)decoderSource).getStatus());
        assertTrue(((BrotliDecoderSource)decoderSource).isReleased());

        Content.Chunk eof = decoderSource.read();
        assertTrue(eof.isLast() && eof.isEmpty() && !Content.Chunk.isFailure(eof));

        decoderSource.fail(new Throwable());
        assertEquals("FAILED", ((BrotliDecoderSource)decoderSource).getStatus());

        Content.Chunk err = decoderSource.read();
        assertTrue(Content.Chunk.isFailure(err));
    }

    @ParameterizedTest
    @MethodSource("textResources")
    public void testImmediateFailReleaseAllResources(String textResourceName) throws Exception
    {
        startBrotli();
        String compressedName = String.format("%s.%s", textResourceName, brotli.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);

        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source decoderSource = brotli.newDecoderSource(fileSource);
        assertFalse(((BrotliDecoderSource)decoderSource).isReleased());

        decoderSource.fail(new Throwable());
        assertEquals("FAILED", ((BrotliDecoderSource)decoderSource).getStatus());
        assertTrue(((BrotliDecoderSource)decoderSource).isReleased());

        Content.Chunk err = decoderSource.read();
        assertTrue(Content.Chunk.isFailure(err));
    }

    @ParameterizedTest
    @MethodSource("textResources")
    public void testFailAfterReadReleaseAllResources(String textResourceName) throws Exception
    {
        startBrotli();
        String compressedName = String.format("%s.%s", textResourceName, brotli.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);

        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source decoderSource = brotli.newDecoderSource(fileSource);
        assertFalse(((BrotliDecoderSource)decoderSource).isReleased());

        Content.Chunk chunk = decoderSource.read();
        // skip empty chunks
        while (chunk.isEmpty() && !chunk.isLast())
            chunk = decoderSource.read();
        assertTrue(chunk.hasRemaining());
        chunk.release();
        assertFalse(((BrotliDecoderSource)decoderSource).isReleased());

        decoderSource.fail(new Throwable());
        assertEquals("FAILED", ((BrotliDecoderSource)decoderSource).getStatus());
        assertTrue(((BrotliDecoderSource)decoderSource).isReleased());

        Content.Chunk err = decoderSource.read();
        assertTrue(Content.Chunk.isFailure(err));
    }
}
