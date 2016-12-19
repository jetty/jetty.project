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

package org.eclipse.jetty.start;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Properties;

public class PropertyDump
{
    public static void main(String[] args)
    {
        System.out.printf("PropertyDump%n");
        // As System Properties
        Properties props = System.getProperties();
        Enumeration<?> names = props.propertyNames();
        while (names.hasMoreElements())
        {
            String name = (String)names.nextElement();
            // only interested in "test." prefixed properties
            if (name.startsWith("test."))
            {
                System.out.printf("System %s=%s%n",name,props.getProperty(name));
            }
        }

        // As File Argument
        for (String arg : args)
        {
            if (arg.endsWith(".properties"))
            {
                Properties aprops = new Properties();
                File propFile = new File(arg);
                try (FileReader reader = new FileReader(propFile))
                {
                    aprops.load(reader);
                    Enumeration<?> anames = aprops.propertyNames();
                    while (anames.hasMoreElements())
                    {
                        String name = (String)anames.nextElement();
                        if (name.startsWith("test."))
                        {
                            System.out.printf("%s %s=%s%n",propFile.getName(),name,aprops.getProperty(name));
                        }
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

        System.exit(0);
    }
}
