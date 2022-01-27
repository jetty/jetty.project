//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.LinkedList;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.graph.Node;

/**
 * Provides a way of forcing the ordered execution of bindings within
 * a declared binding target.
 */
public class OrderedGroupBinding implements AppLifeCycle.Binding
{
    private String[] _bindingTargets;

    private LinkedList<AppLifeCycle.Binding> _orderedBindings;

    public OrderedGroupBinding(String[] bindingTargets)
    {
        _bindingTargets = bindingTargets;
    }

    public void addBinding(AppLifeCycle.Binding binding)
    {
        if (_orderedBindings == null)
        {
            _orderedBindings = new LinkedList<AppLifeCycle.Binding>();
        }

        _orderedBindings.add(binding);
    }

    public void addBindings(AppLifeCycle.Binding[] bindings)
    {
        if (_orderedBindings == null)
        {
            _orderedBindings = new LinkedList<AppLifeCycle.Binding>();
        }

        for (AppLifeCycle.Binding binding : bindings)
        {
            _orderedBindings.add(binding);
        }
    }

    @Override
    public String[] getBindingTargets()
    {
        return _bindingTargets;
    }

    @Override
    public void processBinding(Node node, App app) throws Exception
    {
        for (AppLifeCycle.Binding binding : _orderedBindings)
        {
            binding.processBinding(node, app);
        }
    }
}
