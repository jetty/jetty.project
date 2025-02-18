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

import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.bindings.StandardDeployer;
import org.eclipse.jetty.deploy.bindings.StandardStarter;
import org.eclipse.jetty.deploy.bindings.StandardStopper;
import org.eclipse.jetty.deploy.bindings.StandardUndeployer;
import org.eclipse.jetty.deploy.internal.DeploymentGraph;
import org.eclipse.jetty.deploy.internal.graph.Edge;
import org.eclipse.jetty.deploy.internal.graph.Node;
import org.eclipse.jetty.deploy.internal.graph.Route;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Deployment Manager.
 * <p>
 * Responsibilities:
 * <p>
 * <img alt="deployment manager roles graph" src="doc-files/DeploymentManager_Roles.png">
 * <ol>
 * <li>Tracking ContextHandlers and their location in the Deployment graph.</li>
 * <li>Moving ContextHandlers through the Deployment graph (eg: DEPLOYED, STARTED, UNDEPLOYED, etc.).</li>
 * </ol>
 * <p>
 * <img alt="deployment manager graph" src="doc-files/DeploymentManager.png">
 */
// TODO: fix dumpable to show things like context-handler-collection, contexts being tracked, etc...
@ManagedObject("Deployment Manager")
public class DeploymentManager extends ContainerLifeCycle implements ContextHandlerDeployer
{
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentManager.class);
    private final DeploymentGraph _lifecycle = new DeploymentGraph();
    private final Queue<TrackedContext> _tracked = new ConcurrentLinkedQueue<>();
    private ContextHandlerCollection _contexts;
    private boolean _useStandardBindings = true;
    private Throwable _onStartupErrors;

    /**
     * Add a DeploymentNodeBinding to the graph.
     *
     * @param binding the binding to add.
     */
    // TODO: Rename
    public void addLifeCycleBinding(DeploymentNodeBinding binding)
    {
        _lifecycle.addBinding(binding);
    }

    /**
     * Add a ContextHandler int the graph but perform no actions on it, leaving it in undeployed state.
     *
     * @param contextHandler the ContextHandler to add to the graph.
     */
    @Override
    public void addUndeployed(ContextHandler contextHandler)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("addUndeployed: {}", contextHandler);
        startTracking(contextHandler);
    }

    /**
     * Add a ContextHandler to the graph and then move it a deployed state.
     *
     * @param contextHandler the ContextHandler to deploy.
     */
    @Override
    public void deploy(ContextHandler contextHandler)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("deploy: {}", contextHandler);
        TrackedContext trackedContext = startTracking(contextHandler);

        if (getContexts().isRunning())
        {
            // Only move to STARTED state if the ContextHandlerCollection itself is started.
            requestContextHandlerGoal(trackedContext, DeploymentGraph.STARTED);
        }
        else
        {
            // Otherwise, just make sure it reaches the deployed state.
            requestContextHandlerGoal(trackedContext, DeploymentGraph.DEPLOYED);
        }
    }

    /**
     * Get the bindings that exist
     */
    public Set<DeploymentNodeBinding> getBindings()
    {
        return _lifecycle.getBindings();
    }

    /**
     * Get the bindings that exist for a specific node name.
     *
     * @param nodeName the node to get bindings from
     */
    public Set<DeploymentNodeBinding> getBindings(String nodeName)
    {
        return _lifecycle.getBindings(nodeName);
    }

    /**
     * Get the list of tracked {@link ContextHandler} in the DeploymentManager graph.
     *
     * @return the list of tracked ContextHandlers
     */
    public Collection<ContextHandler> getContextHandlers()
    {
        return _tracked.stream()
            .map((e) -> e.contextHandler)
            .toList();
    }

    /**
     * Get the list of ContextHandlers present at a specific graph node name.
     *
     * @param nodeName the node name to look in
     * @return the list of {@link ContextHandler} present on that node
     */
    @ManagedOperation(value = "list ContextHandlers that are located at specified deployment manager graph node", impact = "ACTION")
    public Collection<ContextHandler> getContextHandlers(@Name("nodeName") String nodeName)
    {
        Set<String> nodeNames = getNodeNames();
        if (!nodeNames.contains(nodeName))
        {
            throw new IllegalArgumentException("Unable to find node [" + nodeName + "] in " +
                nodeNames.stream()
                    .sorted()
                    .collect(Collectors.joining(", ", "[", "]")));
        }

        return getContextHandlers(_lifecycle.getNodeByName(nodeName));
    }

    @ManagedAttribute("The ContextHandlerCollection being managed")
    public ContextHandlerCollection getContexts()
    {
        return _contexts;
    }

    public void setContexts(ContextHandlerCollection contexts)
    {
        this._contexts = contexts;
    }

    @ManagedOperation(value = "list nodes that are tracked by DeploymentManager", impact = "INFO")
    public Set<String> getNodeNames()
    {
        Set<String> names = new TreeSet<>(String::compareToIgnoreCase);
        _lifecycle.getNodes().stream()
            .map(Node::getName)
            .forEach(names::add);
        return names;
    }

    public Server getServer()
    {
        if (_contexts == null)
        {
            return null;
        }
        return _contexts.getServer();
    }

    /**
     * Convenience method to allow for insertion of nodes into the lifecycle.
     *
     * @param existingFromNodeName the existing node start
     * @param existingToNodeName the existing node end
     * @param insertedNodeName the new node to create between the existing nodes
     */
    public void insertLifeCycleNode(String existingFromNodeName, String existingToNodeName, String insertedNodeName)
    {
        Node fromNode = _lifecycle.getNodeByName(existingFromNodeName);
        Node toNode = _lifecycle.getNodeByName(existingToNodeName);
        Edge edge = new Edge(fromNode, toNode);
        _lifecycle.insertNode(edge, insertedNodeName);
    }

    public boolean isUseStandardBindings()
    {
        return _useStandardBindings;
    }

    public void setUseStandardBindings(boolean useStandardBindings)
    {
        this._useStandardBindings = useStandardBindings;
    }

    /**
     * Advanced usage, move a ContextHandler through the DeploymentManager graph by name.
     *
     * @param contextHandler the ContextHandler to move
     * @param goalName the goal graph node by name
     */
    @Override
    public void move(ContextHandler contextHandler, String goalName)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("move: {} -> {}", contextHandler, goalName);
        TrackedContext trackedContext = findTrackedContext(contextHandler);
        requestContextHandlerGoal(trackedContext, goalName);
    }

    public void setLifeCycleBindings(Collection<DeploymentNodeBinding> bindings)
    {
        if (isRunning())
            throw new IllegalStateException();
        for (DeploymentNodeBinding b : _lifecycle.getBindings())
        {
            _lifecycle.removeBinding(b);
        }
        for (DeploymentNodeBinding b : bindings)
        {
            _lifecycle.addBinding(b);
        }
    }

    /**
     * Move a ContextHandler int the graph to an undeployed state, and then remove it from the graph.
     *
     * @param contextHandler the ContextHandler to undeploy.
     */
    @Override
    public void undeploy(ContextHandler contextHandler)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("undeploy: {}", contextHandler);
        TrackedContext trackedContext = findTrackedContext(contextHandler);
        requestContextHandlerGoal(trackedContext, DeploymentGraph.UNDEPLOYED);
        stopTracking(trackedContext);
    }

    @ManagedOperation(value = "undeploy all ContextHandlers being tracked by DeploymentManager")
    public void undeployAll()
    {
        LOG.debug("Undeploy All");
        for (TrackedContext entry : _tracked)
        {
            undeploy(entry.contextHandler);
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        if (getContexts() == null)
            throw new IllegalStateException("No " + ContextHandlerCollection.class.getName() + " defined");

        if (_useStandardBindings)
        {
            LOG.debug("DeploymentManager using standard bindings");
            addLifeCycleBinding(new StandardDeployer());
            addLifeCycleBinding(new StandardStarter());
            addLifeCycleBinding(new StandardStopper());
            addLifeCycleBinding(new StandardUndeployer());
        }

        super.doStart();

        ExceptionUtil.ifExceptionThrow(_onStartupErrors);
    }

    private TrackedContext findTrackedContext(ContextHandler contextHandler)
    {
        return _tracked.stream()
            .filter((e) -> e.contextHandler.equals(contextHandler))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("ContextHandler[%s,%s] not being tracked".formatted(contextHandler.getContextPath(), contextHandler.getVirtualHosts())));
    }

    /**
     * Get Set of {@link ContextHandler}s by {@link Node}
     *
     * @param node the node to look for.
     * @return the collection of ContextHandlers for the node
     */
    private Collection<ContextHandler> getContextHandlers(Node node)
    {
        Objects.requireNonNull(node);

        return _tracked.stream()
            .filter(tracked -> node.equals(tracked.lifecyleNode))
            .map(TrackedContext::getContextHandler)
            .toList();
    }

    /**
     * Move a {@link TrackedContext} through the {@link DeploymentGraph} to the desired {@link Node}, executing each lifecycle step
     * in the process to reach the desired state.
     *
     * @param tracked the internal tracked context to move through the process
     * @param nodeName the name of the node to attain
     */
    private void requestContextHandlerGoal(TrackedContext tracked, String nodeName)
    {
        Node destinationNode = _lifecycle.getNodeByName(nodeName);
        if (destinationNode == null)
        {
            throw new IllegalStateException("Node not present in Deployment Manager: " + nodeName);
        }
        // Compute lifecycle steps
        Route path = _lifecycle.getPath(tracked.lifecyleNode, destinationNode);
        if (path.isEmpty())
        {
            // nothing to do. already there.
            return;
        }

        // Execute each Node binding.  Stopping at any thrown exception.
        try
        {
            Iterator<Node> it = path.getNodes().iterator();
            if (it.hasNext()) // Any entries?
            {
                // The first entry in the path is always the start node
                // We don't want to run bindings on that entry (again)
                it.next(); // skip first entry
                while (it.hasNext())
                {
                    Node node = it.next();
                    LOG.debug("Executing Node {}", node);
                    _lifecycle.runBindings(node, tracked.contextHandler, this);
                    tracked.setLifeCycleNode(node);
                }
            }
        }
        catch (Throwable t)
        {
            String message = nodeName.toUpperCase(Locale.ENGLISH) + " Deployment failed for " + tracked.contextHandler;
            LOG.warn(message, t);
            fail(tracked);

            if (isStarting())
            {
                reportStartupFailure(t);
            }
        }
    }

    @Override
    public void reportStartupFailure(Throwable cause)
    {
        _onStartupErrors = ExceptionUtil.combine(_onStartupErrors, cause);
    }

    private void fail(TrackedContext tracked)
    {
        Node failed = _lifecycle.getNodeByName(DeploymentGraph.FAILED);
        tracked.setLifeCycleNode(failed);
        try
        {
            _lifecycle.runBindings(failed, tracked.contextHandler, this);
        }
        catch (Throwable cause)
        {
            // The runBindings failed for 'failed' node
            LOG.trace("IGNORED", cause);
        }
    }

    private TrackedContext startTracking(ContextHandler contextHandler)
    {
        TrackedContext entry = new TrackedContext();
        entry.contextHandler = contextHandler;
        entry.setLifeCycleNode(_lifecycle.getNodeByName(DeploymentGraph.UNDEPLOYED));
        _tracked.add(entry);
        return entry;
    }

    private void stopTracking(TrackedContext trackedContext)
    {
        _tracked.remove(trackedContext);
    }

    /**
     * A mutable record tracking a single context within the deployment manager.
     */
    private static class TrackedContext
    {
        /**
         * The context being tracked.
         */
        private ContextHandler contextHandler;

        /**
         * The lifecycle node location of this ContextHandler
         */
        private Node lifecyleNode;

        public ContextHandler getContextHandler()
        {
            return contextHandler;
        }

        public Node getLifecyleNode()
        {
            return lifecyleNode;
        }

        void setLifeCycleNode(Node node)
        {
            this.lifecyleNode = node;
        }
    }
}
