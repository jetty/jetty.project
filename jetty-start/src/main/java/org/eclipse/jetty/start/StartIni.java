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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;

import org.eclipse.jetty.start.Props.Prop;

/**
 * Simple Start .INI handler
 */
public class StartIni extends TextFile
{
    private Path basedir;

    public StartIni(Path file) throws IOException
    {
        super(file);
    }

    @Override
    public void addUniqueLine(String line)
    {
        if (line.startsWith("--module="))
        {
            int idx = line.indexOf('=');
            String value = line.substring(idx + 1);
            for (String part : value.split(","))
            {
                super.addUniqueLine("--module=" + expandBaseDir(part));
            }
        }
        else
        {
            super.addUniqueLine(expandBaseDir(line));
        }
    }

    private String expandBaseDir(String line)
    {
        if (line == null)
        {
            return line;
        }

        return line.replace("${start.basedir}", basedir.toString());
    }

    @Override
    public void init()
    {
        try
        {
            basedir = getFile().getParent().toRealPath();
        }
        catch (IOException e)
        {
            basedir = getFile().getParent().normalize().toAbsolutePath();
        }
    }

    public Path getBaseDir()
    {
        return basedir;
    }

    public void update(BaseHome baseHome, Props props) throws IOException
    {
        String update = getFile().getFileName().toString();
        update = update.substring(0, update.lastIndexOf("."));
        String source = baseHome.toShortForm(getFile());

        PrintWriter writer = null;

        try
        {
            for (String line : getAllLines())
            {
                Matcher m = Module.SET_PROPERTY.matcher(line);
                if (m.matches() && m.groupCount() == 3)
                {
                    String name = m.group(2);
                    String value = m.group(3);
                    Prop p = props.getProp(name);

                    if (p != null && (p.source == null || !p.source.endsWith("?=")) && ("#".equals(m.group(1)) || !value.equals(p.value)))
                    {
                        if (writer == null)
                        {
                            writer = new PrintWriter(Files.newBufferedWriter(getFile(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE));
                            for (String l : getAllLines())
                            {
                                if (line.equals(l))
                                    break;
                                writer.println(l);
                            }
                        }

                        StartLog.info("%-15s property updated %s=%s", update, name, p.value);
                        writer.printf("%s=%s%n", name, p.value);
                    }
                    else if (writer != null)
                    {
                        writer.println(line);
                    }
                }
                else if (writer != null)
                {
                    writer.println(line);
                }
            }
        }
        finally
        {
            if (writer != null)
            {
                StartLog.info("%-15s updated %s", update, source);
                writer.close();
            }
        }
    }
}
