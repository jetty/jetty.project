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
