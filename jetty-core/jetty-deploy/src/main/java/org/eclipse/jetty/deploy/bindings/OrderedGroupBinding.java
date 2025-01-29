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

package org.eclipse.jetty.deploy.bindings;

import java.util.Arrays;
import java.util.LinkedList;

import org.eclipse.jetty.deploy.ContextHandlerLifeCycle;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * Provides a way of forcing the ordered execution of bindings within
 * a declared binding target.
 */
public class OrderedGroupBinding implements ContextHandlerLifeCycle.Binding
{
    private String[] _bindingTargets;

    private LinkedList<ContextHandlerLifeCycle.Binding> _orderedBindings;

    public OrderedGroupBinding(String[] bindingTargets)
    {
        _bindingTargets = bindingTargets;
    }

    public void addBinding(ContextHandlerLifeCycle.Binding binding)
    {
        if (_orderedBindings == null)
        {
            _orderedBindings = new LinkedList<>();
        }

        _orderedBindings.add(binding);
    }

    public void addBindings(ContextHandlerLifeCycle.Binding[] bindings)
    {
        if (_orderedBindings == null)
        {
            _orderedBindings = new LinkedList<>();
        }

        _orderedBindings.addAll(Arrays.asList(bindings));
    }

    @Override
    public String[] getBindingTargets()
    {
        return _bindingTargets;
    }

    @Override
    public void processBinding(DeploymentManager deploymentManager, Node node, ContextHandler contextHandler) throws Exception
    {
        for (ContextHandlerLifeCycle.Binding binding : _orderedBindings)
        {
            binding.processBinding(deploymentManager, node, contextHandler);
        }
    }
}
