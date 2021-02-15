//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.extensions;

import java.io.IOException;
import java.util.zip.Deflater;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.common.extensions.compress.CompressExtension;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public class WebSocketExtensionFactory extends ExtensionFactory implements LifeCycle, Dumpable
{
    private final ContainerLifeCycle containerLifeCycle;
    private final WebSocketContainerScope container;
    private final InflaterPool inflaterPool = new InflaterPool(CompressionPool.INFINITE_CAPACITY, true);
    private final DeflaterPool deflaterPool = new DeflaterPool(CompressionPool.INFINITE_CAPACITY, Deflater.DEFAULT_COMPRESSION, true);

    public WebSocketExtensionFactory(WebSocketContainerScope container)
    {
        containerLifeCycle = new ContainerLifeCycle()
        {
            @Override
            public String toString()
            {
                return String.format("%s@%x{%s}", WebSocketExtensionFactory.class.getSimpleName(), hashCode(), containerLifeCycle.getState());
            }
        };

        this.container = container;
        containerLifeCycle.addBean(inflaterPool);
        containerLifeCycle.addBean(deflaterPool);
    }

    @Override
    public Extension newInstance(ExtensionConfig config)
    {
        if (config == null)
        {
            return null;
        }

        String name = config.getName();
        if (StringUtil.isBlank(name))
        {
            return null;
        }

        Class<? extends Extension> extClass = getExtension(name);
        if (extClass == null)
        {
            return null;
        }

        try
        {
            Extension ext = container.getObjectFactory().createInstance(extClass);
            if (ext instanceof AbstractExtension)
            {
                AbstractExtension aext = (AbstractExtension)ext;
                aext.init(container);
                aext.setConfig(config);
            }
            if (ext instanceof CompressExtension)
            {
                CompressExtension cext = (CompressExtension)ext;
                cext.setInflaterPool(inflaterPool);
                cext.setDeflaterPool(deflaterPool);
            }

            return ext;
        }
        catch (Exception e)
        {
            throw new WebSocketException("Cannot instantiate extension: " + extClass, e);
        }
    }

    /* --- All of the below ugliness due to not being able to break API compatibility with ExtensionFactory --- */

    @Override
    public void start() throws Exception
    {
        containerLifeCycle.start();
    }

    @Override
    public void stop() throws Exception
    {
        containerLifeCycle.stop();
    }

    @Override
    public boolean isRunning()
    {
        return containerLifeCycle.isRunning();
    }

    @Override
    public boolean isStarted()
    {
        return containerLifeCycle.isStarted();
    }

    @Override
    public boolean isStarting()
    {
        return containerLifeCycle.isStarting();
    }

    @Override
    public boolean isStopping()
    {
        return containerLifeCycle.isStopping();
    }

    @Override
    public boolean isStopped()
    {
        return containerLifeCycle.isStopped();
    }

    @Override
    public boolean isFailed()
    {
        return containerLifeCycle.isFailed();
    }

    @Override
    public void addLifeCycleListener(Listener listener)
    {
        containerLifeCycle.addLifeCycleListener(listener);
    }

    @Override
    public void removeLifeCycleListener(Listener listener)
    {
        containerLifeCycle.removeLifeCycleListener(listener);
    }

    @Override
    public String dump()
    {
        return containerLifeCycle.dump();
    }

    @Override
    public String dumpSelf()
    {
        return containerLifeCycle.dumpSelf();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        containerLifeCycle.dump(out, indent);
    }

    @Override
    public String toString()
    {
        return containerLifeCycle.toString();
    }
}
