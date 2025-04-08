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

package org.eclipse.jetty.start;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>Collects JPMS arguments specified in a Jetty module under the
 * {@code [jpms]} section, and outputs them to a command line.</p>
 */
class JPMSArgs
{
    private final Set<String> _adds = new LinkedHashSet<>();
    private final Map<String, Set<String>> _patches = new LinkedHashMap<>();
    private final Map<String, Set<String>> _opens = new LinkedHashMap<>();
    private final Map<String, Set<String>> _exports = new LinkedHashMap<>();
    private final Map<String, Set<String>> _reads = new LinkedHashMap<>();

    /**
     * <p>Collects JPMS arguments from a Jetty module {@code [jpms]} section.</p>
     *
     * @param module the Jetty module
     * @param environment the current EE environment
     * @throws IOException when a JPMS path argument is invalid
     */
    void collect(Module module, StartEnvironment environment) throws IOException
    {
        for (String jpmsArg : module.getJPMS())
        {
            jpmsArg = environment.getProperties().expand(jpmsArg);
            String directive;
            if (jpmsArg.startsWith(directive = "add-modules:"))
            {
                String[] names = jpmsArg.substring(directive.length()).split(",");
                Arrays.stream(names).map(String::trim).collect(Collectors.toCollection(() -> _adds));
            }
            else if (jpmsArg.startsWith(directive = "patch-module:"))
            {
                parseJPMSKeyValue(environment.getBaseHome(), module, jpmsArg, directive, true, _patches);
            }
            else if (jpmsArg.startsWith(directive = "add-opens:"))
            {
                parseJPMSKeyValue(environment.getBaseHome(), module, jpmsArg, directive, false, _opens);
            }
            else if (jpmsArg.startsWith(directive = "add-exports:"))
            {
                parseJPMSKeyValue(environment.getBaseHome(), module, jpmsArg, directive, false, _exports);
            }
            else if (jpmsArg.startsWith(directive = "add-reads:"))
            {
                parseJPMSKeyValue(environment.getBaseHome(), module, jpmsArg, directive, false, _reads);
            }
            else
            {
                throw new IllegalArgumentException("Invalid [jpms] directive " + directive + " in module " + module.getName() + ": " + jpmsArg);
            }
        }

        StartLog.debug("Expanded JPMS directives for module %s:%n  add-modules: %s%n  patch-modules: %s%n  add-opens: %s%n  add-exports: %s%n  add-reads: %s",
            module, _adds, _patches, _opens, _exports, _reads);
    }

    private void parseJPMSKeyValue(BaseHome baseHome, Module module, String line, String directive, boolean valueIsFile, Map<String, Set<String>> output) throws IOException
    {
        String valueString = line.substring(directive.length());
        int equals = valueString.indexOf('=');
        if (equals <= 0)
            throw new IllegalArgumentException("Invalid [jpms] directive " + directive + " in module " + module.getName() + ": " + line);
        String delimiter = valueIsFile ? FS.pathSeparator() : ",";
        String key = valueString.substring(0, equals).trim();
        String[] values = valueString.substring(equals + 1).split(delimiter);
        Set<String> result = output.computeIfAbsent(key, k -> new LinkedHashSet<>());
        for (String value : values)
        {
            value = value.trim();
            if (valueIsFile)
            {
                List<Path> paths = baseHome.getPaths(value);
                paths.stream().map(Path::toAbsolutePath).map(Path::toString).collect(Collectors.toCollection(() -> result));
            }
            else
            {
                result.add(value);
            }
        }
    }

    /**
     * <p>Outputs the JPMS arguments of this class to a command line.</p>
     *
     * @param cmd the command line builder to add JPMS arguments to
     */
    void toCommandLine(CommandLineBuilder cmd)
    {
        if (!_adds.isEmpty())
        {
            cmd.addOption("--add-modules");
            cmd.addArg(String.join(",", _adds));
        }
        for (Map.Entry<String, Set<String>> entry : _patches.entrySet())
        {
            cmd.addOption("--patch-module");
            cmd.addArg(entry.getKey(), String.join(File.pathSeparator, entry.getValue()));
        }
        for (Map.Entry<String, Set<String>> entry : _opens.entrySet())
        {
            cmd.addOption("--add-opens");
            cmd.addArg(entry.getKey(), String.join(",", entry.getValue()));
        }
        for (Map.Entry<String, Set<String>> entry : _exports.entrySet())
        {
            cmd.addOption("--add-exports");
            cmd.addArg(entry.getKey(), String.join(",", entry.getValue()));
        }
        for (Map.Entry<String, Set<String>> entry : _reads.entrySet())
        {
            cmd.addOption("--add-reads");
            cmd.addArg(entry.getKey(), String.join(",", entry.getValue()));
        }
    }
}
