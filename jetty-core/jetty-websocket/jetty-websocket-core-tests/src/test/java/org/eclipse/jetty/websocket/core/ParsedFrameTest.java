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

package org.eclipse.jetty.websocket.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.internal.ParsedFrame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ParsedFrameTest
{
    public static Stream<Arguments> chunkSizes()
    {
        // The payload is 23 bytes long.
        return Stream.of(
            Arguments.of(List.of(23)),
            Arguments.of(List.of(4, 4, 4, 4, 4, 3)),
            Arguments.of(List.of(1, 22)),
            Arguments.of(List.of(1, 2, 5, 3, 7, 4, 1)),
            Arguments.of(List.of(3, 3, 3, 3, 3, 3, 3, 2)),
            Arguments.of(List.of(6, 0, 9, 8)),
            Arguments.of(List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
        );
    }

    @ParameterizedTest
    @MethodSource("chunkSizes")
    public void testDemaskMultiBufferPayload(List<Integer> chunkSizes)
    {
        String text = "Hello, WebSocket World!";
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        byte[] masked = text.getBytes(UTF_8);
        RawFrameBuilder.mask(masked, mask);

        List<RetainableByteBuffer> chunks = new ArrayList<>();
        int offset = 0;
        for (int size : chunkSizes)
        {
            chunks.add(RetainableByteBuffer.wrap(Arrays.copyOfRange(masked, offset, offset + size)));
            offset += size;
        }
        assertThat(offset, is(masked.length));

        try (RetainableByteBuffer payload = RetainableByteBuffer.merge(chunks);
             ParsedFrame frame = new ParsedFrame((byte)(0x80 | OpCode.TEXT), mask.clone(), payload))
        {
            assertThat(frame.getPayloadLength(), is((long)masked.length));
            assertThat(frame.getPayloadAsUTF8(), is(text));
        }
    }
}
