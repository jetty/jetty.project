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

package org.eclipse.jetty.util;

public class Jetty
{
    public static final String VERSION;
    public static final String POWERED_BY;
    public static final boolean STABLE;

    static
    {
        Package pkg = Jetty.class.getPackage();
        if (pkg != null &&
                "Eclipse.org - Jetty".equals(pkg.getImplementationVendor()) &&
                pkg.getImplementationVersion() != null)
            VERSION = pkg.getImplementationVersion();
        else
            VERSION = System.getProperty("jetty.version", "9.4.z-SNAPSHOT");

        POWERED_BY="<a href=\"http://eclipse.org/jetty\">Powered by Jetty:// "+VERSION+"</a>";

        // Show warning when RC# or M# is in version string
        STABLE = !VERSION.matches("^.*\\.(RC|M)[0-9]+$");
    }

    private Jetty()
    {
    }
    
}
