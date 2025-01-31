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

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.DeploymentNodeBinding;
import org.eclipse.jetty.deploy.internal.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugBinding implements DeploymentNodeBinding
{
    private static final Logger LOG = LoggerFactory.getLogger(DebugBinding.class);

    final String[] _targets;

    public DebugBinding(String target)
    {
        _targets = new String[]{target};
    }

    public DebugBinding(final String... targets)
    {
        _targets = targets;
    }

    @Override
    public String[] getBindingTargets()
    {
        return _targets;
    }

    @Override
    public void processBinding(DeploymentManager deploymentManager, Node node, ContextHandler contextHandler) throws Exception
    {
        LOG.info("processBinding {} {}", node, contextHandler);
    }
}
