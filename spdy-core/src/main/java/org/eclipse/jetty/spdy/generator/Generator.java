/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;
import java.util.EnumMap;

import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.ControlFrameType;

public class Generator
{
    private final EnumMap<ControlFrameType, ControlFrameGenerator> generators = new EnumMap<>(ControlFrameType.class);
    private final DataFrameGenerator dataFrameGenerator;

    public Generator(CompressionFactory.Compressor compressor)
    {
        HeadersBlockGenerator headersBlockGenerator = new HeadersBlockGenerator(compressor);
        generators.put(ControlFrameType.SYN_STREAM, new SynStreamGenerator(headersBlockGenerator));
        generators.put(ControlFrameType.SYN_REPLY, new SynReplyGenerator(headersBlockGenerator));
        generators.put(ControlFrameType.RST_STREAM, new RstStreamGenerator());
        generators.put(ControlFrameType.SETTINGS, new SettingsGenerator());
        generators.put(ControlFrameType.NOOP, new NoOpGenerator());
        generators.put(ControlFrameType.PING, new PingGenerator());
        generators.put(ControlFrameType.GO_AWAY, new GoAwayGenerator());
        generators.put(ControlFrameType.HEADERS, new HeadersGenerator(headersBlockGenerator));
        generators.put(ControlFrameType.WINDOW_UPDATE, new WindowUpdateGenerator());

        dataFrameGenerator = new DataFrameGenerator();
    }

    public ByteBuffer control(ControlFrame frame)
    {
        ControlFrameGenerator generator = generators.get(frame.getType());
        return generator.generate(frame);
    }

    public ByteBuffer data(int streamId, int windowSize, DataInfo dataInfo)
    {
        return dataFrameGenerator.generate(streamId, windowSize, dataInfo);
    }
}
