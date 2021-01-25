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

package org.eclipse.jetty.start.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.NaturalSort;
import org.eclipse.jetty.start.PathMatchers;
import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.RawArgs;
import org.eclipse.jetty.start.StartIni;
import org.eclipse.jetty.start.StartLog;
import org.eclipse.jetty.start.UsageException;

import static org.eclipse.jetty.start.UsageException.ERR_BAD_ARG;

/**
 * A Directory based {@link ConfigSource}.
 * <p>
 * Such as <code>${jetty.base}</code> or and <code>--include-jetty-dir=[path]</code> sources.
 */
public class DirConfigSource implements ConfigSource
{
    private static final List<String> BANNED_ARGS;

    static
    {
        // Arguments that are not allowed to be in start.ini or start.d/{name}.ini files
        BANNED_ARGS = new ArrayList<>();
        BANNED_ARGS.add("--help");
        BANNED_ARGS.add("-?");
        BANNED_ARGS.add("--stop");
        BANNED_ARGS.add("--dry-run");
        BANNED_ARGS.add("--exec-print");
        BANNED_ARGS.add("--list-config");
        BANNED_ARGS.add("--list-classpath");
        BANNED_ARGS.add("--list-modules");
        BANNED_ARGS.add("--write-module-graph");
        BANNED_ARGS.add("--version");
        BANNED_ARGS.add("-v");
        BANNED_ARGS.add("--download");
        BANNED_ARGS.add("--create-files");
        BANNED_ARGS.add("--add-to-startd");
        BANNED_ARGS.add("--add-to-start");
    }

    private final String id;
    private final Path dir;
    private final int weight;
    private final RawArgs args;
    private final Props props;
    private final Set<StartIni> startInis = new HashSet<>();

    /**
     * Create DirConfigSource with specified identifier and directory.
     *
     * @param id the identifier for this {@link ConfigSource}
     * @param dir the directory for this {@link ConfigSource}
     * @param weight the configuration weight (used for search order)
     * @param canHaveArgs true if this directory can have start.ini or start.d entries. (false for directories like ${jetty.home}, for example)
     * @throws IOException if unable to load the configuration args
     */
    public DirConfigSource(String id, Path dir, int weight, boolean canHaveArgs) throws IOException
    {
        this.id = id;
        this.dir = dir.toAbsolutePath();
        this.weight = weight;
        this.props = new Props();

        this.args = new RawArgs();

        if (canHaveArgs)
        {
            Path iniFile = dir.resolve("start.ini").normalize().toAbsolutePath();

            try
            {
                iniFile = iniFile.toRealPath();
                if (FS.canReadFile(iniFile))
                {
                    StartIni ini = new StartIni(iniFile);
                    startInis.add(ini);
                    args.addAll(ini.getLines(), iniFile);
                    parseAllArgs(ini.getLines(), iniFile.toString());
                }
            }
            catch (NoSuchFileException ignore)
            {
                // ignore
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

                List<Path> paths = new ArrayList<>();

                for (Path diniFile : Files.newDirectoryStream(startDdir, filter))
                {
                    if (FS.canReadFile(diniFile))
                    {
                        paths.add(diniFile);
                    }
                }

                Collections.sort(paths, new NaturalSort.Paths());

                for (Path diniFile : paths)
                {
                    StartLog.debug("Reading %s/start.d/%s - %s", id, diniFile.getFileName(), diniFile);
                    StartIni ini = new StartIni(diniFile);
                    startInis.add(ini);
                    args.addAll(ini.getLines(), diniFile);
                    parseAllArgs(ini.getLines(), diniFile.toString());
                }
            }
        }
    }

    @Override
    public Set<StartIni> getStartInis()
    {
        return startInis;
    }

    private void parseAllArgs(List<String> lines, String origin)
    {
        for (String line : lines)
        {
            String arg = line;
            int idx = line.indexOf('=');
            if (idx > 0)
            {
                arg = line.substring(0, idx);
            }
            if (BANNED_ARGS.contains(arg))
            {
                throw new UsageException(ERR_BAD_ARG, "%s not allowed in %s", arg, origin);
            }
            this.props.addPossibleProperty(line, origin);
        }
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
        DirConfigSource other = (DirConfigSource)obj;
        if (dir == null)
        {
            return other.dir == null;
        }
        else
            return dir.equals(other.dir);
    }

    @Override
    public RawArgs getArgs()
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
    public String getProperty(String key)
    {
        Prop prop = props.getProp(key, false);
        if (prop == null)
        {
            return null;
        }
        return prop.value;
    }

    @Override
    public Props getProps()
    {
        return props;
    }

    @Override
    public int getWeight()
    {
        return weight;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((dir == null) ? 0 : dir.hashCode());
        return result;
    }

    public boolean isPropertyBased()
    {
        return id.contains("${");
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s,%s,args.length=%d]", this.getClass().getSimpleName(), id, dir, getArgs().size());
    }
}
