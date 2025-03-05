//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.deploy;

import java.util.Objects;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleDeployer extends ContainerLifeCycle implements Deployer
{
    private static final Logger LOG = LoggerFactory.getLogger(SimpleDeployer.class);
    private final ContextHandlerCollection _contexts;

    public SimpleDeployer(@Name("contexts") ContextHandlerCollection contexts)
    {
        _contexts = contexts;
    }

    public ContextHandlerCollection getContexts()
    {
        return _contexts;
    }

    @Override
    public void deploy(ContextHandler contextHandler)
    {
        try
        {
            Objects.requireNonNull(_contexts);
            Objects.requireNonNull(contextHandler);
            Callback.Completable blocker = new Callback.Completable();
            _contexts.deployHandler(contextHandler, blocker);
            blocker.get();

            System.err.println("Deploy " + contextHandler + " to " + _contexts);
            new Throwable(Thread.currentThread().getName()).printStackTrace();
            if (_contexts.isRunning())
                contextHandler.start();
            _contexts.manage(contextHandler);
        }
        catch (Throwable t)
        {
            if (isStarting())
                ExceptionUtil.ifExceptionThrowUnchecked(t);
            else
                LOG.warn("Deploy failed {}", contextHandler, t);
        }
    }

    @Override
    public void undeploy(ContextHandler contextHandler)
    {
        if (_contexts.getHandlers().contains(contextHandler))
        {
            try
            {
                contextHandler.stop();
                _contexts.removeHandler(contextHandler);
            }
            catch (Throwable t)
            {
                throw new RuntimeException(t);
            }
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        Objects.requireNonNull(_contexts);
        super.doStart();
    }
}
