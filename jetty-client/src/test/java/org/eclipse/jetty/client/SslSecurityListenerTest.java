// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.client.security.HashRealmResolver;
import org.eclipse.jetty.client.security.Realm;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.ssl.SslSocketConnector;

/**
 * Functional testing.
 */
public class SslSecurityListenerTest extends TestCase
{
    protected  Server _server;
    protected int _port;
    protected HttpClient _httpClient;
    protected Realm _jettyRealm;
    protected int _type = HttpClient.CONNECTOR_SOCKET;
    private static final String APP_CONTEXT = "localhost /";

    @Override
    protected void setUp() throws Exception
    {
        startServer();
        _httpClient = new HttpClient();
        _httpClient.setConnectorType(_type);
        _httpClient.setMaxConnectionsPerAddress(2);
        _httpClient.start();

        _jettyRealm = new Realm()
        {
            public String getId()
            {
                return "MyRealm";
            }

            public String getPrincipal()
            {
                return "jetty";
            }

            public String getCredentials()
            {
                return "jetty";
            }
        };

        HashRealmResolver resolver = new HashRealmResolver();
        resolver.addSecurityRealm(_jettyRealm);
        _httpClient.setRealmResolver(resolver);
    }

    @Override
    protected void tearDown() throws Exception
    {
        Thread.sleep(1000);
        _httpClient.stop();
        Thread.sleep(1000);
        stopServer();
    }

    public void testSslGet() throws Exception
    {
        final CyclicBarrier barrier = new CyclicBarrier(2);
        
        ContentExchange httpExchange = new ContentExchange(true)
        {
            @Override
            protected void onResponseComplete() throws IOException
            {
                super.onResponseComplete();
                try{barrier.await();}catch(Exception e){}
            }
        };
        
        // httpExchange.setURL("https://dav.codehaus.org/user/jesse/index.html");
        httpExchange.setURL("https://localhost:" + _port + "/");
        httpExchange.setMethod(HttpMethods.GET);
        // httpExchange.setRequestHeader("Connection","close");

        _httpClient.send(httpExchange);
        
        barrier.await(10000,TimeUnit.SECONDS);
        
        assertEquals(HttpServletResponse.SC_OK,httpExchange.getResponseStatus());

        // System.err.println(httpExchange.getResponseContent());
        assertTrue(httpExchange.getResponseContent().length()>400);
        
    }

    protected void startServer() throws Exception
    {
        _server = new Server();
        //SslSelectChannelConnector connector = new SslSelectChannelConnector();
        SslSocketConnector connector = new SslSocketConnector();

        String keystore = System.getProperty("user.dir") + File.separator + "src" + File.separator + "test" + File.separator + "resources" + File.separator
                + "keystore";

        connector.setPort(0);
        connector.setKeystore(keystore);
        connector.setPassword("storepwd");
        connector.setKeyPassword("keypwd");

        _server.setConnectors(new Connector[]
        { connector });

        Constraint constraint = new Constraint();
        constraint.setName("Need User or Admin");
        constraint.setRoles(new String[]
        { "user", "admin" });
        constraint.setAuthenticate(true);

        ConstraintMapping cm = new ConstraintMapping();
        cm.setConstraint(constraint);
        cm.setPathSpec("/*");

        HashLoginService loginService = new HashLoginService("MyRealm","src/test/resources/realm.properties");
        _server.addBean(loginService);
        
        BasicAuthenticator authenticator = new BasicAuthenticator();
        
        ConstraintSecurityHandler sh = new ConstraintSecurityHandler();
        sh.setAuthenticator(authenticator);
        
        Set<String> roles = new HashSet<String>(Arrays.asList(new String[]{"user", "admin"}));
        sh.setConstraintMappings(new ConstraintMapping[] { cm }, roles);
        _server.setHandler(sh);

        Handler testHandler = new AbstractHandler()
        {

            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                // System.err.println("passed authentication!\n"+((Request)request).getConnection().getRequestFields());
                
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentType("text/plain");
                if (request.getServerName().equals("jetty.eclipse.org"))
                {
                    response.getOutputStream().println("Proxy request: " + request.getRequestURL());
                }
                else if (request.getMethod().equalsIgnoreCase("GET"))
                {
                    response.getOutputStream().println("<hello>");
                    for (int i = 0; i < 100; i++)
                    {
                        response.getOutputStream().println("  <world>" + i + "</world>");
                        if (i % 20 == 0)
                            response.getOutputStream().flush();
                    }
                    response.getOutputStream().println("</hello>");
                }
                else
                {
                    copyStream(request.getInputStream(),response.getOutputStream());
                }
            }
        };

        sh.setHandler(testHandler);

        _server.start();
        _port = connector.getLocalPort();
    }

    public static void copyStream(InputStream in, OutputStream out)
    {
        try
        {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = in.read(buffer)) >= 0)
            {
                out.write(buffer,0,len);
            }
        }
        catch (EofException e)
        {
            System.err.println(e);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private void stopServer() throws Exception
    {
        _server.stop();
    }
}
