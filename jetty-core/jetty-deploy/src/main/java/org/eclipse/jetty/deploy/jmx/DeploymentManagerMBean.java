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

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

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

    @ManagedOperation(value = "list ContextHandlers that are located at specified ContextHandlerLifeCycle nodes", impact = "ACTION")
    public void requestContextHandlerGoal(String mbeanRef, String nodeName)
    {
        ContextHandler contextHandler = findContextHandlerByMBeanRef(mbeanRef);
        _manager.move(contextHandler, nodeName);
    }

    private ContextHandler findContextHandlerByMBeanRef(String mbeanRef)
    {
        // TODO: figure out how to do this
        return null;
    }
}
