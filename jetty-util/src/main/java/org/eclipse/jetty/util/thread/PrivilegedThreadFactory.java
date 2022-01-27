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

package org.eclipse.jetty.util.thread;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.function.Supplier;

/**
 * Convenience class to ensure that a new Thread is created
 * inside a privileged block. 
 * 
 * This prevents the Thread constructor
 * from pinning the caller's context classloader. This happens
 * when the Thread constructor takes a snapshot of the current
 * calling context - which contains ProtectionDomains that may
 * reference the context classloader - and remembers it for the
 * lifetime of the Thread.
 */
class PrivilegedThreadFactory
{
    /**
     * Use a Supplier to make a new thread, calling it within
     * a privileged block to prevent classloader pinning.
     * 
     * @param newThreadSupplier a Supplier to create a fresh thread
     * @return a new thread, protected from classloader pinning.
     */
    static <T extends Thread> T newThread(Supplier<T> newThreadSupplier)
    {
        return AccessController.doPrivileged(new PrivilegedAction<T>()
        {
            @Override
            public T run()
            {
                return newThreadSupplier.get();
            }
        });
    }
}
