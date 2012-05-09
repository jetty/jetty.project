package org.eclipse.jetty.io;

public interface AsyncConnection
{
    public abstract void onClose();

    public abstract AsyncEndPoint getEndPoint();

    public abstract void onIdleExpired(long idleForMs);

}