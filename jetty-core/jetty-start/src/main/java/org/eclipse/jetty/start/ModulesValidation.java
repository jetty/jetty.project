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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ModulesValidation
{
    public static final String VALIDATE_ORIGIN = "validate-modules";

    public static void validateModules(PrintStream out, Modules modules, List<String> moduleSelection) throws IOException
    {
        List<String> failures = new ArrayList<>();

        List<Module> selectedModules = new ArrayList<>();
        if ((moduleSelection.contains("*") || moduleSelection.isEmpty()))
        {
            selectedModules.addAll(modules.stream().sorted().toList());
        }
        else
        {
            for (String name: moduleSelection)
            {
                Module module = modules.get(name);
                if (module == null)
                {
                    failures.add("%s - [command-line] Unknown module name".formatted(name));
                }
                else
                {
                    selectedModules.add(module);
                }
            }
        }

        List<FileInitializer> fileInitializers = BaseBuilder.getDefaultInitializers(modules.getBaseHome(), modules.getStartArgs());

        for (Module module : selectedModules)
        {
            try
            {
                Props moduleProps = asProps(module);
                Main.initBaseProps(moduleProps, modules.getBaseHome(), VALIDATE_ORIGIN, VALIDATE_ORIGIN);

                Props fullProps = new Props();
                // Load the dependencies' [ini] values first, then the module's own on top,
                // so the module's own values take precedence over those of its dependencies.
                loadDependentProps(modules, module, fullProps);
                fullProps.addAll(moduleProps);

                for (Props.Prop prop: moduleProps)
                {
                    String expandedValue = fullProps.expand(prop.value);
                    if (expandedValue.contains("$") || expandedValue.contains("@"))
                        failures.add("%s - [ini] Bad/Unexpandable property [%s=%s]".formatted(module.getName(), prop.key, prop.value));
                }
                if (!module.getDepends().isEmpty())
                {
                    for (String parent : module.getDepends())
                    {
                        // Check the raw name for the conditional '?' prefix before it is stripped.
                        boolean conditional = Module.isConditionalDependency(parent);
                        parent = Module.normalizeModuleName(parent);
                        String expandedParent = fullProps.expand(parent);
                        if (!conditional && !hasDependency(modules, expandedParent))
                        {
                            failures.add("%s - [depends] Does not exist '%s'".formatted(module.getName(), parent));
                        }
                    }
                }
                if (!module.getBefore().isEmpty())
                {
                    for (String before : module.getBefore())
                    {
                        if (!hasDependency(modules, before))
                        {
                            failures.add("%s - [before] Does not exist '%s'".formatted(module.getName(), before));
                        }
                    }
                }
                if (!module.getAfter().isEmpty())
                {
                    for (String after : module.getAfter())
                    {
                        if (!hasDependency(modules, after))
                        {
                            failures.add("%s - [after] Does not exist '%s'".formatted(module.getName(), after));
                        }
                    }
                }

                // List of [files] destination locations
                List<String> filesLocations = new ArrayList<>();

                if (!module.getFiles().isEmpty())
                {
                    for (String file : module.getFiles())
                    {
                        String fileRef = fullProps.expand(file);
                        if (fileRef.contains("${") || fileRef.contains("@")) // didn't properly expand
                        {
                            failures.add("%s - [files] Unable to expand property '%s'".formatted(module.getName(), file));
                        }
                        else
                        {
                            FileArg fileArg;
                            URI fileURI;
                            try
                            {
                                fileArg = new FileArg(module, fileRef);
                                fileURI = fileArg.uri == null ? null : URI.create(fileArg.uri);
                            }
                            catch (Throwable e)
                            {
                                // Malformed [files] entry (bad syntax or illegal URI): record it and
                                // carry on with this module's remaining files, rather than aborting.
                                failures.add("%s - [files] Invalid '%s' : %s".formatted(module.getName(), file, Utils.asString(e)));
                                continue;
                            }
                            if (fileURI != null)
                            {
                                boolean applicable = false;
                                for (FileInitializer finit : fileInitializers)
                                {
                                    if (finit.isApplicable(fileURI))
                                    {
                                        applicable = true;
                                        try
                                        {
                                            if (!finit.exists(fileURI))
                                                failures.add("%s - [files] Does not exist '%s'".formatted(module.getName(), file));
                                            else
                                            {
                                                String destLocation = moduleProps.expand(fileArg.location);
                                                filesLocations.add(destLocation);
                                            }
                                        }
                                        catch (Throwable e)
                                        {
                                            failures.add("%s - [files] Invalid '%s' : %s".formatted(module.getName(), file, Utils.asString(e)));
                                        }
                                    }
                                }
                                // No initializer handles this URI's scheme (e.g. a typo like
                                // 'mavne://...'); normal processing would throw "Unable to create".
                                if (!applicable)
                                    failures.add("%s - [files] Unable to create '%s'".formatted(module.getName(), file));
                            }
                            else
                            {
                                // Just a raw location reference (no URI)
                                String destLocation = moduleProps.expand(fileArg.location);
                                filesLocations.add(destLocation);
                            }
                        }
                    }
                }

                for (String lib : module.getLibs())
                {
                    boolean found = false;
                    String expandedLib = fullProps.expand(lib);
                    if (expandedLib.endsWith("*.jar"))
                    {
                        if (!expandedLib.endsWith("/**.jar"))
                            failures.add("%s - [lib] Bad glob definition (should end in `/**.jar`) %s".formatted(module.getName(), lib));
                        found = true;
                    }
                    if (filesLocations.contains(expandedLib))
                    {
                        // Found in [files] section
                        found = true;
                    }
                    else
                    {
                        for (Path libPath : modules.getBaseHome().getPaths(expandedLib))
                        {
                            if (Files.exists(libPath))
                            {
                                found = true;
                            }
                        }
                    }
                    if (!found)
                        failures.add("%s - [lib] Does not exist '%s'".formatted(module.getName(), lib));
                }

                for (String xml : module.getXmls())
                {
                    boolean found = false;
                    xml = fullProps.expand(xml);
                    if (filesLocations.contains(xml))
                    {
                        // Found in [files] section
                        found = true;
                    }
                    else
                    {
                        for (Path xmlPath : modules.getBaseHome().getPaths(xml))
                        {
                            if (Files.exists(xmlPath))
                            {
                                found = true;
                            }
                        }
                    }
                    if (!found)
                        failures.add("%s - [xml] Does not exist '%s'".formatted(module.getName(), xml));
                }
            }
            catch (Throwable e)
            {
                // Safety net: a single malformed module must never abort validation of the rest,
                // so that --validate-modules can report every problem in one pass.
                failures.add("%s - Unable to validate : %s".formatted(module.getName(), Utils.asString(e)));
            }
        }

        out.printf("%nValidated %,d module(s)%n", selectedModules.size());

        if (!failures.isEmpty())
        {
            Collections.sort(failures);
            out.printf("%nThere are %d failed module validations%n", failures.size());
            for (int i = 0; i < failures.size(); i++)
            {
                out.printf("  %-3d: %s%n", i + 1, failures.get(i));
            }
            out.println();
            throw new IllegalStateException("Failed to validate modules");
        }
        else
        {
            out.println("No failure detected.");
        }
    }

    private static Props asProps(Module module)
    {
        Props props = new Props();

        for (String ini : module.getIniSection())
        {
            int equals = ini.indexOf('=');
            if (equals >= 0)
            {
                String key = ini.substring(0, equals);
                String value = ini.substring(equals + 1);

                if (key.endsWith("+") || key.endsWith("?"))
                    key = key.substring(0, key.length() - 1);

                props.setProperty(key, value, VALIDATE_ORIGIN);
            }
        }

        return props;
    }

    private static void loadDependentProps(Modules modules, Module module, Props fullProps)
    {
        Set<String> visited = new HashSet<>();
        visited.add(module.getName());
        loadDependentProps(modules, module, fullProps, visited);
    }

    private static void loadDependentProps(Modules modules, Module module, Props fullProps, Set<String> visited)
    {
        for (String dependent: module.getDepends())
        {
            try
            {
                // Strip any conditional '?' prefix so the dependency can be resolved.
                Module depModule = modules.find(Module.normalizeModuleName(dependent));
                // Guard against dependency cycles (e.g. through dynamic 'a/b' modules that
                // the topological sort doesn't see), which would otherwise recurse until
                // the stack overflows.
                if (depModule != null && visited.add(depModule.getName()))
                {
                    Props modProps = asProps(depModule);
                    fullProps.addAll(modProps);
                    // Recurse
                    loadDependentProps(modules, depModule, fullProps, visited);
                }
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private static boolean hasDependency(Modules modules, String name)
    {
        // Dynamic Module Name
        if (name.contains("/"))
        {
            Path file = modules.getBaseHome().getPath("modules/" + name + ".mod");
            return Files.exists(file);
        }
        else
        {
            for (Module module : modules)
            {
                if (module.getName().equals(name))
                    return true;
                Set<Module> provided = modules.getProvided(name);
                if (provided != null && !provided.isEmpty())
                    return true;
            }
        }
        return false;
    }
}
