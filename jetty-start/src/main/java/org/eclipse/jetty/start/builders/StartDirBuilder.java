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

package org.eclipse.jetty.start.builders;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.start.BaseBuilder;
import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.Module;
import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.StartLog;

/**
 * Management of the <code>${jetty.base}/start.d/</code> based configuration.
 * <p>
 * Implementation of the <code>--add-modules=[name]</code> command line behavior
 */
public class StartDirBuilder implements BaseBuilder.Config
{
    private final BaseHome baseHome;
    private final Path startDir;

    public StartDirBuilder(BaseBuilder baseBuilder) throws IOException
    {
        this.baseHome = baseBuilder.getBaseHome();
        this.startDir = baseHome.getBasePath("start.d");
        if (FS.ensureDirectoryExists(startDir))
            StartLog.info("mkdir " + baseHome.toShortForm(startDir));
    }

    @Override
    public String addModule(Module module, Props props) throws IOException
    {
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
            // Create start.d/{name}.ini
            Path ini = startDir.resolve(module.getName() + ".ini");
            try (BufferedWriter writer = Files.newBufferedWriter(ini, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
            {
                module.writeIniSection(writer, props);
            }
            return baseHome.toShortForm(ini);
        }

        return null;
    }
}
