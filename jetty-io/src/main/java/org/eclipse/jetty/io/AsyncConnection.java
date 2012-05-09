package org.eclipse.jetty.io;

public interface AsyncConnection
{

    void onClose();

    void onOpen();

    AsyncEndPoint getEndPoint();

    void onIdleExpired(long idleForMs);
}
