package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import org.eclipse.jetty.util.log.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LocalHttpConnectorTest
{
    private Server _server;
    private LocalHttpConnector _connector;
    
    @Before
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalHttpConnector();
        _server.addConnector(_connector);
        _server.setHandler(new DumpHandler());
        _server.start();
        //_server.dumpStdErr();
    }
    
    @After
    public void tini() throws Exception
    {
        _server.stop();
        _server=null;
        _connector=null;
    }
    
    @Test
    public void testOneGET() throws Exception
    {        
        String response=_connector.getResponses("GET /R1 HTTP/1.0\r\n\r\n");

        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R1"));
        
    }
    
    @Test
    public void testTwoGETs() throws Exception
    {        
        String response=_connector.getResponses(
            "GET /R1 HTTP/1.1\r\n"+
            "Host: localhost\r\n"+
            "\r\n"+
            "GET /R2 HTTP/1.0\r\n\r\n");

        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R1"));
        
        response=response.substring(response.indexOf("</html>")+8);
        
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R2"));
        
    }
    
    @Test
    public void testGETandGET() throws Exception
    {        
        String response=_connector.getResponses("GET /R1 HTTP/1.0\r\n\r\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R1"));
    
        response=_connector.getResponses("GET /R2 HTTP/1.0\r\n\r\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R2"));
    }
}
