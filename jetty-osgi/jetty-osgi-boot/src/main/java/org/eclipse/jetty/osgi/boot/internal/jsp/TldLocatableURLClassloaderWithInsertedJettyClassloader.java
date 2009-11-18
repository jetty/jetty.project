// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot.internal.jsp;

import java.net.URL;

/**
 * Add a classloader to the org.apache.jasper.compiler.TldLocatableURLClassloader.
 * Hopefuly not necessary: still experimenting.
 * @see TldLocatableURLClassloader
 */
public class TldLocatableURLClassloaderWithInsertedJettyClassloader extends TldLocatableURLClassloader
{

    private ClassLoader _internalClassLoader;

    public TldLocatableURLClassloaderWithInsertedJettyClassloader(ClassLoader osgiClassLoader,
            ClassLoader internalClassLoader, URL[] jarsWithTldsInside)
    {
        super(osgiClassLoader, jarsWithTldsInside);
        _internalClassLoader = internalClassLoader;
    }

    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        try
        {
            return super.findClass(name);
        }
        catch (ClassNotFoundException cne)
        {
            if (_internalClassLoader != null)
            {
                return _internalClassLoader.loadClass(name);
            }
            else
            {
                throw cne;
            }
        }
    }
}
