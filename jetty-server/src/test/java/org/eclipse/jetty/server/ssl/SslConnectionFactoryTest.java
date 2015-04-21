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

package org.eclipse.jetty.server.ssl;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.ExtendedSslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SslConnectionFactoryTest
{        
    Server _server;
    int _port;
    
    @Before
    public void before() throws Exception
    {
        String keystorePath = "src/test/resources/snikeystore";
        File keystoreFile = new File(keystorePath);
        if (!keystoreFile.exists())
        {
            throw new FileNotFoundException(keystoreFile.getAbsolutePath());
        }

        _server = new Server();

        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setSecureScheme("https");
        http_config.setSecurePort(8443);
        http_config.setOutputBufferSize(32768);
        HttpConfiguration https_config = new HttpConfiguration(http_config);
        https_config.addCustomizer(new SecureRequestCustomizer());

        
        SslContextFactory sslContextFactory = new ExtendedSslContextFactory();
        sslContextFactory.setKeyStorePath(keystoreFile.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");

        ServerConnector https = new ServerConnector(_server,
            new SslConnectionFactory(sslContextFactory,HttpVersion.HTTP_1_1.asString()),
                new HttpConnectionFactory(https_config));
        https.setPort(0);
        https.setIdleTimeout(30000);

        _server.addConnector(https);
        
        _server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(200);
                response.getWriter().write("url="+request.getRequestURI()+"\nhost="+request.getServerName());
                response.flushBuffer();
            }
        });
        
        _server.start();
        _port=https.getLocalPort();
        
    }
    
    @After
    public void after() throws Exception
    {
        _server.stop();
        _server=null;
    }


    @Test
    public void testPattern() throws Exception
    {
        String[] names = 
            {
                "cn=foo.bar,o=other",
                "   cn=  foo.bar  ,  o=other  ",
                "o=other,cn=foo.bar",
                "  o=other  ,  cn=  foo.bar   ",
                "CN=foo.bar,O=other",
            };
        
        for (String n:names)
        {
            Matcher matcher = ExtendedSslContextFactory.__cnPattern.matcher(n);
            Assert.assertTrue(matcher.matches());
            Assert.assertThat(matcher.group(1),Matchers.equalTo("foo.bar"));
        }
    }
    
    @Test
    public void testConnect() throws Exception
    {
        String response= getResponse("127.0.0.1",null);        
        Assert.assertThat(response,Matchers.containsString("host=127.0.0.1"));
    }
    
    @Test
    public void testSNIConnect() throws Exception
    {
        String response= getResponse("jetty.eclipse.org","jetty.eclipse.org");
        Assert.assertThat(response,Matchers.containsString("host=jetty.eclipse.org"));
        
        response= getResponse("www.example.com","www.example.com");
        Assert.assertThat(response,Matchers.containsString("host=www.example.com"));
        
        response= getResponse("foo.domain.com","*.domain.com");
        Assert.assertThat(response,Matchers.containsString("host=foo.domain.com"));
        
        response= getResponse("m.san.com","san example");
        Assert.assertThat(response,Matchers.containsString("host=m.san.com"));
        
        response= getResponse("www.san.com","san example");
        Assert.assertThat(response,Matchers.containsString("host=www.san.com"));
    }

    
    private String getResponse(String host,String cn) throws Exception
    {
        SslContextFactory clientContextFactory = new SslContextFactory(true);
        clientContextFactory.start();
        SSLSocketFactory factory = clientContextFactory.getSslContext().getSocketFactory();
        
        SSLSocket sslSocket = (SSLSocket)factory.createSocket("127.0.0.1", _port);

        if (cn!=null)
        {        
            SNIHostName serverName = new SNIHostName(host);
            List<SNIServerName> serverNames = new ArrayList<>();
            serverNames.add(serverName);

            SSLParameters params = sslSocket.getSSLParameters();
            params.setServerNames(serverNames);
            sslSocket.setSSLParameters(params);
        }
        sslSocket.startHandshake();

        
        if (cn!=null)
        {                                        
            X509Certificate cert = ((X509Certificate)sslSocket.getSession().getPeerCertificates()[0]);
            
            Assert.assertThat(cert.getSubjectX500Principal().getName("CANONICAL"), Matchers.startsWith("cn="+cn));
        }

        sslSocket.getOutputStream().write(("GET /ctx/path HTTP/1.0\r\nHost: "+host+":"+_port+"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        String response = IO.toString(sslSocket.getInputStream());
        Assert.assertThat(response,Matchers.startsWith("HTTP/1.1 200 OK"));
        Assert.assertThat(response,Matchers.containsString("url=/ctx/path"));
        
        sslSocket.close();
        clientContextFactory.stop();
        return response;
    }
}
