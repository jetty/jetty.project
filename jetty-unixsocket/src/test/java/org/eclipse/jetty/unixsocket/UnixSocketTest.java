//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;

public class UnixSocketTest
{

    private Logger log = Log.getLogger( getClass() );

    //public static void main( String... args )
    @Test
    public void test_unix_socket()
        throws Exception
    {
        if ( OS.IS_WINDOWS)
        {
            return;
        }
        Server server = new Server();
        HttpClient httpClient = null;

        try
        {
            HttpConnectionFactory http = new HttpConnectionFactory();

            Path sockFile = Files.createTempFile( "unix", ".sock" );

            UnixSocketConnector connector = new UnixSocketConnector( server, http );
            connector.setUnixSocket( sockFile.toString() );
            server.addConnector( connector );

            Path socket = Paths.get( connector.getUnixSocket() );
            Files.deleteIfExists( socket );

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

            String method = "GET";
            int content_length = 0;
            String data =
                method + " / HTTP/1.1\r\n" + "Host: unixsock\r\n" + "Content-Length: " + content_length + "\r\n"
                    + "Connection: close\r\n" + "\r\n";

            httpClient = new HttpClient( new HttpClientTransportOverUnixSockets( sockFile.toString() ), null );
            httpClient.start();

            ContentResponse contentResponse = httpClient
                .newRequest( "http://localhost" )
                .send();

            log.info( "response from server:" + contentResponse.getContentAsString() );

            httpClient.stop();
        }
        finally
        {
            stopQuietly( httpClient );
            stopQuietly( server );

        }
    }

    private static void stopQuietly( AbstractLifeCycle abstractLifeCycle )
    {
        if ( abstractLifeCycle != null )
        {
            try
            {
                abstractLifeCycle.stop();
            }
            catch ( Exception e )
            {
                e.printStackTrace();
            }
        }
    }

}
