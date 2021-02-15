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
    Object getLock();
}
