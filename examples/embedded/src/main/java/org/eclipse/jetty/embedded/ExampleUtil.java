//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.embedded;

import org.eclipse.jetty.util.StringUtil;

public class ExampleUtil
{
    /**
     * Get a port, possibly configured from Command line or System property.
     *
     * @param args the command line arguments
     * @param propertyName the property name
     * @param defValue the default value
     * @return the configured port
     */
    public static int getPort(String[] args, String propertyName, int defValue)
    {
        for (String arg : args)
        {
            if (arg.startsWith(propertyName + "="))
            {
                String value = arg.substring(propertyName.length() + 2);
                int port = toInt(value);
                if (isValidPort(port))
                    return port;
            }
        }

        String value = System.getProperty(propertyName);
        int port = toInt(value);
        if (isValidPort(port))
            return port;

        return defValue;
    }

    /**
     * Test if port is in the valid range to be used.
     *
     * @param port the port to test
     * @return true if valid
     */
    private static boolean isValidPort(int port)
    {
        return (port >= 0) && (port <= 65535);
    }

    /**
     * Parse an int, ignoring any {@link NumberFormatException}
     *
     * @param value the string value to parse
     * @return the int (if parsed), or -1 if not parsed.
     */
    private static int toInt(String value)
    {
        if (StringUtil.isBlank(value))
            return -1;

        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException ignored)
        {
            // ignored
            return -1;
        }
    }
}
