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

package org.eclipse.jetty.deploy.jmx;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;

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

    @ManagedAttribute(value = "list ContextHandlers being tracked")
    public Collection<String> getContextHandler()
    {
        return _manager.getContextHandlers()
            .stream()
            .map(Objects::toString)
            .toList();
    }

    @ManagedOperation(value = "list ContextHandlers that are located at specified ContextHandlerLifeCycle nodes", impact = "ACTION")
    public Collection<String> getContext(@Name("nodeName") String nodeName)
    {
        Node node = _manager.getLifeCycle().getNodeByName(nodeName);
        if (node == null)
        {
            throw new IllegalArgumentException("Unable to find node [" + nodeName + "]");
        }

        return _manager.getContextHandlers(node)
            .stream()
            .map(Objects::toString)
            .toList();
    }

    @ManagedOperation(value = "list nodes that are tracked by DeploymentManager", impact = "INFO")
    public Collection<String> getNodes()
    {
        return _manager.getNodes().stream().map(Node::getName).collect(Collectors.toList());
    }

    public Collection<ContextHandler> getContexts() throws Exception
    {
        return Collections.unmodifiableCollection(_manager.getContextHandlers());
    }

    @ManagedOperation(value = "list ContextHandlers that are located at specified ContextHandlerLifeCycle nodes", impact = "ACTION")
    public void requestContextHandlerGoal(String id, String nodeName)
    {
        _manager.requestContextHandlerGoal(id, nodeName);
    }
}
