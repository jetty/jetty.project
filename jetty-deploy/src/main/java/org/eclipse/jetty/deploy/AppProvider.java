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

package org.eclipse.jetty.deploy;

import java.io.IOException;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * Object responsible for providing {@link App}s to the {@link DeploymentManager}
 */
public interface AppProvider extends LifeCycle
{
    /**
     * Set the Deployment Manager
     *
     * @param deploymentManager the deployment manager
     * @throws IllegalStateException if the provider {@link #isRunning()}.
     */
    void setDeploymentManager(DeploymentManager deploymentManager);

    /**
     * Create a ContextHandler for an App
     *
     * @param app The App
     * @return A ContextHandler
     * @throws IOException if unable to create context
     * @throws Exception if unable to create context
     */
    ContextHandler createContextHandler(App app) throws Exception;
}
