//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
