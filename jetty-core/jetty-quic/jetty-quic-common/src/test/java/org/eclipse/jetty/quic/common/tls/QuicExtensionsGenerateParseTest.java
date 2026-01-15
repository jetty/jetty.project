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
import org.eclipse.jetty.tls.common.generator.ExtensionsGenerator;
import org.eclipse.jetty.tls.common.parser.ExtensionsParser;
import org.eclipse.jetty.tls.ext.Extension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class QuicExtensionsGenerateParseTest
{
    @Test
    public void testGenerateParseQuicTransportParametersExtension()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        TransportParameters transportParameters = new TransportParameters();
        // Smaller long value.
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_UNIDIRECTIONAL, 16L);
        // Small long value.
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL, 100L);
        // Large long value.
        transportParameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, 30000L);
        // Larger long value.
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_DATA, 2147483648L);
        // Larger grease id.
        transportParameters.put(TransportParameters.Ids.create(0xFF02DE1AL, TransportParameters.BytesId::new), new byte[]{13, 7, 19});
        // Unknown id.
        transportParameters.put(TransportParameters.Ids.create(0x5000, TransportParameters.BytesId::new), new byte[]{16, 14, 38});
        QuicTransportParametersExtension expected = new QuicTransportParametersExtension(transportParameters);
        ExtensionsGenerator generator = new ExtensionsGenerator(true);
        generator.put(new QuicTransportParametersExtensionGenerator());
        int length = generator.generate(accumulator, List.of(expected));

        ExtensionsParser parser = new ExtensionsParser(true);
        parser.put(new QuicTransportParametersExtensionParser(parser));
        ByteBuffer lengthByteBuffer = ByteBuffer.allocate(2).putShort((short)length).flip();
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer));
        List<Extension> extensions = parser.parse(accumulator);
        assertNotNull(extensions);

        assertEquals(1, extensions.size());
        QuicTransportParametersExtension result = (QuicTransportParametersExtension)extensions.getFirst();
        TransportParameters expectedTransportParameters = expected.parameters();
        TransportParameters resultTransportParameters = result.parameters();
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : expectedTransportParameters)
        {
            switch (entry.getKey())
            {
                case TransportParameters.LongId longId ->
                    Assertions.assertEquals(expectedTransportParameters.get(longId), resultTransportParameters.get(longId));
                case TransportParameters.BytesId bytesId ->
                    Assertions.assertArrayEquals(expectedTransportParameters.get(bytesId), resultTransportParameters.get(bytesId));
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
        result = (QuicTransportParametersExtension)extensions.getFirst();
        resultTransportParameters = result.parameters();
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : expectedTransportParameters)
        {
            switch (entry.getKey())
            {
                case TransportParameters.LongId longId ->
                    Assertions.assertEquals(expectedTransportParameters.get(longId), resultTransportParameters.get(longId));
                case TransportParameters.BytesId bytesId ->
                    Assertions.assertArrayEquals(expectedTransportParameters.get(bytesId), resultTransportParameters.get(bytesId));
            }
        }
    }
}
