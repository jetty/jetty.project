//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.thread;

import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * ThreadCreator
 *
 * Convenience class to ensure that a new Thread is created
 * inside a privileged block. This prevents the Thread constructor
 * from pinning the caller's context classloader. This happens
 * when the Thread constructor takes a snapshot of the current
 * calling context - which contains ProtectionDomains that may
 * reference the context classloader - and remembers it for the
 * lifetime of the Thread.
 */
class ThreadCreator
{
    interface Factory<T extends Thread>
    {
        T newThread();
    }
    
    static <T extends Thread> T create(Factory<T> maker)
    {
        return AccessController.doPrivileged(new PrivilegedAction<T>()
        {
            @Override
            public T run()
            {
                return maker.newThread();
            }
        });
    }
}
