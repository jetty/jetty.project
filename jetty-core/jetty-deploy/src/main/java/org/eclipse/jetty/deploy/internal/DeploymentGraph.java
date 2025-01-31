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

package org.eclipse.jetty.deploy.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.DeploymentNodeBinding;
import org.eclipse.jetty.deploy.internal.graph.Graph;
import org.eclipse.jetty.deploy.internal.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pre-defined graph representing the lifecycle of a {@link ContextHandler} in the {@link DeploymentManager}.
 * <p>
 * Sets up the default {@link Graph}, and manages the bindings of actions for each node
 * via the {@link DeploymentNodeBinding} implementation.
 * <p>
 * <img alt="context-handler lifecycle graph" src="doc-files/ContextHandlerLifeCycle.png">
 */
public class DeploymentGraph extends Graph
{
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentGraph.class);

    private static final String ALL_NODES = "*";

    // Well known existing lifecycle Nodes
    public static final String UNDEPLOYED = "undeployed";
    public static final String DEPLOYING = "deploying";
    public static final String DEPLOYED = "deployed";
    public static final String STARTING = "starting";
    public static final String STARTED = "started";
    public static final String STOPPING = "stopping";
    public static final String UNDEPLOYING = "undeploying";
    public static final String FAILED = "failed";

    private Map<String, List<DeploymentNodeBinding>> lifecyclebindings = new HashMap<String, List<DeploymentNodeBinding>>();

    public DeploymentGraph()
    {
        // Define Default Graph

        // undeployed -> deployed
        addEdge(UNDEPLOYED, DEPLOYING);
        addEdge(DEPLOYING, DEPLOYED);

        // deployed -> started
        addEdge(DEPLOYED, STARTING);
        addEdge(STARTING, STARTED);

        // started -> deployed
        addEdge(STARTED, STOPPING);
        addEdge(STOPPING, DEPLOYED);

        // deployed -> undeployed
        addEdge(DEPLOYED, UNDEPLOYING);
        addEdge(UNDEPLOYING, UNDEPLOYED);

        // failed (unconnected)
        addNode(new Node(FAILED));
    }

    public void addBinding(DeploymentNodeBinding binding)
    {
        for (String nodeName : binding.getBindingTargets())
        {
            List<DeploymentNodeBinding> bindings = lifecyclebindings.get(nodeName);
            if (bindings == null)
            {
                bindings = new ArrayList<>();
            }
            bindings.add(binding);

            lifecyclebindings.put(nodeName, bindings);
        }
    }

    public void removeBinding(DeploymentNodeBinding binding)
    {
        for (String nodeName : binding.getBindingTargets())
        {
            List<DeploymentNodeBinding> bindings = lifecyclebindings.get(nodeName);
            if (bindings != null)
                bindings.remove(binding);
        }
    }

    /**
     * Get all {@link Node} bound objects.
     *
     * @return Set of Object(s) for all lifecycle bindings. never null.
     */
    public Set<DeploymentNodeBinding> getBindings()
    {
        Set<DeploymentNodeBinding> boundset = new HashSet<>();

        for (List<DeploymentNodeBinding> bindings : lifecyclebindings.values())
        {
            boundset.addAll(bindings);
        }

        return boundset;
    }

    /**
     * Get all objects bound to a specific {@link Node}
     *
     * @param node the deployment graph node
     * @return Set of Object(s) for specific lifecycle bindings. never null.
     */
    public Set<DeploymentNodeBinding> getBindings(Node node)
    {
        return getBindings(node.getName());
    }

    /**
     * Get all objects bound to a specific {@link Node}
     *
     * @param nodeName the node name
     * @return Set of Object(s) for specific lifecycle bindings. never null.
     */
    public Set<DeploymentNodeBinding> getBindings(String nodeName)
    {
        Set<DeploymentNodeBinding> boundset = new HashSet<DeploymentNodeBinding>();

        // Specific node binding
        List<DeploymentNodeBinding> bindings = lifecyclebindings.get(nodeName);
        if (bindings != null)
        {
            boundset.addAll(bindings);
        }

        // Special 'all nodes' binding
        bindings = lifecyclebindings.get(ALL_NODES);
        if (bindings != null)
        {
            boundset.addAll(bindings);
        }

        return boundset;
    }

    public void runBindings(Node node, ContextHandler contextHandler, DeploymentManager deploymentManager) throws Throwable
    {
        for (DeploymentNodeBinding binding : getBindings(node))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Calling {} for {}", binding.getClass().getName(), contextHandler);
            binding.processBinding(deploymentManager, node.getName(), contextHandler);
        }
    }
}
