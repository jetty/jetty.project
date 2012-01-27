package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;

public abstract class ControlFrameBodyParser
{
    public abstract boolean parse(ByteBuffer buffer) throws StreamException;
}
