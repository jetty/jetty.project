package org.eclipse.jetty.load.generator;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public class LoadGenerator
{

    private int users;

    private long payloadSize;

    private long responseSize;

    private AtomicLong requestNumber;

    private int selectors = 1;

    private String scheme;

    private String host;

    private int port;

    private String path;

    private String method;

    private Transport transport;

    private HttpClientTransport httpClientTransport;

    private SslContextFactory sslContextFactory;

    private CopyOnWriteArrayList<ResultHandler> resultHandlers;

    private List<Request.Listener> requestListeners;

    private ExecutorService executorService;

    private CopyOnWriteArrayList<HttpClient> clients = new CopyOnWriteArrayList<>();

    protected enum Transport
    {
        HTTP,
        HTTPS,
        H2C,
        H2,
        FCGI
    }

    LoadGenerator( int users, long payloadSize, long requestNumber, String host, int port, String path, String method )
    {
        this.users = users;
        this.payloadSize = payloadSize;
        this.requestNumber = new AtomicLong( requestNumber );
        this.host = host;
        this.port = port;
        this.path = path;
        this.method = method;
    }

    //--------------------------------------------------------------
    //  getters
    //--------------------------------------------------------------

    public int getUsers()
    {
        return users;
    }

    public long getPayloadSize()
    {
        return payloadSize;
    }

    public AtomicLong getRequestNumber()
    {
        return requestNumber;
    }

    public String getHost()
    {
        return host;
    }

    public int getPort()
    {
        return port;
    }

    public String getPath()
    {
        return path;
    }

    public String getMethod()
    {
        return method;
    }

    public Transport getTransport()
    {
        return transport;
    }

    public List<ResultHandler> getResultHandlers()
    {
        return resultHandlers;
    }

    public long getResponseSize()
    {
        return responseSize;
    }

    public HttpClientTransport getHttpClientTransport()
    {
        return httpClientTransport;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    public int getSelectors()
    {
        return selectors;
    }

    public List<Request.Listener> getRequestListeners()
    {
        return requestListeners;
    }

    //--------------------------------------------------------------
    //  component implementation
    //--------------------------------------------------------------

    /**
     * start the generator lifecycle (this doesn't send any requests but just start few internal components)
     */
    public LoadGenerator start()
    {
        this.executorService = Executors.newWorkStealingPool( this.getUsers() );
        return this;
    }

    /**
     * stop (clear resources) the generator lifecycle
     */
    public LoadGenerator stop()
    {
        try
        {
            for ( HttpClient httpClient : this.clients )
            {
                httpClient.stop();
            }
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e.getMessage(), e.getCause() );
        }
        return this;
    }

    /**
     * run the defined load (users / request numbers)
     */
    public Future<LoadGeneratorResult> run()
        throws Exception
    {

        final String url = this.scheme + "://" + this.host + ":" + this.port + ( this.path == null ? "" : this.path );

        return Executors.newFixedThreadPool( 1 ).submit( () -> //
        {

                LoadGeneratorResult loadGeneratorResult = new LoadGeneratorResult();

                HttpClientTransport httpClientTransport = LoadGenerator.this.httpClientTransport != null ? //
                    LoadGenerator.this.httpClientTransport : provideClientTransport( LoadGenerator.this.transport );

                HttpClient httpClient = newHttpClient( httpClientTransport, sslContextFactory );

                LoadGenerator.this.clients.add( httpClient );

                httpClient.getRequestListeners().addAll( LoadGenerator.this.getRequestListeners() );

                // TODO calculate request number per user
                LoadGeneratorRunner loadGeneratorRunner =
                    new LoadGeneratorRunner( httpClient, requestNumber.get(), LoadGenerator.this, url,
                                             loadGeneratorResult );


                executorService.submit( loadGeneratorRunner );

                while ( requestNumber.get() > 0 )
                {
                    // wait until all requests send
                    Thread.sleep( 1 );
                }

                return loadGeneratorResult;

        } );
    }


    protected HttpClient newHttpClient( HttpClientTransport transport, SslContextFactory sslContextFactory )
        throws Exception
    {
        HttpClient httpClient = new HttpClient( transport, sslContextFactory );
        // FIXME weird circularity
        transport.setHttpClient( httpClient );
        httpClient.start();
        return httpClient;
    }

    protected HttpClientTransport provideClientTransport( Transport transport )
    {
        switch ( transport )
        {
            case HTTP:
            case HTTPS:
            {
                return new HttpClientTransportOverHTTP( selectors );
            }
            case H2C:
            case H2:
            {
                HTTP2Client http2Client = newHTTP2Client();
                return new HttpClientTransportOverHTTP2( http2Client );
            }
            /*
            TODO
            case FCGI:
            {
                return new HttpClientTransportOverFCGI(1, false, "");
            }
            */
            default:
            {
                throw new IllegalArgumentException();
            }
        }
    }


    protected HTTP2Client newHTTP2Client()
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.setSelectors( selectors );
        return http2Client;
    }

    //--------------------------------------------------------------
    //  Builder
    //--------------------------------------------------------------

    public static class Builder
    {

        private int users;

        private long payloadSize;

        private long responseSize;

        private long requestNumber;

        private String scheme = "http";

        private String host;

        private int port;

        private String path;

        private String method;

        private Transport transport;

        private List<ResultHandler> resultHandlers;

        private HttpClientTransport httpClientTransport;

        private SslContextFactory sslContextFactory;

        private int selectors = 1;

        private List<Request.Listener> requestListeners;

        public static Builder builder()
        {
            return new Builder();
        }

        public Builder setUsers( int users )
        {
            this.users = users;
            return this;
        }

        public Builder setPayloadSize( long payloadSize )
        {
            this.payloadSize = payloadSize;
            return this;
        }

        public Builder setRequestNumber( long requestNumber )
        {
            this.requestNumber = requestNumber;
            return this;
        }

        public Builder setHost( String host )
        {
            this.host = host;
            return this;
        }

        public Builder setPort( int port )
        {
            this.port = port;
            return this;
        }

        public Builder setPath( String path )
        {
            this.path = path;
            return this;
        }

        public Builder setMethod( String method )
        {
            this.method = method;
            return this;
        }

        public Builder setTransport( Transport transport )
        {
            this.transport = transport;
            return this;
        }

        public Builder setResultHandlers( List<ResultHandler> resultHandlers )
        {
            this.resultHandlers = resultHandlers;
            return this;
        }

        public Builder setResponseSize( long responseSize )
        {
            this.responseSize = responseSize;
            return this;
        }

        public Builder setHttpClientTransport( HttpClientTransport httpClientTransport )
        {
            this.httpClientTransport = httpClientTransport;
            return this;
        }

        public Builder setSslContextFactory( SslContextFactory sslContextFactory )
        {
            this.sslContextFactory = sslContextFactory;
            return this;
        }

        public Builder setSelectors( int selectors )
        {
            this.selectors = selectors;
            return this;
        }

        public Builder setScheme( String scheme )
        {
            this.scheme = scheme;
            return this;
        }

        public Builder setRequestListeners( List<Request.Listener> requestListeners )
        {
            this.requestListeners = requestListeners;
            return this;
        }

        public LoadGenerator build()
        {
            // FIXME control more data input
            LoadGenerator loadGenerator =
                new LoadGenerator( users, payloadSize, requestNumber, host, port, path, method );
            loadGenerator.transport = this.transport;
            loadGenerator.resultHandlers = this.resultHandlers == null ? new CopyOnWriteArrayList<>() //
                : new CopyOnWriteArrayList<>( this.resultHandlers );
            loadGenerator.requestListeners = this.requestListeners == null ? new ArrayList<>() // //
                : this.requestListeners;
            loadGenerator.responseSize = responseSize;
            loadGenerator.httpClientTransport = httpClientTransport;
            loadGenerator.sslContextFactory = sslContextFactory;
            loadGenerator.selectors = selectors;
            loadGenerator.scheme = scheme;
            return loadGenerator;
        }

    }

}
