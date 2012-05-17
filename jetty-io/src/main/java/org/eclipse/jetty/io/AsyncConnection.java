package org.eclipse.jetty.io;

public interface AsyncConnection
{
    void onOpen();
    void onClose();
    AsyncEndPoint getEndPoint();
}
