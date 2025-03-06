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

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.compression.zstandard.internal.ZstandardDecoderSource;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ZstandardDecoderSourceTest extends AbstractZstdTest
{
    @ParameterizedTest
    @MethodSource("textResources")
    public void testDecodeText(String textResourceName) throws Exception
    {
        startZstd();
        String compressedName = String.format("%s.%s", textResourceName, zstd.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);

        // TODO: sizedPool config of size 1?
        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source decoderSource = zstd.newDecoderSource(fileSource);

        String result = Content.Source.asString(decoderSource);
        String expected = Files.readString(uncompressed);
        assertEquals(expected, result);
        assertTrue(((ZstandardDecoderSource)decoderSource).isReleased());

        Content.Chunk eof = decoderSource.read();
        assertTrue(eof.isLast() && eof.isEmpty() && !Content.Chunk.isFailure(eof));

        decoderSource.fail(new Throwable());

        Content.Chunk err = decoderSource.read();
        assertTrue(Content.Chunk.isFailure(err));
    }


    @ParameterizedTest
    @MethodSource("textResources")
    public void testImmediateFailReleaseAllResources(String textResourceName) throws Exception
    {
        startZstd();
        String compressedName = String.format("%s.%s", textResourceName, zstd.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);

        // TODO: sizedPool config of size 1?
        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source decoderSource = zstd.newDecoderSource(fileSource);
        assertFalse(((ZstandardDecoderSource)decoderSource).isReleased());

        decoderSource.fail(new Throwable());
        assertTrue(((ZstandardDecoderSource)decoderSource).isReleased());

        Content.Chunk err = decoderSource.read();
        assertTrue(Content.Chunk.isFailure(err));
    }

    @ParameterizedTest
    @MethodSource("textResources")
    public void testFailAfterReadReleaseAllResources(String textResourceName) throws Exception
    {
        startZstd();
        String compressedName = String.format("%s.%s", textResourceName, zstd.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);

        // TODO: sizedPool config of size 1?
        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source decoderSource = zstd.newDecoderSource(fileSource);
        assertFalse(((ZstandardDecoderSource)decoderSource).isReleased());

        Content.Chunk chunk = decoderSource.read();
        // skip empty chunks
        while (chunk.isEmpty() && !chunk.isLast())
            chunk = decoderSource.read();
        assertTrue(chunk.hasRemaining());
        chunk.release();

        decoderSource.fail(new Throwable());
        assertTrue(((ZstandardDecoderSource)decoderSource).isReleased());

        Content.Chunk err = decoderSource.read();
        assertTrue(Content.Chunk.isFailure(err));
    }
}
