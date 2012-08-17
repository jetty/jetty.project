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

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * DOMLeakPreventer
 *
 * See http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6916498
 * 
 * Prevent the RuntimeException that is a static member of AbstractDOMParser
 * from pinning a webapp classloader by causing it to be set here by a non-webapp classloader.
 * 
 * Note that according to the bug report, a heap dump may not identify the GCRoot, making 
 * it difficult to identify the cause of the leak.
 *
 */
public class DOMLeakPreventer extends AbstractLeakPreventer
{
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.util.preventers.AbstractLeakPreventer#prevent(java.lang.ClassLoader)
     */
    @Override
    public void prevent(ClassLoader loader)
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try 
        {
            factory.newDocumentBuilder();
        } 
        catch (Exception e) 
        {
            LOG.warn(e);
        }

    }

}
