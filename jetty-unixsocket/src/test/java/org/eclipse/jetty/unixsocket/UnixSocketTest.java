//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.unixsocket;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.concurrent.ExecutionException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;

@EnabledOnOs({LINUX, MAC})
public class UnixSocketTest
{
    private static final Logger log = Log.getLogger(UnixSocketTest.class);

    private Server server;
    private HttpClient httpClient;
    private Path sockFile;

    @BeforeEach
    public void before() throws Exception
    {
        server = null;
        httpClient = null;
        String unixSocketTmp = System.getProperty( "unix.socket.tmp" );
        if(StringUtil.isNotBlank( unixSocketTmp ) )
        {
            sockFile = Files.createTempFile( Paths.get(unixSocketTmp), "unix", ".sock" );
        } else {
            sockFile = Files.createTempFile("unix", ".sock" );
        }
        assertTrue(Files.deleteIfExists(sockFile),"temp sock file cannot be deleted");

    }
    
    @AfterEach
    public void after() throws Exception
    {
        if (httpClient!=null)
            httpClient.stop();
        if (server!=null)
            server.stop();
        // Force delete, this will fail if UnixSocket was not closed properly in the implementation
        FS.delete( sockFile);
    }
    
    @Test
    public void testUnixSocket() throws Exception
    {
        server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();

        UnixSocketConnector connector = new UnixSocketConnector( server, http );
        connector.setUnixSocket( sockFile.toString() );
        server.addConnector( connector );

        server.setHandler( new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle( String target, Request baseRequest, HttpServletRequest request,
                    HttpServletResponse response )
                            throws IOException, ServletException
            {
                int l = 0;
                if ( request.getContentLength() != 0 )
                {
                    InputStream in = request.getInputStream();
                    byte[] buffer = new byte[4096];
                    int r = 0;
                    while ( r >= 0 )
                    {
                        l += r;
                        r = in.read( buffer );
                    }
                }
                log.info( "UnixSocketTest: request received" );
                baseRequest.setHandled( true );
                response.setStatus( 200 );
                response.getWriter().write( "Hello World " + new Date() + "\r\n" );
                response.getWriter().write(
                        "remote=" + request.getRemoteAddr() + ":" + request.getRemotePort() + "\r\n" );
                response.getWriter().write(
                        "local =" + request.getLocalAddr() + ":" + request.getLocalPort() + "\r\n" );
                response.getWriter().write( "read =" + l + "\r\n" );
            }
        } );

        server.start();

        httpClient = new HttpClient( new HttpClientTransportOverUnixSockets( sockFile.toString() ), null );
        httpClient.start();

        ContentResponse contentResponse = httpClient
                .newRequest( "http://localhost" )
                .send();

        log.debug( "response from server: {}", contentResponse.getContentAsString() );

        assertThat(contentResponse.getContentAsString(), containsString( "Hello World" ));
    }

    @Test
    public void testNotLocal() throws Exception
    {        
        httpClient = new HttpClient( new HttpClientTransportOverUnixSockets( sockFile.toString() ), null );
        httpClient.start();
        
        ExecutionException e = assertThrows(ExecutionException.class, ()->{
            httpClient.newRequest( "http://google.com" ).send();
        });
        assertThat(e.getCause(), instanceOf(IOException.class));
        assertThat(e.getCause().getMessage(),containsString("UnixSocket cannot connect to google.com"));
    }
}
