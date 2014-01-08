//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
 *
 */
public class GCThreadLeakPreventer extends AbstractLeakPreventer
{
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.util.preventers.AbstractLeakPreventer#prevent(java.lang.ClassLoader)
     */
    @Override
    public void prevent(ClassLoader loader)
    {
        try
        {
            Class clazz = Class.forName("sun.misc.GC");
            Method requestLatency = clazz.getMethod("requestLatency", new Class[] {long.class});
            requestLatency.invoke(null, Long.valueOf(Long.MAX_VALUE-1));
        }
        catch (ClassNotFoundException e)
        {
            LOG.ignore(e);
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

}
