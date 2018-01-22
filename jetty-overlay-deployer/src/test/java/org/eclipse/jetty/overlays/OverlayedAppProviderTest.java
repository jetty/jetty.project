//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.overlays;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class OverlayedAppProviderTest
{
    File _tmp;
    File _scan;
    File _webapps;
    File _templates;
    File _nodes;
    File _instances;

    @Before
    public void before() throws Exception
    {
        _tmp=File.createTempFile("OAPTest",null);
        if (_tmp.exists())
            IO.delete(_tmp);
        _tmp.mkdir();

        _scan = new File(_tmp,"scan").getCanonicalFile();
        _webapps = new File(_scan,OverlayedAppProvider.WEBAPPS);
        _templates = new File(_scan,OverlayedAppProvider.TEMPLATES);
        _nodes = new File(_scan,OverlayedAppProvider.NODES);
        _instances = new File(_scan,OverlayedAppProvider.INSTANCES);
        _webapps.mkdirs();
        _templates.mkdir();
        _nodes.mkdir();
        _instances.mkdir();
    }

    @After
    public void after() throws Exception
    {
        if (_tmp.exists())
            IO.delete(_tmp);
    }

    @Test
    public void testScanForWars() throws Exception
    {
        final ConcurrentLinkedQueue<Set<String>> scanned = new ConcurrentLinkedQueue<Set<String>>();
        OverlayedAppProvider provider = new OverlayedAppProvider()
        {
            /* ------------------------------------------------------------ */
            /**
             * @see org.eclipse.jetty.overlays.OverlayedAppProvider#updateLayers(java.util.Set)
             */
            @Override
            protected void updateLayers(Set<String> filenames)
            {
                scanned.offer(filenames);
            }
        };
        provider.setScanInterval(0);


        provider.setScanDir(_scan);
        provider.start();
        provider.scan();
        provider.scan();

        assertTrue(scanned.isEmpty());


        // Check scanning for archives
        File war = new File(_webapps,"foo-1.2.3.war");
        touch(war);
        File template = new File(_templates,"foo=foo-1.2.3.war");
        touch(template);
        File node = new File(_nodes,"nodeA.war");
        touch(node);
        File instance = new File(_instances,"foo=instance.war");
        touch(instance);

        provider.scan();
        provider.scan();

        Set<String> results = scanned.poll();
        assertTrue(results!=null);
        assertEquals(4,results.size());
        assertTrue(results.contains("webapps/foo-1.2.3.war"));
        assertTrue(results.contains("templates/foo=foo-1.2.3.war"));
        assertTrue(results.contains("nodes/nodeA.war"));
        assertTrue(results.contains("instances/foo=instance.war"));

        provider.scan();
        provider.scan();
        assertTrue(scanned.isEmpty());

        IO.delete(war);
        IO.delete(template);
        IO.delete(node);
        IO.delete(instance);

        provider.scan();
        provider.scan();
        results = scanned.poll();
        assertTrue(results!=null);
        assertEquals(4,results.size());
        assertTrue(results.contains("webapps/foo-1.2.3.war"));
        assertTrue(results.contains("templates/foo=foo-1.2.3.war"));
        assertTrue(results.contains("nodes/nodeA.war"));
        assertTrue(results.contains("instances/foo=instance.war"));

    }

    @Test
    public void testScanForDirs() throws Exception
    {
        final ConcurrentLinkedQueue<Set<String>> scanned = new ConcurrentLinkedQueue<Set<String>>();
        OverlayedAppProvider provider = new OverlayedAppProvider()
        {
            /* ------------------------------------------------------------ */
            /**
             * @see org.eclipse.jetty.overlays.OverlayedAppProvider#updateLayers(java.util.Set)
             */
            @Override
            protected void updateLayers(Set<String> filenames)
            {
                scanned.offer(filenames);
            }
        };
        provider.setScanInterval(0);


        provider.setScanDir(_scan);
        provider.start();
        provider.scan();

        assertTrue(scanned.isEmpty());


        // Check scanning for directories
        File war = new File(_webapps,"foo-1.2.3");
        war.mkdir();
        File template = new File(_templates,"foo=foo-1.2.3");
        template.mkdir();
        File node = new File(_nodes,"nodeA");
        node.mkdir();
        File instance = new File(_instances,"foo=instance");
        instance.mkdir();
        for (File f : new File[] { war,template,node,instance } )
        {
            File webinf = new File(f,"WEB-INF");
            webinf.mkdir();
            touch(webinf,"web.xml");
        }

        provider.scan();
        provider.scan();

        Set<String> results = scanned.poll();
        assertTrue(results!=null);
        assertEquals(4,results.size());
        assertTrue(results.contains("webapps/foo-1.2.3"));
        assertTrue(results.contains("templates/foo=foo-1.2.3"));
        assertTrue(results.contains("nodes/nodeA"));
        assertTrue(results.contains("instances/foo=instance"));

        provider.scan();
        provider.scan();
        assertTrue(scanned.isEmpty());


        // Touch everything
        touch(war,"WEB-INF/web.xml");
        touch(war,"WEB-INF/spring.XML");
        touch(war,"WEB-INF/other");
        touch(war,"WEB-INF/lib/bar.jar");
        touch(war,"WEB-INF/classes/bar.class");

        for (File d : new File[]{template,node,instance})
        {
            touch(d,"WEB-INF/web-fragment.xml");
            touch(d,"WEB-INF/overlay.xml");
            touch(d,"WEB-INF/other");
            touch(d,"WEB-INF/lib/bar.jar");
        }

        provider.scan();
        provider.scan();
        results = scanned.poll();
        assertTrue(results!=null);
        assertEquals(4,results.size());
        assertTrue(results.contains("webapps/foo-1.2.3"));
        assertTrue(results.contains("templates/foo=foo-1.2.3"));
        assertTrue(results.contains("nodes/nodeA"));
        assertTrue(results.contains("instances/foo=instance"));


        // Touch xml
        Thread.sleep(1000); // needed so last modified is different
        for (File d : new File[]{war,template,node,instance})
            touch(d,"WEB-INF/web.xml");
        provider.scan();
        provider.scan();
        results = scanned.poll();
        assertTrue(results!=null);
        assertEquals(4,results.size());
        assertTrue(results.contains("webapps/foo-1.2.3"));
        assertTrue(results.contains("templates/foo=foo-1.2.3"));
        assertTrue(results.contains("nodes/nodeA"));
        assertTrue(results.contains("instances/foo=instance"));

        // Touch XML
        Thread.sleep(1000);
        for (File d : new File[]{war,template,node,instance})
            touch(d,"WEB-INF/spring.XML");
        provider.scan();
        provider.scan();
        results = scanned.poll();
        assertTrue(results!=null);
        assertEquals(4,results.size());
        assertTrue(results.contains("webapps/foo-1.2.3"));
        assertTrue(results.contains("templates/foo=foo-1.2.3"));
        assertTrue(results.contains("nodes/nodeA"));
        assertTrue(results.contains("instances/foo=instance"));


        // Touch unrelated
        for (File d : new File[]{war,template,node,instance})
            touch(d,"index.html");
        provider.scan();
        provider.scan();
        results = scanned.poll();
        assertEquals(null,results);

        // Touch jar
        Thread.sleep(1000);
        for (File d : new File[]{war,template,node,instance})
            touch(d,"WEB-INF/lib/bar.jar");
        provider.scan();
        provider.scan();
        results = scanned.poll();
        assertTrue(results!=null);
        assertEquals(4,results.size());
        assertTrue(results.contains("webapps/foo-1.2.3"));
        assertTrue(results.contains("templates/foo=foo-1.2.3"));
        assertTrue(results.contains("nodes/nodeA"));
        assertTrue(results.contains("instances/foo=instance"));

        // touch other class
        Thread.sleep(1000);
        for (File d : new File[]{war,template,node,instance})
            touch(d,"index.html");
        provider.scan();
        provider.scan();
        results = scanned.poll();
        assertTrue(scanned.isEmpty());


        // delete all
        IO.delete(war);
        IO.delete(template);
        IO.delete(node);
        IO.delete(instance);

        provider.scan();
        provider.scan();
        results = scanned.poll();
        assertTrue(results!=null);
        assertEquals(4,results.size());
        assertTrue(results.contains("webapps/foo-1.2.3"));
        assertTrue(results.contains("templates/foo=foo-1.2.3"));
        assertTrue(results.contains("nodes/nodeA"));
        assertTrue(results.contains("instances/foo=instance"));

    }


    @Test
    public void testTriageURI() throws Exception
    {
        final BlockingQueue<String> scanned = new LinkedBlockingQueue<String>();
        OverlayedAppProvider provider = new OverlayedAppProvider()
        {
            protected void removeInstance(String name)
            {
                scanned.add("removeInstance "+name);
            }
            protected Instance loadInstance(String name, File origin)
            {
                scanned.add("loadInstance "+name);
                scanned.add(origin.getAbsolutePath());
                return null;
            }
            protected void removeNode()
            {
                scanned.add("removeNode");
            }
            protected Node loadNode(File origin)
            {
                scanned.add("loadNode");
                scanned.add(origin.getAbsolutePath());
                return null;
            }
            protected void removeTemplate(String name)
            {
                scanned.add("removeTemplate "+name);
            }
            protected Template loadTemplate(String name, File origin)
            {
                scanned.add("loadTemplate "+name);
                scanned.add(origin.getAbsolutePath());
                return null;
            }
            protected void removeWebapp(String name)
            {
                scanned.add("removeWebapp "+name);
            }
            protected Webapp loadWebapp(String name, File origin)
            {
                scanned.add("loadWebapp "+name);
                scanned.add(origin.getAbsolutePath());
                return null;
            }

            protected void redeploy()
            {
            }

        };
        provider.setScanInterval(0);
        provider.setNodeName("nodeA");


        provider.setScanDir(_scan);
        provider.start();
        provider.scan();

        assertTrue(scanned.isEmpty());

        // Add a war
        File war = new File(_webapps,"foo-1.2.3.war");
        touch(war);
        provider.scan();
        provider.scan();
        assertEquals("loadWebapp foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(war.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));


        // Add a template
        File template = new File(_templates,"foo=foo-1.2.3.war");
        touch(template);
        provider.scan();
        provider.scan();
        assertEquals("loadTemplate foo=foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(template.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));

        // Add a node
        File nodeA = new File(_nodes,"nodeA.war");
        touch(nodeA);
        provider.scan();
        provider.scan();
        assertEquals("loadNode",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(nodeA.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));

        // Add another node
        File nodeB = new File(_nodes,"nodeB.war");
        provider.scan();
        provider.scan();
        assertTrue(scanned.isEmpty());

        // Add an instance
        File instance = new File(_instances,"foo=instance.war");
        touch(instance);
        provider.scan();
        provider.scan();
        assertEquals("loadInstance foo=instance",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(instance.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));


        // Add a war dir
        File warDir = new File(_webapps,"foo-1.2.3");
        warDir.mkdir();
        File warDirWI = new File(warDir,"WEB-INF");
        warDirWI.mkdir();
        touch(warDirWI,"web.xml");
        provider.scan();
        provider.scan();
        assertEquals("loadWebapp foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(warDir.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));

        // Add a template dir
        File templateDir = new File(_templates,"foo=foo-1.2.3");
        templateDir.mkdir();
        File templateDirWI = new File(templateDir,"WEB-INF");
        templateDirWI.mkdir();
        touch(templateDirWI,"web.xml");
        provider.scan();
        provider.scan();
        assertEquals("loadTemplate foo=foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(templateDir.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));

        // Add a node dir
        File nodeADir = new File(_nodes,"nodeA");
        nodeADir.mkdir();
        File nodeADirWI = new File(nodeADir,"WEB-INF");
        nodeADirWI.mkdir();
        touch(nodeADirWI,"web.xml");
        provider.scan();
        provider.scan();
        assertEquals("loadNode",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(nodeADir.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));

        // Add another node dir
        File nodeBDir = new File(_nodes,"nodeB");
        nodeBDir.mkdir();
        File nodeBDirWI = new File(nodeBDir,"WEB-INF");
        nodeBDirWI.mkdir();
        touch(nodeBDirWI,"web.xml");
        provider.scan();
        provider.scan();
        assertTrue(scanned.isEmpty());

        // Add an instance dir
        File instanceDir = new File(_instances,"foo=instance");
        instanceDir.mkdir();
        File instanceDirWI = new File(instanceDir,"WEB-INF");
        instanceDirWI.mkdir();
        touch(instanceDirWI,"web.xml");
        provider.scan();
        provider.scan();
        assertEquals("loadInstance foo=instance",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(instanceDir.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));


        // touch archives will be ignored.
        Thread.sleep(1000);
        touch(war);
        touch(template);
        touch(nodeA);
        touch(nodeB);
        touch(instance);
        provider.scan();
        provider.scan();
        assertTrue(scanned.isEmpty());

        // Touch directories
        for (File d : new File[]{warDir,templateDir,nodeADir,nodeBDir,instanceDir})
            touch(d,"WEB-INF/web.xml");
        provider.scan();
        provider.scan();
        assertEquals(8,scanned.size());
        scanned.clear();

        // Remove web dir
        IO.delete(warDir);
        provider.scan();
        provider.scan();
        assertEquals("removeWebapp foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals("loadWebapp foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(war.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));

        // Remove template dir
        IO.delete(templateDir);
        provider.scan();
        provider.scan();
        assertEquals("removeTemplate foo=foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals("loadTemplate foo=foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(template.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));

        // Remove nodeA dir
        IO.delete(nodeADir);
        provider.scan();
        provider.scan();
        assertEquals("removeNode",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals("loadNode",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(nodeA.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));

        // Remove nodeB dir
        IO.delete(nodeBDir);
        provider.scan();
        provider.scan();
        assertTrue(scanned.isEmpty());


        // Remove instance dir
        IO.delete(instanceDir);
        provider.scan();
        provider.scan();
        assertEquals("removeInstance foo=instance",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals("loadInstance foo=instance",scanned.poll(1,TimeUnit.SECONDS));
        assertEquals(instance.getAbsolutePath(),scanned.poll(1,TimeUnit.SECONDS));

        // Remove web
        IO.delete(war);
        provider.scan();
        provider.scan();
        assertEquals("removeWebapp foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));

        // Remove template
        IO.delete(template);
        provider.scan();
        provider.scan();
        assertEquals("removeTemplate foo=foo-1.2.3",scanned.poll(1,TimeUnit.SECONDS));

        // Remove nodeA dir
        IO.delete(nodeA);
        provider.scan();
        provider.scan();
        assertEquals("removeNode",scanned.poll(1,TimeUnit.SECONDS));

        // Remove nodeB dir
        IO.delete(nodeB);
        provider.scan();
        provider.scan();
        assertTrue(scanned.isEmpty());

        // Remove instance dir
        IO.delete(instance);
        provider.scan();
        provider.scan();
        assertEquals("removeInstance foo=instance",scanned.poll(1,TimeUnit.SECONDS));

        provider.scan();
        provider.scan();
        assertTrue(scanned.isEmpty());

    }


    private void touch(File base,String path)
    {
        try
        {
            File target = new File(new URI(base.toURI().toString()+path));
            target.getParentFile().mkdirs();
            touch(target);
        }
        catch (URISyntaxException e)
        {
            e.printStackTrace();
        }
    }


    private void touch(File file)
    {
        try
        {
            IO.delete(file);
            try (OutputStream out = new FileOutputStream(file,false))
            {
                out.write("<h1>Hello</h1>".getBytes());
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
    }
}
