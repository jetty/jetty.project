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

package org.eclipse.jetty.util;

public class Jetty
{
    public static final String VERSION;

    static
    {
        Package pkg = Jetty.class.getPackage();
        if (pkg != null &&
                "Eclipse.org - Jetty".equals(pkg.getImplementationVendor()) &&
                pkg.getImplementationVersion() != null)
            VERSION = pkg.getImplementationVersion();
        else
            VERSION = System.getProperty("jetty.version", "9.2.z-SNAPSHOT");
    }

    private Jetty()
    {
    }
}
