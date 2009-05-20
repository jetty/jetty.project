package org.eclipse.jetty.servers;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

public class SimpleWebServer 
{
    private String _directory;
    private Server _server;
    private Connector _connector;
    private int _port = 0; // random
    
    public SimpleWebServer( int port, String directory )
    {
        _directory = directory;
        _port = port;
    }
    
    public SimpleWebServer( String directory )
    {
        _directory = directory;
    }
    
    public int getPort()
    {
        return _port;
    }
    
    public void start() throws Exception
    {
        _server = new Server();
        _connector = new SelectChannelConnector();
        _connector.setPort( _port );
        _server.setConnectors( new Connector[] { _connector } );
        
        ContextHandler _contextHandler = new ContextHandler();
        _contextHandler.setContextPath( "/" );
        ResourceHandler _resourceHandler = new ResourceHandler();
        _resourceHandler.setResourceBase( _directory );
        
        _contextHandler.setHandler( _resourceHandler );
        _server.setHandler( _contextHandler );
        
        _server.start();
        
        _port = _connector.getLocalPort();
    }
    
    public void stop() throws Exception
    {
        _server.stop();
    }
    
    public String toString()
    {
        return "[" + this.getClass().getName() + "]/" + _port + "/" + _directory ;
    }
}
