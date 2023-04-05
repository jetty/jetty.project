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

package org.eclipse.jetty.util.thread;

import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.function.Supplier;

import org.eclipse.jetty.util.security.SecurityUtils;

/**
 * <p>Convenience {@link Thread} factory that ensure threads are
 * created without referencing any web application {@link ClassLoader}.</p>
 * <p>Up to Java 17, the {@code Thread} constructor was taking a
 * snapshot of the calling context, which may contain a {@link ProtectionDomain}
 * that references a web application {@code ClassLoader}
 * (for example if the creation of the {@code Thread} was triggered
 * by some operation performed by the web application).
 * The {@code Thread} might then be pooled and prevent the
 * web application {@code ClassLoader} to be garbage collected
 * when the web application is undeployed.
 * For this reason, {@code Thread}s must be created in a privileged
 * action, which restricts the calling context to just the caller
 * frame, not all the frames in the stack.</p>
 * <p>Since Java 18 and the removal of the Java security manager
 * and related classes by JEP 411, {@code Thread}s do not retain
 * the calling context, so there is no need to create them in a
 * privileged action.</p>
 */
class PrivilegedThreadFactory
{
    /**
     * <p>Creates a new {@link Thread} from the given {@link Supplier},
     * without retaining the calling context.</p>
     *
     * @param creator the action that creates the {@link Thread}
     * @return a new {@link Thread} without retaining the calling context
     */
    static <T extends Thread> T newThread(PrivilegedAction<T> creator)
    {
        return SecurityUtils.doPrivileged(creator);
    }

    private PrivilegedThreadFactory()
    {
    }
}
