package org.eclipse.jetty.spdy.nio;

import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SslConnection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.ISession;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.StandardSession;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SPDYServerConnector extends SelectChannelConnector
{
    private final ServerSessionFrameListener listener;
    private final SslContextFactory sslContextFactory;

    public SPDYServerConnector(ServerSessionFrameListener listener)
    {
        this(listener, null);
    }

    public SPDYServerConnector(ServerSessionFrameListener listener, SslContextFactory sslContextFactory)
    {
        this.listener = listener;
        this.sslContextFactory = sslContextFactory;
        if (sslContextFactory != null)
            addBean(sslContextFactory);
    }

    @Override
    protected AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endPoint)
    {
        if (sslContextFactory != null)
        {
            SSLEngine engine = newSSLEngine(sslContextFactory, channel);
            SslConnection sslConnection = new SslConnection(engine, endPoint);
            endPoint.setConnection(sslConnection);
            endPoint = sslConnection.getSslEndPoint();
        }

        CompressionFactory compressionFactory = newCompressionFactory();
        Parser parser = newParser(compressionFactory.newDecompressor());
        Generator generator = newGenerator(compressionFactory.newCompressor());

        AsyncSPDYConnection connection = new AsyncSPDYConnection(endPoint, parser);
        endPoint.setConnection(connection);

        Session session = newSession(connection, listener, parser, generator);

        // TODO: this is called in the selector thread, which is not good
        // NPE guard to support tests
        if (listener != null)
            listener.onConnect(session);

        return connection;
    }

    protected SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        try
        {
            String peerHost = channel.socket().getInetAddress().getHostAddress();
            int peerPort = channel.socket().getPort();
            SSLEngine engine = sslContextFactory.newSslEngine(peerHost, peerPort);
            engine.setUseClientMode(false);
            engine.beginHandshake();
            return engine;
        }
        catch (SSLException x)
        {
            throw new RuntimeException(x);
        }
    }

    protected CompressionFactory newCompressionFactory()
    {
        return new StandardCompressionFactory();
    }

    protected Parser newParser(CompressionFactory.Decompressor decompressor)
    {
        return new Parser(decompressor);
    }

    protected Generator newGenerator(CompressionFactory.Compressor compressor)
    {
        return new Generator(compressor);
    }

    protected Session newSession(ISession.Controller controller, Session.FrameListener listener, Parser parser, Generator generator)
    {
        StandardSession session = new StandardSession(controller, 2, listener, generator);
        parser.addListener(session);
        return session;
    }
}
