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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.PathMatchers;
import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.StartIni;

/**
 * A Directory based {@link ConfigSource}.
 * <p>
 * Such as <code>${jetty.base}</code> or and <code>--extra-start-dir=[path]</code> sources.
 */
public class DirConfigSource implements ConfigSource
{
    private final String id;
    private final Path dir;
    private final int weight;
    private final List<String> args;
    private final Props props;

    /**
     * Create DirConfigSource with specified identifier and directory.
     * 
     * @param id
     *            the identifier for this {@link ConfigSource}
     * @param dir
     *            the directory for this {@link ConfigSource}
     * @param weight
     *            the configuration weight (used for search order)
     * @param canHaveArgs
     *            true if this directory can have start.ini or start.d entries. (false for directories like ${jetty.home}, for example)
     * @throws IOException
     *             if unable to load the configuration args
     */
    public DirConfigSource(String id, Path dir, int weight, boolean canHaveArgs) throws IOException
    {
        this.id = id;
        this.dir = dir;
        this.weight = weight;
        this.props = new Props();

        this.args = new ArrayList<>();

        if (canHaveArgs)
        {
            Path iniFile = dir.resolve("start.ini");
            if (FS.canReadFile(iniFile))
            {
                StartIni ini = new StartIni(iniFile);
                args.addAll(ini.getLines());
                this.props.addAllProperties(ini.getLines(),iniFile.toString());
            }

            Path startDdir = dir.resolve("start.d");

            if (FS.canReadDirectory(startDdir))
            {
                DirectoryStream.Filter<Path> filter = new DirectoryStream.Filter<Path>()
                {
                    PathMatcher iniMatcher = PathMatchers.getMatcher("glob:**/start.d/*.ini");

                    @Override
                    public boolean accept(Path entry) throws IOException
                    {
                        return iniMatcher.matches(entry);
                    }
                };

                for (Path diniFile : Files.newDirectoryStream(startDdir,filter))
                {
                    if (FS.canReadFile(diniFile))
                    {
                        StartIni ini = new StartIni(diniFile);
                        args.addAll(ini.getLines());
                        this.props.addAllProperties(ini.getLines(),diniFile.toString());
                    }
                }
            }
        }
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((dir == null)?0:dir.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DirConfigSource other = (DirConfigSource)obj;
        if (dir == null)
        {
            if (other.dir != null)
                return false;
        }
        else if (!dir.equals(other.dir))
            return false;
        return true;
    }

    @Override
    public List<String> getArgs()
    {
        return args;
    }

    public Path getDir()
    {
        return dir;
    }

    @Override
    public String getId()
    {
        return id;
    }

    @Override
    public int getWeight()
    {
        return weight;
    }

    @Override
    public Props getProps()
    {
        return props;
    }

    @Override
    public String getProperty(String key)
    {
        Prop prop = props.getProp(key,false);
        if (prop == null)
        {
            return null;
        }
        return prop.value;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s,%s,args.length=%d]",this.getClass().getSimpleName(),id,dir,getArgs().size());
    }
}
