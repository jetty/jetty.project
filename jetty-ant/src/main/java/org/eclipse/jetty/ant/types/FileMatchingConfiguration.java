//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ant.types;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.tools.ant.DirectoryScanner;

/**
 * Describes set of files matched by <code>&lt;fileset/&gt;</code> elements in ant configuration
 * file. It is used to group application classes, libraries, and scannedTargets
 * elements.
 */
public class FileMatchingConfiguration
{

    private List<DirectoryScanner> directoryScanners;

    public FileMatchingConfiguration()
    {
        this.directoryScanners = new ArrayList<>();
    }

    /**
     * @param directoryScanner new directory scanner retrieved from the
     * <code>&lt;fileset/&gt;</code> element.
     */
    public void addDirectoryScanner(DirectoryScanner directoryScanner)
    {
        this.directoryScanners.add(directoryScanner);
    }

    /**
     * @return a list of base directories denoted by a list of directory
     * scanners.
     */
    public List<File> getBaseDirectories()
    {
        List<File> baseDirs = new ArrayList<>();
        Iterator<DirectoryScanner> scanners = directoryScanners.iterator();
        while (scanners.hasNext())
        {
            DirectoryScanner scanner = (DirectoryScanner)scanners.next();
            baseDirs.add(scanner.getBasedir());
        }

        return baseDirs;
    }

    /**
     * Checks if passed file is scanned by any of the directory scanners.
     *
     * @param pathToFile a fully qualified path to tested file.
     * @return true if so, false otherwise.
     */
    public boolean isIncluded(String pathToFile)
    {
        Iterator<DirectoryScanner> scanners = directoryScanners.iterator();
        while (scanners.hasNext())
        {
            DirectoryScanner scanner = (DirectoryScanner)scanners.next();
            scanner.scan();
            String[] includedFiles = scanner.getIncludedFiles();

            for (int i = 0; i < includedFiles.length; i++)
            {
                File includedFile = new File(scanner.getBasedir(), includedFiles[i]);
                if (pathToFile.equalsIgnoreCase(includedFile.getAbsolutePath()))
                {
                    return true;
                }
            }
        }

        return false;
    }
}
