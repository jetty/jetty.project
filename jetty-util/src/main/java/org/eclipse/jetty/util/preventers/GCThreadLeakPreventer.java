//========================================================================
//Copyright 2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

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
