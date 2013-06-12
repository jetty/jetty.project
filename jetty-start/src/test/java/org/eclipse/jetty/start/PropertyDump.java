//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import java.util.Enumeration;
import java.util.Properties;

public class PropertyDump
{
    public static void main(String[] args)
    {
        Properties props = System.getProperties();
        Enumeration<?> names = props.propertyNames();
        while (names.hasMoreElements())
        {
            String name = (String)names.nextElement();
            // only interested in "test." prefixed properties
            if (name.startsWith("test."))
            {
                System.out.printf("%s=%s%n",name,props.getProperty(name));
            }
        }
        System.exit(0);
    }
}
