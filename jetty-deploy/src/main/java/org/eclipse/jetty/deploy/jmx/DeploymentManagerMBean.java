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

package org.eclipse.jetty.deploy.jmx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;

@SuppressWarnings("unused")
@ManagedObject("MBean Wrapper for DeploymentManager")
public class DeploymentManagerMBean extends ObjectMBean
{
    private final DeploymentManager _manager;

    public DeploymentManagerMBean(Object managedObject)
    {
        super(managedObject);
        _manager = (DeploymentManager)managedObject;
    }

    @ManagedAttribute(value = "list apps being tracked")
    public Collection<String> getApps()
    {
        List<String> ret = new ArrayList<>();
        for (DeploymentManager.AppEntry entry : _manager.getAppEntries())
        {
            ret.add(toRef(entry.getApp()));
        }
        return ret;
    }

    @ManagedOperation(value = "list nodes that are tracked by DeploymentManager", impact = "INFO")
    public Collection<String> getNodes()
    {
        return _manager.getNodes().stream().map(Node::getName).collect(Collectors.toList());
    }

    @ManagedOperation(value = "list apps that are located at specified App LifeCycle nodes", impact = "ACTION")
    public Collection<String> getApps(@Name("nodeName") String nodeName)
    {
        Node node = _manager.getLifeCycle().getNodeByName(nodeName);
        if (node == null)
        {
            throw new IllegalArgumentException("Unable to find node [" + nodeName + "]");
        }

        List<String> ret = new ArrayList<>();
        for (DeploymentManager.AppEntry entry : _manager.getAppEntries())
        {
            if (node.equals(entry.getLifecyleNode()))
            {
                ret.add(toRef(entry.getApp()));
            }
        }
        return ret;
    }

    private String toRef(App app)
    {
        return String.format("originId=%s,contextPath=%s,appProvider=%s", app.getContextPath(), app.getOriginId(), app.getAppProvider().getClass().getName());
    }

    public Collection<ContextHandler> getContexts() throws Exception
    {
        List<ContextHandler> apps = new ArrayList<ContextHandler>();
        for (App app : _manager.getApps())
        {
            apps.add(app.getContextHandler());
        }
        return apps;
    }

    @ManagedAttribute("Registered AppProviders")
    public List<String> getAppProviders()
    {
        return _manager.getAppProviders().stream().map(String::valueOf).collect(Collectors.toList());
    }

    public void requestAppGoal(String appId, String nodeName)
    {
        _manager.requestAppGoal(appId, nodeName);
    }
}
