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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.Props;

/**
 * Configuration Source representing the Command Line arguments.
 */
public class CommandLineConfigSource implements ConfigSource
{
    public static final String CMD_LINE_SOURCE = "<command-line>";

    private final List<String> args;
    private final Props props;
    private final Path homePath;
    private final Path basePath;

    public CommandLineConfigSource(String rawargs[])
    {
        this.args = Arrays.asList(rawargs);
        this.props = new Props();
        for (String arg : args)
        {
            this.props.addPossibleProperty(arg,CMD_LINE_SOURCE);
        }

        Path home = FS.toOptionalPath(getProperty("jetty.home"));
        Path base = FS.toOptionalPath(getProperty("jetty.base"));

        if (home != null)
        {
            // logic if home is specified
            if (base == null)
            {
                base = home;
                setProperty("jetty.base",base.toString(),"<internal-fallback>");
            }
        }

        this.homePath = home;
        this.basePath = base;

        // Update System Properties
        setSystemProperty("jetty.home",homePath.toAbsolutePath().toString());
        setSystemProperty("jetty.base",basePath.toAbsolutePath().toString());
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

    public Path getBasePath()
    {
        return basePath;
    }

    public Path getHomePath()
    {
        return homePath;
    }

    @Override
    public String getId()
    {
        return CMD_LINE_SOURCE;
    }

    @Override
    public String getProperty(String key)
    {
        return props.getString(key);
    }

    @Override
    public Props getProps()
    {
        return props;
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

    public void setProperty(String key, String value, String origin)
    {
        this.props.setProperty(key,value,origin);
    }

    public void setSystemProperty(String key, String value)
    {
        this.props.setSystemProperty(key,value);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s,args.length=%d]",this.getClass().getSimpleName(),getId(),getArgs().size());
    }
}
