package org.eclipse.jetty.spdy.nio;

import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;

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
        CompressionFactory compressionFactory = newCompressionFactory();
        Parser parser = newParser(compressionFactory.newDecompressor());
        Generator generator = newGenerator(compressionFactory.newCompressor());

        AsyncConnection result;
        ISession.Controller controller;
        if (sslContextFactory != null)
        {
            SSLEngine engine = newSSLEngine(sslContextFactory, channel);
            SslConnection sslConnection = new SslConnection(engine, endPoint);
            endPoint.setConnection(sslConnection);
            AsyncEndPoint sslEndPoint = sslConnection.getSslEndPoint();
            AsyncSPDYConnection connection = new AsyncSPDYConnection(sslEndPoint, parser);
            sslEndPoint.setConnection(connection);
            result = sslConnection;
            controller = connection;
        }
        else
        {
            AsyncSPDYConnection connection = new AsyncSPDYConnection(endPoint, parser);
            endPoint.setConnection(connection);
            result = connection;
            controller = connection;
        }

        Session session = newSession(controller, listener, parser, generator);

        // TODO: this is called in the selector thread, which is not optimal
        // NPE guard to support tests
        if (listener != null)
            listener.onConnect(session);

        return result;
    }

    protected SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSslEngine(peerHost, peerPort);
        engine.setUseClientMode(false);
        return engine;
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
