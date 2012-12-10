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

package org.eclipse.jetty.ant;

import java.net.HttpURLConnection;
import java.net.URI;

import junit.framework.Assert;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Ignore;
import org.junit.Test;

public class JettyAntTaskTest
{
    
    @Ignore
    //@Test
    public void testConnectorTask() throws Exception
    {
        AntBuild build = new AntBuild(MavenTestingUtils.getTestResourceFile("connector-test.xml").getAbsolutePath());
      
        build.start();
        
        URI uri = new URI("http://" + build.getJettyHost() + ":" + build.getJettyPort());
        
        HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
        
        connection.connect();
        
        Assert.assertEquals(404,connection.getResponseCode());
        
        build.stop();
    }


    @Test
    @Ignore("need to update connector")
    public void testWebApp () throws Exception
    {
        AntBuild build = new AntBuild(MavenTestingUtils.getTestResourceFile("webapp-test.xml").getAbsolutePath());
      
        build.start();
        
        URI uri = new URI("http://" + build.getJettyHost() + ":" + build.getJettyPort() + "/");
        
        HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
        
        connection.connect();
        
        Assert.assertEquals(200,connection.getResponseCode());
        
        System.err.println("Stop build!");
        build.stop();
    }

   
}
