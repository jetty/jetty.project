//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.*;

import java.io.File;

import junit.framework.Assert;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.FileResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class ContextHandlerAliasTest
{
    private static final Logger LOG = Log.getLogger(ContextHandlerAliasTest.class);
	
    private Server _server;
    private ContextHandler _ctx;
    private File _tmp;
    private File _dir;
    
    /**
     * Create a symlink using java.nio.file.Files technique.
     * Ignore test if JDK7 not present or not found
     * 
     * @param link the file of the symbolic link to create
     * @param target the target of the sumbolic link
     */
	private void createNIOSymlink(File link, File target) {
		try {
			Class<?> cFiles = Class.forName("java.nio.file.Files", false, Thread.currentThread().getContextClassLoader());
			Object pathLink = TypeUtil.call(File.class, "toPath", link, new Object[] {});
			Object pathTarget = TypeUtil.call(File.class, "toPath", link, new Object[] {});

			TypeUtil.call(cFiles, "createSymbolicLink", cFiles, new Object[] { pathLink, pathTarget });
		} catch (Exception e) {
			LOG.debug("Ignoring lack of JDK7", e);
			Assume.assumeNoException(e);
		}
	}

    @Before
    public void before() throws Exception
    {
        _server=new Server();
        _ctx = new ContextHandler();
        _server.setHandler(_ctx);
        
        _tmp = MavenTestingUtils.getTargetTestingDir(ContextHandlerAliasTest.class.getName()).getCanonicalFile();
        FS.ensureEmpty(_tmp);
        
        File root = new File(_tmp,getClass().getName());
        assertTrue(root.mkdir());

        File webInf = new File(root,"WEB-INF");
        assertTrue(webInf.mkdir());

        assertTrue(new File(webInf,"jsp").mkdir());
        assertTrue(new File(webInf,"web.xml").createNewFile());
        assertTrue(new File(root,"index.html").createNewFile());

        _dir=root;
        _ctx.setBaseResource(Resource.newResource(_dir));
        _server.start();
    }

    @After
    public void after() throws Exception
    {
        _server.stop();
        if (_tmp!=null && _tmp.exists())
            IO.delete(_tmp);
    }
    
    @Test
    public void testGetResources() throws Exception
    {
        Resource r =_ctx.getResource("/index.html");
        Assert.assertTrue(r.exists());
    }
    
    @Test
    public void testJvmNullBugAlias() throws Exception
    {
        // JVM Files ignores null characters at end of name
        String normal="/index.html";
        String withnull="/index.html\u0000";
        
        _ctx.setAliases(true);
        Assert.assertTrue(_ctx.getResource(normal).exists());
        Assert.assertTrue(_ctx.getResource(withnull).exists());
        _ctx.setAliases(false);
        Assert.assertTrue(_ctx.getResource(normal).exists());
        Assert.assertNull(_ctx.getResource(withnull));
    }
    
    @Test
    public void testSymLinkToContext() throws Exception
    {
        File symlink = new File(_tmp,"symlink");
        try
        {
        	createNIOSymlink(symlink, _dir);

            _server.stop();
            _ctx.setBaseResource(FileResource.newResource(symlink));
            _ctx.setAliases(false);
            _server.start();

            Resource r =_ctx.getResource("/index.html");
            Assert.assertTrue(r.exists());
        }
        finally
        {
            symlink.delete();
        }
    }
    
	@Test
    public void testSymLinkToContent() throws Exception
    {
        File symlink = new File(_dir,"link.html");
        try
        {
            createNIOSymlink(symlink, new File(_dir,"index.html"));

            _ctx.setAliases(true);
            Assert.assertTrue(_ctx.getResource("/index.html").exists());
            Assert.assertTrue(_ctx.getResource("/link.html").exists());
            
            _ctx.setAliases(false);
            Assert.assertTrue(_ctx.getResource("/index.html").exists());
            Assert.assertNull(_ctx.getResource("/link.html"));
            
        }
        finally
        {
            symlink.delete();
        }
    }

    @Test
    public void testSymLinkToContentWithSuffixCheck() throws Exception
    {
        File symlink = new File(_dir,"link.html");
        try
        {
        	createNIOSymlink(symlink,new File(_dir,"index.html"));

            _ctx.setAliases(false);
            _ctx.addAliasCheck(new ContextHandler.ApproveSameSuffixAliases());
            Assert.assertTrue(_ctx.getResource("/index.html").exists());
            Assert.assertTrue(_ctx.getResource("/link.html").exists());
        }
        finally
        {
            symlink.delete();
        }
    }
    
    @Test
    public void testSymLinkToContentWithPathPrefixCheck() throws Exception
    {
        File symlink = new File(_dir,"dirlink");
        try
        {
        	createNIOSymlink(symlink,new File(_dir,"."));

            _ctx.setAliases(false);
            _ctx.addAliasCheck(new ContextHandler.ApprovePathPrefixAliases());
            Assert.assertTrue(_ctx.getResource("/index.html").exists());
            Assert.assertTrue(_ctx.getResource("/dirlink/index.html").exists());
        }
        finally
        {
            symlink.delete();
        }
    }
}
