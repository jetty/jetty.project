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

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public class ContextHandlerGetResourceTest
{
    private static boolean OS_ALIAS_SUPPORTED;
    private static Server server;
    private static ContextHandler context;
    private static File docroot;
    private static File otherroot;
    private final static AtomicBoolean allowAliases= new AtomicBoolean(false);
    private final static AtomicBoolean allowSymlinks= new AtomicBoolean(false);
    
    @BeforeClass
    public static void beforeClass()  throws Exception
    {
        File testRoot = MavenTestingUtils.getTargetTestingDir(ContextHandlerGetResourceTest.class.getSimpleName());
        FS.ensureEmpty(testRoot);
        docroot = new File(testRoot,"docroot").getCanonicalFile().getAbsoluteFile();
        FS.ensureEmpty(docroot);
        File index = new File(docroot,"index.html");
        index.createNewFile();
        File sub = new File(docroot,"subdir");
        sub.mkdir();
        File data = new File(sub,"data.txt");
        data.createNewFile();
        File verylong = new File(sub,"TextFile.Long.txt");
        verylong.createNewFile();

        otherroot = new File(testRoot, "otherroot").getCanonicalFile().getAbsoluteFile();
        FS.ensureEmpty(otherroot);
        File other = new File(otherroot,"other.txt");
        other.createNewFile();
        
        File transit = new File(docroot.getParentFile(),"transit");
        transit.delete();
        
        if (OS.IS_UNIX)
        {
            // Create alias as 8.3 name so same test will produce an alias on both windows an normal systems
            File eightDotThree=new File(sub,"TEXTFI~1.TXT");
            Files.createSymbolicLink(eightDotThree.toPath(),verylong.toPath());
            
            Files.createSymbolicLink(new File(docroot,"other").toPath(),new File("../transit").toPath());
            Files.createSymbolicLink(transit.toPath(),otherroot.toPath());
            
            // /web/logs -> /var/logs -> /media/internal/logs
            // where /media/internal -> /media/internal-physical/
            new File(docroot,"media/internal-physical/logs").mkdirs();
            Files.createSymbolicLink(new File(docroot,"media/internal").toPath(),new File(docroot,"media/internal-physical").toPath());
            new File(docroot,"var").mkdir();
            Files.createSymbolicLink(new File(docroot,"var/logs").toPath(),new File(docroot,"media/internal/logs").toPath());
            new File(docroot,"web").mkdir();
            Files.createSymbolicLink(new File(docroot,"web/logs").toPath(),new File(docroot,"var/logs").toPath()); 
            new File(docroot,"media/internal-physical/logs/file.log").createNewFile();
            
            System.err.println("docroot="+docroot);
        }
        
        OS_ALIAS_SUPPORTED = new File(sub, "TEXTFI~1.TXT").exists(); 
        
        server = new Server();
        context =new ContextHandler("/");
        context.clearAliasChecks();
        context.addAliasCheck(new ContextHandler.ApproveNonExistentDirectoryAliases());
        context.setBaseResource(Resource.newResource(docroot));
        context.addAliasCheck(new ContextHandler.AliasCheck()
        {
            final AllowSymLinkAliasChecker symlinkcheck = new AllowSymLinkAliasChecker();
            @Override
            public boolean check(String path, Resource resource)
            {
                if (allowAliases.get())
                    return true;
                if (allowSymlinks.get())
                    return symlinkcheck.check(path,resource);
                return allowAliases.get();
            }
        });
        
        server.setHandler(context);
        server.start();
    }
    
    @AfterClass
    public static void afterClass()  throws Exception
    {
        server.stop(); 
    }


    @Test
    public void testBadPath() throws Exception
    {
        final String path="bad";
        try
        {
            context.getResource(path);
            fail("Expected " + MalformedURLException.class);
        }
        catch(MalformedURLException e)
        {
        }
        
        try
        {
            context.getServletContext().getResource(path);
            fail("Expected " + MalformedURLException.class);
        }
        catch(MalformedURLException e)
        {
        }
    }
    
    @Test
    public void testGetUnknown() throws Exception
    {
        final String path="/unknown.txt";
        Resource resource=context.getResource(path);
        assertEquals("unknown.txt",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }
    
    @Test
    public void testGetUnknownDir() throws Exception
    {
        final String path="/unknown/";
        Resource resource=context.getResource(path);
        assertEquals("unknown",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testRoot() throws Exception
    {
        final String path="/";
        Resource resource=context.getResource(path);
        assertEquals(docroot,resource.getFile());
        assertTrue(resource.exists());
        assertTrue(resource.isDirectory());
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()));
    }

    @Test
    public void testSubdir() throws Exception
    {
        final String path="/subdir";
        Resource resource=context.getResource(path);
        assertEquals(docroot,resource.getFile().getParentFile());
        assertTrue(resource.exists());
        assertTrue(resource.isDirectory());
        assertTrue(resource.toString().endsWith("/"));
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile());
    }

    @Test
    public void testSubdirSlash() throws Exception
    {
        final String path="/subdir/";
        Resource resource=context.getResource(path);
        assertEquals(docroot,resource.getFile().getParentFile());
        assertTrue(resource.exists());
        assertTrue(resource.isDirectory());
        assertTrue(resource.toString().endsWith("/"));
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile());
    }

    @Test
    public void testGetKnown() throws Exception
    {
        final String path="/index.html";
        Resource resource=context.getResource(path);
        assertEquals("index.html",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertTrue(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile());
    }

    @Test
    public void testNormalize() throws Exception
    {
        final String path="/down/.././index.html";
        Resource resource=context.getResource(path);
        assertEquals("index.html",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertTrue(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile());
    }

    @Test
    public void testTooNormal() throws Exception
    {
        final String path="/down/.././../";
        Resource resource=context.getResource(path);
        assertNull(resource);
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testDeep() throws Exception
    {
        final String path="/subdir/data.txt";
        Resource resource=context.getResource(path);
        assertEquals("data.txt",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile().getParentFile());
        assertTrue(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile().getParentFile());
    }

    @Test
    public void testEncodedSlash() throws Exception
    {
        final String path="/subdir%2Fdata.txt";
        
        Resource resource=context.getResource(path);
        assertEquals("subdir%2Fdata.txt",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testEncodedSlosh() throws Exception
    {
        final String path="/subdir%5Cdata.txt";
        
        Resource resource=context.getResource(path);
        assertEquals("subdir%5Cdata.txt",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testEncodedNull() throws Exception
    {
        final String path="/subdir/data.txt%00";
        
        Resource resource=context.getResource(path);
        assertEquals("data.txt%00",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }
    

    @Test
    public void testSlashSlash() throws Exception
    {
        File expected = new File(docroot, OS.separators("subdir/data.txt"));
        URL expectedUrl = expected.toURI().toURL();
        
        String path="//subdir/data.txt";
        Resource resource=context.getResource(path);
        assertThat("Resource: " + resource, resource, nullValue());
        URL url=context.getServletContext().getResource(path);
        assertThat("Resource: " + url, url, nullValue());

        path="/subdir//data.txt";
        resource=context.getResource(path);
        assertThat("Resource: " + resource, resource, nullValue());
        url=context.getServletContext().getResource(path);
        assertThat("Resource: " + url, url, nullValue());
    }

    @Test
    public void testAliasedFile() throws Exception
    {
        Assume.assumeTrue("OS Supports 8.3 Aliased / Alternate References",OS_ALIAS_SUPPORTED);
        final String path="/subdir/TEXTFI~1.TXT";
        
        Resource resource=context.getResource(path);
        assertNull(resource);
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testAliasedFileAllowed() throws Exception
    {
        Assume.assumeTrue("OS Supports 8.3 Aliased / Alternate References",OS_ALIAS_SUPPORTED);
        try
        {
            allowAliases.set(true);
            final String path="/subdir/TEXTFI~1.TXT";

            Resource resource=context.getResource(path);
            assertNotNull(resource);
            assertEquals(context.getResource("/subdir/TextFile.Long.txt").getURI(),resource.getAlias());
            
            URL url=context.getServletContext().getResource(path);
            assertNotNull(url);
            assertEquals(docroot,new File(url.toURI()).getParentFile().getParentFile());
        }
        finally
        {
            allowAliases.set(false);
        }
        
    }

    @Test
    public void testSymlinkKnown() throws Exception
    {
        Assume.assumeTrue(OS.IS_UNIX);
        
        try
        {
            allowSymlinks.set(true);

            final String path="/other/other.txt";

            Resource resource=context.getResource(path);
            assertNotNull(resource);
            assertEquals("other.txt",resource.getFile().getName());
            assertEquals(docroot,resource.getFile().getParentFile().getParentFile());
            assertTrue(resource.exists());

            URL url=context.getServletContext().getResource(path);
            assertEquals(docroot,new File(url.toURI()).getParentFile().getParentFile());
        }
        finally
        {
            allowSymlinks.set(false);
        } 
        
    }
    
    @Test
    public void testSymlinkNested() throws Exception
    {
        Assume.assumeTrue(OS.IS_UNIX);
        
        try
        {
            allowSymlinks.set(true);

            final String path="/web/logs/file.log";

            Resource resource=context.getResource(path);
            assertNotNull(resource);
            assertEquals("file.log",resource.getFile().getName());
            assertTrue(resource.exists());
        }
        finally
        {
            allowSymlinks.set(false);
        } 

    }

    @Test
    public void testSymlinkUnknown() throws Exception
    {
        if (!OS.IS_UNIX)
            return;
        try
        {
            allowSymlinks.set(true);

            final String path="/other/unknown.txt";

            Resource resource=context.getResource(path);
            assertNotNull(resource);
            assertEquals("unknown.txt",resource.getFile().getName());
            assertEquals(docroot,resource.getFile().getParentFile().getParentFile());
            assertFalse(resource.exists());

            URL url=context.getServletContext().getResource(path);
            assertNull(url);
        }
        finally
        {
            allowSymlinks.set(false);
        }
    }
}
