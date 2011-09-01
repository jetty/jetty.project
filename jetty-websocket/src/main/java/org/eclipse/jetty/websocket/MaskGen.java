package org.eclipse.jetty.websocket;

public interface MaskGen
{
    void genMask(byte[] mask);
}