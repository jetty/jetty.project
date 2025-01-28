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

import org.eclipse.jetty.server.Server;

/**
 * A component that is responsible for driving the add/remove of {@link App}
 * to the DeploymentManager via the provided {@link Manager} interface.
 */
public interface AppProvider
{
    /**
     * Deployment Manager specific methods that are available to the AppProvider.
     */
    interface Manager
    {
        Server getServer();

        default void addApp(App app)
        {
            addApp(app, AppLifeCycle.STARTED);
        }

        void addApp(App app, String goalName);

        void requestAppGoal(App app, String goalName);

        void removeApp(App app);
    }

    /**
     * The Manager to use for manipulating Apps for this provider.
     *
     * @param manager the manager interface.
     */
    void setManager(Manager manager);
}
