//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
                System.out.printf("System %s=%s%n", name, props.getProperty(name));
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
                            System.out.printf("%s %s=%s%n", propFile.getName(), name, aprops.getProperty(name));
                        }
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }
}
