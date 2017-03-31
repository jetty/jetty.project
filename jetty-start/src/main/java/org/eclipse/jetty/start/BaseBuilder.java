//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.start.builders.StartDirBuilder;
import org.eclipse.jetty.start.builders.StartIniBuilder;
import org.eclipse.jetty.start.fileinits.MavenLocalRepoFileInitializer;
import org.eclipse.jetty.start.fileinits.TestFileInitializer;
import org.eclipse.jetty.start.fileinits.UriFileInitializer;
import org.eclipse.jetty.start.graph.CriteriaSetPredicate;
import org.eclipse.jetty.start.graph.UniqueCriteriaPredicate;
import org.eclipse.jetty.start.graph.Predicate;
import org.eclipse.jetty.start.graph.Selection;

/**
 * Build a start configuration in <code>${jetty.base}</code>, including
 * ini files, directories, and libs. Also handles License management.
 */
public class BaseBuilder
{
    public static interface Config
    {
        /**
         * Add a module to the start environment in <code>${jetty.base}</code>
         *
         * @param module
         *            the module to add
         * @return true if module was added, false if module was not added
         *         (because that module already exists)
         * @throws IOException if unable to add the module
         */
        public boolean addModule(Module module) throws IOException;
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
            // No downloads performed
            fileInitializers.add(new TestFileInitializer());
        }
        else if (args.isDownload())
        {
            // Downloads are allowed to be performed
            // Setup Maven Local Repo
            Path localRepoDir = args.getMavenLocalRepoDir();
            if (localRepoDir != null)
            {
                // Use provided local repo directory
                fileInitializers.add(new MavenLocalRepoFileInitializer(baseHome,localRepoDir));
            }
            else
            {
                // No no local repo directory (direct downloads)
                fileInitializers.add(new MavenLocalRepoFileInitializer(baseHome));
            }

            // Normal URL downloads
            fileInitializers.add(new UriFileInitializer(baseHome));
        }
    }

    private void ackLicenses() throws IOException
    {
        if (startArgs.isLicenseCheckRequired())
        {
            if (startArgs.isApproveAllLicenses())
            {
                StartLog.info("All Licenses Approved via Command Line Option");
            }
            else
            {
                Licensing licensing = new Licensing();
                for (Module module : startArgs.getAllModules().getSelected())
                {
                    if (!module.hasFiles(baseHome,startArgs.getProperties()))
                    {
                        module.addModule(module, licensing.licenseMap);
                    }
                }
                if (licensing.hasLicenses())
                {
                    StartLog.debug("Requesting License Acknowledgement");
                    if (!licensing.acknowledgeLicenses())
                    {
                        StartLog.warn(EXITING_LICENSE_NOT_ACKNOWLEDGED);
                        System.exit(1);
                    }
                }
            }
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
        boolean dirty = false;

        String dirCriteria = "<add-to-startd>";
        String iniCriteria = "<add-to-start-ini>";
        Selection startDirSelection = new Selection(dirCriteria);
        Selection startIniSelection = new Selection(iniCriteria);
        
        List<String> startDNames = new ArrayList<>();
        startDNames.addAll(startArgs.getAddToStartdIni());
        List<String> startIniNames = new ArrayList<>();
        startIniNames.addAll(startArgs.getAddToStartIni());

        int count = 0;
        count += modules.selectNodes(startDNames,startDirSelection);
        count += modules.selectNodes(startIniNames,startIniSelection);

        // look for ambiguous declaration found in both places
        Predicate ambiguousPredicate = new CriteriaSetPredicate(dirCriteria,iniCriteria);
        List<Module> ambiguous = modules.getMatching(ambiguousPredicate);

        if (ambiguous.size() > 0)
        {
            StringBuilder warn = new StringBuilder();
            warn.append("Ambiguous module locations detected, defaulting to --add-to-start for the following module selections:");
            warn.append(" [");
            
            for (int i = 0; i < ambiguous.size(); i++)
            {
                if (i > 0)
                {
                    warn.append(", ");
                }
                warn.append(ambiguous.get(i).getName());
            }
            warn.append(']');
            StartLog.warn(warn.toString());
        }

        StartLog.debug("Adding %s new module(s)",count);
        
        // Acknowledge Licenses
        ackLicenses();

        // Collect specific modules to enable
        // Should match 'criteria', with no other selections.explicit
        Predicate startDMatcher = new UniqueCriteriaPredicate(dirCriteria);
        Predicate startIniMatcher = new UniqueCriteriaPredicate(iniCriteria);

        List<Module> startDModules = modules.getMatching(startDMatcher);
        List<Module> startIniModules = modules.getMatching(startIniMatcher);

        List<FileArg> files = new ArrayList<FileArg>();

        if (!startDModules.isEmpty())
        {
            StartDirBuilder builder = new StartDirBuilder(this);
            for (Module mod : startDModules)
            {
                if (ambiguous.contains(mod))
                {
                    // skip ambiguous module
                    continue;
                }
                
                if (mod.isSkipFilesValidation())
                {
                    StartLog.debug("Skipping [files] validation on %s",mod.getName());
                } 
                else 
                {
                    dirty |= builder.addModule(mod);
                    for (String file : mod.getFiles())
                    {
                        files.add(new FileArg(mod,startArgs.getProperties().expand(file)));
                    }
                }
            }
        }

        if (!startIniModules.isEmpty())
        {
            StartIniBuilder builder = new StartIniBuilder(this);
            for (Module mod : startIniModules)
            {
                if (mod.isSkipFilesValidation())
                {
                    StartLog.debug("Skipping [files] validation on %s",mod.getName());
                } 
                else 
                {
                    dirty |= builder.addModule(mod);
                    for (String file : mod.getFiles())
                    {
                        files.add(new FileArg(mod,startArgs.getProperties().expand(file)));
                    }
                }
            }
        }
        
        // Process files
        files.addAll(startArgs.getFiles());
        dirty |= processFileResources(files);

        return dirty;
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
     * @param arg
     *            the fileArg to work with
     * @param file
     *            the resolved file reference to work with
     * @return true if change was made as a result of the file, false if no change made.
     * @throws IOException
     *             if there was an issue in processing this file
     */
    private boolean processFileResource(FileArg arg, Path file) throws IOException
    {
        if (startArgs.isDownload() && (arg.uri != null))
        {
            // now on copy/download paths (be safe above all else)
            if (!file.startsWith(baseHome.getBasePath()))
            {
                throw new IOException("For security reasons, Jetty start is unable to process maven file resource not in ${jetty.base} - " + file);
            }
            
            // make the directories in ${jetty.base} that we need
            FS.ensureDirectoryExists(file.getParent());
            
            URI uri = URI.create(arg.uri);

            // Process via initializers
            for (FileInitializer finit : fileInitializers)
            {
                if (finit.init(uri,file,arg.location))
                {
                    // Completed successfully
                    return true;
                }
            }

            return false;
        }
        else
        {
            // Process directly
            boolean isDir = arg.location.endsWith("/");

            if (FS.exists(file))
            {
                // Validate existence
                if (isDir)
                {
                    if (!Files.isDirectory(file))
                    {
                        throw new IOException("Invalid: path should be a directory (but isn't): " + file);
                    }
                    if (!FS.canReadDirectory(file))
                    {
                        throw new IOException("Unable to read directory: " + file);
                    }
                }
                else
                {
                    if (!FS.canReadFile(file))
                    {
                        throw new IOException("Unable to read file: " + file);
                    }
                }

                return false;
            }

            if (isDir)
            {
                // Create directory
                StartLog.log("MKDIR",baseHome.toShortForm(file));
                return FS.ensureDirectoryExists(file);
            }
            else
            {
                // Warn on missing file (this has to be resolved manually by user)
                String shortRef = baseHome.toShortForm(file);
                if (startArgs.isTestingModeEnabled())
                {
                    StartLog.log("TESTING MODE","Skipping required file check on: %s",shortRef);
                    return true;
                }

                StartLog.warn("Missing Required File: %s",baseHome.toShortForm(file));
                startArgs.setRun(false);
                if (arg.uri != null)
                {
                    StartLog.warn("  Can be downloaded From: %s",arg.uri);
                    StartLog.warn("  Run start.jar --create-files to download");
                }

                return true;
            }
        }
    }

    /**
     * Process the {@link FileArg} for startup, assume that all licenses have
     * been acknowledged at this stage.
     *
     * @param files
     *            the list of {@link FileArg}s to process
     * @return true if base directory modified, false if left untouched
     */
    private boolean processFileResources(List<FileArg> files) throws IOException
    {
        if ((files == null) || (files.isEmpty()))
        {
            return false;
        }

        boolean dirty = false;

        List<String> failures = new ArrayList<String>();

        for (FileArg arg : files)
        {
            Path file = baseHome.getBasePath(arg.location);
            try
            {
                dirty |= processFileResource(arg,file);
            }
            catch (Throwable t)
            {
                StartLog.warn(t);
                failures.add(String.format("[%s] %s - %s",t.getClass().getSimpleName(),t.getMessage(),file.toAbsolutePath().toString()));
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
