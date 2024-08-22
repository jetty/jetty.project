//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Collections;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Predicate;

public class PropertyDump
{
    public static void main(String[] args)
    {
        System.out.printf("PropertyDump%n");

        Predicate<String> nameSelectionPredicate =
            (name) ->
                name.startsWith("test.") ||
                    name.startsWith("jetty.");

        // As System Properties
        Properties props = System.getProperties();
        props.stringPropertyNames()
            .stream()
            .filter(nameSelectionPredicate)
            .sorted()
            .forEach((name) ->
                System.out.printf("System %s=%s%n", name, props.getProperty(name)));

        // As File Argument
        for (String arg : args)
        {
            System.out.printf("Arg [%s]%n", arg);
            if (arg.endsWith(".properties"))
            {
                Properties aprops = new Properties();
                File propFile = new File(arg);
                try (FileReader reader = new FileReader(propFile))
                {
                    aprops.load(reader);
                    Collections.list(aprops.propertyNames())
                        .stream()
                        .map(Objects::toString)
                        .filter(nameSelectionPredicate)
                        .sorted()
                        .forEach((name) ->
                            System.out.printf("%s %s=%s%n", propFile.getName(), name, aprops.getProperty(name)));
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }
}
