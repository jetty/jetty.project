//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.test;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class DigestPostTest
{
    private static final String NC = "00000001";
    
    public final static String __message = 
        "0123456789 0123456789 0123456789 0123456789 0123456789 0123456789 0123456789 0123456789 \n"+
        "9876543210 9876543210 9876543210 9876543210 9876543210 9876543210 9876543210 9876543210 \n"+
        "1234567890 1234567890 1234567890 1234567890 1234567890 1234567890 1234567890 1234567890 \n"+
        "0987654321 0987654321 0987654321 0987654321 0987654321 0987654321 0987654321 0987654321 \n"+
        "abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz \n"+
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ ABCDEFGHIJKLMNOPQRSTUVWXYZ ABCDEFGHIJKLMNOPQRSTUVWXYZ \n"+
        "Now is the time for all good men to come to the aid of the party.\n"+
        "How now brown cow.\n"+
        "The quick brown fox jumped over the lazy dog.\n";
    
    public volatile static String _received = null;
    private static Server _server;

    public static class TestLoginService extends AbstractLoginService
    {
        protected Map<String, UserPrincipal> users = new HashMap<>();
        protected Map<String, String[]> roles = new HashMap<>();
     
      
        public TestLoginService(String name)
        {
            setName(name);
        }

        public void putUser (String username, Credential credential, String[] rolenames)
        {
            UserPrincipal userPrincipal = new UserPrincipal(username,credential);
            users.put(username, userPrincipal);
            roles.put(username, rolenames);
        }
        
        /** 
         * @see org.eclipse.jetty.security.AbstractLoginService#loadRoleInfo(org.eclipse.jetty.security.AbstractLoginService.UserPrincipal)
         */
        @Override
        protected String[] loadRoleInfo(UserPrincipal user)
        {
          return roles.get(user.getName());
        }

        /** 
         * @see org.eclipse.jetty.security.AbstractLoginService#loadUserInfo(java.lang.String)
         */
        @Override
        protected UserPrincipal loadUserInfo(String username)
        {
            return users.get(username);
        }
    }
    
    
    @BeforeClass
    public static void setUpServer()
    {
        try
        {
            _server = new Server();
            _server.setConnectors(new Connector[] { new ServerConnector(_server) });

            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SECURITY);
            context.setContextPath("/test");
            context.addServlet(PostServlet.class,"/");

            TestLoginService realm = new TestLoginService("test");
            realm.putUser("testuser",new Password("password"),new String[]{"test"});
            _server.addBean(realm);
            
            ConstraintSecurityHandler security=(ConstraintSecurityHandler)context.getSecurityHandler();
            security.setAuthenticator(new DigestAuthenticator());
            security.setLoginService(realm);
           
            Constraint constraint = new Constraint("SecureTest","test");
            constraint.setAuthenticate(true);
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setConstraint(constraint);
            mapping.setPathSpec("/*");
            
            security.setConstraintMappings(Collections.singletonList(mapping));
            
            HandlerCollection handlers = new HandlerCollection();
            handlers.setHandlers(new Handler[]
            { context, new DefaultHandler() });
            _server.setHandler(handlers);
            
            _server.start();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }
    }

    @AfterClass
    public static void tearDownServer() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testServerDirectlyHTTP10() throws Exception
    {
        Socket socket = new Socket("127.0.0.1",((NetworkConnector)_server.getConnectors()[0]).getLocalPort());
        byte[] bytes = __message.getBytes(StandardCharsets.UTF_8);

        _received=null;
        socket.getOutputStream().write(
                ("POST /test/ HTTP/1.0\r\n"+
                "Host: 127.0.0.1:"+((NetworkConnector)_server.getConnectors()[0]).getLocalPort()+"\r\n"+
                "Content-Length: "+bytes.length+"\r\n"+
                "\r\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();

        String result = IO.toString(socket.getInputStream());

        Assert.assertTrue(result.startsWith("HTTP/1.1 401 Unauthorized"));
        Assert.assertEquals(null,_received);
        
        int n=result.indexOf("nonce=");
        String nonce=result.substring(n+7,result.indexOf('"',n+7));
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] b= md.digest(String.valueOf(System.currentTimeMillis()).getBytes(org.eclipse.jetty.util.StringUtil.__ISO_8859_1));            
        String cnonce=encode(b);
        String digest="Digest username=\"testuser\" realm=\"test\" nonce=\""+nonce+"\" uri=\"/test/\" algorithm=MD5 response=\""+
        newResponse("POST","/test/",cnonce,"testuser","test","password",nonce,"auth")+
        "\" qop=auth nc="+NC+" cnonce=\""+cnonce+"\"";
              
        
        socket = new Socket("127.0.0.1",((NetworkConnector)_server.getConnectors()[0]).getLocalPort());

        _received=null;
        socket.getOutputStream().write(
                ("POST /test/ HTTP/1.0\r\n"+
                "Host: 127.0.0.1:"+((NetworkConnector)_server.getConnectors()[0]).getLocalPort()+"\r\n"+
                "Content-Length: "+bytes.length+"\r\n"+
                "Authorization: "+digest+"\r\n"+
                "\r\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();

        result = IO.toString(socket.getInputStream());

        Assert.assertTrue(result.startsWith("HTTP/1.1 200 OK"));
        Assert.assertEquals(__message,_received);
    }

    @Test
    public void testServerDirectlyHTTP11() throws Exception
    {
        Socket socket = new Socket("127.0.0.1",((NetworkConnector)_server.getConnectors()[0]).getLocalPort());
        byte[] bytes = __message.getBytes(StandardCharsets.UTF_8);

        _received=null;
        socket.getOutputStream().write(
                ("POST /test/ HTTP/1.1\r\n"+
                "Host: 127.0.0.1:"+((NetworkConnector)_server.getConnectors()[0]).getLocalPort()+"\r\n"+
                "Content-Length: "+bytes.length+"\r\n"+
                "\r\n").getBytes("UTF-8"));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();

        Thread.sleep(100);
        
        byte[] buf=new byte[4096];
        int len=socket.getInputStream().read(buf);
        String result=new String(buf,0,len,StandardCharsets.UTF_8);

        Assert.assertTrue(result.startsWith("HTTP/1.1 401 Unauthorized"));
        Assert.assertEquals(null,_received);
        
        int n=result.indexOf("nonce=");
        String nonce=result.substring(n+7,result.indexOf('"',n+7));
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] b= md.digest(String.valueOf(System.currentTimeMillis()).getBytes(StringUtil.__ISO_8859_1));            
        String cnonce=encode(b);
        String digest="Digest username=\"testuser\" realm=\"test\" nonce=\""+nonce+"\" uri=\"/test/\" algorithm=MD5 response=\""+
        newResponse("POST","/test/",cnonce,"testuser","test","password",nonce,"auth")+
        "\" qop=auth nc="+NC+" cnonce=\""+cnonce+"\"";

        _received=null;
        socket.getOutputStream().write(
                ("POST /test/ HTTP/1.0\r\n"+
                "Host: 127.0.0.1:"+((NetworkConnector)_server.getConnectors()[0]).getLocalPort()+"\r\n"+
                "Content-Length: "+bytes.length+"\r\n"+
                "Authorization: "+digest+"\r\n"+
                "\r\n").getBytes("UTF-8"));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();

        result = IO.toString(socket.getInputStream());

        Assert.assertTrue(result.startsWith("HTTP/1.1 200 OK"));
        Assert.assertEquals(__message,_received);
    }

    @Test
    public void testServerWithHttpClientStringContent() throws Exception
    {
        String srvUrl = "http://127.0.0.1:" + ((NetworkConnector)_server.getConnectors()[0]).getLocalPort() + "/test/";        
        HttpClient client = new HttpClient();

        try
        {
            AuthenticationStore authStore = client.getAuthenticationStore();
            authStore.addAuthentication(new DigestAuthentication(new URI(srvUrl), "test", "testuser", "password"));
            client.start();

            Request request = client.newRequest(srvUrl);
            request.method(HttpMethod.POST);
            request.content(new BytesContentProvider(__message.getBytes("UTF8")));
            _received=null;
            request = request.timeout(5, TimeUnit.SECONDS);
            ContentResponse response = request.send();
            Assert.assertEquals(__message,_received);
            Assert.assertEquals(200,response.getStatus());
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testServerWithHttpClientStreamContent() throws Exception
    {
        String srvUrl = "http://127.0.0.1:" + ((NetworkConnector)_server.getConnectors()[0]).getLocalPort() + "/test/";       
        HttpClient client = new HttpClient();
        try
        {
            AuthenticationStore authStore = client.getAuthenticationStore();
            authStore.addAuthentication(new DigestAuthentication(new URI(srvUrl), "test", "testuser", "password"));   
            client.start();

            String sent = IO.toString(new FileInputStream("src/test/resources/message.txt"));
            
            Request request = client.newRequest(srvUrl);
            request.method(HttpMethod.POST);
            request.content(new StringContentProvider(sent));
            _received=null;
            request = request.timeout(5, TimeUnit.SECONDS);
            ContentResponse response = request.send();
           
            Assert.assertEquals(200,response.getStatus());
            Assert.assertEquals(sent,_received);

        }
        finally
        {
            client.stop();
        }
    }

 

    public static class PostServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException
        {
            String received = IO.toString(request.getInputStream());
            _received = received;

            response.setStatus(200);
            response.getWriter().println("Received "+received.length()+" bytes");
        }

    }

    protected String newResponse(String method, String uri, String cnonce, String principal, String realm, String credentials, String nonce, String qop)
        throws Exception
    {       
        MessageDigest md = MessageDigest.getInstance("MD5");

        // calc A1 digest
        md.update(principal.getBytes(StringUtil.__ISO_8859_1));
        md.update((byte)':');
        md.update(realm.getBytes(StringUtil.__ISO_8859_1));
        md.update((byte)':');
        md.update(credentials.getBytes(StringUtil.__ISO_8859_1));
        byte[] ha1 = md.digest();
        // calc A2 digest
        md.reset();
        md.update(method.getBytes(StringUtil.__ISO_8859_1));
        md.update((byte)':');
        md.update(uri.getBytes(StringUtil.__ISO_8859_1));
        byte[] ha2=md.digest();

        md.update(TypeUtil.toString(ha1,16).getBytes(StringUtil.__ISO_8859_1));
        md.update((byte)':');
        md.update(nonce.getBytes(StringUtil.__ISO_8859_1));
        md.update((byte)':');
        md.update(NC.getBytes(StringUtil.__ISO_8859_1));
        md.update((byte)':');
        md.update(cnonce.getBytes(StringUtil.__ISO_8859_1));
        md.update((byte)':');
        md.update(qop.getBytes(StringUtil.__ISO_8859_1));
        md.update((byte)':');
        md.update(TypeUtil.toString(ha2,16).getBytes(StringUtil.__ISO_8859_1));
        byte[] digest=md.digest();

        // check digest
        return encode(digest);
    }
    
    private static String encode(byte[] data)
    {
        StringBuffer buffer = new StringBuffer();
        for (int i=0; i<data.length; i++) 
        {
            buffer.append(Integer.toHexString((data[i] & 0xf0) >>> 4));
            buffer.append(Integer.toHexString(data[i] & 0x0f));
        }
        return buffer.toString();
    }
}
