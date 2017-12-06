package org.eclipse.jetty.client;

import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.unixsocket.UnixSocketConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;

public class UnixSocketTest
{

    public static void main( String... args )
        throws Exception
    {
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

//
//            UnixSocketAddress address = new UnixSocketAddress( sockFile.toFile() );
//            UnixSocketChannel channel = UnixSocketChannel.open( address);
//            System.out.println("connected to " + channel.getRemoteSocketAddress());
//            PrintWriter w = new PrintWriter( Channels.newOutputStream( channel));
//            InputStreamReader r = new InputStreamReader( Channels.newInputStream( channel));
//            w.print(data);
//            w.flush();
//
//            CharBuffer result = CharBuffer.allocate( 4096);
//            String total="";
//            int l = 0;
//            while (l>=0)
//            {
//                if (l>0)
//                {
//                    result.flip();
//                    total += result.toString();
//                }
//                result.clear();
//                l = r.read(result);
//            }
//            System.out.println("read from server: " + total);


            httpClient = new HttpClient( new HttpClientTransportOverUnixSockets( sockFile.toString() ), null );
            httpClient.start();

            ContentResponse contentResponse = httpClient
                .newRequest( "http://localhost" )
                .send();

            System.out.println( "response from server:" + contentResponse.getContentAsString() );

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
