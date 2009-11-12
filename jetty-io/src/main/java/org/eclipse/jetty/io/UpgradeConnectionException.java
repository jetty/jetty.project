package org.eclipse.jetty.io;

/* ------------------------------------------------------------ */
/** Upgrade Connection Exception
 * This exception is thrown when processing a protocol upgrade
 * to exit all the current connection handling and to
 * allow the {@link ConnectedEndPoint} to handle the new exception.
 * 
 * Code that calls {@link org.eclipse.jetty.io.Connection#handle()}
 * should catch this exception and call {@link ConnectedEndPoint#setConnection(org.eclipse.jetty.io.Connection)}
 * with the new connection and then immediately call handle() again.
 */
public class UpgradeConnectionException extends RuntimeException
{
    Connection _connection;
    
    public UpgradeConnectionException(Connection newConnection)
    {
        _connection=newConnection;
    }
    
    public Connection getConnection()
    {
        return _connection;
    }
}
