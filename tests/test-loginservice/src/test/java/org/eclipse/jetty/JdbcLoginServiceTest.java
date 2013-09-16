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

package org.eclipse.jetty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Collections;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.derby.tools.ij;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.security.Realm;
import org.eclipse.jetty.client.security.SimpleRealmResolver;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.JDBCLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class JdbcLoginServiceTest
{ 
    private static String _content =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. "+
        "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque "+
        "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. "+
        "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam "+
        "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate "+
        "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. "+
        "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum "+
        "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa "+
        "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam "+
        "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. "+
        "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse "+
        "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.";

    private static File _docRoot;
    private static Server _server;
    private static HttpClient _client;
    private static Realm _realm;
    private static String _protocol;
    private static String _baseUrl;
    private static String _requestContent;
    
    protected static boolean createDB(String homeDir, String fileName, String dbUrl)
    {
        FileInputStream fileStream = null;
        try
        {
            File scriptFile = new File(fileName);
            fileStream = new FileInputStream(scriptFile);
            
            Loader.loadClass(fileStream.getClass(), "org.apache.derby.jdbc.EmbeddedDriver").newInstance();
            Connection connection = DriverManager.getConnection(dbUrl, "", "");
            
            OutputStream out = new ByteArrayOutputStream();
            int result = ij.runScript(connection, fileStream, "UTF-8", out, "UTF-8");

            return (result==0);
        }
        catch (Exception e)
        {
            return false;
        }
        finally {
            if (fileStream!=null)
            {
                try
                {
                    fileStream.close();
                }
                catch (IOException e) {}
            }
        }
    }

    protected static void configureServer(Server server)
        throws Exception
    {
        setProtocol("http");
        setRealm(new Realm()
                 {
                     public String getId()
                     {
                         return "JdbcRealm";
                     }
                
                     public String getPrincipal()
                     {
                         return "jetty";
                     }
                
                     public String getCredentials()
                     {
                         return "jetty";
                     }
                 });
                        
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);
        
        LoginService loginService = new JDBCLoginService("JdbcRealm", "./src/test/resources/jdbcrealm.properties");
        server.addBean(loginService); 

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        server.setHandler(security);

        Constraint constraint = new Constraint();
        constraint.setName("auth");
        constraint.setAuthenticate( true );
        constraint.setRoles(new String[]{"user", "admin"});

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec( "/*" );
        mapping.setConstraint( constraint );

        Set<String> knownRoles = new HashSet<String>();
        knownRoles.add("user");
        knownRoles.add("admin");
        
        security.setConstraintMappings(Collections.singletonList(mapping), knownRoles);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);
        security.setStrict(false);
        
        ServletContextHandler root = new ServletContextHandler();
        root.setContextPath("/");
        root.setResourceBase(getBasePath());
        ServletHolder servletHolder = new ServletHolder( new DefaultServlet() );
        servletHolder.setInitParameter( "gzip", "true" );
        root.addServlet( servletHolder, "/*" );    

        Handler handler = new TestHandler(getBasePath());       
        
        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[]{handler, root});
        security.setHandler(handlers);
    }
 
    @BeforeClass
     public static void setUp()
         throws Exception
     {
         _docRoot = new File("target/test-output/docroot/");
         _docRoot.mkdirs();
         _docRoot.deleteOnExit();
         
         File content = new File(_docRoot,"input.txt");
         FileOutputStream out = new FileOutputStream(content);
         out.write(_content.getBytes("utf-8"));
         out.close();

         File dbRoot = new File("target/test-output/derby");
         String dbPath = dbRoot.getAbsolutePath();
         System.setProperty("derby.system.home", dbPath);
         if (!dbRoot.exists())
         {
             dbRoot.mkdirs();
             createDB(dbPath, "src/test/resources/createdb.sql", "jdbc:derby:jdbcrealm;create=true");
         }
         
         _server = new Server();
         configureServer(_server);
         _server.start();

         int port = _server.getConnectors()[0].getLocalPort();
         _baseUrl = _protocol+"://localhost:"+port+ "/";
     }
     
     @AfterClass
     public static void tearDown()
         throws Exception
     {
         if (_server != null)
         {
             _server.stop();
             _server = null;
         }
     }
     
     @Test
     public void testPut() throws Exception
     {
         startClient(_realm);
     
         ContentExchange putExchange = new ContentExchange();
         putExchange.setURL(getBaseUrl() + "output.txt");
         putExchange.setMethod(HttpMethods.PUT);
         putExchange.setRequestContent(new ByteArrayBuffer(_content.getBytes()));
     
         _client.send(putExchange);
         int state = putExchange.waitForDone();
     
         int responseStatus = putExchange.getResponseStatus();
     
         stopClient();
     
         boolean statusOk = (responseStatus == 200 || responseStatus == 201);
         assertTrue(statusOk);
         
         String content = IO.toString(new FileInputStream(new File(_docRoot,"output.txt")));
         assertEquals(_content,content);
     }
     
     @Test
     public void testGet() throws Exception
     {
         startClient(_realm);
     
         ContentExchange getExchange = new ContentExchange();
         getExchange.setURL(getBaseUrl() + "input.txt");
         getExchange.setMethod(HttpMethods.GET);
     
         _client.send(getExchange);
         int state = getExchange.waitForDone();
     
         String content = "";
         int responseStatus = getExchange.getResponseStatus();
         if (responseStatus == HttpStatus.OK_200)
         {
             content = getExchange.getResponseContent();
         }
     
         stopClient();
     
         assertEquals(HttpStatus.OK_200,responseStatus);
         assertEquals(_content,content);
     }

     @Test
     public void testHead() throws Exception
     {
         startClient(_realm);
     
         ContentExchange getExchange = new ContentExchange();
         getExchange.setURL(getBaseUrl() + "input.txt");
         getExchange.setMethod(HttpMethods.HEAD);
     
         _client.send(getExchange);
         int state = getExchange.waitForDone();
     
         int responseStatus = getExchange.getResponseStatus();

         stopClient();
     
         assertEquals(HttpStatus.OK_200,responseStatus);
     }

     @Test
     public void testPost() throws Exception
     {
         startClient(_realm);
     
         ContentExchange postExchange = new ContentExchange();
         postExchange.setURL(getBaseUrl() + "test");
         postExchange.setMethod(HttpMethods.POST);
         postExchange.setRequestContent(new ByteArrayBuffer(_content.getBytes()));
    
         _client.send(postExchange);
         int state = postExchange.waitForDone();
     
         int responseStatus = postExchange.getResponseStatus();
  
         stopClient();
     
         assertEquals(HttpStatus.OK_200,responseStatus);
         assertEquals(_content,_requestContent);
     }
     
     protected void startClient(Realm realm)
         throws Exception
     {
         _client = new HttpClient();
         _client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
         if (realm != null)
             _client.setRealmResolver(new SimpleRealmResolver(realm));
         _client.start();
     }
     
     protected void stopClient()
         throws Exception
     {
         if (_client != null)
         {
             _client.stop();
             _client = null;
         }
     }
     
     protected static String getBasePath()
     {
         return _docRoot.getAbsolutePath();
     }
     
     protected String getBaseUrl()
     {
         return _baseUrl;
     }
     
     protected HttpClient getClient()
     {
         return _client;
     }
     
     protected Realm getRealm()
     {
         return _realm;
     }
     
     protected String getContent()
     {
         return _content;
     }
     
     protected static void setProtocol(String protocol)
     {
         _protocol = protocol;
     }
     
     protected static void setRealm(Realm realm)
     {
         _realm = realm;
     }
     
     public static void copyStream(InputStream in, OutputStream out)
     {
         try
         {
             byte[] buffer=new byte[1024];
             int len;
             while ((len=in.read(buffer))>=0)
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

     protected static class TestHandler extends AbstractHandler {
         private final String resourcePath;

         public TestHandler(String repositoryPath) {
             this.resourcePath = repositoryPath;
         }

         public void handle(String target, Request baseRequest,
                 HttpServletRequest request, HttpServletResponse response)
             throws IOException, ServletException
         {
             if (baseRequest.isHandled())
             {
                 return;
             }

             OutputStream out = null;
             
             if (baseRequest.getMethod().equals("PUT"))
             {
                 baseRequest.setHandled(true);

                 File file = new File(resourcePath, URLDecoder.decode(request.getPathInfo()));
                 file.getParentFile().mkdirs();
                 file.deleteOnExit();
             
                 out = new FileOutputStream(file);

                     response.setStatus(HttpServletResponse.SC_CREATED);
             }
             
             if (baseRequest.getMethod().equals("POST"))
             {
                 baseRequest.setHandled(true);
                 out = new ByteArrayOutputStream();

                 response.setStatus(HttpServletResponse.SC_OK);
             }
             
             if (out != null)
             {
                 ServletInputStream in = request.getInputStream();
                 try
                 {
                     copyStream( in, out );
                 }
                 finally
                 {
                     in.close();
                     out.close();
                 }
                 
                 if (!(out instanceof FileOutputStream))
                     _requestContent = out.toString();
             }
             
         }
     }
}
