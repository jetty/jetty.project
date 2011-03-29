package org.eclipse.jetty.server.session;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Random;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

public abstract class AbstractRemoveSessionTest
{
    public abstract AbstractTestServer createServer(int port, int max, int scavenge);
    
    
    @Test
    public void testRemoveSession() throws Exception
    {
        Random random = new Random(System.nanoTime());

        String contextPath = "";
        String servletMapping = "/server";
        int port = random.nextInt(50000) + 10000;
        int scavengePeriod = 3;
        AbstractTestServer server = createServer(port, 1, scavengePeriod);
        ServletContextHandler context = server.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);
        server.start();
        try
        {
            HttpClient client = new HttpClient();
            client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
            client.start();
            try
            {
                ContentExchange exchange = new ContentExchange(true);
                exchange.setMethod(HttpMethods.GET);
                exchange.setURL("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                client.send(exchange);
                exchange.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange.getResponseStatus());
                String sessionCookie = exchange.getResponseFields().getStringField("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                //now delete the session
                exchange = new ContentExchange(true);
                exchange.setMethod(HttpMethods.GET);
                exchange.setURL("http://localhost:" + port + contextPath + servletMapping + "?action=delete");
                exchange.getRequestFields().add("Cookie", sessionCookie);
                client.send(exchange);
                exchange.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange.getResponseStatus());
                
                
                // The session is not there anymore, but we present an old cookie
                // The server creates a new session, we must ensure we released all locks
                exchange = new ContentExchange(true);
                exchange.setMethod(HttpMethods.GET);
                exchange.setURL("http://localhost:" + port + contextPath + servletMapping + "?action=check");
                exchange.getRequestFields().add("Cookie", sessionCookie);
                client.send(exchange);
                exchange.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange.getResponseStatus());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server.stop();
        }

    }
    public static class TestServlet extends HttpServlet
    {
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                request.getSession(true);
            }
            else if ("delete".equals(action))
            {
                HttpSession s = request.getSession(false);
                assertNotNull(s);
                s.invalidate();
                s = request.getSession(false);
                assertNull(s);
            }
            else
            {
               HttpSession s = request.getSession(false);
               assertNull(s);
            }
        }
    }
    
}
