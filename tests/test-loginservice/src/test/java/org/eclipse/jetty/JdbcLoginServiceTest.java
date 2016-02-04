//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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



import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.JDBCLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
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
    private static HttpClient _client;
    private static String __realm = "JdbcRealm";
    private static URI _baseUri;
    private static DatabaseLoginServiceTestServer _testServer;
    
 

    @BeforeClass
    public static void setUp() throws Exception
    {
        _docRoot = MavenTestingUtils.getTargetTestingDir("loginservice-test");
        FS.ensureDirExists(_docRoot);
        File content = new File(_docRoot,"input.txt");
      
        
        try (FileOutputStream out = new FileOutputStream(content))
        {
            out.write(_content.getBytes("utf-8"));
        }

        //drop any tables that might have existed
        File scriptFile = MavenTestingUtils.getTestResourceFile("droptables.sql");
        int result =  DatabaseLoginServiceTestServer.runscript(scriptFile);
        //ignore result, if the tables dont already exist, derby spits out an error
        
        //create the tables afresh
        scriptFile = MavenTestingUtils.getTestResourceFile("createdb.sql");
        result = DatabaseLoginServiceTestServer.runscript(scriptFile);
        assertThat("runScript result",result, is(0));
        
        File jdbcRealmFile = MavenTestingUtils.getTestResourceFile("jdbcrealm.properties");
        
        LoginService loginService = new JDBCLoginService(__realm, jdbcRealmFile.getAbsolutePath());
        _testServer = new DatabaseLoginServiceTestServer();
        _testServer.setResourceBase(_docRoot.getAbsolutePath());
        _testServer.setLoginService(loginService);
        _testServer.start();
        _baseUri = _testServer.getBaseUri();
     }

     @AfterClass
     public static void tearDown()
         throws Exception
     {
         if (_testServer != null)
         {
             _testServer.stop();
             _testServer = null;
         }
     }

     @Test
     public void testPut() throws Exception
     {
         try
         {
             startClient();

             Request request = _client.newRequest(_baseUri.resolve("output.txt"));
             request.method(HttpMethod.PUT);
             request.content(new BytesContentProvider(_content.getBytes()));
             ContentResponse response = request.send();
             int responseStatus = response.getStatus();
             boolean statusOk = (responseStatus == 200 || responseStatus == 201);
             assertTrue(statusOk);
             String content = IO.toString(new FileInputStream(new File(_docRoot,"output.txt")));
             assertEquals(_content,content);
         }
         finally
         {
             stopClient();
         }
     }

     @Test
     public void testGet() throws Exception
     {
         try
         {
             startClient();

             ContentResponse response = _client.GET(_baseUri.resolve("input.txt"));
             assertEquals(HttpServletResponse.SC_OK,response.getStatus());
             assertEquals(_content, response.getContentAsString());
         }
         finally
         {
             stopClient();
         }
     }
     
     @Test
     public void testGetNonExistantUser () throws Exception
     {
         try
         {
             startClient("foo", "bar");
             ContentResponse response = _client.GET(_baseUri.resolve("input.txt"));
             assertEquals(HttpServletResponse.SC_UNAUTHORIZED,response.getStatus());
         }
         finally
         {
             stopClient();
         }
     }

     //Head requests to jetty-client are not working: see https://bugs.eclipse.org/bugs/show_bug.cgi?id=394552
     @Ignore
     public void testHead() throws Exception
     {
         try
         {
             startClient();

             Request request = _client.newRequest(_baseUri.resolve("input.txt"));
             request.method(HttpMethod.HEAD);
             ContentResponse response = request.send();
             int responseStatus = response.getStatus();
             assertEquals(HttpStatus.OK_200,responseStatus);
         }
         finally
         {
             stopClient();
         }
     }

     @Test
     public void testPost() throws Exception
     {
         try
         {
             startClient();

             Request request = _client.newRequest(_baseUri.resolve("test"));
             request.method(HttpMethod.POST);
             request.content(new BytesContentProvider(_content.getBytes()));
             ContentResponse response = request.send();
             assertEquals(HttpStatus.OK_200,response.getStatus());
             assertEquals(_content,_testServer.getTestHandler().getRequestContent());
         }
         finally
         {
             stopClient();
         }
     }

     protected void startClient(String user, String pwd)
         throws Exception
     {
         _client = new HttpClient();
         QueuedThreadPool executor = new QueuedThreadPool();
         executor.setName(executor.getName() + "-client");
         _client.setExecutor(executor);
         AuthenticationStore authStore = _client.getAuthenticationStore();
         authStore.addAuthentication(new BasicAuthentication(_baseUri, __realm, user, pwd));
         _client.start();
     }

     protected void startClient()
         throws Exception
     {
         startClient("jetty", "jetty");
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

     protected HttpClient getClient()
     {
         return _client;
     }

     protected String getContent()
     {
         return _content;
     }
}
