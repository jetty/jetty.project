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

import java.awt.Toolkit;

/**
 * AWTLeakPreventer
 *
 * See https://issues.jboss.org/browse/AS7-3733
 * 
 * The java.awt.Toolkit class has a static field that is the default toolkit. 
 * Creating the default toolkit causes the creation of an EventQueue, which has a 
 * classloader field initialized by the thread context class loader. 
 *
 */
public class AWTLeakPreventer extends AbstractLeakPreventer
{
   
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.util.preventers.AbstractLeakPreventer#prevent(java.lang.ClassLoader)
     */
    @Override
    public void prevent(ClassLoader loader)
    {
        LOG.debug("Pinning classloader for java.awt.EventQueue using "+loader);
        Toolkit.getDefaultToolkit();
    }

}
