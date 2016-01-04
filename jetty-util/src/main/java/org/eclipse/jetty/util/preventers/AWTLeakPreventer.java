//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
        if (LOG.isDebugEnabled())
            LOG.debug("Pinning classloader for java.awt.EventQueue using "+loader);
        Toolkit.getDefaultToolkit();
    }

}
