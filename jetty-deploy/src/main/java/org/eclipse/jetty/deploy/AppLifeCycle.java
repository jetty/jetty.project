// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.deploy.graph.Graph;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.util.log.Log;

/**
 * The lifecycle of an App in the {@link DeploymentManager}.
 * 
 * Setups a the default {@link Graph}, and manages the bindings to the life cycle via the {@link DeployLifeCycleBinding}
 * annotation.
 * <p>
 * <img src="doc-files/AppLifeCycle.png">
 */
public class AppLifeCycle extends Graph
{
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
         * @param node
         *            the node being processed
         * @param app
         *            the app being processed
         * @param deploymentManager
         *            the {@link DeploymentManager} tracking the {@link AppLifeCycle} and {@link App}
         * @throws Exception
         *             if any problem severe enough to halt the AppLifeCycle processing
         */
        void processBinding(Node node, App app) throws Exception;
    }

    // Private string constants defined to avoid typos on repeatedly used strings 
    private static final String NODE_UNDEPLOYED = "undeployed";
    private static final String NODE_DEPLOYING = "deploying";
    private static final String NODE_DEPLOYED = "deployed";
    private static final String NODE_STARTING = "starting";
    private static final String NODE_STARTED = "started";
    private static final String NODE_STOPPING = "stopping";
    private static final String NODE_UNDEPLOYING = "undeploying";
    private Map<String, List<Binding>> lifecyclebindings = new HashMap<String, List<Binding>>();

    public AppLifeCycle()
    {
        // Define Default Graph

        // undeployed -> deployed
        addEdge(NODE_UNDEPLOYED,NODE_DEPLOYING);
        addEdge(NODE_DEPLOYING,NODE_DEPLOYED);

        // deployed -> started
        addEdge(NODE_DEPLOYED,NODE_STARTING);
        addEdge(NODE_STARTING,NODE_STARTED);

        // started -> deployed
        addEdge(NODE_STARTED,NODE_STOPPING);
        addEdge(NODE_STOPPING,NODE_DEPLOYED);

        // deployed -> undeployed
        addEdge(NODE_DEPLOYED,NODE_UNDEPLOYING);
        addEdge(NODE_UNDEPLOYING,NODE_UNDEPLOYED);
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
            
            lifecyclebindings.put(nodeName,bindings);
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
     * @return Set of Object(s) for specific lifecycle bindings. never null.
     */
    public Set<AppLifeCycle.Binding> getBindings(Node node)
    {
        return getBindings(node.getName());
    }

    /**
     * Get all objects bound to a specific {@link Node}
     * 
     * @return Set of Object(s) for specific lifecycle bindings. never null.
     */
    public Set<AppLifeCycle.Binding> getBindings(String nodeName)
    {
        Set<Binding> boundset = new HashSet<Binding>();

        List<Binding> bindings = lifecyclebindings.get(nodeName);
        if (bindings == null)
        {
            return boundset;
        }

        boundset.addAll(bindings);

        return boundset;
    }

    public void runBindings(Node node, App app, DeploymentManager deploymentManager) throws Throwable
    {
        List<Binding> bindings = new ArrayList<Binding>();
        bindings.addAll(getBindings(ALL_NODES)); // Bindings (special) All Nodes
        bindings.addAll(getBindings(node)); // Specific Node

        for (Binding binding : bindings)
        {
            Log.info("Calling " + binding.getClass().getName());
            binding.processBinding(node,app);
        }
    }
}
