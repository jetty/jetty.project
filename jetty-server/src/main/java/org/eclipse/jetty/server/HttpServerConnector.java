package org.eclipse.jetty.server;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HttpServerConnector extends SelectChannelConnector
{
    public HttpServerConnector(Server server)
    {
        this(server, null);
    }

    public HttpServerConnector(Server server, SslContextFactory sslContextFactory)
    {
        this(server, null, null, null, sslContextFactory, 0, 0);
    }

    public HttpServerConnector(@Name("server") Server server, @Name("executor") Executor executor, @Name("scheduler") ScheduledExecutorService scheduler, @Name("bufferPool") ByteBufferPool pool, @Name("sslContextFactory") SslContextFactory sslContextFactory, @Name("acceptors") int acceptors, @Name("selectors") int selectors)
    {
        super(server, executor, scheduler, pool, sslContextFactory, acceptors, selectors);
        setDefaultConnectionFactory(new HttpServerConnectionFactory(this));
    }
}
