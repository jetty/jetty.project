package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.DataFrame;

public class DataFrameGenerator extends FrameGenerator<DataFrame>
{
    public DataFrameGenerator(WebSocketPolicy policy)
    {
        super(policy);
    }

    @Override
    public void fillPayload(ByteBuffer buffer, DataFrame binary)
    {
        BufferUtil.put(binary.getPayload(),buffer);
    }
}
