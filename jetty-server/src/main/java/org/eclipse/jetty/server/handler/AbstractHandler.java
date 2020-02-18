//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * AbstractHandler.
 * <p>A convenience implementation of {@link Handler} that uses the
 * {@link ContainerLifeCycle} to provide:<ul>
 * <li>start/stop behavior
 * <li>a bean container
 * <li>basic {@link Dumpable} support
 * <li>a {@link Server} reference
 * <li>optional error dispatch handling
 * </ul>
 */
@ManagedObject("Jetty Handler")
public abstract class AbstractHandler extends ContainerLifeCycle implements Handler
{
    private static final Logger LOG = Log.getLogger(AbstractHandler.class);

    private Server _server;

    public AbstractHandler()
    {
    }

    @Override
    public abstract boolean handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    /*
     * @see org.eclipse.thread.LifeCycle#start()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("starting {}", this);
        if (_server == null)
            LOG.warn("No Server set for {}", this);
        super.doStart();
    }

    /*
     * @see org.eclipse.thread.LifeCycle#stop()
     */
    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stopping {}", this);
        super.doStop();
    }

    @Override
    public void setServer(Server server)
    {
        if (_server == server)
            return;
        if (isStarted())
            throw new IllegalStateException(getState());
        _server = server;
    }

    @Override
    public Server getServer()
    {
        return _server;
    }

    @Override
    public void destroy()
    {
        if (!isStopped())
            throw new IllegalStateException("!STOPPED");
        super.destroy();
    }
}
