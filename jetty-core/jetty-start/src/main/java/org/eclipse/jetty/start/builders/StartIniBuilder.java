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

package org.eclipse.jetty.start.builders;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.start.BaseBuilder;
import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.Module;
import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.StartLog;

/**
 * Management of the <code>${jetty.base}/start.ini</code> based configuration.
 * <p>
 * Implementation of the <code>--add-module=[name]</code> command line behavior
 */
public class StartIniBuilder implements BaseBuilder.Config
{
    private final BaseHome baseHome;
    private final Path startIni;

    /* List of modules already present in start.ini */
    private Set<String> modulesPresent = new HashSet<>();

    /* List of properties (keys only) already present in start.ini */
    private Set<String> propsPresent = new HashSet<>();

    public StartIniBuilder(BaseBuilder baseBuilder) throws IOException
    {
        this.baseHome = baseBuilder.getBaseHome();
        this.startIni = baseHome.getBasePath("start.ini");

        if (Files.exists(startIni))
        {
            parseIni();
        }
    }

    private void parseIni() throws IOException
    {
        try (BufferedReader reader = Files.newBufferedReader(startIni, StandardCharsets.UTF_8))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                line = line.trim();
                if (line.startsWith("--module="))
                {
                    List<String> moduleNames = Props.getValues(line);
                    this.modulesPresent.addAll(moduleNames);
                }
                else if (!line.startsWith("-") && line.contains("="))
                {
                    String key = line.substring(0, line.indexOf('='));
                    this.propsPresent.add(key);
                }
            }
        }
    }

    @Override
    public String addModule(Module module, Props props) throws IOException
    {
        if (modulesPresent.contains(module.getName()))
        {
            StartLog.info("%-15s already initialised in %s", module.getName(), baseHome.toShortForm(startIni));
            // skip, already present
            return null;
        }

        if (module.isDynamic())
        {
            if (module.hasIniTemplate())
            {
                // warn
                StartLog.warn("%-15s not adding [ini-template] from dynamic module", module.getName());
            }
            return null;
        }

        if (module.hasIniTemplate() || !module.isTransitive())
        {
            // Append to start.ini
            try (BufferedWriter writer = Files.newBufferedWriter(startIni, StandardCharsets.UTF_8, StandardOpenOption.APPEND, StandardOpenOption.CREATE))
            {
                module.writeIniSection(writer, props);
            }
            return baseHome.toShortForm(startIni);
        }

        return null;
    }
}
