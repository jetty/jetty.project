package org.eclipse.jetty.spdy;

import java.nio.channels.SocketChannel;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.nio.AsyncSPDYConnection;
import org.eclipse.jetty.spdy.parser.Parser;

public class SPDYServerConnector extends SelectChannelConnector
{
    private final ServerSessionFrameListener listener;

    public SPDYServerConnector(ServerSessionFrameListener listener)
    {
        this.listener = listener;
    }

    @Override
    protected AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endPoint)
    {
        CompressionFactory compressionFactory = newCompressionFactory();
        Parser parser = newParser(compressionFactory.newDecompressor());
        Generator generator = newGenerator(compressionFactory.newCompressor());

        AsyncSPDYConnection connection = new AsyncSPDYConnection(endPoint, parser);
        Session session = newSession(connection, listener, parser, generator);

        listener.onConnect(session);

        return connection;
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
