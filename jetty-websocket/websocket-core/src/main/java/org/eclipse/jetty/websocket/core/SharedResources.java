package org.eclipse.jetty.websocket.core;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;

public class SharedResources
{
    public static SharedResources ensureSharedResources(ServletContext servletContext) throws ServletException
    {
        ContextHandler contextHandler = ContextHandler.getContextHandler(servletContext);

        // Ensure a mapping exists
        SharedResources resources = contextHandler.getBean(SharedResources.class);
        if (resources == null)
        {
            resources = new SharedResources();
            resources.setContextClassLoader(servletContext.getClassLoader());
            contextHandler.addBean(resources);
        }

        return resources;
    }

    public SharedResources()
    {
        this(new WebSocketExtensionRegistry(), new DecoratedObjectFactory(), new MappedByteBufferPool());
    }

    public SharedResources(WebSocketExtensionRegistry extensionRegistry, DecoratedObjectFactory objectFactory, ByteBufferPool bufferPool)
    {
        this.extensionRegistry = extensionRegistry;
        this.objectFactory = objectFactory;
        this.bufferPool = bufferPool;
    }

    private DecoratedObjectFactory objectFactory;
    private ClassLoader contextClassLoader;
    private WebSocketExtensionRegistry extensionRegistry;
    private ByteBufferPool bufferPool;


    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public void setContextClassLoader(ClassLoader classLoader)
    {
        contextClassLoader = classLoader;
    }

    public ClassLoader getContextClassloader()
    {
        return contextClassLoader;
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