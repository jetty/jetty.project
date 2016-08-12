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

package org.eclipse.jetty.load.generator;


import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@RunWith( Parameterized.class )
public class LoadGeneratorTest
{
    SslContextFactory sslContextFactory;

    Server server;

    ServerConnector connector;

    final LoadGenerator.Transport transport;

    final int usersNumber;

    Logger logger = Log.getLogger( getClass());

    public LoadGeneratorTest( LoadGenerator.Transport transport, int usersNumber )
    {
        this.transport = transport;
        this.usersNumber = usersNumber;
    }

    /*
    @Parameterized.Parameters( name = "transport: {0}" )
    public static Object[] parameters()
        throws Exception
    {

        return new Object[]{
            LoadGenerator.Transport.HTTP }; //, LoadGenerator.Transport.HTTPS,
            //LoadGenerator.Transport.H2C }; // LoadGenerator.Transport.values(); LoadGenerator.Transport.H2,
    }

    @Parameterized.Parameters( name = "usersNumber: {0}" )
    public static Object[] usersNumber()
        throws Exception
    {
        return new Object[]{ new Integer( 1 ) };
    }

    */

    @Parameterized.Parameters(name = "transport/users: {0},{1}")
    public static Collection<Object[]> data() {


        List<LoadGenerator.Transport> transports = new ArrayList<>(  );

        transports.add( LoadGenerator.Transport.HTTP );

        // FIXME LoadGenerator.Transport.H2, issue with ALPN
        // FIXME other transports
        //transports.add( LoadGenerator.Transport.HTTPS );
        //transports.add( LoadGenerator.Transport.H2 );
        //transports.add( LoadGenerator.Transport.H2C );
        //transports.add( LoadGenerator.Transport.FCGI);


        // number of users
        List<Integer> users = Arrays.asList( 1, 2 );

        List<Object[]> parameters = new ArrayList<>(  );

        for ( LoadGenerator.Transport transport : transports) {
            for (Integer userNumber : users){
                parameters.add( new Object[]{transport, userNumber});
            }

        }
        return parameters;
    }

    @Test
    public void simple_test()
        throws Exception
    {

        TestResultHandler testResponseHandler = new TestResultHandler();

        TestRequestListener testRequestListener = new TestRequestListener();

        startServer( new LoadHandler() );

        Scheduler scheduler = new ScheduledExecutorScheduler( getClass().getName() + "-scheduler", false);

        LoadGenerator loadGenerator = LoadGenerator.Builder.builder() //
            .setHost( "localhost" ) //
            .setPort( connector.getLocalPort() ) //
            .setUsers( 1 ) //
            .setRequestRate( 1 ) //
            .setResultHandlers( Arrays.asList( testResponseHandler ) ) //
            .setRequestListeners( Arrays.asList( testRequestListener ) ) //
            .setTransport( this.transport ) //
            .setHttpClientScheduler( scheduler ) //
            .build() //
            .start();

        LoadGeneratorResult result = loadGenerator.run();
        loadGenerator.setResponseSize( 100 );

        Thread.sleep( 5000 );

        loadGenerator.setRequestRate( 10 );

        loadGenerator.setResponseSize( 0 );

        loadGenerator.setMethod( HttpMethod.POST.asString() );

        loadGenerator.setPayloadSize( 1000 );

        Thread.sleep( 3000 );

        Assert.assertTrue("successReponsesReceived :" + testResponseHandler.successReponsesReceived.get(), //
                          testResponseHandler.successReponsesReceived.get() > 1);

        logger.info( "successReponsesReceived: {}", testResponseHandler.successReponsesReceived.get() );

        Assert.assertTrue("failedReponsesReceived: " + testResponseHandler.failedReponsesReceived.get(), //
                          testResponseHandler.failedReponsesReceived.get() < 1);

        Assert.assertEquals( testResponseHandler.successReponsesReceived.get(), result.getTotalResponse().longValue() );

        Assert.assertEquals( testResponseHandler.successReponsesReceived.get(), result.getTotalSuccess().longValue() );

        Assert.assertNotNull( result );

        loadGenerator.stop();

    }

    //---------------------------------------------------
    // utilities
    //---------------------------------------------------

    static class TestResultHandler
        implements ResultHandler
    {

        AtomicLong successReponsesReceived = new AtomicLong();

        AtomicLong failedReponsesReceived = new AtomicLong();

        @Override
        public void onResponse( Result result )
        {
            if (result.isSucceeded())
            {
                successReponsesReceived.incrementAndGet();
            }
            if (result.isFailed())
            {
                result.getFailure().printStackTrace();
                failedReponsesReceived.incrementAndGet();
            }
        }

    }

    static class TestRequestListener
        implements Request.Listener
    {
        AtomicLong onSuccessNumber = new AtomicLong();

        @Override
        public void onQueued( Request request )
        {

        }

        @Override
        public void onBegin( Request request )
        {

        }

        @Override
        public void onHeaders( Request request )
        {

        }

        @Override
        public void onCommit( Request request )
        {

        }

        @Override
        public void onContent( Request request, ByteBuffer content )
        {

        }

        @Override
        public void onSuccess( Request request )
        {
            onSuccessNumber.incrementAndGet();
        }

        @Override
        public void onFailure( Request request, Throwable failure )
        {

        }
    }


    protected void startServer( HttpServlet handler )
        throws Exception
    {
        sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath( "src/test/resources/keystore.jks" );
        sslContextFactory.setKeyStorePassword( "storepwd" );
        sslContextFactory.setTrustStorePath( "src/test/resources/truststore.jks" );
        sslContextFactory.setTrustStorePassword( "storepwd" );
        sslContextFactory.setUseCipherSuitesOrder( true );
        sslContextFactory.setCipherComparator( HTTP2Cipher.COMPARATOR );
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName( "server" );
        server = new Server( serverThreads );
        server.setSessionIdManager( new HashSessionIdManager() );
        connector = newServerConnector( server );
        server.addConnector( connector );

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet( new ServletHolder( handler ), "/*" );

        server.start();
    }


    protected ServerConnector newServerConnector( Server server )
    {
        return new ServerConnector( server, provideServerConnectionFactory( transport ) );
    }

    protected ConnectionFactory[] provideServerConnectionFactory( LoadGenerator.Transport transport )
    {
        List<ConnectionFactory> result = new ArrayList<>();
        switch ( transport )
        {
            case HTTP:
            {
                result.add( new HttpConnectionFactory( new HttpConfiguration() ) );
                break;
            }
            case HTTPS:
            {
                HttpConfiguration configuration = new HttpConfiguration();
                configuration.addCustomizer( new SecureRequestCustomizer() );
                HttpConnectionFactory http = new HttpConnectionFactory( configuration );
                SslConnectionFactory ssl = new SslConnectionFactory( sslContextFactory, http.getProtocol() );
                result.add( ssl );
                result.add( http );
                break;
            }
            case H2C:
            {
                result.add( new HTTP2CServerConnectionFactory( new HttpConfiguration() ) );
                break;
            }
            case H2:
            {
                HttpConfiguration configuration = new HttpConfiguration();
                configuration.addCustomizer( new SecureRequestCustomizer() );
                HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory( configuration );
                ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory( "h2" );
                SslConnectionFactory ssl = new SslConnectionFactory( sslContextFactory, alpn.getProtocol() );
                result.add( ssl );
                result.add( alpn );
                result.add( h2 );
                break;
            }
            case FCGI:
            {
                result.add( new ServerFCGIConnectionFactory( new HttpConfiguration() ) );
                break;
            }
            default:
            {
                throw new IllegalArgumentException();
            }
        }
        return result.toArray( new ConnectionFactory[result.size()] );
    }

    private class LoadHandler
        extends HttpServlet
    {

        @Override
        protected void service( HttpServletRequest request, HttpServletResponse response )
            throws ServletException, IOException
        {

            String method = request.getMethod().toUpperCase( Locale.ENGLISH );

            HttpSession httpSession = request.getSession( );

            switch ( method )
            {
                case "GET":
                {
                    int contentLength = request.getIntHeader( "X-Download" );
                    if ( contentLength > 0 )
                    {
                        Log.getLogger( getClass() ).info( "contentLength: {}", contentLength );
                        response.setHeader( "X-Content", String.valueOf( contentLength ) );
                        response.getOutputStream().write( new byte[contentLength] );
                    }
                    break;
                }
                case "POST":
                {
                    Log.getLogger( getClass() ).info( "method: {}", method );
                    response.setHeader( "X-Content", request.getHeader( "X-Upload" ) );
                    IO.copy( request.getInputStream(), response.getOutputStream() );
                    break;
                }
            }

            if ( Boolean.parseBoolean( request.getHeader( "X-Close" ) ) )
            {
                response.setHeader( "Connection", "close" );
            }
        }
    }

}
