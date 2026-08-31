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

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ModulesValidation
{
    public static void validateModules(Modules modules, PrintStream out, List<String> moduleSelection) throws IOException
    {
        List<Module> selectedModules = (moduleSelection.contains("*") || moduleSelection.isEmpty())
            ? modules.stream().sorted().toList()
            : moduleSelection.stream().map(modules::get).toList();

        List<String> failures = new ArrayList<>();
        final String validateOrigin = "validate-modules";

        for (Module module : selectedModules)
        {
            if (module == null)
                return;

            Props props = new Props();
            Main.initBaseProps(props, modules.getBaseHome(), validateOrigin, validateOrigin);
            for (String ini : module.getIniSection())
            {
                int equals = ini.indexOf('=');
                if (equals >= 0)
                {
                    String key = ini.substring(0, equals);
                    String value = ini.substring(equals + 1);

                    if (key.endsWith("+") || key.endsWith("?"))
                        key = key.substring(0, key.length() - 1);

                    props.setProperty(key, value, validateOrigin);
                }
            }

            Set<String> provides = module.getProvides();
            provides.remove(module.getName());
            out.printf("%n     Module: %s %s%n", module.getName(), !provides.isEmpty() ? provides : "");
            for (String description : module.getDescription())
            {
                out.printf("           : %s%n", description);
            }
            if (module.getEnvironment() != null)
            {
                out.printf("Environment: %s%n", module.getEnvironment());
            }
            for (Props.Prop prop: props)
            {
                out.printf("       Prop: %s=%s%n", prop.key, prop.value);
                String expandedValue = props.expand(prop.value);
                if (expandedValue.contains("$") || expandedValue.contains("@"))
                    failures.add("%s - [ini] Bad/Unexpandable property [%s=%s]".formatted(module.getName(), prop.key, prop.value));
            }
            if (!module.getTags().isEmpty())
            {
                String label = "       Tags: %s";
                for (String t : module.getTags())
                {
                    out.printf(label, t);
                    label = ", %s";
                }
                out.println();
            }
            if (!module.getDepends().isEmpty())
            {
                for (String parent : module.getDepends())
                {
                    parent = Module.normalizeModuleName(parent);
                    out.printf("     Depend: %s", parent);
                    if (Module.isConditionalDependency(parent))
                        out.print(" [conditional]");
                    else if (!hasDependency(modules, parent))
                    {
                        out.print(" [MISSING]");
                        failures.add("%s - [depends] Does not exist '%s'".formatted(module.getName(), parent));
                    }
                    out.println();
                }
            }
            if (!module.getBefore().isEmpty())
            {
                for (String before : module.getBefore())
                {
                    out.printf("     Before: %s", before);
                    if (!hasDependency(modules, before))
                    {
                        out.print(" [MISSING]");
                        failures.add("%s - [before] Does not exist '%s'".formatted(module.getName(), before));
                    }
                    out.println();
                }
            }
            if (!module.getAfter().isEmpty())
            {
                for (String after : module.getAfter())
                {
                    out.printf("      After: %s", after);
                    if (!hasDependency(modules, after))
                    {
                        out.print(" [MISSING]");
                        failures.add("%s - [after] Does not exist '%s'".formatted(module.getName(), after));
                    }
                    out.println();
                }
            }

            // List of [files] destination locations
            List<String> filesLocations = new ArrayList<>();

            if (!module.getFiles().isEmpty())
            {
                List<FileInitializer> fileInitializers = BaseBuilder.getDefaultInitializers(modules.getBaseHome(), modules.getStartArgs());

                for (String file : module.getFiles())
                {
                    out.printf("       FILE: %s%n", file);
                    String fileRef = props.expand(file);
                    if (fileRef.contains("${") || fileRef.contains("@")) // didn't properly expand
                    {
                        failures.add("%s - [files] Unable to expand property '%s'".formatted(module.getName(), file));
                    }
                    else
                    {
                        FileArg fileArg = new FileArg(module, fileRef);
                        URI fileURI = fileArg.uri == null ? null : URI.create(fileArg.uri);
                        for (FileInitializer finit : fileInitializers)
                        {
                            if (finit.isApplicable(fileURI))
                            {
                                try
                                {
                                    if (!finit.exists(fileURI))
                                        failures.add("%s - [files] Does not exist '%s'".formatted(module.getName(), file));
                                    else
                                    {
                                        String destLocation = props.expand(fileArg.location);
                                        filesLocations.add(destLocation);
                                    }
                                }
                                catch (IOException e)
                                {
                                    failures.add("%s - [files] Invalid '%s' : %s".formatted(module.getName(), file, Utils.asString(e)));
                                }
                            }
                        }
                    }
                }
            }

            for (String lib : module.getLibs())
            {
                boolean found = false;
                out.printf("        LIB: %s", lib);
                String expandedLib = props.expand(lib);
                if (expandedLib.endsWith("**.jar"))
                {
                    out.print(" [glob]");
                    found = true;
                }
                if (expandedLib.endsWith("/"))
                {
                    out.print(" [dir]");
                    found = true;
                }
                if (filesLocations.contains(expandedLib))
                {
                    // Found in [files] section
                    out.print(" [files-defined]");
                    found = true;
                }
                else
                {
                    for (Path libPath : modules.getBaseHome().getPaths(expandedLib))
                    {
                        if (Files.exists(libPath))
                        {
                            out.printf(" [path:%s]", libPath);
                            found = true;
                        }
                    }
                }
                out.println();
                if (!found)
                    failures.add("%s - [lib] Does not exist '%s'".formatted(module.getName(), lib));
            }

            for (String xml : module.getXmls())
            {
                boolean found = false;
                out.printf("        XML: %s", xml);
                xml = props.expand(xml);
                if (filesLocations.contains(xml))
                {
                    // Found in [files] section
                    out.print(" [files-defined]");
                    found = true;
                }
                else
                {
                    for (Path xmlPath : modules.getBaseHome().getPaths(xml))
                    {
                        if (Files.exists(xmlPath))
                        {
                            out.printf(" [path:%s]", xmlPath);
                            found = true;
                        }
                    }
                }
                out.println();
                if (!found)
                    failures.add("%s - [xml] Does not exist '%s'".formatted(module.getName(), xml));
            }
            for (String jpms : module.getJPMS())
            {
                out.printf("        JPMS: %s%n", jpms);
            }
            for (String jvm : module.getJvmArgs())
            {
                out.printf("        JVM: %s%n", jvm);
            }
            if (module.isEnabled())
            {
                for (String selection : module.getEnableSources())
                {
                    out.printf("    Enabled: %s%n", selection);
                }
            }
        }

        if (!failures.isEmpty())
        {
            System.err.printf("%n%nThere are %d failed module validations%n", failures.size());
            for (int i = 0; i < failures.size(); i++)
            {
                System.err.printf("  %-3d: %s%n", i + 1, failures.get(i));
            }
            throw new IllegalStateException("Failed to validate modules");
        }
    }

    private static boolean hasDependency(Modules modules, String name)
    {
        // Check tags
        for (Module module: modules)
        {
            if (module.getTags().contains(name))
                return true;
            if (module.getName().equals(name))
                return true;
            Set<Module> provided = modules.getProvided(name);
            if (provided != null && !provided.isEmpty())
                return true;
        }
        return false;
    }
}
