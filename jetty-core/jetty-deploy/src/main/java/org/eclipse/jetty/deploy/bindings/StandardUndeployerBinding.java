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

import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.deploy.DeploymentNodeBinding;
import org.eclipse.jetty.deploy.GoalDeployer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Blocker;

public class StandardUndeployerBinding implements DeploymentNodeBinding
{
    @Override
    public Collection<String> getBindingTargets()
    {
        return List.of("undeploying");
    }

    @Override
    public void processBinding(GoalDeployer goalDeployer, String nodeName, ContextHandler contextHandler) throws Exception
    {
        ContextHandlerCollection contexts = goalDeployer.getContexts();
        try (Blocker.Callback blocker = Blocker.callback())
        {
            contexts.undeployHandler(contextHandler, blocker);
            blocker.block();
        }
        contextHandler.destroy();
    }
}
