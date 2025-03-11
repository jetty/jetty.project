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

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;

/**
 * A Direct {@link Deployer} implementation.
 * This {@code Deployer} will {@link ContextHandlerCollection#deployHandler(Handler, Callback) deploy}
 * a {@link ContextHandler} directly to the {@link ContextHandlerCollection} and {@link LifeCycle#start() start} it if
 * appropriate.
 */
public class DirectDeployer extends ContainerLifeCycle implements Deployer
{
    private static final Logger LOG = LoggerFactory.getLogger(DirectDeployer.class);
    private final ContextHandlerCollection _contexts;
    private final boolean _startBeforeRedeploy;

    /**
     * @param contexts The {@link ContextHandlerCollection} to which to deploy {@link ContextHandler}s.
     */
    public DirectDeployer(@Name("contexts") ContextHandlerCollection contexts)
    {
        this(contexts, false);
    }

    /**
     * @param contexts The {@link ContextHandlerCollection} to which to deploy {@link ContextHandler}s.
     * @param startBeforeRedeploy If {@code true}, the new handler is started before a redeploy.
     */
    public DirectDeployer(@Name("contexts") ContextHandlerCollection contexts,
                          @Name("startBeforeRedeploy") boolean startBeforeRedeploy)
    {
        _contexts = requireNonNull(contexts);
        _startBeforeRedeploy = startBeforeRedeploy;
        installBean(_contexts, false);
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
            if (LOG.isDebugEnabled())
                LOG.debug("deploy: {} {}", this, contextHandler);
            requireNonNull(contextHandler);
            Callback.Completable blocker = new Callback.Completable();
            _contexts.deployHandler(contextHandler, blocker);
            blocker.get();

            if (_contexts.isRunning())
                contextHandler.start();
            _contexts.manage(contextHandler);
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} Deploy failed {}", this, contextHandler, t);

            ExceptionUtil.ifExceptionThrowUnchecked(t);
        }
    }

    // TODO this is a speculative new API to better support hot redeploy without interruption of service.
    public void redeploy(ContextHandler oldHandler, ContextHandler newHandler)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("deploy: {} {}", this, newHandler);

            requireNonNull(newHandler).setServer(requireNonNull(oldHandler.getServer()));
            if (_startBeforeRedeploy && _contexts.isRunning())
                newHandler.start();

            Callback.Completable blocker = new Callback.Completable();
            _contexts.redeployHandler(oldHandler, newHandler, blocker);
            blocker.get();

            if (!_startBeforeRedeploy && _contexts.isRunning())
                newHandler.start();
            _contexts.manage(newHandler);
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} Redeploy failed {}", this, newHandler, t);

            ExceptionUtil.ifExceptionThrowUnchecked(t);
        }
    }

    @Override
    public void undeploy(ContextHandler contextHandler)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("undeploy: {} {}", this, contextHandler);

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
    public String toString()
    {
        return "%s@%x{contexts=%s,sbrd=%b}".formatted(TypeUtil.toShortName(getClass()), hashCode(), _contexts, _startBeforeRedeploy);
    }
}
