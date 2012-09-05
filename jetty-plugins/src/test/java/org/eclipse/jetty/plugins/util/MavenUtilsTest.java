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
// ========================================================================
//
package org.eclipse.jetty.plugins.util;

import java.io.File;

import org.junit.Ignore;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class MavenUtilsTest
{
    @Test
    @Ignore("Very environment specific, so disabled in commit. Enable if working on the code.")
    public void testFindUserSettingsXml()
    {
        File settingsXml = MavenUtils.findUserSettingsXml();
        assertThat("settings.xml is found", settingsXml.exists(), is(true));
    }

    @Test
    @Ignore("Very environment specific, so disabled in commit. Enable if working on the code.")
    public void testGetLocalRepositoryLocation()
    {
        String repositoryLocation = MavenUtils.getLocalRepositoryLocation();
        assertThat("maven repository exists", new File(repositoryLocation).exists(), is(true));
    }
}
