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

package org.eclipse.jetty.start.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.RawArgs;
import org.eclipse.jetty.start.UsageException;
import org.eclipse.jetty.start.Utils;

/**
 * Configuration Source representing the Command Line arguments.
 */
public class CommandLineConfigSource implements ConfigSource
{
    public static final String ORIGIN_INTERNAL_FALLBACK = "<internal-fallback>";
    public static final String ORIGIN_CMD_LINE = "<command-line>";
    public static final String ORIGIN_SYSTEM_PROPERTY = "<system-property>";

    private final RawArgs args;
    private final Props props;
    private final Path homePath;
    private final Path basePath;

    public CommandLineConfigSource(String[] rawargs)
    {
        this.args = new RawArgs();
        this.props = new Props();
        for (String arg : rawargs)
        {
            this.args.addArg(arg, ORIGIN_CMD_LINE);
            this.props.addPossibleProperty(arg, ORIGIN_CMD_LINE);
        }

        // Setup ${jetty.base} and ${jetty.home}
        this.homePath = findJettyHomePath().toAbsolutePath();
        this.basePath = findJettyBasePath().toAbsolutePath();

        // Update System Properties
        setSystemProperty(BaseHome.JETTY_HOME, homePath.toAbsolutePath().toString());
        setSystemProperty(BaseHome.JETTY_BASE, basePath.toAbsolutePath().toString());
    }

    private final Path findJettyBasePath()
    {
        // If a jetty property is defined, use it
        Prop prop = this.props.getProp(BaseHome.JETTY_BASE, false);
        if (prop != null && !Utils.isBlank(prop.value))
        {
            return FS.toPath(prop.value);
        }

        // If a system property is defined, use it
        String val = System.getProperty(BaseHome.JETTY_BASE);
        if (!Utils.isBlank(val))
        {
            setProperty(BaseHome.JETTY_BASE, val, ORIGIN_SYSTEM_PROPERTY);
            return FS.toPath(val);
        }

        // Lastly, fall back to base == ${user.dir}
        Path base = FS.toPath(this.props.getString("user.dir", "."));
        setProperty(BaseHome.JETTY_BASE, base.toString(), ORIGIN_INTERNAL_FALLBACK);
        return base;
    }

    private final Path findJettyHomePath()
    {
        // If a jetty property is defined, use it
        Prop prop = this.props.getProp(BaseHome.JETTY_HOME, false);
        if (prop != null && !Utils.isBlank(prop.value))
        {
            return FS.toPath(prop.value);
        }

        // If a system property is defined, use it
        String val = System.getProperty(BaseHome.JETTY_HOME);
        if (!Utils.isBlank(val))
        {
            setProperty(BaseHome.JETTY_HOME, val, ORIGIN_SYSTEM_PROPERTY);
            return FS.toPath(val);
        }

        // Attempt to find path relative to content in jetty's start.jar
        // based on lookup for the Main class (from jetty's start.jar)
        String classRef = "org/eclipse/jetty/start/Main.class";
        URL jarfile = this.getClass().getClassLoader().getResource(classRef);
        if (jarfile != null)
        {
            Matcher m = Pattern.compile("jar:(file:.*)!/" + classRef).matcher(jarfile.toString());
            if (m.matches())
            {
                // ${jetty.home} is relative to found BaseHome class
                try
                {
                    Path home = Paths.get(new URI(m.group(1))).getParent();
                    setProperty(BaseHome.JETTY_HOME, home.toString(), ORIGIN_INTERNAL_FALLBACK);
                    return home;
                }
                catch (URISyntaxException e)
                {
                    throw new UsageException(UsageException.ERR_UNKNOWN, e);
                }
            }
        }

        // Lastly, fall back to ${user.dir} default
        Path home = FS.toPath(System.getProperty("user.dir", "."));
        setProperty(BaseHome.JETTY_HOME, home.toString(), "<user.dir>");
        return home;
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
    public RawArgs getArgs()
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
        return ORIGIN_CMD_LINE;
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
        result = (prime * result) + ((args == null) ? 0 : args.hashCode());
        return result;
    }

    public void setProperty(String key, String value, String origin)
    {
        this.props.setProperty(key, value, origin);
    }

    public void setSystemProperty(String key, String value)
    {
        this.props.setSystemProperty(key, value);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s,args.length=%d]", this.getClass().getSimpleName(), getId(), getArgs().size());
    }
}
