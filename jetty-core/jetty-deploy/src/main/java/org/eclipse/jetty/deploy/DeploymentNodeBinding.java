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

import org.eclipse.jetty.deploy.internal.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * A binding for a node on the deployment graph, to perform actions when
 * a ContextHandler reaches that point on the graph.
 */
public interface DeploymentNodeBinding
{
    /**
     * Get a list of target nodes that this implementation should bind to.
     *
     * @return the array of String node names to bind to. (use <code>"*"</code> to bind to all known node names)
     */
    String[] getBindingTargets();

    /**
     * Event called to perform an action when targeted node on the Deployment graph
     * has a ContextHandler move through it.
     *
     * @param node the node being processed
     * @param contextHandler the contextHandler being processed
     * @throws Exception if any problem severe enough to halt the ContextHandler  processing
     */
    void processBinding(DeploymentManager deploymentManager, Node node, ContextHandler contextHandler) throws Exception;
}
