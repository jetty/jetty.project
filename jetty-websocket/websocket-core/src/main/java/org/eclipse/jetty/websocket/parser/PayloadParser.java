package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

public abstract class PayloadParser
{
    public abstract boolean parse(ByteBuffer buffer);
    public abstract void reset();
}
