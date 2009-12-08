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
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.eclipse.jetty.deploy.bindings.StandardDeployer;
import org.eclipse.jetty.deploy.bindings.StandardStarter;
import org.eclipse.jetty.deploy.bindings.StandardStopper;
import org.eclipse.jetty.deploy.bindings.StandardUndeployer;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.deploy.graph.Path;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;

/**
 * The Deployment Manager.
 * <p>
 * Responsibilities:
 * <p>
 * <img src="doc-files/DeploymentManager_Roles.png">
 * <ol>
 * <li>Tracking Apps and their LifeCycle Location</li>
 * <li>Managing AppProviders and the Apps that they provide.</li>
 * <li>Executing AppLifeCycle on App based on current and desired LifeCycle Location.</li>
 * </ol>
 * <p>
 * <img src="doc-files/DeploymentManager.png">
 */
public class DeploymentManager extends AbstractLifeCycle
{
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
            this.stateTimestamps.put(node,Long.valueOf(System.currentTimeMillis()));
        }
    }

    private final List<AppProvider> providers = new ArrayList<AppProvider>();
    private final AppLifeCycle lifecycle = new AppLifeCycle();
    private final LinkedList<AppEntry> apps = new LinkedList<AppEntry>();
    private AttributesMap contextAttributes = new AttributesMap();
    private ConfigurationManager configurationManager;
    private ContextHandlerCollection contexts;
    private boolean useStandardBindings = true;
    private String defaultLifeCycleGoal = "started";

    /**
     * Receive an app for processing.
     * 
     * Most commonly used by the various {@link AppProvider} implementations.
     */
    public void addApp(App app)
    {
        Log.info("App Added: " + app.getOriginId());
        AppEntry entry = new AppEntry();
        entry.app = app;
        entry.setLifeCycleNode(lifecycle.getNodeByName("undeployed"));
        apps.add(entry);

        if (defaultLifeCycleGoal != null)
        {
            // Immediately attempt to go to default lifecycle state
            this.requestAppGoal(entry,defaultLifeCycleGoal);
        }
    }

    public void addAppProvider(AppProvider provider)
    {
        providers.add(provider);
        if (isStarted() || isStarting())
        {
            startAppProvider(provider);
        }
    }

    public void addLifeCycleBinding(AppLifeCycle.Binding binding)
    {
        lifecycle.addBinding(binding);
    }

    @Override
    protected void doStart() throws Exception
    {
        if (useStandardBindings)
        {
            Log.info("Using standard bindings");
            addLifeCycleBinding(new StandardDeployer());
            addLifeCycleBinding(new StandardStarter());
            addLifeCycleBinding(new StandardStopper());
            addLifeCycleBinding(new StandardUndeployer());
        }

        Log.info("Starting all Providers: " + providers.size());
        // Start all of the AppProviders
        for (AppProvider provider : providers)
        {
            startAppProvider(provider);
        }
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        Log.info("Stopping all Providers: " + providers.size());

        // Stop all of the AppProviders
        for (AppProvider provider : providers)
        {
            try
            {
                provider.stop();
            }
            catch (Exception e)
            {
                Log.warn("Unable to start AppProvider",e);
            }
        }
        super.doStop();
    }

    private AppEntry findAppByContextId(String contextId)
    {
        if (contextId == null)
        {
            return null;
        }

        for (AppEntry entry : apps)
        {
            if (contextId.equals(entry.app.getContextId()))
            {
                return entry;
            }
        }
        return null;
    }

    private AppEntry findAppByOriginId(String originId)
    {
        if (originId == null)
        {
            return null;
        }

        for (AppEntry entry : apps)
        {
            if (originId.equals(entry.app.getOriginId()))
            {
                return entry;
            }
        }
        return null;
    }

    public App getAppByContextId(String contextId)
    {
        AppEntry entry = findAppByContextId(contextId);
        if (entry == null)
        {
            return null;
        }
        return entry.app;
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
        return apps;
    }

    public Collection<AppProvider> getAppProviders()
    {
        return providers;
    }

    public Collection<App> getApps()
    {
        List<App> ret = new ArrayList<App>();
        for (AppEntry entry : apps)
        {
            ret.add(entry.app);
        }
        return ret;
    }

    /**
     * Get Set of {@link App}s by {@link Node}
     * 
     * @param state
     *            the state to look for.
     * @return
     */
    public Collection<App> getApps(Node node)
    {
        List<App> ret = new ArrayList<App>();
        for (AppEntry entry : apps)
        {
            if (entry.lifecyleNode == node)
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

        String contextId = app.getContextId();
        if (contextId == null)
        {
            // No context? Likely not deployed or started yet.
            return ret;
        }

        for (AppEntry entry : apps)
        {
            if (entry.app.equals(app))
            {
                // Its the input app. skip it.
                // TODO: is this filter needed?
                continue;
            }

            if (contextId.equals(entry.app.getContextId()))
            {
                ret.add(entry.app);
            }
        }
        return ret;
    }

    /**
     * Get a contextAttribute that will be set for every Context deployed by this provider.
     * 
     * @param name
     * @return
     */
    public Object getContextAttribute(String name)
    {
        return contextAttributes.getAttribute(name);
    }

    public ConfigurationManager getConfigurationManager()
    {
        return configurationManager;
    }

    public AttributesMap getContextAttributes()
    {
        return contextAttributes;
    }

    public ContextHandlerCollection getContexts()
    {
        return contexts;
    }

    public String getDefaultLifeCycleGoal()
    {
        return defaultLifeCycleGoal;
    }

    public AppLifeCycle getLifeCycle()
    {
        return lifecycle;
    }

    public Server getServer()
    {
        if (contexts == null)
        {
            return null;
        }
        return contexts.getServer();
    }

    /**
     * Remove the app from the tracking of the DeploymentManager
     * 
     * @param app
     *            if the app is Unavailable remove it from the deployment manager.
     */
    public void removeApp(App app)
    {
        ListIterator<AppEntry> it = apps.listIterator();
        while (it.hasNext())
        {
            AppEntry entry = it.next();
            if (entry.app.equals(app) && "undeployed".equals(entry.lifecyleNode.getName()))
            {
                Log.info("Remove App: " + entry.app);
                it.remove();
            }
        }
    }

    public void removeAppProvider(AppProvider provider)
    {
        providers.remove(provider);
        try
        {
            provider.stop();
        }
        catch (Exception e)
        {
            Log.warn("Unable to stop Provider",e);
        }
    }

    /**
     * Remove a contextAttribute that will be set for every Context deployed by this provider.
     * 
     * @param name
     */
    public void removeContextAttribute(String name)
    {
        contextAttributes.removeAttribute(name);
    }

    /**
     * Move an {@link App} through the {@link AppLifeCycle} to the desired {@link Node}, executing each lifecycle step
     * in the process to reach the desired state.
     * 
     * @param app
     *            the app to move through the process
     * @param nodeName
     *            the name of the node to attain
     */
    public void requestAppGoal(App app, String nodeName)
    {
        AppEntry appentry = findAppByContextId(app.getContextId());
        if (appentry == null)
        {
            appentry = findAppByOriginId(app.getOriginId());
            if (appentry == null)
            {
                throw new IllegalStateException("App not being tracked by Deployment Manager: " + app);
            }
        }
        requestAppGoal(appentry,nodeName);
    }

    /**
     * Move an {@link App} through the {@link AppLifeCycle} to the desired {@link Node}, executing each lifecycle step
     * in the process to reach the desired state.
     * 
     * @param appentry
     *            the internal appentry to move through the process
     * @param nodeName
     *            the name of the node to attain
     */
    private void requestAppGoal(AppEntry appentry, String nodeName)
    {
        Node destinationNode = lifecycle.getNodeByName(nodeName);
        // Compute lifecycle steps
        Path path = lifecycle.getPath(appentry.lifecyleNode,destinationNode);
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
                    Log.info("Executing Node: " + node);
                    lifecycle.runBindings(node,appentry.app,this);
                    appentry.setLifeCycleNode(node);
                }
            }
        }
        catch (Throwable t)
        {
            Log.warn("Unable to reach node goal: " + nodeName,t);
        }
    }

    /**
     * Move an {@link App} through the {@link AppLifeCycle} to the desired {@link Node}, executing each lifecycle step
     * in the process to reach the desired state.
     * 
     * @param appId
     *            the id of the app to move through the process
     * @param nodeName
     *            the name of the node to attain
     */
    public void requestAppGoal(String appId, String nodeName)
    {
        AppEntry appentry = findAppByContextId(appId);
        if (appentry == null)
        {
            appentry = findAppByOriginId(appId);
            if (appentry == null)
            {
                throw new IllegalStateException("App not being tracked by Deployment Manager: " + appId);
            }
        }
        requestAppGoal(appentry,nodeName);
    }

    /**
     * Set a contextAttribute that will be set for every Context deployed by this provider.
     * 
     * @param name
     * @param value
     */
    public void setContextAttribute(String name, Object value)
    {
        contextAttributes.setAttribute(name,value);
    }

    public void setConfigurationManager(ConfigurationManager configurationManager)
    {
        this.configurationManager = configurationManager;
    }

    public void setContextAttributes(AttributesMap contextAttributes)
    {
        this.contextAttributes = contextAttributes;
    }

    public void setContexts(ContextHandlerCollection contexts)
    {
        this.contexts = contexts;
    }

    public void setDefaultLifeCycleGoal(String defaultLifeCycleState)
    {
        this.defaultLifeCycleGoal = defaultLifeCycleState;
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
            Log.warn("Unable to start AppProvider",e);
        }
    }

    public void undeployAll()
    {
        Log.info("Undeploy (All) started");
        for (AppEntry appentry : apps)
        {
            Log.info("Undeploy: " + appentry);
            requestAppGoal(appentry,"undeployed");
        }
        Log.info("Undeploy (All) completed");
    }

    public boolean isUseStandardBindings()
    {
        return useStandardBindings;
    }

    public void setUseStandardBindings(boolean useStandardBindings)
    {
        this.useStandardBindings = useStandardBindings;
    }
}
