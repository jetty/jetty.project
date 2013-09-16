//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.servlets;

import java.io.IOException;

import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;


public abstract class AbstractBalancerServletTest
{

    private boolean _stickySessions;

    private Server _node1;

    private Server _node2;

    private Server _balancerServer;

    private HttpClient _httpClient;

    @Before
    public void setUp() throws Exception
    {
        _httpClient = new HttpClient();
        _httpClient.registerListener("org.eclipse.jetty.client.RedirectListener");
        _httpClient.start();
    }

    @After
    public void tearDown() throws Exception
    {
        stopServer(_node1);
        stopServer(_node2);
        stopServer(_balancerServer);
        _httpClient.stop();
    }

    private void stopServer(Server server)
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            // Do nothing
        }
    }

    protected void setStickySessions(boolean stickySessions)
    {
        _stickySessions = stickySessions;
    }

    protected void startBalancer(Class<? extends HttpServlet> httpServletClass) throws Exception
    {
        _node1 = createServer(new ServletHolder(httpServletClass.newInstance()),"/pipo","/molo/*");
        setSessionIdManager(_node1,"node1");
        _node1.start();

        _node2 = createServer(new ServletHolder(httpServletClass.newInstance()),"/pipo","/molo/*");
        setSessionIdManager(_node2,"node2");
        _node2.start();

        BalancerServlet balancerServlet = new BalancerServlet();
        ServletHolder balancerServletHolder = new ServletHolder(balancerServlet);
        balancerServletHolder.setInitParameter("StickySessions",String.valueOf(_stickySessions));
        balancerServletHolder.setInitParameter("ProxyPassReverse","true");
        balancerServletHolder.setInitParameter("BalancerMember." + "node1" + ".ProxyTo","http://localhost:" + getServerPort(_node1));
        balancerServletHolder.setInitParameter("BalancerMember." + "node2" + ".ProxyTo","http://localhost:" + getServerPort(_node2));

        _balancerServer = createServer(balancerServletHolder,"/pipo","/molo/*");
        _balancerServer.start();
    }

    private Server createServer(ServletHolder servletHolder, String appContext, String servletUrlPattern)
    {
        Server server = new Server();
        SelectChannelConnector httpConnector = new SelectChannelConnector();
        server.addConnector(httpConnector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath(appContext);
        server.setHandler(context);

        context.addServlet(servletHolder,servletUrlPattern);

        return server;
    }

    private void setSessionIdManager(Server node, String nodeName)
    {
        HashSessionIdManager sessionIdManager = new HashSessionIdManager();
        sessionIdManager.setWorkerName(nodeName);
        node.setSessionIdManager(sessionIdManager);
    }

    private int getServerPort(Server node)
    {
        return node.getConnectors()[0].getLocalPort();
    }

    protected byte[] sendRequestToBalancer(String requestUri) throws IOException, InterruptedException
    {
        ContentExchange exchange = new ContentExchange()
        {
            @Override
            protected void onResponseHeader(Buffer name, Buffer value) throws IOException
            {
                // Cookie persistence
                if (name.toString().equals("Set-Cookie"))
                {
                    String cookieVal = value.toString();
                    if (cookieVal.startsWith("JSESSIONID="))
                    {
                        String jsessionid = cookieVal.split(";")[0].substring("JSESSIONID=".length());
                        _httpClient.getDestination(getAddress(),false).addCookie(new HttpCookie("JSESSIONID",jsessionid));
                    }
                }
            }
        };
        exchange.setURL("http://localhost:" + getServerPort(_balancerServer) + "/pipo/molo/" + requestUri);
        exchange.setMethod(HttpMethods.GET);

        _httpClient.send(exchange);
        exchange.waitForDone();

        return exchange.getResponseContentBytes();
    }

}
