package org.eclipse.jetty.client;

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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.log.Log;

/* Test expiring connections
 * 
 * Test contributed by: Michiel Thuys for JETTY-806
 */
public class ExpireTest extends TestCase
{
    HttpClient client;

    Server server;

    AtomicInteger expireCount = new AtomicInteger();

    final String host = "localhost";

    int _port;

    @Override
    protected void setUp() throws Exception
    {
        client = new HttpClient();
        client.setConnectorType( HttpClient.CONNECTOR_SELECT_CHANNEL );
        client.setTimeout( 200 );
        client.setMaxRetries( 0 );
        client.setMaxConnectionsPerAddress(100);
        try
        {
            client.start();
        }
        catch ( Exception e )
        {
            throw new Error( "Cannot start HTTP client: " + e );
        }

        // Create server
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setHost( host );
        connector.setPort( 0 );
        server.setConnectors( new Connector[] { connector } );
        server.setHandler( new AbstractHandler()
        {
            public void handle( String target, Request baseRequest, HttpServletRequest servletRequest, HttpServletResponse response ) throws IOException,
                ServletException
            {
                Request request = (Request) servletRequest;
                try
                {
                    Thread.sleep( 2000 );
                }
                catch ( InterruptedException e )
                {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                request.setHandled( true );
            }
        } );
        try
        {
            server.start();
            _port = connector.getLocalPort();
        }
        catch ( Exception e )
        {
            Log.warn( "Cannot create server: " + e );
        }
    }

    @Override
    protected void tearDown() throws Exception
    {
        client.stop();
        server.stop();
    }

    public void testExpire() throws IOException
    {
        String baseUrl = "http://" + host + ":" + _port + "/";

        int count = 200;
        expireCount.set( 0 );
        Log.info( "Starting test on " + baseUrl );

        for (int i=0;i<count;i++)
        {
            if (i%10==0)
                System.err.print('.');
            expireCount.incrementAndGet();
            final ContentExchange ex = new ContentExchange()
            {
                @Override
                protected void onExpire()
                {
                    expireCount.decrementAndGet();
                }
            };
            ex.setMethod( "GET" );
            ex.setURL( baseUrl );

            client.send( ex );
            try
            {
                Thread.sleep( 50 );
            }
            catch ( InterruptedException e )
            {
                break;
            }
        }
        // Log.info("Test done");
        // Wait to be sure that all exchanges have expired
        try
        {
            Thread.sleep( 2000 );
            int loops = 0;
            while ( expireCount.get()>0 && loops < 10 ) // max out at 30 seconds
            {
                Log.info( "waiting for test to complete: "+expireCount.get()+" of "+count );
                ++loops;
                Thread.sleep( 2000 );
            }
            Thread.sleep( 2000 );
        }
        catch ( InterruptedException e )
        {
        }
        System.err.println('!');

        assertEquals( 0, expireCount.get() );
    }
}
