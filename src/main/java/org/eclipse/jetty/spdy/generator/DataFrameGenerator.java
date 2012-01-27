package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.frames.DataFrame;

public class DataFrameGenerator
{
    public ByteBuffer generate(int streamId, int windowSize, DataInfo dataInfo)
    {
        ByteBuffer buffer = ByteBuffer.allocateDirect(DataFrame.HEADER_LENGTH + windowSize);
        buffer.position(DataFrame.HEADER_LENGTH);
        // Guaranteed to always be > 0
        int read = dataInfo.getBytes(buffer);

        buffer.putInt(0, streamId & 0x7F_FF_FF_FF);
        buffer.putInt(4, read & 0x00_FF_FF_FF);

        // TODO: compression can be done here, as long as we have one DataFrameGenerator per stream
        // since the compression context for data is per-stream, without dictionary
        byte flags = dataInfo.isConsumed() && dataInfo.isClose() ? DataInfo.FLAG_FIN : 0;
        buffer.put(4, flags);

        buffer.flip();
        return buffer;
    }
}
