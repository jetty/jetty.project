package org.eclipse.jetty.io;

public interface SelectableEndPoint extends EndPoint
{
    public abstract void setWriteInterested(boolean interested);

    public abstract boolean isWriteInterested();

    public abstract void setReadInterested(boolean interested);

    public abstract boolean isReadInterested();

    /* ------------------------------------------------------------ */
    SelectableConnection getSelectableConnection();

    /* ------------------------------------------------------------ */
    /** Callback when idle.
     * <p>An endpoint is idle if there has been no IO activity for 
     * {@link #getMaxIdleTime()} and {@link #isCheckForIdle()} is true.
     * @param idleForMs TODO
     */
    public void onIdleExpired(long idleForMs);

    /* ------------------------------------------------------------ */
    /** Set if the endpoint should be checked for idleness
     */
    public void setCheckForIdle(boolean check);

    /* ------------------------------------------------------------ */
    /** Get if the endpoint should be checked for idleness
     */
    public boolean isCheckForIdle();
    
    public long getLastNotIdleTimestamp();
    
    public void checkForIdle(long now);

}
