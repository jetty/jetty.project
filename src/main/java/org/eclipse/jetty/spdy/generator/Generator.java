package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;
import java.util.EnumMap;

import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.StreamException;
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

    public ByteBuffer control(ControlFrame frame) throws StreamException
    {
        ControlFrameGenerator generator = generators.get(frame.getType());
        return generator.generate(frame);
    }

    public ByteBuffer data(int streamId, int windowSize, DataInfo dataInfo)
    {
        return dataFrameGenerator.generate(streamId, windowSize, dataInfo);
    }
}
