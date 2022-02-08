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

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.DebugListener;

/**
 * A Deployment binding that installs a DebugListener in all deployed contexts
 */
public class DebugListenerBinding extends DebugBinding
{
    final DebugListener _debugListener;

    public DebugListenerBinding()
    {
        this(new DebugListener());
    }

    public DebugListenerBinding(DebugListener debugListener)
    {
        super(new String[]{"deploying"});
        _debugListener = debugListener;
    }

    public DebugListener getDebugListener()
    {
        return _debugListener;
    }

    @Override
    public void processBinding(Node node, App app) throws Exception
    {
        app.getContextHandler().addEventListener(_debugListener);
    }
}
