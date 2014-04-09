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

package org.eclipse.jetty.start;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration Source representing the Command Line arguments.
 */
public class CommandLineConfigSource implements ConfigSource
{
    public static final String CMD_LINE_SOURCE = "<command-line>";

    private final List<String> args;

    public CommandLineConfigSource(String rawargs[])
    {
        args = Arrays.asList(rawargs);
    }

    @Override
    public String getId()
    {
        return CMD_LINE_SOURCE;
    }

    @Override
    public List<String> getArgs()
    {
        return args;
    }
}
