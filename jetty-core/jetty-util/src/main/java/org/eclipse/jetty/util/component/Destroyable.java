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

package org.eclipse.jetty.util.component;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

/**
 * <p>A Destroyable is an object which can be destroyed.</p>
 * <p>Typically a Destroyable is a {@link LifeCycle} component that can hold onto
 * resources over multiple start/stop cycles.   A call to destroy will release all
 * resources and will prevent any further start/stop cycles from being successful.</p>
 */
@ManagedObject
public interface Destroyable
{
    @ManagedOperation(value = "Destroys this component", impact = "ACTION")
    void destroy();
}
