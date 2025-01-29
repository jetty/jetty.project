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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.deploy.bindings.StandardDeployer;
import org.eclipse.jetty.deploy.bindings.StandardStarter;
import org.eclipse.jetty.deploy.bindings.StandardStopper;
import org.eclipse.jetty.deploy.bindings.StandardUndeployer;
import org.eclipse.jetty.deploy.graph.Edge;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.deploy.graph.Route;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Deployment Manager.
 * <p>
 * Responsibilities:
 * <p>
 * <img alt="deployment manager roles graph" src="doc-files/DeploymentManager_Roles.png">
 * <ol>
 * <li>Tracking Apps and their LifeCycle Location</li>
 * <li>Executing ContextHandlerLifeCycle on App based on current and desired LifeCycle Location.</li>
 * </ol>
 * <p>
 * <img alt="deployment manager graph" src="doc-files/DeploymentManager.png">
 */
// TODO: fix dumpable to show things like context-handler-collection, contexts being tracked, etc...
@ManagedObject("Deployment Manager")
public class DeploymentManager extends ContainerLifeCycle implements ContextHandlerManagement
{
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentManager.class);

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
         * The lifecycle node location of this App
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

    private final AutoLock _lock = new AutoLock();
    private Throwable _onStartupErrors;
    private final ContextHandlerLifeCycle _lifecycle = new ContextHandlerLifeCycle();
    private final Queue<TrackedContext> _tracked = new ConcurrentLinkedQueue<>();
    private ContextHandlerCollection _contexts;
    private boolean _useStandardBindings = true;

    /**
     * Add a ContextHandler to the tracking, and move it to the desired node name.
     *
     * @param contextHandler the context handler
     * @param nodeName the requested node to reach
     */
    @Override
    public void addContextHandler(ContextHandler contextHandler, String nodeName)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("addContextHandler: {} -> {}", contextHandler, nodeName);
        TrackedContext entry = new TrackedContext();
        entry.contextHandler = contextHandler;
        entry.setLifeCycleNode(_lifecycle.getNodeByName(ContextHandlerLifeCycle.UNDEPLOYED));
        _tracked.add(entry);

        if (isRunning())
        {
            // Immediately attempt to go to default lifecycle state
            this.requestContextHandlerGoal(entry, nodeName);
        }
    }

    public void setLifeCycleBindings(Collection<ContextHandlerLifeCycle.Binding> bindings)
    {
        if (isRunning())
            throw new IllegalStateException();
        for (ContextHandlerLifeCycle.Binding b : _lifecycle.getBindings())
        {
            _lifecycle.removeBinding(b);
        }
        for (ContextHandlerLifeCycle.Binding b : bindings)
        {
            _lifecycle.addBinding(b);
        }
    }

    public Collection<ContextHandlerLifeCycle.Binding> getLifeCycleBindings()
    {
        return Collections.unmodifiableSet(_lifecycle.getBindings());
    }

    public void addLifeCycleBinding(ContextHandlerLifeCycle.Binding binding)
    {
        _lifecycle.addBinding(binding);
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

        try (AutoLock l = _lock.lock())
        {
            ExceptionUtil.ifExceptionThrow(_onStartupErrors);
        }

        super.doStart();
    }

    private TrackedContext findTrackedContext(String id)
    {
        if (id == null)
            return null;

        for (TrackedContext entry : _tracked)
        {
            if (id.equals(entry.contextHandler.getID()))
                return entry;
        }
        return null;
    }

    public ContextHandler findContextHandler(String id)
    {
        TrackedContext entry = findTrackedContext(id);
        return entry == null ? null : entry.getContextHandler();
    }

    public Collection<ContextHandler> getContextHandlers()
    {
        return _tracked.stream()
            .map((e) -> e.contextHandler)
            .toList();
    }

    /**
     * Get Set of {@link ContextHandler}s by {@link Node}
     *
     * @param node the node to look for.
     * @return the collection of ContextHandlers for the node
     */
    public Collection<ContextHandler> getContextHandlers(Node node)
    {
        Objects.requireNonNull(node);

        List<ContextHandler> ret = new ArrayList<>();
        for (TrackedContext entry : _tracked)
        {
            if (node.equals(entry.lifecyleNode))
            {
                ret.add(entry.contextHandler);
            }
        }
        return ret;
    }

    public Collection<ContextHandler> getContextHandlers(String nodeName)
    {
        return getContextHandlers(_lifecycle.getNodeByName(nodeName));
    }

    @ManagedAttribute("The ContextHandlerCollection being managed")
    public ContextHandlerCollection getContexts()
    {
        return _contexts;
    }

    public ContextHandlerLifeCycle getLifeCycle()
    {
        return _lifecycle;
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
     * Remove the ContextHandler from the DeploymentManager tracking.
     *
     * @param contextHandler the contextHandler to remove it from the deployment manager.
     * @param goalName the name of the node to attain before removal of contextHandler.
     */
    @Override
    public void removeContextHandler(ContextHandler contextHandler, String goalName)
    {
        LOG.info("removeContextHandler: {}, {}", contextHandler, goalName);
        Iterator<TrackedContext> it = _tracked.iterator();
        while (it.hasNext())
        {
            TrackedContext entry = it.next();
            if (entry.contextHandler.equals(contextHandler))
            {
                if (!goalName.equals(entry.lifecyleNode.getName()))
                    requestContextHandlerGoal(entry.contextHandler, goalName);
                it.remove();
            }
        }
    }

    /**
     * Move an {@link ContextHandler} through the {@link ContextHandlerLifeCycle} to the desired {@link Node}, executing each lifecycle step
     * in the process to reach the desired state.
     *
     * @param contextHandler the ContextHandler to move through the process
     * @param nodeName the name of the node to attain
     */
    @Override
    @ManagedOperation(value = "request the context handler to be moved to the specified lifecycle node", impact = "ACTION")
    public void requestContextHandlerGoal(ContextHandler contextHandler, String nodeName)
    {
        TrackedContext tracked = findTrackedContext(contextHandler.getID());
        if (tracked == null)
        {
            throw new IllegalStateException("ContextHandler not being tracked by Deployment Manager: " + contextHandler);
        }

        requestContextHandlerGoal(tracked, nodeName);
    }

    /**
     * Move a {@link TrackedContext} through the {@link ContextHandlerLifeCycle} to the desired {@link Node}, executing each lifecycle step
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
            LOG.warn("Unable to reach node goal: {}", nodeName, t);
            
            // migrate to FAILED node
            Node failed = _lifecycle.getNodeByName(ContextHandlerLifeCycle.FAILED);
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

            if (isStarting())
            {
                addOnStartupError(t);
            }
        }
    }

    private void addOnStartupError(Throwable cause)
    {
        try (AutoLock l = _lock.lock())
        {
            _onStartupErrors = ExceptionUtil.combine(_onStartupErrors, cause);
        }
    }

    public void requestContextHandlerGoal(@Name("contextId") String contextHandlerId, @Name("nodeName") String nodeName)
    {
        TrackedContext tracked = findTrackedContext(contextHandlerId);
        if (tracked == null)
        {
            throw new IllegalStateException("ContextHandler not being tracked by Deployment Manager: " + contextHandlerId);
        }
        requestContextHandlerGoal(tracked, nodeName);
    }

    public void setContexts(ContextHandlerCollection contexts)
    {
        this._contexts = contexts;
    }

    public void undeployAll()
    {
        LOG.debug("Undeploy All");
        for (TrackedContext entry : _tracked)
        {
            requestContextHandlerGoal(entry, ContextHandlerLifeCycle.UNDEPLOYED);
        }
    }

    public boolean isUseStandardBindings()
    {
        return _useStandardBindings;
    }

    public void setUseStandardBindings(boolean useStandardBindings)
    {
        this._useStandardBindings = useStandardBindings;
    }

    public Collection<Node> getNodes()
    {
        return _lifecycle.getNodes();
    }
}
