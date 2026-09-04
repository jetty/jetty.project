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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ModulesValidation
{
    public static final String VALIDATE_ORIGIN = "validate-modules";

    public static void validateModules(Modules modules, List<String> moduleSelection) throws IOException
    {
        List<Module> selectedModules = (moduleSelection.contains("*") || moduleSelection.isEmpty())
            ? modules.stream().sorted().toList()
            : moduleSelection.stream().map(modules::get).toList();

        List<String> failures = new ArrayList<>();

        for (Module module : selectedModules)
        {
            if (module == null)
                return;

            Props moduleProps = asProps(module);
            Main.initBaseProps(moduleProps, modules.getBaseHome(), VALIDATE_ORIGIN, VALIDATE_ORIGIN);

            Props fullProps = new Props();
            fullProps.addAll(moduleProps);
            loadDependentProps(modules, module, fullProps);

            Set<String> provides = module.getProvides();
            provides.remove(module.getName());
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
                    parent = Module.normalizeModuleName(parent);
                    if (!Module.isConditionalDependency(parent) && !hasDependency(modules, parent))
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
                List<FileInitializer> fileInitializers = BaseBuilder.getDefaultInitializers(modules.getBaseHome(), modules.getStartArgs());

                for (String file : module.getFiles())
                {
                    String fileRef = fullProps.expand(file);
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
                                        String destLocation = moduleProps.expand(fileArg.location);
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
                String expandedLib = fullProps.expand(lib);
                if (expandedLib.endsWith("*.jar"))
                {
                    if (!expandedLib.endsWith("/**.jar"))
                        failures.add("%s - [lib] Bad glob definition (should end in `/**.jar`) %s".formatted(module.getName(), lib));
                    found = true;
                }
                if (expandedLib.endsWith("/"))
                {
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

        System.err.printf("%nValidated %,d modules%n", selectedModules.size());

        if (!failures.isEmpty())
        {
            Collections.sort(failures);
            System.err.printf("%nThere are %d failed module validations%n", failures.size());
            for (int i = 0; i < failures.size(); i++)
            {
                System.err.printf("  %-3d: %s%n", i + 1, failures.get(i));
            }
            throw new IllegalStateException("Failed to validate modules");
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
        for (String dependent: module.getDepends())
        {
            try
            {
                Module depModule = modules.find(dependent);
                if (depModule != null)
                {
                    Props modProps = asProps(depModule);
                    fullProps.addAll(modProps);
                    // Recurse
                    loadDependentProps(modules, depModule, fullProps);
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
