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

package org.eclipse.jetty.start;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

import java.io.File;
import java.io.IOException;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Assert;
import org.junit.Test;

public class ModuleTest
{
    private Module loadTestHomeModule(String moduleFileName) throws IOException
    {
        File file = MavenTestingUtils.getTestResourceFile("usecases/home/modules/" + moduleFileName);
        return new Module(new BaseHome(),file);
    }

    @Test
    public void testLoadWebSocket() throws IOException
    {
        Module Module = loadTestHomeModule("websocket.mod");

        Assert.assertThat("Module Name",Module.getName(),is("websocket"));
        Assert.assertThat("Module Parents Size",Module.getParentNames().size(),is(2));
        Assert.assertThat("Module Parents",Module.getParentNames(),containsInAnyOrder("annotations","server"));
        Assert.assertThat("Module Xmls Size",Module.getXmls().size(),is(1));
        Assert.assertThat("Module Xmls",Module.getXmls(),contains("etc/jetty-websockets.xml"));
        Assert.assertThat("Module Options Size",Module.getLibs().size(),is(1));
        Assert.assertThat("Module Options",Module.getLibs(),contains("lib/websocket/*.jar"));
    }
}
