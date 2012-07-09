package org.eclipse.jetty.websocket.io;

import java.util.LinkedList;

@SuppressWarnings("serial")
public class FrameQueue extends LinkedList<FrameBytes<?>>
{
    public void append(FrameBytes<?> bytes)
    {
        addLast(bytes);
    }

    public void prepend(FrameBytes<?> bytes)
    {
        addFirst(bytes);
    }
}
