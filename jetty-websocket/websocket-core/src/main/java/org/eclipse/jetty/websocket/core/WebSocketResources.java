package org.eclipse.jetty.websocket.core;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;

public class WebSocketResources
{
    public static WebSocketResources ensureWebSocketResources(ServletContext servletContext) throws ServletException
    {
        ContextHandler contextHandler = ContextHandler.getContextHandler(servletContext);

        // Ensure a mapping exists
        WebSocketResources resources = contextHandler.getBean(WebSocketResources.class);
        if (resources == null)
        {
            resources = new WebSocketResources();
            contextHandler.addBean(resources);
        }

        return resources;
    }

    public WebSocketResources()
    {
        this(new WebSocketExtensionRegistry(), new DecoratedObjectFactory(), new MappedByteBufferPool());
    }

    public WebSocketResources(WebSocketExtensionRegistry extensionRegistry, DecoratedObjectFactory objectFactory, ByteBufferPool bufferPool)
    {
        this.extensionRegistry = extensionRegistry;
        this.objectFactory = objectFactory;
        this.bufferPool = bufferPool;
    }

    private DecoratedObjectFactory objectFactory;
    private WebSocketExtensionRegistry extensionRegistry;
    private ByteBufferPool bufferPool;


    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }
}