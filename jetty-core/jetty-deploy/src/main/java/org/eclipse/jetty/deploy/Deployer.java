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

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * Deployer for ContextHandlers
 */
public interface Deployer
{
    /**
     * Deploy a ContextHandler to the server, and start it if appropriate.
     *
     * @param contextHandler the {@link ContextHandler} to deploy.
     *
     */
    @ManagedOperation(value = "Deploy the ContextHandler",  impact = "ACTION")
    void deploy(ContextHandler contextHandler);

    /**
     * Redeploy a ContextHandler to the server, and start it if appropriate.
     * If possible, this is done atomically without any period of no handler being deployed.
     * However, it maybe implemented as an {@link #undeploy(ContextHandler)} followed
     * by a {@link #deploy(ContextHandler)}
     *
     * @param oldContextHandler the {@link ContextHandler} to undeploy.
     * @param newContextHandler the {@link ContextHandler} to deploy.
     *
     */
    @ManagedOperation(value = "Redeploy the ContextHandler",  impact = "ACTION")
    default void redeploy(ContextHandler oldContextHandler, ContextHandler newContextHandler)
    {
        undeploy(oldContextHandler);
        deploy(newContextHandler);
    }

    /**
     * Undeploy and stop a ContextHandler.
     *
     * @param contextHandler the {@link ContextHandler} to undeploy.
     *
     */
    @ManagedOperation(value = "Undeploy the ContextHandler",  impact = "ACTION")
    void undeploy(ContextHandler contextHandler);

    interface Listener extends EventListener
    {
        /**
         * Called when a {@link ContextHandler} is first seen by the {@link Deployer}.
         * @param contextHandler The {@link ContextHandler} seen.
         */
        default void onCreated(ContextHandler contextHandler)
        {}

        /**
         * Event called when a {@link ContextHandler} is added to the {@link org.eclipse.jetty.server.handler.ContextHandlerCollection}.
         * A {@code ContextHandler} is deployed when it is both added and {@link #onStarted(ContextHandler) started}.
         * @param contextHandler The {@link ContextHandler} added.
         */
        default void onDeploying(ContextHandler contextHandler)
        {}

        /**
         * Event called when a {@link ContextHandler} is {@link LifeCycle#isStarting() starting}.
         * @param contextHandler The {@link ContextHandler} starting.
         */
        default void onStarting(ContextHandler contextHandler)
        {}

        /**
         * Event called when a {@link ContextHandler} is {@link LifeCycle#isStarted() started}.
         * A {@code contextHandler} is deployed when it is both {@link #onDeploying(ContextHandler) added} and started.
         * @param contextHandler The {@link ContextHandler} added.
         */
        default void onStarted(ContextHandler contextHandler)
        {}

        /**
         * Event called when a {@link ContextHandler} is both {@link LifeCycle#isStarted() started} and
         * added to the {@link org.eclipse.jetty.server.handler.ContextHandlerCollection}.
         * @param contextHandler The {@link ContextHandler} deployed.
         */
        default void onDeployed(ContextHandler contextHandler)
        {}

        /**
         * Event called when a {@link ContextHandler} is removed from the {@link org.eclipse.jetty.server.handler.ContextHandlerCollection}.
         * A {@code ContextHandler} is undeployed when it is both removed and {@link #onStopped(ContextHandler) stopped}.
         * @param contextHandler The {@link ContextHandler} undeploying.
         */
        default void onUndeploying(ContextHandler contextHandler)
        {}

        /**
         * Event called when a {@link ContextHandler} is {@link LifeCycle#isStopping() stopping}.
         * @param contextHandler The {@link ContextHandler} stopping.
         */
        default void onStopping(ContextHandler contextHandler)
        {}

        /**
         * Event called when a {@link ContextHandler} is {@link LifeCycle#isStopped() stopped}.
         * @param contextHandler The {@link ContextHandler} stopped.
         */
        default void onStopped(ContextHandler contextHandler)
        {}

        /**
         * Event called when a {@link ContextHandler} is both {@link #onStopped(ContextHandler) stopped} and
         * removed from the {@link org.eclipse.jetty.server.handler.ContextHandlerCollection}.
         * @param contextHandler The {@link ContextHandler} undeployed.
         */
        default void onUndeployed(ContextHandler contextHandler)
        {}

        /**
         * Event called when a {@link ContextHandler} is {@link LifeCycle#isFailed() failed}.
         * @param contextHandler The {@link ContextHandler} that failed.
         * @param cause The cause of the failure.
         */
        default void onFailure(ContextHandler contextHandler, Throwable cause)
        {}

        /**
         * Called when a {@link ContextHandler} is last seen by the {@link Deployer}.
         * @param contextHandler The {@link ContextHandler} seen.
         */
        default void onRemoved(ContextHandler contextHandler)
        {}

    }
}