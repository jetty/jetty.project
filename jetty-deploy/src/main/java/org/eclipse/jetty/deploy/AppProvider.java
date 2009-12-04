// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy;

import org.eclipse.jetty.util.component.LifeCycle;

/**
 * Object responsible for providing {@link App}s to the {@link DeploymentManager}
 */
public interface AppProvider extends LifeCycle
{
    /**
     * Set the Deployment Manager
     * 
     * @param deploymentManager
     * @throws IllegalStateException
     *             if the provider {@link #isRunning()}.
     */
    void setDeploymentManager(DeploymentManager deploymentManager);
}
