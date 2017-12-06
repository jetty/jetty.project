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

package org.eclipse.jetty.unixsocket.client;

import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.http.HttpChannelOverHTTP;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.client.http.HttpSenderOverHTTP;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.unixsocket.UnixSocketEndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.util.List;
import java.util.Map;

public class HttpClientTransportOverUnixSockets
    extends HttpClientTransportOverHTTP
{
    private String _unixSocket;

    private static final Logger LOGGER = Log.getLogger( HttpClientTransportOverUnixSockets.class );

    public HttpClientTransportOverUnixSockets( String unixSocket )
    {
        if ( unixSocket == null )
        {
            throw new IllegalArgumentException( "Unix socket file cannot be null" );
        }
        this._unixSocket = unixSocket;
    }

    @Override
    protected void doStart()
        throws Exception
    {
        super.doStart();
    }

    @Override
    protected HttpConnectionOverHTTP newHttpConnection( EndPoint endPoint, HttpDestination destination,
                                                        Promise<Connection> promise )
    {
        return super.newHttpConnection( endPoint, destination, promise );
    }

    @Override
    public void connect( InetSocketAddress address, Map<String, Object> context )
    {
        // no op
    }


    @Override
    public HttpDestination newHttpDestination( Origin origin )
    {
        return new HttpDestinationOverUnixSocket( getHttpClient(), origin );
    }



    class HttpDestinationOverUnixSocket
        extends HttpDestinationOverHTTP
    {

        public HttpDestinationOverUnixSocket( HttpClient client, Origin origin )
        {
            super( client, origin );
        }

        @Override
        protected void send( HttpRequest request, List<Response.ResponseListener> listeners )
        {
            super.send( request, listeners );
        }

        @Override
        public void send()
        {
            if ( getHttpExchanges().isEmpty() )
            {
                return;
            }

            final HttpExchange exchange = getHttpExchanges().poll();

            try
            {
                UnixSocketAddress address =
                    new UnixSocketAddress( HttpClientTransportOverUnixSockets.this._unixSocket );
                UnixSocketChannel channel = UnixSocketChannel.open( address );
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug( "connected to {}", channel.getRemoteSocketAddress() );

                InputStreamReader r = new InputStreamReader( Channels.newInputStream( channel ) );
                HttpConnectionOverUnixSocket httpConnectionOverUnixSocket =
                    new HttpConnectionOverUnixSocket( null, this, null, channel );
                httpConnectionOverUnixSocket.send( exchange );

                CharBuffer result = CharBuffer.allocate( 4096 );
                String response = "";
                int l = 0;
                while ( l >= 0 )
                {
                    if ( l > 0 )
                    {
                        result.flip();
                        response += result.toString();
                    }
                    result.clear();
                    l = r.read( result );
                }
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug( "read from server: {}", response );



                exchange.getResponseListeners().stream().forEach( responseListener -> {
                    if (responseListener instanceof Response.CompleteListener)
                    {
                        ((Response.CompleteListener)responseListener).onComplete( null );
                    }
                } );

            }
            catch ( IOException e )
            {
                // TODO cleanup
                e.printStackTrace();
            }


        }

        @Override
        protected SendFailure send( Connection connection, HttpExchange exchange )
        {
            return null;
        }

    }

    class HttpConnectionOverUnixSocket
        extends HttpConnectionOverHTTP
    {

        private HttpChannelOverUnixSocket httpChannelOverUnixSocket;

        private Delegate delegate;

        public HttpConnectionOverUnixSocket( EndPoint endPoint, HttpDestination destination,
                                             Promise<Connection> promise, UnixSocketChannel channel )
        {
            super( new  UnixSocketEndPoint(channel, null, null, null), destination, promise );
            httpChannelOverUnixSocket = newHttpChannel();
            this.delegate = new Delegate( destination, httpChannelOverUnixSocket );
        }

        @Override
        protected SendFailure send( HttpExchange exchange )
        {
            return delegate.send( exchange );
        }

        @Override
        protected HttpChannelOverUnixSocket newHttpChannel()
        {
            return new HttpChannelOverUnixSocket(this);
        }

        @Override
        public void close()
        {
            //System.out.println( "close" );
        }

        @Override
        public boolean isClosed()
        {
            return false;
        }
    }


    class HttpChannelOverUnixSocket extends HttpChannelOverHTTP
    {
        public HttpChannelOverUnixSocket( HttpConnectionOverHTTP connection )
        {
            super( connection );
        }

        @Override
        protected HttpSenderOverHTTP newHttpSender()
        {
            return new HttpSenderOverHTTP(this);
        }
    }


    class Delegate extends HttpConnection
    {
        private HttpChannelOverUnixSocket httpChannelOverUnixSocket;
        public Delegate( HttpDestination destination, HttpChannelOverUnixSocket httpChannelOverUnixSocket )
        {
            super( destination );
            this.httpChannelOverUnixSocket = httpChannelOverUnixSocket;
        }


        @Override
        protected SendFailure send( HttpExchange exchange )
        {
            HttpRequest httpRequest = exchange.getRequest();
            normalizeRequest( httpRequest );
            return send( httpChannelOverUnixSocket, exchange );
        }

        @Override
        public void close()
        {

        }

        @Override
        public boolean isClosed()
        {
            return false;
        }
    }




}
