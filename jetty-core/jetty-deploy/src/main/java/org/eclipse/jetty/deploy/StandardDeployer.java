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

import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.Container;
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
public class StandardDeployer extends ContainerLifeCycle implements Deployer
{
    private static final Logger LOG = LoggerFactory.getLogger(StandardDeployer.class);
    private final ContextHandlerCollection _contexts;
    private final boolean _atomicRedeploy;
    private final ListenerAdaptor _listenerAdaptor;

    /**
     * @param contexts The {@link ContextHandlerCollection} to which to deploy {@link ContextHandler}s.
     */
    public StandardDeployer(@Name("contexts") ContextHandlerCollection contexts)
    {
        this(contexts, true);
    }

    /**
     * @param contexts The {@link ContextHandlerCollection} to which to deploy {@link ContextHandler}s.
     * @param atomicRedeploy If {@code true}, the new handler is deployed before undeploying the old handler
     *                       This may result in instances of the same application running at the same time.
     */
    public StandardDeployer(@Name("contexts") ContextHandlerCollection contexts,
                            @Name("atomicRedeploy") boolean atomicRedeploy)
    {
        _contexts = requireNonNull(contexts);
        _atomicRedeploy = atomicRedeploy;
        installBean(_contexts, false);
        _listenerAdaptor = new ListenerAdaptor(_contexts);
        _contexts.addEventListener(_listenerAdaptor);
    }

    public ContextHandlerCollection getContexts()
    {
        return _contexts;
    }

    @Override
    public boolean addEventListener(EventListener listener)
    {
        if  (super.addEventListener(listener))
        {
            if (listener instanceof Deployer.Listener deployerListener)
                _listenerAdaptor._listeners.add(deployerListener);
            return true;
        }
        return false;
    }

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if  (super.removeEventListener(listener))
        {
            if (listener instanceof Deployer.Listener deployerListener)
                _listenerAdaptor._listeners.remove(deployerListener);
            return true;
        }
        return false;
    }

    @Override
    public void deploy(ContextHandler contextHandler)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("deploy: {} {}", this, contextHandler);
        requireNonNull(contextHandler);

        contextHandler.addEventListener(_listenerAdaptor);
        _listenerAdaptor.notify(contextHandler, Deployer.Listener::onCreated);

        try
        {
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

    @Override
    public void redeploy(ContextHandler oldHandler, ContextHandler newContextHandler)
    {
        try
        {
            LOG.info("Redeploy {}", newContextHandler);

            requireNonNull(newContextHandler).setServer(requireNonNull(oldHandler.getServer()));

            newContextHandler.addEventListener(_listenerAdaptor);
            _listenerAdaptor.notify(newContextHandler, Deployer.Listener::onCreated);

            if (_atomicRedeploy && _contexts.isRunning())
                newContextHandler.start();

            Callback.Completable blocker = new Callback.Completable();
            _contexts.redeployHandler(oldHandler, newContextHandler, blocker);
            blocker.get();

            if (!_atomicRedeploy && _contexts.isRunning())
                newContextHandler.start();
            _contexts.manage(newContextHandler);
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} Redeploy failed {}", this, newContextHandler, t);

            ExceptionUtil.ifExceptionThrowUnchecked(t);
        }
        finally
        {
            _listenerAdaptor.notify(oldHandler, Deployer.Listener::onRemoved);
            oldHandler.removeEventListener(_listenerAdaptor);
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
            finally
            {
                _listenerAdaptor.notify(contextHandler, Deployer.Listener::onRemoved);
                contextHandler.removeEventListener(_listenerAdaptor);
            }
        }
    }

    @Override
    public String toString()
    {
        return "%s@%x{contexts=%s,atomicRedeploy=%b}".formatted(TypeUtil.toShortName(getClass()), hashCode(), _contexts, _atomicRedeploy);
    }

    private static class ListenerAdaptor implements LifeCycle.Listener, Container.Listener
    {
        private final ContextHandlerCollection _contexts;
        private final List<Deployer.Listener> _listeners = new CopyOnWriteArrayList<>();

        private ListenerAdaptor(ContextHandlerCollection contexts)
        {
            _contexts = contexts;
        }

        private void notify(ContextHandler contextHandler, BiConsumer<Deployer.Listener, ContextHandler> method)
        {
            for (Deployer.Listener listener : _listeners)
            {
                try
                {
                    method.accept(listener, contextHandler);
                }
                catch (Throwable t)
                {
                    LOG.warn("listener failure {}", listener, t);
                }
            }
        }

        @Override
        public void beanAdded(Container parent, Object child)
        {
            if (child instanceof ContextHandler contextHandler)
            {
                notify(contextHandler, contextHandler.isStarted()
                    ? Deployer.Listener::onDeployed
                    : Deployer.Listener::onDeploying);
            }
        }

        @Override
        public void beanRemoved(Container parent, Object child)
        {
            if (child instanceof ContextHandler contextHandler)
            {
                notify(contextHandler, contextHandler.isStarted()
                    ? Deployer.Listener::onUndeploying
                    : Deployer.Listener::onUndeployed);
            }
        }

        @Override
        public void lifeCycleFailure(LifeCycle bean, Throwable cause)
        {
            if (bean instanceof ContextHandler contextHandler)
                notify(contextHandler, (dl, ch) -> dl.onFailure(ch, cause));
        }

        @Override
        public void lifeCycleStarted(LifeCycle bean)
        {
            if (bean instanceof ContextHandler contextHandler)
            {
                notify(contextHandler, Deployer.Listener::onStarted);
                if (_contexts.contains(contextHandler))
                    notify(contextHandler, Deployer.Listener::onDeployed);
            }
        }

        @Override
        public void lifeCycleStarting(LifeCycle event)
        {
            if (event instanceof ContextHandler contextHandler)
            {
                if (!_contexts.contains(contextHandler))
                    notify(contextHandler, Deployer.Listener::onDeploying);
                notify(contextHandler, Deployer.Listener::onStarting);
            }
        }

        @Override
        public void lifeCycleStopped(LifeCycle event)
        {
            if (event instanceof ContextHandler contextHandler)
            {
                notify(contextHandler, Deployer.Listener::onStopped);
                if (!_contexts.contains(contextHandler))
                    notify(contextHandler, Deployer.Listener::onUndeployed);
            }
        }

        @Override
        public void lifeCycleStopping(LifeCycle event)
        {
            if (event instanceof ContextHandler contextHandler)
            {
                if (_contexts.contains(contextHandler))
                    notify(contextHandler, Deployer.Listener::onUndeploying);
                notify(contextHandler, Deployer.Listener::onStopping);
            }
        }
    }
}
