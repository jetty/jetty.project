package org.eclipse.jetty.servlets;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

/**
 * @author tsegismont
 */
public class BalancerServletTest extends AbstractBalancerServletTest
{

    @Test
    public void testRoundRobinBalancer() throws Exception
    {
        setStickySessions(false);
        startBalancer(CounterServlet.class);

        for (int i = 0; i < 10; i++)
        {
            byte[] responseBytes = sendRequestToBalancer("/");
            String returnedCounter = readFirstLine(responseBytes);
            // RR : response should increment every other request
            String expectedCounter = String.valueOf(i / 2);
            assertEquals(expectedCounter,returnedCounter);
        }
    }

    @Test
    public void testStickySessionsBalancer() throws Exception
    {
        setStickySessions(true);
        startBalancer(CounterServlet.class);

        for (int i = 0; i < 10; i++)
        {
            byte[] responseBytes = sendRequestToBalancer("/");
            String returnedCounter = readFirstLine(responseBytes);
            // RR : response should increment on each request
            String expectedCounter = String.valueOf(i);
            assertEquals(expectedCounter,returnedCounter);
        }
    }

    @Test
    public void testProxyPassReverse() throws Exception
    {
        setStickySessions(false);
        startBalancer(RelocationServlet.class);

        byte[] responseBytes = sendRequestToBalancer("index.html");
        String msg = readFirstLine(responseBytes);
        assertEquals("success",msg);
    }

    private String readFirstLine(byte[] responseBytes) throws IOException
    {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(responseBytes)));
        return reader.readLine();
    }

    @SuppressWarnings("serial")
    public static final class CounterServlet extends HttpServlet
    {

        private int counter;

        @Override
        public void init() throws ServletException
        {
            counter = 0;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            // Force session creation
            req.getSession();
            resp.setContentType("text/plain");
            resp.getWriter().println(counter++);
        }
    }

    @SuppressWarnings("serial")
    public static final class RelocationServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            if (req.getRequestURI().endsWith("/index.html"))
            {
                resp.sendRedirect("http://localhost:" + req.getLocalPort() + req.getContextPath() + req.getServletPath() + "/other.html?secret=pipo%20molo");
                return;
            }
            resp.setContentType("text/plain");
            if ("pipo molo".equals(req.getParameter("secret")))
            {
                resp.getWriter().println("success");
            }
            else
            {
                resp.getWriter().println("failure");
            }
        }
    }

}
