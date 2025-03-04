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

package org.eclipse.jetty.deploy;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleDeployStrategy implements DeployStrategy
{
    private static final Logger LOG = LoggerFactory.getLogger(SimpleDeployStrategy.class);
    private DeploymentManager deploymentManager;

    public SimpleDeployStrategy(DeploymentManager deploymentManager)
    {
        this.deploymentManager = deploymentManager;
    }

    @Override
    public void onContextCreated(ContextHandler context)
    {
        deploymentManager.deploy(context);
    }

    @Override
    public void onContextRemoved(ContextHandler context)
    {
        deploymentManager.undeploy(context);
    }

    @Override
    public void onContextFailed(Throwable cause)
    {
        if (deploymentManager.isStarting())
            deploymentManager.reportStartupFailure(cause);
        else
            LOG.warn("Context Failure", cause);
    }
}
