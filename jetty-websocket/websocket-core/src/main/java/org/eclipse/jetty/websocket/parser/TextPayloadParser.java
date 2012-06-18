package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

public class TextPayloadParser extends PayloadParser
{
    private Parser baseParser;

    public TextPayloadParser(Parser parser)
    {
        this.baseParser = parser;
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void reset()
    {
        // TODO Auto-generated method stub

    }

}
