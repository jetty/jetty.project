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
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;

public class StandardUndeployer implements AppLifeCycle.Binding
{
    @Override
    public String[] getBindingTargets()
    {
        return new String[]
            {"undeploying"};
    }

    @Override
    public void processBinding(Node node, App app) throws Exception
    {
        ContextHandlerCollection contexts = app.getDeploymentManager().getContexts();
        ContextHandler context = app.getContextHandler();
        Callback.Completable blocker = new Callback.Completable();
        contexts.undeployHandler(context, blocker);
        blocker.get();
        context.destroy();
    }
}
