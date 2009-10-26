// ========================================================================
// Copyright (c) 1997-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util.resource;


import java.io.File;
import java.io.FilePermission;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.net.URL;
import java.util.jar.JarInputStream;

import junit.framework.TestSuite;

import org.eclipse.jetty.util.IO;

public class ResourceTest extends junit.framework.TestCase
{

    public static String __userDir = System.getProperty("basedir", ".");
    public static URL __userURL=null;
    private static String __relDir="";
    private static File tmpFile;

    private static final boolean DIR=true;
    private static final boolean EXISTS=true;
    
    class Data
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
    
    public ResourceTest(String name)
    {
        super(name);
    }

    /* ------------------------------------------------------------ */
    public static void main(String[] args)
    {
        junit.textui.TestRunner.run(suite());
    }
    
    /* ------------------------------------------------------------ */
    public static junit.framework.Test suite()
    {
        return new TestSuite(ResourceTest.class);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void setUp()
        throws Exception
    {
        if (data!=null)
            return;
        
        File file = new File(__userDir);
        file=new File(file.getCanonicalPath());
        __userURL=file.toURL();
        
        __userURL = new URL(__userURL.toString() + "src/test/java/org/eclipse/jetty/util/resource/");
		FilePermission perm = (FilePermission) __userURL.openConnection().getPermission();
		__userDir = new File(perm.getName()).getCanonicalPath() + File.separatorChar;
		__relDir = "src/test/java/org/eclipse/jetty/util/resource/".replace('/', File.separatorChar);  
        
        System.err.println("User Dir="+__userDir);
        System.err.println("Rel  Dir="+__relDir);
        System.err.println("User URL="+__userURL);

        tmpFile=File.createTempFile("test",null).getCanonicalFile();
        tmpFile.deleteOnExit();
        
        data = new Data[50];
        int i=0;

        data[i++]=new Data(tmpFile.toString(),EXISTS,!DIR);
        
        int rt=i;
        data[i++]=new Data(__userURL,EXISTS,DIR);
        data[i++]=new Data(__userDir,EXISTS,DIR);
        data[i++]=new Data(__relDir,EXISTS,DIR);
        data[i++]=new Data(__userURL+"ResourceTest.java",EXISTS,!DIR);
        data[i++]=new Data(__userDir+"ResourceTest.java",EXISTS,!DIR);
        data[i++]=new Data(__relDir+"ResourceTest.java",EXISTS,!DIR);
        data[i++]=new Data(__userURL+"NoName.txt",!EXISTS,!DIR);
        data[i++]=new Data(__userDir+"NoName.txt",!EXISTS,!DIR);
        data[i++]=new Data(__relDir+"NoName.txt",!EXISTS,!DIR);

        data[i++]=new Data(data[rt],"ResourceTest.java",EXISTS,!DIR);
        data[i++]=new Data(data[rt],"/ResourceTest.java",EXISTS,!DIR);
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
    @Override
    protected void tearDown()
        throws Exception
    {
    }
    

    /* ------------------------------------------------------------ */
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
    public void testEncoding() throws Exception
    {
        Resource r =Resource.newResource("/tmp/a file with,spe#ials/");
        assertTrue(r.getURL().toString().indexOf("a%20file%20with,spe%23ials")>0);
        assertTrue(r.getFile().toString().indexOf("a file with,spe#ials")>0);
    }

    /* ------------------------------------------------------------ */
    public void testJarFile()
    throws Exception
    {
      
        String s = "jar:"+__userURL+"TestData/test.zip!/subdir/";
        Resource r = Resource.newResource(s);
        InputStream is = r.getInputStream();        
        JarInputStream jin = new JarInputStream(is);
        assertNotNull(is);
        assertNotNull(jin);
        
    }
    
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
            public boolean accept(File directory, String name)
            {
                return name.equals("dotdot.txt");
            }
        };        
        assertEquals(0, dest.listFiles(dotdotFilenameFilter).length);
        assertEquals(0, dest.getParentFile().listFiles(dotdotFilenameFilter).length);

        FilenameFilter extractfileFilenameFilter = new FilenameFilter() {
            public boolean accept(File directory, String name)
            {
                return name.equals("extract-filenotdir");
            }
        };
        assertEquals(0, dest.listFiles(extractfileFilenameFilter).length);
        assertEquals(0, dest.getParentFile().listFiles(extractfileFilenameFilter).length);

        FilenameFilter currentDirectoryFilenameFilter = new FilenameFilter() {
            public boolean accept(File directory, String name)
            {
                return name.equals("current.txt");
            }
        };
        assertEquals(1, dest.listFiles(currentDirectoryFilenameFilter).length);
        assertEquals(0, dest.getParentFile().listFiles(currentDirectoryFilenameFilter).length);        
    }
    
    /**
     * Test a class path resource for existence.
     */
    public void testClassPathResourceClassRelative()
    {
        final String classPathName="Resource.class";

        Resource resource=Resource.newClassPathResource(classPathName);

        assertTrue(resource!=null);

        // A class path cannot be a directory
        assertFalse("Class path cannot be a directory.",resource.isDirectory());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }

    /**
     * Test a class path resource for existence.
     */
    public void testClassPathResourceClassAbsolute()
    {
        final String classPathName="/org/eclipse/jetty/util/resource/Resource.class";

        Resource resource=Resource.newClassPathResource(classPathName);

        assertTrue(resource!=null);

        // A class path cannot be a directory
        assertFalse("Class path cannot be a directory.",resource.isDirectory());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }

    /**
     * Test a class path resource for directories.
     */
    public void testClassPathResourceDirectory() throws Exception
    {
        final String classPathName="/";

        Resource resource=Resource.newClassPathResource(classPathName);

        
        assertTrue(resource!=null);
        
        // A class path must be a directory
        assertTrue("Class path must be a directory.",resource.isDirectory());

        assertTrue("Class path returned file must be a directory.",resource.getFile().isDirectory());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }

    /**
     * Test a class path resource for a file.
     */
    public void testClassPathResourceFile() throws Exception
    {
        final String fileName="resource.txt";
        final String classPathName="/"+fileName;

        // Will locate a resource in the class path
        Resource resource=Resource.newClassPathResource(classPathName);

        assertTrue(resource!=null);
        
        // A class path cannot be a directory
        assertFalse("Class path must be a directory.",resource.isDirectory());

        assertTrue(resource!=null);
        
        File file=resource.getFile();

        assertTrue("File returned from class path should not be null.",file!=null);
        assertEquals("File name from class path is not equal.",fileName,file.getName());
        assertTrue("File returned from class path should be a file.",file.isFile());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }
    
    
    
    
}
