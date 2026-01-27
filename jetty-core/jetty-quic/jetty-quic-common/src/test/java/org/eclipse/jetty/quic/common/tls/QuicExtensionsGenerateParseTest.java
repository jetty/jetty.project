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

package org.eclipse.jetty.quic.common.tls;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.api.tls.ext.QuicTransportParametersExtension;
import org.eclipse.jetty.quic.common.tls.generator.QuicTransportParametersExtensionGenerator;
import org.eclipse.jetty.quic.common.tls.parser.QuicTransportParametersExtensionParser;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.tls.common.generator.ExtensionsGenerator;
import org.eclipse.jetty.tls.common.parser.ExtensionsParser;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class QuicExtensionsGenerateParseTest
{
    @Test
    public void testGenerateParseQuicTransportParametersExtension()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        TransportParameters generatedParams = new TransportParameters();
        // Smaller long value.
        generatedParams.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_UNIDIRECTIONAL, 16L);
        // Small long value.
        generatedParams.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL, 100L);
        // Large long value.
        generatedParams.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, 30000L);
        // Larger long value.
        generatedParams.put(TransportParameters.Ids.INITIAL_MAX_DATA, 2147483648L);
        // Larger grease id.
        generatedParams.put(TransportParameters.Ids.create(0xFF02DE1AL, TransportParameters.BytesId::new), new byte[]{13, 7, 19});
        // Remove it from the static Ids map, so parsing does not find this id.
        TransportParameters.Ids.remove(0xFF02DE1AL);
        // Unknown id. Generated as long, will be parsed as bytes.
        long unknownId = 0x5000;
        long unknownValue = 1052198;
        byte[] unknownValueBytes = new byte[VarLenInt.length(unknownValue)];
        VarLenInt.encode(ByteBuffer.wrap(unknownValueBytes), unknownValue);
        generatedParams.put(TransportParameters.Ids.create(unknownId, TransportParameters.LongId::new), unknownValue);
        // Remove it from the static Ids map, so parsing does not find this id.
        TransportParameters.Ids.remove(unknownId);
        // Zero-length value.
        // When being the last, it is an edge case for parsing,
        // since there are no more bytes to read in the buffer.
        generatedParams.put(TransportParameters.Ids.DISABLE_ACTIVE_MIGRATION, BufferUtil.EMPTY_BYTES);
        QuicTransportParametersExtension generated = new QuicTransportParametersExtension(generatedParams);
        ExtensionsGenerator generator = new ExtensionsGenerator(true);
        generator.put(new QuicTransportParametersExtensionGenerator());
        int length = generator.generate(accumulator, List.of(generated));

        ExtensionsParser parser = new ExtensionsParser(true);
        parser.put(new QuicTransportParametersExtensionParser(parser));
        ByteBuffer lengthByteBuffer = ByteBuffer.allocate(2).putShort((short)length).flip();
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer));
        List<Extension> extensions = parser.parse(accumulator);
        assertNotNull(extensions);

        assertEquals(1, extensions.size());
        QuicTransportParametersExtension parsed = (QuicTransportParametersExtension)extensions.getFirst();
        TransportParameters parseParams = parsed.transportParameters();
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : generatedParams)
        {
            switch (entry.getKey())
            {
                case TransportParameters.LongId longId when longId.id() != unknownId ->
                    assertEquals(generatedParams.get(longId), parseParams.get(longId));
                case TransportParameters.LongId _ ->
                {
                    // Generated as unknown long, parsed as bytes.
                    byte[] parsedValue = (byte[])parseParams.get(entry.getKey());
                    assertArrayEquals(unknownValueBytes, parsedValue);
                }
                case TransportParameters.BytesId bytesId ->
                    assertArrayEquals(generatedParams.get(bytesId), parseParams.get(bytesId));
            }
        }

        // Parse again one byte at a time.
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer.flip()));
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            extensions = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        parsed = (QuicTransportParametersExtension)extensions.getFirst();
        parseParams = parsed.transportParameters();
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : generatedParams)
        {
            switch (entry.getKey())
            {
                case TransportParameters.LongId longId when longId.id() != unknownId ->
                    assertEquals(generatedParams.get(longId), parseParams.get(longId));
                case TransportParameters.LongId _ ->
                {
                    // Generated as unknown long, parsed as bytes.
                    byte[] parsedValue = (byte[])parseParams.get(entry.getKey());
                    assertArrayEquals(unknownValueBytes, parsedValue);
                }
                case TransportParameters.BytesId bytesId ->
                    assertArrayEquals(generatedParams.get(bytesId), parseParams.get(bytesId));
            }
        }
    }
}
