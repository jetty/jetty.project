//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.FilePermission;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.IO;
import org.junit.BeforeClass;
import org.junit.Test;


public class ResourceTest
{
    public static String __userDir = System.getProperty("basedir", ".");
    public static URL __userURL=null;
    private static String __relDir="";
    private static File tmpFile;

    private static final boolean DIR=true;
    private static final boolean EXISTS=true;

    static class Data
    {
        Resource resource;
        String test;
        boolean exists;
        boolean dir;
        String content;

        Data(Data data,String path,boolean exists, boolean dir)
            throws Exception
        {
            this.test=data.resource+"+"+path;
            resource=data.resource.addPath(path);
            this.exists=exists;
            this.dir=dir;
        }

        Data(Data data,String path,boolean exists, boolean dir, String content)
            throws Exception
        {
            this.test=data.resource+"+"+path;
            resource=data.resource.addPath(path);
            this.exists=exists;
            this.dir=dir;
            this.content=content;
        }

        Data(URL url,boolean exists, boolean dir)
            throws Exception
        {
            this.test=url.toString();
            this.exists=exists;
            this.dir=dir;
            resource=Resource.newResource(url);
        }

        Data(String url,boolean exists, boolean dir)
            throws Exception
        {
            this.test=url;
            this.exists=exists;
            this.dir=dir;
            resource=Resource.newResource(url);
        }

        Data(String url,boolean exists, boolean dir, String content)
            throws Exception
        {
            this.test=url;
            this.exists=exists;
            this.dir=dir;
            this.content=content;
            resource=Resource.newResource(url);
        }
    }

    public static Data[] data;

    /* ------------------------------------------------------------ */
    @BeforeClass
    public static void setUp()
    throws Exception
    {
        if (data!=null)
            return;

        File file = new File(__userDir);
        file=new File(file.getCanonicalPath());
        URI uri = file.toURI();
        __userURL=uri.toURL();
        
        __userURL = MavenTestingUtils.getTestResourcesDir().toURI().toURL();
	FilePermission perm = (FilePermission) __userURL.openConnection().getPermission();
	__userDir = new File(perm.getName()).getCanonicalPath() + File.separatorChar;
	__relDir = "src/test/resources/".replace('/', File.separatorChar);  
        
        //System.err.println("User Dir="+__userDir);
        //System.err.println("Rel  Dir="+__relDir);
        //System.err.println("User URL="+__userURL);

        tmpFile=File.createTempFile("test",null).getCanonicalFile();
        tmpFile.deleteOnExit();

        data = new Data[50];
        int i=0;

        data[i++]=new Data(tmpFile.toString(),EXISTS,!DIR);

        int rt=i;
        data[i++]=new Data(__userURL,EXISTS,DIR);
        data[i++]=new Data(__userDir,EXISTS,DIR);
        data[i++]=new Data(__relDir,EXISTS,DIR);
        data[i++]=new Data(__userURL+"resource.txt",EXISTS,!DIR);
        data[i++]=new Data(__userDir+"resource.txt",EXISTS,!DIR);
        data[i++]=new Data(__relDir+"resource.txt",EXISTS,!DIR);
        data[i++]=new Data(__userURL+"NoName.txt",!EXISTS,!DIR);
        data[i++]=new Data(__userDir+"NoName.txt",!EXISTS,!DIR);
        data[i++]=new Data(__relDir+"NoName.txt",!EXISTS,!DIR);

        data[i++]=new Data(data[rt],"resource.txt",EXISTS,!DIR);
        data[i++]=new Data(data[rt],"/resource.txt",EXISTS,!DIR);
        data[i++]=new Data(data[rt],"NoName.txt",!EXISTS,!DIR);
        data[i++]=new Data(data[rt],"/NoName.txt",!EXISTS,!DIR);

        int td=i;
        data[i++]=new Data(data[rt],"TestData",EXISTS,DIR);
        data[i++]=new Data(data[rt],"TestData/",EXISTS,DIR);
        data[i++]=new Data(data[td],"alphabet.txt",EXISTS,!DIR,"ABCDEFGHIJKLMNOPQRSTUVWXYZ");

        data[i++]=new Data("jar:file:/somejar.jar!/content/",!EXISTS,DIR);
        data[i++]=new Data("jar:file:/somejar.jar!/",!EXISTS,DIR);

        int tj=i;
        data[i++]=new Data("jar:"+__userURL+"TestData/test.zip!/",EXISTS,DIR);
        data[i++]=new Data(data[tj],"Unkown",!EXISTS,!DIR);
        data[i++]=new Data(data[tj],"/Unkown/",!EXISTS,DIR);

        data[i++]=new Data(data[tj],"subdir",EXISTS,DIR);
        data[i++]=new Data(data[tj],"/subdir/",EXISTS,DIR);
        data[i++]=new Data(data[tj],"alphabet",EXISTS,!DIR,
                           "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        data[i++]=new Data(data[tj],"/subdir/alphabet",EXISTS,!DIR,
                           "ABCDEFGHIJKLMNOPQRSTUVWXYZ");

        Resource base = Resource.newResource(__userDir);
        Resource dir0 = base.addPath("TestData");
        assertTrue(dir0.isDirectory());
        assertTrue(dir0.toString().endsWith("/"));
        assertTrue(dir0.getAlias()==null);
        Resource dir1 = base.addPath("TestData/");
        assertTrue(dir1.isDirectory());
        assertTrue(dir1.toString().endsWith("/"));
        assertTrue(dir1.getAlias()==null);


    }

    /* ------------------------------------------------------------ */
    @Test
    public void testResourceExists()
    {
        for (int i=0;i<data.length;i++)
        {
            if (data[i]==null)
                continue;

            assertEquals(""+i+":"+data[i].test,data[i].exists,data[i].resource.exists());
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testResourceDir()
    {
        for (int i=0;i<data.length;i++)
        {
            if (data[i]==null)
                continue;

            assertEquals(""+i+":"+data[i].test,data[i].dir,data[i].resource.isDirectory());
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testResourceContent()
        throws Exception
    {
        for (int i=0;i<data.length;i++)
        {
            if (data[i]==null || data[i].content==null)
                continue;

            InputStream in = data[i].resource.getInputStream();
            String c=IO.toString(in);
            assertTrue(""+i+":"+data[i].test,c.startsWith(data[i].content));
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testEncoding() throws Exception
    {
        Resource r =Resource.newResource("/tmp/a file with,spe#ials/");
        assertTrue(r.getURL().toString().indexOf("a%20file%20with,spe%23ials")>0);
        assertTrue(r.getFile().toString().indexOf("a file with,spe#ials")>0);
        r.delete();
        assertFalse("File should have been deleted.",r.exists());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testJarFile()
    throws Exception
    {
        String s = "jar:"+__userURL+"TestData/test.zip!/subdir/";
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

        s = "jar:"+__userURL+"TestData/test.zip!/subdir/subsubdir/";
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
        String s = "jar:"+__userURL+"TestData/test.zip!/subdir/";
        Resource r = Resource.newResource(s);
        Collection<Resource> deep=r.getAllResources();
        
        assertEquals(4, deep.size());
    }
    
    @Test
    public void testJarFileIsContainedIn ()
    throws Exception
    {
        String s = "jar:"+__userURL+"TestData/test.zip!/subdir/";
        Resource r = Resource.newResource(s);
        Resource container = Resource.newResource(__userURL+"TestData/test.zip");

        assertTrue(r instanceof JarFileResource);
        JarFileResource jarFileResource = (JarFileResource)r;

        assertTrue(jarFileResource.isContainedIn(container));

        container = Resource.newResource(__userURL+"TestData");
        assertFalse(jarFileResource.isContainedIn(container));
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testJarFileLastModified ()
    throws Exception
    {
        String s = "jar:"+__userURL+"TestData/test.zip!/subdir/numbers";

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
        String s = "jar:"+__userURL+"TestData/extract.zip!/";
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

    /**
     * Test a class path resource for existence.
     */
    @Test
    public void testClassPathResourceClassRelative()
    {
        final String classPathName="Resource.class";

        try(Resource resource=Resource.newClassPathResource(classPathName);)
        {
            // A class path cannot be a directory
            assertFalse("Class path cannot be a directory.",resource.isDirectory());

            // A class path must exist
            assertTrue("Class path resource does not exist.",resource.exists());
        }
    }

    /**
     * Test a class path resource for existence.
     */
    @Test
    public void testClassPathResourceClassAbsolute()
    {
        final String classPathName="/org/eclipse/jetty/util/resource/Resource.class";

        Resource resource=Resource.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse("Class path cannot be a directory.",resource.isDirectory());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }

    /**
     * Test a class path resource for directories.
     */
    @Test
    public void testClassPathResourceDirectory() throws Exception
    {
        final String classPathName="/";

        Resource resource=Resource.newClassPathResource(classPathName);

        // A class path must be a directory
        assertTrue("Class path must be a directory.",resource.isDirectory());

        assertTrue("Class path returned file must be a directory.",resource.getFile().isDirectory());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }

    /**
     * Test a class path resource for a file.
     */
    @Test
    public void testClassPathResourceFile() throws Exception
    {
        final String fileName="resource.txt";
        final String classPathName="/"+fileName;

        // Will locate a resource in the class path
        Resource resource=Resource.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse("Class path must be a directory.",resource.isDirectory());

        assertTrue(resource!=null);

        File file=resource.getFile();

        assertEquals("File name from class path is not equal.",fileName,file.getName());
        assertTrue("File returned from class path should be a file.",file.isFile());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }

    @Test
    public void testUncPathResourceFile() throws Exception
    {
        // This test is intended to run only on Windows platform
        assumeTrue(OS.IS_WINDOWS);

        String path = __userURL.toURI().getPath().replace('/','\\')+"resource.txt";
        //System.err.println(path);

        Resource resource = Resource.newResource(path, false);
        //System.err.println(resource);
        assertTrue(resource.exists());

        /*

        String uncPath = "\\\\127.0.0.1"+__userURL.toURI().getPath().replace('/','\\').replace(':','$')+"ResourceTest.java";
        System.err.println(uncPath);

        Resource uncResource = Resource.newResource(uncPath, false);
        System.err.println(uncResource);
        assertTrue(uncResource.exists());

        */
    }
}
