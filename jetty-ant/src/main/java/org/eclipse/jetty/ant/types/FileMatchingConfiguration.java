//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings.
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

    private List directoryScanners;

    public FileMatchingConfiguration()
    {
        this.directoryScanners = new ArrayList();
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
    public List getBaseDirectories()
    {
        List baseDirs = new ArrayList();
        Iterator scanners = directoryScanners.iterator();
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
        Iterator scanners = directoryScanners.iterator();
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
