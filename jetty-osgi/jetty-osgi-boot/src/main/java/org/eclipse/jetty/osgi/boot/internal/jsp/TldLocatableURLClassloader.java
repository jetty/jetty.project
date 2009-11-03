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
import java.net.URLClassLoader;

/**
 * Tricky url classloader.
 * In fact we don't want a real URLClassLoader: we want OSGi to provide its classloader
 * and let it does.
 * But to let {@link org.apache.jasper.compiler.TldLocationsCache} find the core tlds inside the jars
 * we must be a URLClassLoader that returns an array of jars where tlds are stored
 * when the method getURLs is called.
 */
public class TldLocatableURLClassloader extends URLClassLoader
{

    private URL[] _jarsWithTldsInside;

    public TldLocatableURLClassloader(ClassLoader osgiClassLoader, URL[] jarsWithTldsInside)
    {
        super(new URL[] {},osgiClassLoader);
        _jarsWithTldsInside = jarsWithTldsInside;
    }

    /**
     * @return the jars that contains tlds so that TldLocationsCache or
     * TldScanner can find them.
     */
    @Override
    public URL[] getURLs()
    {
        return _jarsWithTldsInside;
    }
}
