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

package org.eclipse.jetty.start.config;

import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.start.Props;

/**
 * Configuration Source representing the Command Line arguments.
 */
public class CommandLineConfigSource implements ConfigSource
{
    public static final String CMD_LINE_SOURCE = "<command-line>";

    private final List<String> args;
    private final Props props;

    public CommandLineConfigSource(String rawargs[])
    {
        this.args = Arrays.asList(rawargs);
        this.props = new Props();
        this.props.addAllProperties(args, CMD_LINE_SOURCE);
    }
    
    @Override
    public Props getProps()
    {
        return props;
    }
    
    @Override
    public String getProperty(String key)
    {
        return props.getString(key);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        CommandLineConfigSource other = (CommandLineConfigSource)obj;
        if (args == null)
        {
            if (other.args != null)
            {
                return false;
            }
        }
        else if (!args.equals(other.args))
        {
            return false;
        }
        return true;
    }

    @Override
    public List<String> getArgs()
    {
        return args;
    }

    @Override
    public String getId()
    {
        return CMD_LINE_SOURCE;
    }

    @Override
    public int getWeight()
    {
        return -1; // default value for command line
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((args == null)?0:args.hashCode());
        return result;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s,args.length=%d]",this.getClass().getSimpleName(),getId(),getArgs().size());
    }
}
