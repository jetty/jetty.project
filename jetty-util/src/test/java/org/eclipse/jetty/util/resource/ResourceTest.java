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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ResourceTest
{
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
        
        Data(URI uri,boolean exists, boolean dir)
                throws Exception
        {
            this.test=uri.toASCIIString();
            this.exists=exists;
            this.dir=dir;
            resource=Resource.newResource(uri);
        }
        
        Data(File file,boolean exists, boolean dir)
                throws Exception
        {
            this.test=file.toString();
            this.exists=exists;
            this.dir=dir;
            resource=Resource.newResource(file);
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
        
        @Override
        public String toString()
        {
            return this.test;
        }
    }
    
    static class UseCases
    {
        final Collection<Data[]> data;
        final File fileRef;
        final URI uriRef;
        final String relRef;
        
        final Data[] baseCases;
        
        public UseCases(String ref) throws Exception {
            this.data = new ArrayList<Data[]>();
            // relative directory reference
            this.relRef = OS.separators(ref);
            // File object reference
            this.fileRef = MavenTestingUtils.getProjectDir(relRef);
            // URI reference
            this.uriRef = fileRef.toURI();
            
            // create baseline cases
            baseCases = new Data[] { 
                new Data(relRef,EXISTS,DIR), 
                new Data(uriRef,EXISTS,DIR), 
                new Data(fileRef,EXISTS,DIR) 
            };
            
            // add all baseline cases
            for (Data bcase : baseCases)
            {
                addCase(bcase);
            }
        }
        
        public void addCase(Data ucase)
        {
            this.data.add(new Data[]{ ucase });
        }
        
        public void addAllSimpleCases(String subpath, boolean exists, boolean dir) 
            throws Exception
        {
            addCase(new Data(OS.separators(relRef + subpath), exists, dir));
            addCase(new Data(uriRef.resolve(subpath).toURL(), exists, dir));
            addCase(new Data(new File(fileRef,subpath),exists, dir));
        }
        
        public Data addAllAddPathCases(String subpath, boolean exists, boolean dir) throws Exception
        {
            Data bdata = null;
            
            for (Data bcase : baseCases)
            {
                bdata = new Data(bcase, subpath, exists, dir);
                addCase(bdata);
            }
            
            return bdata;
        }
    }
    

    @Parameters(name="{0}")
    public static Collection<Data[]> data() throws Exception
    {
        UseCases cases = new UseCases("src/test/resources/");
        
        File testDir = MavenTestingUtils.getTargetTestingDir(ResourceTest.class.getName());
        FS.ensureEmpty(testDir);
        File tmpFile = File.createTempFile("test",null,testDir);
        
        cases.addCase(new Data(tmpFile.toString(),EXISTS,!DIR));
        
        // Some resource references.
        cases.addAllSimpleCases("resource.txt",EXISTS,!DIR);
        cases.addAllSimpleCases("NoName.txt",!EXISTS,!DIR);
        
        // Some addPath() forms
        cases.addAllAddPathCases("resource.txt",EXISTS,!DIR);
        cases.addAllAddPathCases("/resource.txt",EXISTS,!DIR);
        cases.addAllAddPathCases("//resource.txt",EXISTS,!DIR);
        cases.addAllAddPathCases("NoName.txt",!EXISTS,!DIR);
        cases.addAllAddPathCases("/NoName.txt",!EXISTS,!DIR);
        cases.addAllAddPathCases("//NoName.txt",!EXISTS,!DIR);

        Data tdata1 = cases.addAllAddPathCases("TestData", EXISTS, DIR);
        Data tdata2 = cases.addAllAddPathCases("TestData/", EXISTS, DIR);
        
        cases.addCase(new Data(tdata1, "alphabet.txt", EXISTS,!DIR,"ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        cases.addCase(new Data(tdata2, "alphabet.txt", EXISTS,!DIR,"ABCDEFGHIJKLMNOPQRSTUVWXYZ"));

        cases.addCase(new Data("jar:file:/somejar.jar!/content/",!EXISTS,DIR));
        cases.addCase(new Data("jar:file:/somejar.jar!/",!EXISTS,DIR));

        String urlRef = cases.uriRef.toASCIIString();
        Data zdata = new Data("jar:"+urlRef +"TestData/test.zip!/",EXISTS,DIR);
        cases.addCase(zdata);
        cases.addCase(new Data(zdata,"Unkown",!EXISTS,!DIR));
        cases.addCase(new Data(zdata,"/Unkown/",!EXISTS,DIR));

        cases.addCase(new Data(zdata,"subdir",EXISTS,DIR));
        cases.addCase(new Data(zdata,"/subdir/",EXISTS,DIR));
        cases.addCase(new Data(zdata,"alphabet",EXISTS,!DIR,
                           "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        cases.addCase(new Data(zdata,"/subdir/alphabet",EXISTS,!DIR,
                           "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        
        cases.addAllAddPathCases("/TestData/test/subdir/subsubdir/",EXISTS,DIR);
        cases.addAllAddPathCases("//TestData/test/subdir/subsubdir/",EXISTS,DIR);
        cases.addAllAddPathCases("/TestData//test/subdir/subsubdir/",EXISTS,DIR);
        cases.addAllAddPathCases("/TestData/test//subdir/subsubdir/",EXISTS,DIR);
        cases.addAllAddPathCases("/TestData/test/subdir//subsubdir/",EXISTS,DIR);
        
        cases.addAllAddPathCases("TestData/test/subdir/subsubdir/",EXISTS,DIR);
        cases.addAllAddPathCases("TestData/test/subdir/subsubdir//",EXISTS,DIR);
        cases.addAllAddPathCases("TestData/test/subdir//subsubdir/",EXISTS,DIR);
        cases.addAllAddPathCases("TestData/test//subdir/subsubdir/",EXISTS,DIR);

        cases.addAllAddPathCases("/TestData/../TestData/test/subdir/subsubdir/",EXISTS,DIR);

        return cases.data;
    }
    
    @Parameter(value=0)
    public Data data;

    @Test
    public void testResourceExists()
    {
        assertThat("Exists: " + data.resource.getName(), data.resource.exists(), equalTo(data.exists));
    }

    @Test
    public void testResourceDir()
    {
        assertThat("Is Directory: " + data.test, data.resource.isDirectory(),equalTo(data.dir));
    }

    @Test
    public void testEncodeAddPath ()
    throws Exception
    {
        if (data.dir)
        {
            Resource r = data.resource.addPath("foo%/b r");
            Assert.assertThat(r.getURI().toString(),Matchers.endsWith("/foo%25/b%20r"));
        }
    }
    
    @Test
    public void testResourceContent()
        throws Exception
    {
        assumeThat(data.content, notNullValue());
        
        InputStream in = data.resource.getInputStream();
        String c = IO.toString(in);
        assertThat("Content: " + data.test,c,startsWith(data.content));
    }
}
