package org.eclipse.jetty.servers;

import java.io.File;

import junit.framework.TestCase;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;

public class SimpleWebServerTest extends TestCase
{
    SimpleWebServer _server;

    protected void setUp() throws Exception
    {
        super.setUp();
        
        _server = new SimpleWebServer( new File( getCurrentWorkingDirectory() + File.separator + "src/test/resources/simple-web-server" ).getAbsolutePath() );
        _server.start();
        
        System.out.println( _server.toString() );
    }

    protected void tearDown() throws Exception
    {
        super.tearDown();
        
        _server.stop();
    }
    
    public void testSimpleGet() throws Exception
    {
        HttpClient _client = new HttpClient();
        _client.setConnectorType( HttpClient.CONNECTOR_SELECT_CHANNEL );
        _client.start();
       
        ContentExchange _exchange = new ContentExchange();
        _exchange.setURL( "http://localhost:" + _server.getPort() + "/a.txt" );
        
        _client.send( _exchange );
        
        _exchange.waitForDone();
        
        assertEquals( "text", _exchange.getResponseContent() );
        
        _client.stop();
    }

    private String getCurrentWorkingDirectory()
    {
        String dir = System.getProperty( "basedir" );
        
        if ( dir == null )
        {
            dir = System.getProperty( "workspace_loc" );
        }
        
        if ( dir == null )
        {
            dir = System.getProperty( "user.dir" );
        }
        
        return dir;
    }
    
    
}
