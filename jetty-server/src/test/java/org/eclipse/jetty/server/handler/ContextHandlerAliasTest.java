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

import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.FileResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @version $Revision$
 */
public class ContextHandlerAliasTest
{
    private Server _server;
    private ContextHandler _ctx;
    private File _tmp;
    private File _dir;
    
 
    @Before
    public void before() throws Exception
    {
        _server=new Server();
        _ctx = new ContextHandler();
        _server.setHandler(_ctx);
        
        
        _tmp = new File( System.getProperty( "basedir", "." ) + "/target/tmp/aliastests" ).getCanonicalFile();
        if (_tmp.exists())
            IO.delete(_tmp);
        assertTrue(_tmp.mkdirs());
        
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
            Files.createSymbolicLink(symlink.toPath(),_dir.toPath());

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
            Files.createSymbolicLink(symlink.toPath(),new File(_dir,"index.html").toPath());

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
            Files.createSymbolicLink(symlink.toPath(),new File(_dir,"index.html").toPath());

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
            Files.createSymbolicLink(symlink.toPath(),new File(_dir,".").toPath());

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
