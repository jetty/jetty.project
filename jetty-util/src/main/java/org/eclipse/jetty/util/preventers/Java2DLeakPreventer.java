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

/**
 * Java2DLeakPreventer
 *
 * Prevent pinning of webapp classloader by pre-loading sun.java2d.Disposer class
 * before webapp classloaders are created.
 * 
 * See https://issues.apache.org/bugzilla/show_bug.cgi?id=51687
 *
 */
public class Java2DLeakPreventer extends AbstractLeakPreventer
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
            Class.forName("sun.java2d.Disposer", true, loader);
        }
        catch (ClassNotFoundException e)
        {
            LOG.ignore(e);
        }
    }

}
