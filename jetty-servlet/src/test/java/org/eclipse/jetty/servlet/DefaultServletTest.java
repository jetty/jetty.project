package org.eclipse.jetty.servlet;

import java.io.File;

import junit.framework.AssertionFailedError;
import junit.framework.TestCase;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;

public class DefaultServletTest extends TestCase
{
    private Server server;
    private LocalConnector connector;
    private ServletContextHandler context;

    protected void setUp() throws Exception
    {
        super.setUp();

        server = new Server();
        server.setSendServerVersion(false);

        connector = new LocalConnector();

        context = new ServletContextHandler();
        context.setContextPath("/context");
        context.setWelcomeFiles(new String[] {}); // no welcome files

        server.setHandler(context);
        server.addConnector(connector);

        server.start();
    }

    protected void tearDown() throws Exception
    {
        super.tearDown();

        if (server != null)
        {
            server.stop();
        }
    }

    public void testListingXSS() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class,"/listing/*");
        defholder.setInitParameter("dirAllowed","true");
        defholder.setInitParameter("redirectWelcome","false");
        defholder.setInitParameter("gzip","false");

        File resBase = new File("src/test/resources");

        assertTrue("resBase.exists",resBase.exists());
        assertTrue("resBase.isDirectory",resBase.isDirectory());

        String resBasePath = resBase.getAbsolutePath();
        defholder.setInitParameter("resourceBase",resBasePath);

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /context/listing/;<script>window.alert(\"hi\");</script> HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        String response = connector.getResponses(req1.toString());

        assertResponseContains("listing/one/",response);
        assertResponseContains("listing/two/",response);
        assertResponseContains("listing/three/",response);

        assertResponseNotContains("<script>",response);
    }

    private void assertResponseNotContains(String forbidden, String response)
    {
        int idx = response.indexOf(forbidden);
        if (idx != (-1))
        {
            // Found (when should not have)
            StringBuffer err = new StringBuffer();
            err.append("Response contain forbidden string \"").append(forbidden).append("\"");
            err.append("\n").append(response);

            System.err.println(err);
            throw new AssertionFailedError(err.toString());
        }
    }

    private void assertResponseContains(String expected, String response)
    {
        int idx = response.indexOf(expected);
        if (idx == (-1))
        {
            // Not found
            StringBuffer err = new StringBuffer();
            err.append("Response does not contain expected string \"").append(expected).append("\"");
            err.append("\n").append(response);

            System.err.println(err);
            throw new AssertionFailedError(err.toString());
        }
    }
}
