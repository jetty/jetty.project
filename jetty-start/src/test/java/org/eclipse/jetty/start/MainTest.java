// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/* ------------------------------------------------------------ */
/**
 */
public class MainTest
{
    /* ------------------------------------------------------------ */
    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception
    {
        System.setProperty("jetty.home",this.getClass().getResource("/jetty.home").getFile());
    }

    /**
     * Test method for {@link org.eclipse.jetty.start.StartIniParser#loadStartIni(java.lang.String)}.
     * @throws IOException 
     */
    @Test
    public void testLoadStartIni() throws IOException
    {
        URL startIni = this.getClass().getResource("/jetty.home/");
        System.setProperty("jetty.home",startIni.getFile());
        Main main = new Main();
        List<String> args = main.parseStartIniFiles();
        assertEquals("Expected 5 uncommented lines in start.ini",9,args.size());
        assertEquals("First uncommented line in start.ini doesn't match expected result","OPTIONS=Server,jsp,resources,websocket,ext",args.get(0));
        assertEquals("Last uncommented line in start.ini doesn't match expected result","etc/jetty-testrealm.xml",args.get(8));
    }

    @Test
    public void testExpandCommandLine() throws Exception
    {
        Main main = new Main();
        List<String> args = main.expandCommandLine(new String[]{});
        assertEquals("start.ini OPTIONS","OPTIONS=Server,jsp,resources,websocket,ext",args.get(0));
        assertEquals("start.d/jmx OPTIONS","OPTIONS=jmx",args.get(5));
        assertEquals("start.d/jmx XML","--pre=etc/jetty-jmx.xml",args.get(6));
        assertEquals("start.d/websocket OPTIONS","OPTIONS=websocket",args.get(7));
    }
    
    @Test
    public void testProcessCommandLine() throws Exception
    {
        Main main = new Main();
        List<String> args = main.expandCommandLine(new String[]{});
        List<String> xmls = main.processCommandLine(args);

        assertEquals("jmx --pre","etc/jetty-jmx.xml",xmls.get(0));
        assertEquals("start.ini","etc/jetty.xml",xmls.get(1));
        assertEquals("start.d","etc/jetty-testrealm.xml",xmls.get(5));
    }

}
