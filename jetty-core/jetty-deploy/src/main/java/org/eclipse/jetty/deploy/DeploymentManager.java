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
 * <li>Executing AppLifeCycle on App based on current and desired LifeCycle Location.</li>
 * </ol>
 * <p>
 * <img alt="deployment manager graph" src="doc-files/DeploymentManager.png">
 */
@ManagedObject("Deployment Manager")
public class DeploymentManager extends ContainerLifeCycle implements AppProvider.Manager
{
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentManager.class);

    /**
     * Represents a single tracked app within the deployment manager.
     */
    public class AppEntry
    {
        /**
         * The app being tracked.
         */
        private App app;

        /**
         * The lifecycle node location of this App
         */
        private Node lifecyleNode;

        public App getApp()
        {
            return app;
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
    private final AppLifeCycle _lifecycle = new AppLifeCycle();
    private final Queue<AppEntry> _apps = new ConcurrentLinkedQueue<AppEntry>();
    private ContextHandlerCollection _contexts;
    private boolean _useStandardBindings = true;

    /**
     * Receive an app for processing, and
     *
     * @param app the app
     */
    @Override
    public void addApp(App app, String nodeName)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("addApp: {} -> {}", app, nodeName);
        AppEntry entry = new AppEntry();
        entry.app = app;
        entry.setLifeCycleNode(_lifecycle.getNodeByName(AppLifeCycle.UNDEPLOYED));
        _apps.add(entry);

        if (isRunning())
        {
            // Immediately attempt to go to default lifecycle state
            this.requestAppGoal(entry, nodeName);
        }
    }

    public void addAppProvider(AppProvider provider)
    {
        provider.setManager(this);
        addBean(provider);
    }

    public Collection<AppProvider> getAppProviders()
    {
        return getBeans(AppProvider.class);
    }

    public void setLifeCycleBindings(Collection<AppLifeCycle.Binding> bindings)
    {
        if (isRunning())
            throw new IllegalStateException();
        for (AppLifeCycle.Binding b : _lifecycle.getBindings())
        {
            _lifecycle.removeBinding(b);
        }
        for (AppLifeCycle.Binding b : bindings)
        {
            _lifecycle.addBinding(b);
        }
    }

    public Collection<AppLifeCycle.Binding> getLifeCycleBindings()
    {
        return Collections.unmodifiableSet(_lifecycle.getBindings());
    }

    public void addLifeCycleBinding(AppLifeCycle.Binding binding)
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
            throw new IllegalStateException("No Contexts");

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

    private AppEntry findAppEntry(String appId)
    {
        if (appId == null)
            return null;

        for (AppEntry entry : _apps)
        {
            String name = entry.app.getName();
            if (appId.equals(name))
                return entry;
        }
        return null;
    }

    public App getApp(String appId)
    {
        AppEntry entry = findAppEntry(appId);
        return entry == null ? null : entry.getApp();
    }

    public Collection<AppEntry> getAppEntries()
    {
        return Collections.unmodifiableCollection(_apps);
    }

    public Collection<App> getApps()
    {
        List<App> ret = new ArrayList<>();
        for (AppEntry entry : _apps)
        {
            ret.add(entry.app);
        }
        return ret;
    }

    /**
     * Get Set of {@link App}s by {@link Node}
     *
     * @param node the node to look for.
     * @return the collection of apps for the node
     */
    public Collection<App> getApps(Node node)
    {
        Objects.requireNonNull(node);

        List<App> ret = new ArrayList<>();
        for (AppEntry entry : _apps)
        {
            if (node.equals(entry.lifecyleNode))
            {
                ret.add(entry.app);
            }
        }
        return ret;
    }

    public Collection<App> getApps(String nodeName)
    {
        return getApps(_lifecycle.getNodeByName(nodeName));
    }

    @ManagedAttribute("Deployed Contexts")
    public ContextHandlerCollection getContexts()
    {
        return _contexts;
    }

    public AppLifeCycle getLifeCycle()
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
     * Remove the app from the tracking of the DeploymentManager
     *
     * @param app if the app is Unavailable remove it from the deployment manager.
     */
    public void removeApp(App app)
    {
        LOG.info("removeApp: {}", app);
        Iterator<AppEntry> it = _apps.iterator();
        while (it.hasNext())
        {
            AppEntry entry = it.next();
            if (entry.app.equals(app))
            {
                if (!AppLifeCycle.UNDEPLOYED.equals(entry.lifecyleNode.getName()))
                    requestAppGoal(entry.app, AppLifeCycle.UNDEPLOYED);
                it.remove();
            }
        }
    }

    /**
     * Move an {@link App} through the {@link AppLifeCycle} to the desired {@link Node}, executing each lifecycle step
     * in the process to reach the desired state.
     *
     * @param app the app to move through the process
     * @param nodeName the name of the node to attain
     */
    public void requestAppGoal(App app, String nodeName)
    {
        AppEntry appentry = findAppEntry(app.getName());
        if (appentry == null)
        {
            throw new IllegalStateException("App not being tracked by Deployment Manager: " + app);
        }

        requestAppGoal(appentry, nodeName);
    }

    /**
     * Move an {@link App} through the {@link AppLifeCycle} to the desired {@link Node}, executing each lifecycle step
     * in the process to reach the desired state.
     *
     * @param appentry the internal appentry to move through the process
     * @param nodeName the name of the node to attain
     */
    private void requestAppGoal(AppEntry appentry, String nodeName)
    {
        Node destinationNode = _lifecycle.getNodeByName(nodeName);
        if (destinationNode == null)
        {
            throw new IllegalStateException("Node not present in Deployment Manager: " + nodeName);
        }
        // Compute lifecycle steps
        Route path = _lifecycle.getPath(appentry.lifecyleNode, destinationNode);
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
                    _lifecycle.runBindings(node, appentry.app, this);
                    appentry.setLifeCycleNode(node);
                }
            }
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to reach node goal: {}", nodeName, t);
            
            // migrate to FAILED node
            Node failed = _lifecycle.getNodeByName(AppLifeCycle.FAILED);
            appentry.setLifeCycleNode(failed);
            try
            {
                _lifecycle.runBindings(failed, appentry.app, this);
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

    /**
     * Move an {@link App} through the {@link AppLifeCycle} to the desired {@link Node}, executing each lifecycle step
     * in the process to reach the desired state.
     *
     * @param appId the id of the app to move through the process
     * @param nodeName the name of the node to attain
     */
    @ManagedOperation(value = "request the app to be moved to the specified lifecycle node", impact = "ACTION")
    public void requestAppGoal(@Name("appId") String appId, @Name("nodeName") String nodeName)
    {
        AppEntry appentry = findAppEntry(appId);
        if (appentry == null)
        {
            throw new IllegalStateException("App not being tracked by Deployment Manager: " + appId);
        }
        requestAppGoal(appentry, nodeName);
    }

    public void setContexts(ContextHandlerCollection contexts)
    {
        this._contexts = contexts;
    }

    public void undeployAll()
    {
        LOG.debug("Undeploy All");
        for (AppEntry appentry : _apps)
        {
            requestAppGoal(appentry, AppLifeCycle.UNDEPLOYED);
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
