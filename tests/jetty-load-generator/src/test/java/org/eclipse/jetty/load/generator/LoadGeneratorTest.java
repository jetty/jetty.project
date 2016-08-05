package org.eclipse.jetty.load.generator;


import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RunWith( Parameterized.class )
public class LoadGeneratorTest
{
    protected SslContextFactory sslContextFactory;

    protected Server server;

    protected ServerConnector connector;

    protected final LoadGenerator.Transport transport;

    public LoadGeneratorTest( LoadGenerator.Transport transport )
    {
        this.transport = transport;
    }

    @Parameterized.Parameters( name = "transport: {0}" )
    public static Object[] parameters()
        throws Exception
    {
        // FIXME LoadGenerator.Transport.H2, issue with ALPN
        return new Object[]{ LoadGenerator.Transport.HTTP, LoadGenerator.Transport.HTTPS, LoadGenerator.Transport.H2C };// LoadGenerator.Transport.values();
    }

    @Test
    public void one_user_with_one_request()
        throws Exception
    {

        TestResultHandler testResponseHandler = new TestResultHandler();

        TestRequestListener testRequestListener = new TestRequestListener();

        startServer( new LoadHandler() );

        LoadGenerator loadGenerator = LoadGenerator.Builder.builder() //
            .setHost( "localhost" ) //
            .setPort( connector.getLocalPort() ) //
            .setUsers( 1 ) //
            .setRequestNumber( 1 ) //
            .setResultHandlers( Arrays.asList( testResponseHandler ) ) //
            .setRequestListeners( Arrays.asList( testRequestListener ) ) //
            .setTransport( LoadGenerator.Transport.HTTP ) //
            .build() //
            .start();

        Future<LoadGeneratorResult> result = loadGenerator.run();

        LoadGeneratorResult loadGeneratorResult = result.get( 10, TimeUnit.MINUTES );

        Assert.assertEquals( 1, testResponseHandler.reponsesReceived.longValue() );

        Assert.assertEquals( 1, testResponseHandler.reponsesReceived.longValue() );

        Assert.assertNotNull( loadGeneratorResult );

    }

    @Test
    public void one_user_with_ten_request()
        throws Exception
    {

        TestResultHandler testResponseHandler = new TestResultHandler();

        TestRequestListener testRequestListener = new TestRequestListener();

        startServer( new LoadHandler() );

        LoadGenerator loadGenerator = LoadGenerator.Builder.builder() //
            .setHost( "localhost" ) //
            .setPort( connector.getLocalPort() ) //
            .setUsers( 1 ) //
            .setRequestNumber( 10 ) //
            .setResultHandlers( Arrays.asList( testResponseHandler ) ) //
            .setRequestListeners( Arrays.asList( testRequestListener ) ) //
            .setTransport( LoadGenerator.Transport.HTTP ) //
            .build() //
            .start();

        Future<LoadGeneratorResult> result = loadGenerator.run();

        LoadGeneratorResult loadGeneratorResult = result.get( 10, TimeUnit.MINUTES );

        Assert.assertEquals( 10, testResponseHandler.reponsesReceived.longValue() );

        Assert.assertEquals( 10, testResponseHandler.reponsesReceived.longValue() );

        Assert.assertNotNull( loadGeneratorResult );

    }

    //---------------------------------------------------
    // utilities
    //---------------------------------------------------

    static class TestResultHandler
        implements ResultHandler
    {

        AtomicLong reponsesReceived = new AtomicLong();

        @Override
        public void onResponse( Result result )
        {
            reponsesReceived.incrementAndGet();
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


    protected void startServer( Handler handler )
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
        connector = newServerConnector( server );
        server.addConnector( connector );
        server.setHandler( handler );
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
        extends AbstractHandler
    {
        @Override
        public void handle( String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request,
                            HttpServletResponse response )
            throws IOException, ServletException
        {
            String method = request.getMethod().toUpperCase( Locale.ENGLISH );
            switch ( method )
            {
                case "GET":
                {
                    int contentLength = request.getIntHeader( "X-Download" );
                    if ( contentLength > 0 )
                    {
                        response.setHeader( "X-Content", String.valueOf( contentLength ) );
                        response.getOutputStream().write( new byte[contentLength] );
                    }
                    break;
                }
                case "POST":
                {
                    response.setHeader( "X-Content", request.getHeader( "X-Upload" ) );
                    IO.copy( request.getInputStream(), response.getOutputStream() );
                    break;
                }
            }

            if ( Boolean.parseBoolean( request.getHeader( "X-Close" ) ) )
            {
                response.setHeader( "Connection", "close" );
            }

            baseRequest.setHandled( true );
        }
    }

}
