// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.webapp.verifier;

import java.io.File;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * AbstractArchiveScanningRule for Rules that scan the archive contents.
 */
public abstract class AbstractArchiveScanningRule extends AbstractRule
{
    public abstract String getDescription();

    public abstract String getName();

    @Override
    public void visitWebInfLibJar(String path, File archive, JarFile jar)
    {
        scanClassesInArchive(path,jar);
    }

    @Override
    public void visitWebInfLibZip(String path, File archive, ZipFile zip)
    {
        scanClassesInArchive(path,zip);
    }

    private String asClassname(String path)
    {
        StringBuffer name = new StringBuffer();
        for (char c : path.toCharArray())
        {
            if (c == '/')
            {
                name.append(".");
            }
            else
            {
                name.append(c);
            }
        }
        if (name.toString().endsWith(".class"))
        {
            name.delete(name.length() - 6,name.length() - 1);
        }
        return name.toString();
    }

    private void scanClassesInArchive(String path, ZipFile zip)
    {
        String className;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements())
        {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().endsWith(".class"))
            {
                className = asClassname(entry.getName());
                visitArchiveClass(path + "!/" + entry.getName(),className,zip,entry);
            }
            else
            {
                visitArchiveResource(path + "!/" + entry.getName(),zip,entry);
            }
        }
    }

    public void visitArchiveResource(String path, ZipFile zip, ZipEntry entry)
    {
        /* override to do something with */
    }

    public void visitArchiveClass(String path, String className, ZipFile archive, ZipEntry archiveEntry)
    {
        /* override to do something with */
    }
}
