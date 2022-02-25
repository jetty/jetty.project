//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.deploy.graph.Graph;
import org.eclipse.jetty.deploy.graph.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lifecycle of an App in the {@link DeploymentManager}.
 * <p>
 * Setups a the default {@link Graph}, and manages the bindings to the life cycle via the {@link AppLifeCycle.Binding}
 * annotation.
 * <p>
 * <img alt="app lifecycle graph" src="doc-files/AppLifeCycle.png">
 */
public class AppLifeCycle extends Graph
{
    private static final Logger LOG = LoggerFactory.getLogger(AppLifeCycle.class);

    private static final String ALL_NODES = "*";

    public static interface Binding
    {
        /**
         * Get a list of targets that this implementation should bind to.
         *
         * @return the array of String node names to bind to. (use <code>"*"</code> to bind to all known node names)
         */
        String[] getBindingTargets();

        /**
         * Event called to process a {@link AppLifeCycle} binding.
         *
         * @param node the node being processed
         * @param app the app being processed
         * @throws Exception if any problem severe enough to halt the AppLifeCycle processing
         */
        void processBinding(Node node, App app) throws Exception;
    }

    // Well known existing lifecycle Nodes
    public static final String UNDEPLOYED = "undeployed";
    public static final String DEPLOYING = "deploying";
    public static final String DEPLOYED = "deployed";
    public static final String STARTING = "starting";
    public static final String STARTED = "started";
    public static final String STOPPING = "stopping";
    public static final String UNDEPLOYING = "undeploying";
    public static final String FAILED = "failed";

    private Map<String, List<Binding>> lifecyclebindings = new HashMap<String, List<Binding>>();

    public AppLifeCycle()
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

    public void addBinding(AppLifeCycle.Binding binding)
    {
        for (String nodeName : binding.getBindingTargets())
        {
            List<Binding> bindings = lifecyclebindings.get(nodeName);
            if (bindings == null)
            {
                bindings = new ArrayList<Binding>();
            }
            bindings.add(binding);

            lifecyclebindings.put(nodeName, bindings);
        }
    }

    public void removeBinding(AppLifeCycle.Binding binding)
    {
        for (String nodeName : binding.getBindingTargets())
        {
            List<Binding> bindings = lifecyclebindings.get(nodeName);
            if (bindings != null)
                bindings.remove(binding);
        }
    }

    /**
     * Get all {@link Node} bound objects.
     *
     * @return Set of Object(s) for all lifecycle bindings. never null.
     */
    public Set<AppLifeCycle.Binding> getBindings()
    {
        Set<Binding> boundset = new HashSet<Binding>();

        for (List<Binding> bindings : lifecyclebindings.values())
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
    public Set<AppLifeCycle.Binding> getBindings(Node node)
    {
        return getBindings(node.getName());
    }

    /**
     * Get all objects bound to a specific {@link Node}
     *
     * @param nodeName the node name
     * @return Set of Object(s) for specific lifecycle bindings. never null.
     */
    public Set<AppLifeCycle.Binding> getBindings(String nodeName)
    {
        Set<Binding> boundset = new HashSet<Binding>();

        // Specific node binding
        List<Binding> bindings = lifecyclebindings.get(nodeName);
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

    public void runBindings(Node node, App app, DeploymentManager deploymentManager) throws Throwable
    {
        for (Binding binding : getBindings(node))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Calling {} for {}", binding.getClass().getName(), app);
            binding.processBinding(node, app);
        }
    }
}
