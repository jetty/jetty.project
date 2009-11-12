package org.eclipse.jetty.io;

public interface ConnectedEndPoint extends EndPoint
{
    Connection getConnection();
    void setConnection(Connection connection);
}
