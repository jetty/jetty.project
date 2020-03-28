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

package org.eclipse.jetty.util.preventers;

import java.lang.reflect.Method;

/**
 * GCThreadLeakPreventer
 *
 * Prevents a call to sun.misc.GC.requestLatency pinning a webapp classloader
 * by calling it with a non-webapp classloader. The problem appears to be that
 * when this method is called, a daemon thread is created which takes the
 * context classloader. A known caller of this method is the RMI impl. See
 * http://stackoverflow.com/questions/6626680/does-java-garbage-collection-log-entry-full-gc-system-mean-some-class-called
 *
 * This preventer will start the thread with the longest possible interval, although
 * subsequent calls can vary that. Recommend to only use this class if you're doing
 * RMI.
 *
 * Inspired by Tomcat JreMemoryLeakPrevention.
 */
public class GCThreadLeakPreventer extends AbstractLeakPreventer
{

    @Override
    public void prevent(ClassLoader loader)
    {
        try
        {
            Class<?> clazz = Class.forName("sun.misc.GC");
            Method requestLatency = clazz.getMethod("requestLatency", new Class[]{long.class});
            requestLatency.invoke(null, (Long)(Long.MAX_VALUE - 1));
        }
        catch (ClassNotFoundException e)
        {
            LOG.trace("IGNORED", e);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to set GC latency", e);
        }
    }
}
