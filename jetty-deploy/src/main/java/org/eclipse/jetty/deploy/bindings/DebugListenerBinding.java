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
