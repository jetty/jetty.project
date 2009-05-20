package org.eclipse.jetty.servers;

import java.awt.Window;
import java.io.File;

import javax.swing.JFrame;
import javax.swing.KeyStroke;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

import junit.framework.TestCase;

public class SecureSimpleWebServerTest extends TestCase
{
	SecurityManager oldSecurityManager = System.getSecurityManager();
	
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
    
    public void testSetSecurityManager() throws Exception
    {
        SecureJettySecurityManager customManager = new SecureJettySecurityManager();
        customManager.setCanSetSecurityManager( false ); // see comment in class, just testing options
        
        System.setSecurityManager( customManager );  
        
        try
        {
            System.setSecurityManager( oldSecurityManager );
            
            assertFalse( "failed to stop security manager reset", true );
        }
        catch ( SecurityException se )
        {
            assertEquals( SecureJettySecurityManager.__SET_SECURITY_MANAGER_DENIED, se.getMessage() );
        }
        
        customManager.setCanSetSecurityManager( true );
        
        System.setSecurityManager( oldSecurityManager );
    }
    
    public void testSimpleGet() throws Exception
    {
        System.setSecurityManager( new SecureJettySecurityManager() );  
        
        HttpClient _client = new HttpClient();
        _client.setConnectorType( HttpClient.CONNECTOR_SELECT_CHANNEL );
        _client.start();
       
        ContentExchange _exchange = new ContentExchange();
        _exchange.setURL( "http://localhost:" + _server.getPort() + "/a.txt" );
        
        _client.send( _exchange );
        
        _exchange.waitForDone();
        
        assertEquals( "text", _exchange.getResponseContent() );
        
        _client.stop();
        
        System.setSecurityManager( oldSecurityManager );
    }
    
    public void /*test*/PackageDeny() // throws exceptions on multiple threads but not in this usage, so blocks access but can't trap it? might be better for verification ahead of time.
    {
        SecureJettySecurityManager customManager = new SecureJettySecurityManager();
        customManager.addDeniedPackage( "sun.awt" );
        
        System.setSecurityManager( customManager );  
        
        try
        {
            JFrame frame = new JFrame();
            
            assertFalse( "failed to stop frame access", true );
        }
        catch ( SecurityException se )
        {
            assertEquals( SecureJettySecurityManager.__PACKAGE_ACCESS_DENIED, se.getMessage() );
        }
  
        System.setSecurityManager( oldSecurityManager );
        
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
