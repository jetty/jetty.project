package org.eclipse.jetty.client.http;

import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Scheduler;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.util.List;
import java.util.Map;

public class HttpClientTransportOverUnixSockets
    extends HttpClientTransportOverHTTP
{
    private String _unixSocket;

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
        System.out.println( "HttpClientTransportOverUnixSockets#connect" );
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
                System.out.println("connected to " + channel.getRemoteSocketAddress());
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
                System.out.println( "read from server: " + response );
            }
            catch ( IOException e )
            {
                e.printStackTrace();
            }

//            exchange.getResponseListeners().stream().forEach( responseListener -> {
//                if (responseListener instanceof Response.CompleteListener)
//                {
//                    ((Response.CompleteListener)responseListener).onComplete( null );
//                }
//            } );
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
//        public HttpConnectionOverUnixSocket( HttpDestination destination )
//        {
//            super( destination );
//        }

        private HttpChannelOverUnixSocket httpChannelOverUnixSocket;

        private UnixSocketChannel channel;

        private Delegate delegate;

        public HttpConnectionOverUnixSocket( EndPoint endPoint, HttpDestination destination,
                                             Promise<Connection> promise, UnixSocketChannel channel )
        {
            super( new EndPointOverUnixSocket(null, channel), destination, promise );
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
            System.out.println( "close" );
        }

        @Override
        public boolean isClosed()
        {
            return false;
        }
    }

    class EndPointOverUnixSocket extends AbstractEndPoint
    {

        //private final OutputStream outputStream;
        private final PrintWriter printWriter;


        public EndPointOverUnixSocket( Scheduler scheduler, UnixSocketChannel channel )
        {
            super( scheduler );
            //this.outputStream = Channels.newOutputStream(channel);
            this.printWriter = new PrintWriter( Channels.newOutputStream( channel ) );
        }

        @Override
        public void checkFlush()
            throws IOException
        {
            super.checkFlush();
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            return null;
        }

        @Override
        public int fill( ByteBuffer buffer )
            throws IOException
        {
            return 0;
        }

        @Override
        public boolean flush( ByteBuffer... buffer )
            throws IOException
        {
            for (ByteBuffer byteBuffer : buffer)
            {
                // TODO only debugging must use
                //BufferUtil.writeTo( byteBuffer, outputStream  );
                String content = BufferUtil.toString( byteBuffer );
                //outputStream.write( content.getBytes() );
                printWriter.print( content );
            }
            //outputStream.flush();
            printWriter.flush();
            return true;
        }

        @Override
        public Object getTransport()
        {
            return null;
        }

        @Override
        protected void onIncompleteFlush()
        {
            System.out.println( "onIncompleteFlush" );
        }

        @Override
        protected void needsFillInterest()
            throws IOException
        {
            System.out.println( "needsFillInterest" );
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
            return new HttpSenderOverUnixSocket(this);
        }

        @Override
        public void receive()
        {
            super.receive();
        }

        @Override
        public void send()
        {
            super.send();
        }
    }


    class HttpSenderOverUnixSocket extends HttpSenderOverHTTP
    {
        public HttpSenderOverUnixSocket( HttpChannelOverHTTP channel )
        {
            super( channel );
        }

        @Override
        protected void sendHeaders( HttpExchange exchange, HttpContent content, Callback callback )
        {
            super.sendHeaders( exchange, content, callback );
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
