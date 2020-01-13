//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

/**
 * <p>Implementations of this interface expose a lock object
 * via {@link #getLock()} so that callers can synchronize
 * externally on that lock:</p>
 * <pre>
 * if (iterator instanceof Synchronizable)
 * {
 *     Object element = null;
 *     synchronized (((Synchronizable)iterator).getLock())
 *     {
 *         if (iterator.hasNext())
 *             element = iterator.next();
 *     }
 * }
 * </pre>
 * <p>In the example above, the calls to {@code hasNext()} and
 * {@code next()} are performed "atomically".</p>
 */
public interface Synchronizable
{
    /**
     * @return the lock object to synchronize on
     */
    public Object getLock();
}
