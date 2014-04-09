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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * A Directory based {@link ConfigSource}.
 * <p>
 * Such as <code>${jetty.base}</code> or and <code>--extra-start-dir=[path]</code> sources.
 */
public class DirConfigSource implements ConfigSource
{
    private final String id;
    private final Path dir;
    private final List<String> args;

    /**
     * Create DirConfigSource with specified identifier and directory.
     * 
     * @param id
     *            the identifier for this {@link ConfigSource}
     * @param dir
     *            the directory for this {@link ConfigSource}
     * @param canHaveArgs
     *            true if this directory can have start.ini or start.d entries. (false for directories like ${jetty.home}, for example)
     * @throws IOException
     *             if unable to load the configuration args
     */
    public DirConfigSource(String id, Path dir, boolean canHaveArgs) throws IOException
    {
        this.id = id;
        this.dir = dir;

        this.args = new ArrayList<>();

        if (canHaveArgs)
        {
            Path iniFile = dir.resolve("start.ini");
            if (FS.canReadFile(iniFile))
            {
                StartIni ini = new StartIni(iniFile);
                args.addAll(ini.getArgs());
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
                        args.addAll(ini.getArgs());
                    }
                }
            }
        }
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
    public List<String> getArgs()
    {
        return args;
    }
}
