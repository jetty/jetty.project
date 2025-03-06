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

/**
 * Deployer for ContextHandlers
 */
public interface Deployer
{
    /**
     * Add a ContextHandler to the graph and then move it a deployed state.
     *
     * @param contextHandler the ContextHandler to deploy.
     */
    void deploy(ContextHandler contextHandler);

    /**
     * Move a ContextHandler int the graph to an undeployed state, and then remove it from the graph.
     *
     * @param contextHandler the ContextHandler to undeploy.
     */
    void undeploy(ContextHandler contextHandler);

    interface GoalOriented extends Deployer
    {
        /**
         * Add a ContextHandler int the graph but perform no actions on it, leaving it in undeployed state.
         *
         * @param contextHandler the ContextHandler to add to the graph.
         */
        default void addUndeployed(ContextHandler contextHandler)
        {
        }

        /**
         * Advanced usage, move a ContextHandler through the DeploymentManager graph by name.
         *
         * @param contextHandler the ContextHandler to move
         * @param goalName the goal graph node by name
         */
        default void move(ContextHandler contextHandler, String goalName)
        {
        }
    }
}