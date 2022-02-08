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

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.start.builders.StartDirBuilder;
import org.eclipse.jetty.start.builders.StartIniBuilder;
import org.eclipse.jetty.start.fileinits.BaseHomeFileInitializer;
import org.eclipse.jetty.start.fileinits.LocalFileInitializer;
import org.eclipse.jetty.start.fileinits.MavenLocalRepoFileInitializer;
import org.eclipse.jetty.start.fileinits.TestFileInitializer;
import org.eclipse.jetty.start.fileinits.UriFileInitializer;

/**
 * Build a start configuration in {@code ${jetty.base}}, including
 * ini files, directories, and libs. Also handles License management.
 */
public class BaseBuilder
{
    public static interface Config
    {
        /**
         * Add a module to the start environment in {@code ${jetty.base}}
         *
         * @param module the module to add
         * @param props The properties to substitute into a template
         * @return The ini file if module was added, null if module was not added.
         * @throws IOException if unable to add the module
         */
        public String addModule(Module module, Props props) throws IOException;
    }

    private static final String EXITING_LICENSE_NOT_ACKNOWLEDGED = "Exiting: license not acknowledged!";

    private final BaseHome baseHome;
    private final List<FileInitializer> fileInitializers;
    private final StartArgs startArgs;

    public BaseBuilder(BaseHome baseHome, StartArgs args)
    {
        this.baseHome = baseHome;
        this.startArgs = args;
        this.fileInitializers = new ArrayList<>();

        // Establish FileInitializers
        if (args.isTestingModeEnabled())
        {
            // Copy from basehome
            fileInitializers.add(new BaseHomeFileInitializer(baseHome));

            // Handle local directories
            fileInitializers.add(new LocalFileInitializer(baseHome));

            // No downloads performed
            fileInitializers.add(new TestFileInitializer(baseHome));
        }
        else if (args.isCreateFiles())
        {
            // Handle local directories
            fileInitializers.add(new LocalFileInitializer(baseHome));

            // Downloads are allowed to be performed
            // Setup Maven Local Repo
            Path localRepoDir = args.findMavenLocalRepoDir();
            if (localRepoDir != null)
            {
                // Use provided local repo directory
                fileInitializers.add(new MavenLocalRepoFileInitializer(baseHome, localRepoDir,
                    args.getMavenLocalRepoDir() == null,
                    startArgs.getMavenBaseUri()));
            }
            else
            {
                // No no local repo directory (direct downloads)
                fileInitializers.add(new MavenLocalRepoFileInitializer(baseHome));
            }

            // Copy from basehome
            fileInitializers.add(new BaseHomeFileInitializer(baseHome));

            // Normal URL downloads
            fileInitializers.add(new UriFileInitializer(baseHome));
        }
    }

    /**
     * Build out the Base directory (if needed)
     *
     * @return true if base directory was changed, false if left unchanged.
     * @throws IOException if unable to build
     */
    public boolean build() throws IOException
    {
        Modules modules = startArgs.getAllModules();

        // Select all the added modules to determine which ones are newly enabled
        Set<String> newlyAdded = new HashSet<>();
        if (!startArgs.getStartModules().isEmpty())
        {
            for (String name : startArgs.getStartModules())
            {
                newlyAdded.addAll(modules.enable(name, "--add-module"));
                if (!newlyAdded.contains(name))
                {
                    Set<String> sources = modules.get(name).getEnableSources();
                    sources.remove("--add-module");
                    StartLog.info("%s already enabled by %s", name, sources);
                }
            }
        }

        if (StartLog.isDebugEnabled())
            StartLog.debug("Newly Added %s", newlyAdded);

        // Check the licenses
        if (startArgs.isLicenseCheckRequired())
        {
            Licensing licensing = new Licensing();
            for (String name : newlyAdded)
            {
                licensing.addModule(modules.get(name));
            }

            if (licensing.hasLicenses())
            {
                if (startArgs.isApproveAllLicenses())
                {
                    StartLog.info("All Licenses Approved via Command Line Option");
                }
                else if (!licensing.acknowledgeLicenses())
                {
                    StartLog.warn(EXITING_LICENSE_NOT_ACKNOWLEDGED);
                    System.exit(1);
                }
            }
        }

        // generate the files
        List<FileArg> files = new ArrayList<>();
        AtomicReference<BaseBuilder.Config> builder = new AtomicReference<>();
        AtomicBoolean modified = new AtomicBoolean();

        Path startd = getBaseHome().getBasePath("start.d");
        Path startini = getBaseHome().getBasePath("start.ini");

        if (startArgs.isCreateStartIni())
        {
            if (!Files.exists(startini))
            {
                if (Files.exists(getBaseHome().getBasePath("start.jar")))
                    StartLog.warn("creating start.ini in ${jetty.home} is not recommended!");
                StartLog.info("create %s", baseHome.toShortForm(startini));
                Files.createFile(startini);
                modified.set(true);
            }

            if (Files.exists(startd))
            {
                // Copy start.d files into start.ini
                DirectoryStream.Filter<Path> filter = new DirectoryStream.Filter<>()
                {
                    private final PathMatcher iniMatcher = PathMatchers.getMatcher("glob:**/start.d/*.ini");

                    @Override
                    public boolean accept(Path entry)
                    {
                        return iniMatcher.matches(entry);
                    }
                };
                List<Path> paths = new ArrayList<>();
                try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(startd, filter))
                {
                    for (Path path : dirStream)
                    {
                        paths.add(path);
                    }
                }
                paths.sort(new NaturalSort.Paths());

                // Read config from start.d
                List<String> startLines = new ArrayList<>();
                for (Path path : paths)
                {
                    StartLog.info("copy " + baseHome.toShortForm(path) + " into " + baseHome.toShortForm(startini));
                    startLines.add("");
                    startLines.add("# Config from " + baseHome.toShortForm(path));
                    startLines.addAll(Files.readAllLines(path));
                }

                // append config to start.ini
                try (FileWriter out = new FileWriter(startini.toFile(), true))
                {
                    for (String line : startLines)
                    {
                        out.append(line).append(System.lineSeparator());
                    }
                }

                // delete start.d files
                for (Path path : paths)
                {
                    Files.delete(path);
                }
                Files.delete(startd);
            }
        }

        if ((startArgs.isCreateStartD() && (!Files.exists(startd) || Files.exists(startini))) ||
            (!newlyAdded.isEmpty() && !Files.exists(startini) && !Files.exists(startd)))
        {
            if (Files.exists(getBaseHome().getBasePath("start.jar")) && !startArgs.isCreateStartD())
            {
                StartLog.warn("creating start.d in ${jetty.home} is not recommended!");
                if (!startArgs.isCreateStartD())
                {
                    BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
                    System.err.printf("%nProceed (y/N)? ");
                    String response = input.readLine();

                    if (Utils.isBlank(response) || !response.toLowerCase(Locale.ENGLISH).startsWith("y"))
                        System.exit(1);
                }
            }

            if (FS.ensureDirectoryExists(startd))
            {
                StartLog.info("mkdir " + baseHome.toShortForm(startd));
                modified.set(true);
            }

            if (Files.exists(startini) && startArgs.isCreateStartD())
            {
                int ini = 0;
                Path startdStartini = startd.resolve("start.ini");
                while (Files.exists(startdStartini))
                {
                    ini++;
                    startdStartini = startd.resolve("start" + ini + ".ini");
                }
                Files.move(startini, startdStartini);
                modified.set(true);
            }
        }

        boolean useStartD = Files.exists(startd);
        if (useStartD && Files.exists(startini))
            StartLog.warn("Use of both %s and %s is deprecated", getBaseHome().toShortForm(startd), getBaseHome().toShortForm(startini));

        builder.set(useStartD ? new StartDirBuilder(this) : new StartIniBuilder(this));

        // Collect the filesystem operations to perform,
        // only for those modules that are enabled.
        newlyAdded.stream()
            .map(modules::get)
            .filter(Module::isEnabled)
            .forEach(module ->
            {
                String ini = null;
                try
                {
                    if (module.isSkipFilesValidation())
                    {
                        StartLog.debug("Skipping [files] validation on %s", module.getName());
                    }
                    else
                    {
                        // if (explicitly added and ini file modified)
                        if (startArgs.getStartModules().contains(module.getName()))
                        {
                            ini = builder.get().addModule(module, startArgs.getProperties());
                            if (ini != null)
                                modified.set(true);
                        }
                        for (String file : module.getFiles())
                        {
                            files.add(new FileArg(module, startArgs.getProperties().expand(file)));
                        }
                    }
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }

                if (module.isDynamic())
                {
                    for (String s : module.getEnableSources())
                    {
                        StartLog.info("%-15s %s", module.getName(), s);
                    }
                }
                else if (module.isTransitive())
                {
                    if (module.hasIniTemplate())
                    {
                        StartLog.info("%-15s transitively enabled, ini template available with --add-module=%s",
                            module.getName(),
                            module.getName());
                    }
                    else
                    {
                        StartLog.info("%-15s transitively enabled", module.getName());
                    }
                }
                else
                {
                    StartLog.info("%-15s initialized in %s", module.getName(), ini);
                }
            });

        files.addAll(startArgs.getFiles());
        if (!files.isEmpty() && processFileResources(files))
            modified.set(true);

        return modified.get();
    }

    public BaseHome getBaseHome()
    {
        return baseHome;
    }

    public StartArgs getStartArgs()
    {
        return startArgs;
    }

    /**
     * Process a specific file resource
     *
     * @param arg the fileArg to work with
     * @return true if change was made as a result of the file, false if no change made.
     * @throws IOException if there was an issue in processing this file
     */
    private boolean processFileResource(FileArg arg) throws IOException
    {
        URI uri = arg.uri == null ? null : URI.create(arg.uri);

        if (startArgs.isCreateFiles())
        {
            for (FileInitializer finit : fileInitializers)
            {
                if (finit.isApplicable(uri))
                    return finit.create(uri, arg.location);
            }

            throw new IOException(String.format("Unable to create %s", arg));
        }

        for (FileInitializer finit : fileInitializers)
        {
            if (finit.isApplicable(uri))
                if (!finit.check(uri, arg.location))
                    startArgs.setRun(false);
        }
        return false;
    }

    /**
     * Process the {@link FileArg} for startup, assume that all licenses have
     * been acknowledged at this stage.
     *
     * @param files the list of {@link FileArg}s to process
     * @return true if base directory modified, false if left untouched
     */
    private boolean processFileResources(List<FileArg> files)
    {
        if ((files == null) || (files.isEmpty()))
        {
            return false;
        }

        boolean dirty = false;

        List<String> failures = new ArrayList<>();

        for (FileArg arg : files)
        {
            try
            {
                boolean processed = processFileResource(arg);
                dirty |= processed;
            }
            catch (Throwable t)
            {
                StartLog.warn(t);
                failures.add(String.format("[%s] %s - %s", t.getClass().getSimpleName(), t.getMessage(), arg.location));
            }
        }

        if (!failures.isEmpty())
        {
            StringBuilder err = new StringBuilder();
            err.append("Failed to process all file resources.");
            for (String failure : failures)
            {
                err.append(System.lineSeparator()).append(" - ").append(failure);
            }
            StartLog.warn(err.toString());

            throw new RuntimeException(err.toString());
        }

        return dirty;
    }
}
