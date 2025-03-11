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

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedOperation;

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
     * Undeploy and stop a ContextHandler.
     *
     * @param contextHandler the {@link ContextHandler} to undeploy.
     *
     */
    @ManagedOperation(value = "Undeploy the ContextHandler",  impact = "ACTION")
    void undeploy(ContextHandler contextHandler);

    /**
     * A Goal Oriented Deployer that will allow deployment in steps.
     */
    interface GoalOriented extends Deployer
    {
        /**
         * Add a ContextHandler into the graph but perform no actions on it, leaving it in undeployed state.
         *
         * @param contextHandler the ContextHandler to add to the graph.
         */
         void addUndeployed(ContextHandler contextHandler);

        /**
         * Advanced usage, move a ContextHandler through the Deployment graph by name.
         *
         * @param contextHandler the ContextHandler to move
         * @param goalName the goal graph node by name
         */
        void move(ContextHandler contextHandler, String goalName);
    }
}