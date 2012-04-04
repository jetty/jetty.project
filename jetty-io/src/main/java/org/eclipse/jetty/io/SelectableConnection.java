package org.eclipse.jetty.io;

public interface SelectableConnection extends Connection
{
    SelectableEndPoint getSelectableEndPoint();

    Runnable onReadable();
    Runnable onWriteable();
    
    public boolean blockReadable();
    
    public boolean blockWriteable();

    /**
     * Called when the connection idle timeout expires
     * @param idleForMs TODO
     */
    void onIdleExpired(long idleForMs);
    
    void onInputShutdown();
    
    /**
     * Called when the connection is closed
     */
    void onClose();
    
    
}
