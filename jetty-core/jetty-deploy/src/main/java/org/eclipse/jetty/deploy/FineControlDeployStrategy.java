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

import org.eclipse.jetty.deploy.internal.DeploymentGraph;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FineControlDeployStrategy implements DeployStrategy
{
    private static final Logger LOG = LoggerFactory.getLogger(SimpleDeployStrategy.class);
    private DeploymentManager deploymentManager;
    private String goalCreatedRunning = DeploymentGraph.STARTED;
    private String goalCreatedStarted = DeploymentGraph.DEPLOYED;
    private String goalRemoved = DeploymentGraph.UNDEPLOYED;
    private String goalStopStracking = goalRemoved;

    public FineControlDeployStrategy(DeploymentManager deploymentManager)
    {
        this.deploymentManager = deploymentManager;
    }

    @Override
    public void onContextCreated(ContextHandler context)
    {
        DeploymentManager.TrackedContext trackedContext = deploymentManager.startTracking(context);
        if (deploymentManager.getContexts().isRunning())
        {
            // Only move to STARTED state if the ContextHandlerCollection itself is started.
            deploymentManager.requestContextHandlerGoal(trackedContext, goalCreatedRunning);
        }
        else
        {
            // Otherwise, just make sure it reaches the deployed state.
            deploymentManager.requestContextHandlerGoal(trackedContext, goalCreatedStarted);
        }
    }

    @Override
    public void onContextRemoved(ContextHandler context)
    {
        move(context, goalRemoved);
        if (goalStopStracking.equalsIgnoreCase(goalRemoved))
            stopTracking(context);
    }

    public void move(ContextHandler context, String destinationGoal)
    {
        DeploymentManager.TrackedContext trackedContext = deploymentManager.findTrackedContext(context);
        if (trackedContext != null)
            deploymentManager.requestContextHandlerGoal(trackedContext, destinationGoal);
    }

    public void stopTracking(ContextHandler context)
    {
        DeploymentManager.TrackedContext trackedContext = deploymentManager.findTrackedContext(context);
        if (trackedContext != null)
            deploymentManager.stopTracking(trackedContext);
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
