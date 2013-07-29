//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server.deploy;

import static org.hamcrest.Matchers.*;

import java.net.URI;

import org.junit.Assert;
import org.junit.Test;

public class DiscoveredEndpointsTest
{
    /**
     * Attempt to get an Archive URI, to a class known to be in an archive.
     */
    @Test
    public void testGetArchiveURI_InJar()
    {
        Class<?> clazz = javax.websocket.server.ServerContainer.class;
        URI archiveURI = DiscoveredEndpoints.getArchiveURI(clazz);
        // should point to a JAR file
        Assert.assertThat("Archive URI for: " + clazz.getName(),
                archiveURI.toASCIIString(),
                endsWith("javax.websocket-api-1.0.jar"));
    }
    
    /**
     * Get an Archive URI for a class reference that is known to not be in an archive.
     */
    @Test
    public void testGetArchiveURI_InClassDirectory()
    {
        Class<?> clazz = DiscoveredEndpointsTest.class;
        URI archivePath = DiscoveredEndpoints.getArchiveURI(clazz);
        // Should be null, as it does not point to an archive
        Assert.assertThat("Archive URI for: " + clazz,
                archivePath,
                nullValue());
    }
}
