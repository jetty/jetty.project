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

import org.eclipse.jetty.deploy.ContextHandlerLifeCycle;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;

public class StandardStarter implements ContextHandlerLifeCycle.Binding
{
    @Override
    public String[] getBindingTargets()
    {
        return new String[]{"starting"};
    }

    @Override
    public void processBinding(DeploymentManager deploymentManager, Node node, ContextHandler contextHandler) throws Exception
    {
        ContextHandlerCollection contexts = deploymentManager.getContexts();

        if (contexts.isStarted() && contextHandler.isStopped())
        {
            // start the handler manually
            contextHandler.start();

            // After starting let the context manage state
            contexts.manage(contextHandler);
        }
    }
}
