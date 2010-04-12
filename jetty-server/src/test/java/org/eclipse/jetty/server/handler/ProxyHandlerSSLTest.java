package org.eclipse.jetty.server.handler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import junit.framework.TestCase;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;

/**
 * @version $Revision$ $Date$
 */
public class ProxyHandlerSSLTest extends TestCase
{
    private Server server;
    private Connector serverConnector;
    private Server proxy;
    private Connector proxyConnector;

    @Override
    protected void setUp() throws Exception
    {
        server = new Server();
        serverConnector = new SslSelectChannelConnector();
        server.addConnector(serverConnector);
//        server.setHandler(new EchoHandler());
        server.start();

        proxy = new Server();
        proxyConnector = new SslSelectChannelConnector();
        proxy.addConnector(proxyConnector);
        proxy.setHandler(new ProxyHandler()
        {
            @Override
            protected boolean isTunnelSecure(String host, int port)
            {
                return true;
            }
        });
        proxy.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        proxy.stop();
        proxy.join();

        server.stop();
        server.join();
    }

    public void testHttpConnectWithNormalRequest() throws Exception
    {
        String request = "" +
                "CONNECT localhost:" + serverConnector.getLocalPort() + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
        Socket socket = new Socket("localhost", proxyConnector.getLocalPort());
        try
        {
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            InputStream input = socket.getInputStream();
//            Response response = readResponse(input);
//            System.err.println(response);
//            assertEquals("200", response.code);

            // Now what ? Upgrade the socket to SSL ?

        }
        finally
        {
            socket.close();
        }
    }
}
