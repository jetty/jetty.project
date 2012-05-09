package org.eclipse.jetty.io;

public interface AsyncConnection
{
    public void onOpen();

    public void onClose();

    public AsyncEndPoint getEndPoint();

    public void onIdleExpired(long idleForMs);
}
