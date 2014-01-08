//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;
import java.util.EnumMap;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.ControlFrameType;

public class Generator
{
    final static boolean useDirectBuffers=false;
    private final EnumMap<ControlFrameType, ControlFrameGenerator> generators = new EnumMap<>(ControlFrameType.class);
    private final DataFrameGenerator dataFrameGenerator;

    public Generator(ByteBufferPool bufferPool, CompressionFactory.Compressor compressor)
    {
        HeadersBlockGenerator headersBlockGenerator = new HeadersBlockGenerator(compressor);
        generators.put(ControlFrameType.SYN_STREAM, new SynStreamGenerator(bufferPool, headersBlockGenerator));
        generators.put(ControlFrameType.SYN_REPLY, new SynReplyGenerator(bufferPool, headersBlockGenerator));
        generators.put(ControlFrameType.RST_STREAM, new RstStreamGenerator(bufferPool));
        generators.put(ControlFrameType.SETTINGS, new SettingsGenerator(bufferPool));
        generators.put(ControlFrameType.NOOP, new NoOpGenerator(bufferPool));
        generators.put(ControlFrameType.PING, new PingGenerator(bufferPool));
        generators.put(ControlFrameType.GO_AWAY, new GoAwayGenerator(bufferPool));
        generators.put(ControlFrameType.HEADERS, new HeadersGenerator(bufferPool, headersBlockGenerator));
        generators.put(ControlFrameType.WINDOW_UPDATE, new WindowUpdateGenerator(bufferPool));
        generators.put(ControlFrameType.CREDENTIAL, new CredentialGenerator(bufferPool));

        dataFrameGenerator = new DataFrameGenerator(bufferPool);
    }

    public ByteBuffer control(ControlFrame frame)
    {
        ControlFrameGenerator generator = generators.get(frame.getType());
        return generator.generate(frame);
    }

    public ByteBuffer data(int streamId, int length, DataInfo dataInfo)
    {
        return dataFrameGenerator.generate(streamId, length, dataInfo);
    }
}
