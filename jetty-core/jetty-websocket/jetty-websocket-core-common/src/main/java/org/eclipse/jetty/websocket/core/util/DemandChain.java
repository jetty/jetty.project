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

package org.eclipse.jetty.websocket.core.util;

import org.eclipse.jetty.websocket.core.Extension;
import org.eclipse.jetty.websocket.core.ExtensionStack;

/**
 * This is extended by an {@link Extension} so it can intercept demand calls.
 * Demand is called by the application and the call is forwarded through the {@link ExtensionStack}
 * for every {@link Extension} which implements this interface.
 */
public interface DemandChain
{
    void demand();

    default void setNextDemand(DemandChain nextDemand)
    {
    }
}
