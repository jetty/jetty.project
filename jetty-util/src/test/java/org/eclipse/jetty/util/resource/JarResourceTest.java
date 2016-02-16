//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.Test;

public class JarResourceTest
{
    private String testResURI = MavenTestingUtils.getTestResourcesDir().getAbsoluteFile().toURI().toASCIIString();

    @Test
    public void testJarFile()
    throws Exception
    {
        String s = "jar:"+testResURI+"TestData/test.zip!/subdir/";
        Resource r = Resource.newResource(s);

        Set<String> entries = new HashSet<>(Arrays.asList(r.list()));
        assertEquals(3,entries.size());
        assertTrue(entries.contains("alphabet"));
        assertTrue(entries.contains("numbers"));
        assertTrue(entries.contains("subsubdir/"));

        File extract = File.createTempFile("extract", null);
        if (extract.exists())
            extract.delete();
        extract.mkdir();
        extract.deleteOnExit();

        r.copyTo(extract);

        Resource e = Resource.newResource(extract.getAbsolutePath());

        entries = new HashSet<>(Arrays.asList(e.list()));
        assertEquals(3,entries.size());
        assertTrue(entries.contains("alphabet"));
        assertTrue(entries.contains("numbers"));
        assertTrue(entries.contains("subsubdir/"));
        IO.delete(extract);

        s = "jar:"+testResURI+"TestData/test.zip!/subdir/subsubdir/";
        r = Resource.newResource(s);

        entries = new HashSet<>(Arrays.asList(r.list()));
        assertEquals(2,entries.size());
        assertTrue(entries.contains("alphabet"));
        assertTrue(entries.contains("numbers"));

        extract = File.createTempFile("extract", null);
        if (extract.exists())
            extract.delete();
        extract.mkdir();
        extract.deleteOnExit();

        r.copyTo(extract);

        e = Resource.newResource(extract.getAbsolutePath());

        entries = new HashSet<>(Arrays.asList(e.list()));
        assertEquals(2,entries.size());
        assertTrue(entries.contains("alphabet"));
        assertTrue(entries.contains("numbers"));
        IO.delete(extract);

    }

    /* ------------------------------------------------------------ */
    @Test
    public void testJarFileGetAllResoures()
    throws Exception
    {
        String s = "jar:"+testResURI+"TestData/test.zip!/subdir/";
        Resource r = Resource.newResource(s);
        Collection<Resource> deep=r.getAllResources();
        
        assertEquals(4, deep.size());
    }
    
    @Test
    public void testJarFileIsContainedIn ()
    throws Exception
    {
        String s = "jar:"+testResURI+"TestData/test.zip!/subdir/";
        Resource r = Resource.newResource(s);
        Resource container = Resource.newResource(testResURI+"TestData/test.zip");

        assertTrue(r instanceof JarFileResource);
        JarFileResource jarFileResource = (JarFileResource)r;

        assertTrue(jarFileResource.isContainedIn(container));

        container = Resource.newResource(testResURI+"TestData");
        assertFalse(jarFileResource.isContainedIn(container));
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testJarFileLastModified ()
    throws Exception
    {
        String s = "jar:"+testResURI+"TestData/test.zip!/subdir/numbers";

        try(ZipFile zf = new ZipFile(MavenTestingUtils.getTestResourceFile("TestData/test.zip")))
        {
            long last = zf.getEntry("subdir/numbers").getTime();

            Resource r = Resource.newResource(s);
            assertEquals(last,r.lastModified());
        }
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testJarFileCopyToDirectoryTraversal () throws Exception
    {
        String s = "jar:"+testResURI+"TestData/extract.zip!/";
        Resource r = Resource.newResource(s);

        assertTrue(r instanceof JarResource);
        JarResource jarResource = (JarResource)r;

        File destParent = File.createTempFile("copyjar", null);
        if (destParent.exists())
            destParent.delete();
        destParent.mkdir();
        destParent.deleteOnExit();

        File dest = new File(destParent.getCanonicalPath()+"/extract");
        if(dest.exists())
            dest.delete();
        dest.mkdir();
        dest.deleteOnExit();

        jarResource.copyTo(dest);

        // dest contains only the valid entry; dest.getParent() contains only the dest directory
        assertEquals(1, dest.listFiles().length);
        assertEquals(1, dest.getParentFile().listFiles().length);

        FilenameFilter dotdotFilenameFilter = new FilenameFilter() {
            @Override
            public boolean accept(File directory, String name)
            {
                return name.equals("dotdot.txt");
            }
        };
        assertEquals(0, dest.listFiles(dotdotFilenameFilter).length);
        assertEquals(0, dest.getParentFile().listFiles(dotdotFilenameFilter).length);

        FilenameFilter extractfileFilenameFilter = new FilenameFilter() {
            @Override
            public boolean accept(File directory, String name)
            {
                return name.equals("extract-filenotdir");
            }
        };
        assertEquals(0, dest.listFiles(extractfileFilenameFilter).length);
        assertEquals(0, dest.getParentFile().listFiles(extractfileFilenameFilter).length);

        FilenameFilter currentDirectoryFilenameFilter = new FilenameFilter() {
            @Override
            public boolean accept(File directory, String name)
            {
                return name.equals("current.txt");
            }
        };
        assertEquals(1, dest.listFiles(currentDirectoryFilenameFilter).length);
        assertEquals(0, dest.getParentFile().listFiles(currentDirectoryFilenameFilter).length);

        IO.delete(dest);
        assertFalse(dest.exists());
    }


}
