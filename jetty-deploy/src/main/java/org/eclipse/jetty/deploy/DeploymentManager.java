//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.deploy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.deploy.bindings.StandardDeployer;
import org.eclipse.jetty.deploy.bindings.StandardStarter;
import org.eclipse.jetty.deploy.bindings.StandardStopper;
import org.eclipse.jetty.deploy.bindings.StandardUndeployer;
import org.eclipse.jetty.deploy.graph.Edge;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.deploy.graph.Path;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Locker.Lock;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * The Deployment Manager.
 * <p>
 * Responsibilities:
 * <p>
 * <img alt="deployment manager roles graph" src="doc-files/DeploymentManager_Roles.png">
 * <ol>
 * <li>Tracking Apps and their LifeCycle Location</li>
 * <li>Managing AppProviders and the Apps that they provide.</li>
 * <li>Executing AppLifeCycle on App based on current and desired LifeCycle Location.</li>
 * </ol>
 * <p>
 * <img alt="deployment manager graph" src="doc-files/DeploymentManager.png">
 */
@ManagedObject("Deployment Manager")
public class DeploymentManager extends ContainerLifeCycle
{
    private static final Logger LOG = Log.getLogger(DeploymentManager.class);

    /**
     * Represents a single tracked app within the deployment manager.
     */
    public class AppEntry
    {
        /**
         * Version of the app.
         *
         * Note: Auto-increments on each {@link DeploymentManager#addApp(App)}
         */
        private int version;

        /**
         * The app being tracked.
         */
        private App app;

        /**
         * The lifecycle node location of this App
         */
        private Node lifecyleNode;

        /**
         * Tracking the various AppState timestamps (in system milliseconds)
         */
        private Map<Node, Long> stateTimestamps = new HashMap<Node, Long>();

        public App getApp()
        {
            return app;
        }

        public Node getLifecyleNode()
        {
            return lifecyleNode;
        }

        public Map<Node, Long> getStateTimestamps()
        {
            return stateTimestamps;
        }

        public int getVersion()
        {
            return version;
        }

        void setLifeCycleNode(Node node)
        {
            this.lifecyleNode = node;
            this.stateTimestamps.put(node, Long.valueOf(System.currentTimeMillis()));
        }
    }
    
    private MultiException _onStartupErrors;
    private Locker _errorLocker = new Locker();
    private final List<AppProvider> _providers = new ArrayList<AppProvider>();
    private final AppLifeCycle _lifecycle = new AppLifeCycle();
    private final Queue<AppEntry> _apps = new ConcurrentLinkedQueue<AppEntry>();
    private AttributesMap _contextAttributes = new AttributesMap();
    private ContextHandlerCollection _contexts;
    private boolean _useStandardBindings = true;
    private String _defaultLifeCycleGoal = AppLifeCycle.STARTED;

    /**
     * Receive an app for processing.
     *
     * Most commonly used by the various {@link AppProvider} implementations.
     *
     * @param app the app
     */
    public void addApp(App app)
    {
        LOG.debug("Deployable added: {}", app.getOriginId());
        AppEntry entry = new AppEntry();
        entry.app = app;
        entry.setLifeCycleNode(_lifecycle.getNodeByName("undeployed"));
        _apps.add(entry);

        if (isRunning() && _defaultLifeCycleGoal != null)
        {
            // Immediately attempt to go to default lifecycle state
            this.requestAppGoal(entry, _defaultLifeCycleGoal);
        }
    }

    /**
     * Set the AppProviders.
     * The providers passed are added via {@link #addBean(Object)} so that
     * their lifecycles may be managed as a {@link ContainerLifeCycle}.
     *
     * @param providers the app provider list
     */
    public void setAppProviders(Collection<AppProvider> providers)
    {
        if (isRunning())
            throw new IllegalStateException();

        _providers.clear();
        removeBeans();
        for (AppProvider provider : providers)
        {
            if (_providers.add(provider))
                addBean(provider);
        }
    }

    public Collection<AppProvider> getAppProviders()
    {
        return Collections.unmodifiableList(_providers);
    }

    public void addAppProvider(AppProvider provider)
    {
        if (isRunning())
            throw new IllegalStateException();
        _providers.add(provider);
        addBean(provider);
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

        // Start all of the AppProviders
        for (AppProvider provider : _providers)
        {
            startAppProvider(provider);
        }

        try (Lock lock = _errorLocker.lock())
        {
            if (_onStartupErrors != null)
                _onStartupErrors.ifExceptionThrow();
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        // Stop all of the AppProviders
        for (AppProvider provider : _providers)
        {
            try
            {
                provider.stop();
            }
            catch (Exception e)
            {
                LOG.warn("Unable to start AppProvider", e);
            }
        }
        super.doStop();
    }

    private AppEntry findAppByOriginId(String originId)
    {
        if (originId == null)
        {
            return null;
        }

        for (AppEntry entry : _apps)
        {
            if (originId.equals(entry.app.getOriginId()))
            {
                return entry;
            }
        }
        return null;
    }

    public App getAppByOriginId(String originId)
    {
        AppEntry entry = findAppByOriginId(originId);
        if (entry == null)
        {
            return null;
        }
        return entry.app;
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

    public List<App> getAppsWithSameContext(App app)
    {
        List<App> ret = new ArrayList<App>();
        if (app == null)
        {
            return ret;
        }

        String contextId = app.getContextPath();
        if (contextId == null)
        {
            // No context? Likely not deployed or started yet.
            return ret;
        }

        for (AppEntry entry : _apps)
        {
            if (entry.app.equals(app))
            {
                // Its the input app. skip it.
                // TODO: is this filter needed?
                continue;
            }

            if (contextId.equals(entry.app.getContextPath()))
            {
                ret.add(entry.app);
            }
        }
        return ret;
    }

    /**
     * Get a contextAttribute that will be set for every Context deployed by this provider.
     *
     * @param name context attribute name
     * @return the context attribute value
     */
    public Object getContextAttribute(String name)
    {
        return _contextAttributes.getAttribute(name);
    }

    public AttributesMap getContextAttributes()
    {
        return _contextAttributes;
    }

    @ManagedAttribute("Deployed Contexts")
    public ContextHandlerCollection getContexts()
    {
        return _contexts;
    }

    public String getDefaultLifeCycleGoal()
    {
        return _defaultLifeCycleGoal;
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
        Iterator<AppEntry> it = _apps.iterator();
        while (it.hasNext())
        {
            AppEntry entry = it.next();
            if (entry.app.equals(app))
            {
                if (!AppLifeCycle.UNDEPLOYED.equals(entry.lifecyleNode.getName()))
                    requestAppGoal(entry.app, AppLifeCycle.UNDEPLOYED);
                it.remove();
                LOG.debug("Deployable removed: {}", entry.app);
            }
        }
    }

    public void removeAppProvider(AppProvider provider)
    {
        if (_providers.remove(provider))
            removeBean(provider);

        try
        {
            provider.stop();
        }
        catch (Exception e)
        {
            LOG.warn("Unable to stop Provider", e);
        }
    }

    /**
     * Remove a contextAttribute that will be set for every Context deployed by this provider.
     *
     * @param name the context attribute name
     */
    public void removeContextAttribute(String name)
    {
        _contextAttributes.removeAttribute(name);
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
        AppEntry appentry = findAppByOriginId(app.getOriginId());
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
        Path path = _lifecycle.getPath(appentry.lifecyleNode, destinationNode);
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
            LOG.warn("Unable to reach node goal: " + nodeName, t);
            // migrate to FAILED node
            Node failed = _lifecycle.getNodeByName(AppLifeCycle.FAILED);
            appentry.setLifeCycleNode(failed);
            try
            {
                _lifecycle.runBindings(failed, appentry.app, this);
            }
            catch (Throwable ignore)
            {
                // The runBindings failed for 'failed' node
                LOG.ignore(ignore);
            }

            if (isStarting())
            {
                addOnStartupError(t);
            }
        }
    }

    private void addOnStartupError(Throwable cause)
    {
        try (Lock lock = _errorLocker.lock())
        {
            if (_onStartupErrors == null)
                _onStartupErrors = new MultiException();
            
            _onStartupErrors.add(cause);
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
        AppEntry appentry = findAppByOriginId(appId);
        if (appentry == null)
        {
            throw new IllegalStateException("App not being tracked by Deployment Manager: " + appId);
        }
        requestAppGoal(appentry, nodeName);
    }

    /**
     * Set a contextAttribute that will be set for every Context deployed by this provider.
     *
     * @param name the context attribute name
     * @param value the context attribute value
     */
    public void setContextAttribute(String name, Object value)
    {
        _contextAttributes.setAttribute(name, value);
    }

    public void setContextAttributes(AttributesMap contextAttributes)
    {
        this._contextAttributes = contextAttributes;
    }

    public void setContexts(ContextHandlerCollection contexts)
    {
        this._contexts = contexts;
    }

    public void setDefaultLifeCycleGoal(String defaultLifeCycleState)
    {
        this._defaultLifeCycleGoal = defaultLifeCycleState;
    }

    private void startAppProvider(AppProvider provider)
    {
        try
        {
            provider.setDeploymentManager(this);
            provider.start();
        }
        catch (Exception e)
        {
            LOG.warn("Unable to start AppProvider", e);
        }
    }

    public void undeployAll()
    {
        LOG.debug("Undeploy All");
        for (AppEntry appentry : _apps)
        {
            requestAppGoal(appentry, "undeployed");
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

    public Collection<App> getApps(String nodeName)
    {
        return getApps(_lifecycle.getNodeByName(nodeName));
    }

    public void scope(XmlConfiguration xmlc, Resource webapp)
        throws IOException
    {
        xmlc.setJettyStandardIdsAndProperties(getServer(), webapp);
    }
}
