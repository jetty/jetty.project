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

package org.eclipse.jetty.start;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class FileArgTest
{
    @SuppressWarnings("serial")
    private static class UseCases extends ArrayList<String[]>
    {
        public void add(String rawfileref, String expectedUri, String expectedLocation)
        {
            this.add(new String[] { rawfileref, expectedUri, expectedLocation });
        }
    }

    @Parameters(name = "{0}")
    public static List<String[]> data()
    {
        UseCases data = new UseCases();
        data.add("resource",null,"resource");
        data.add("lib/logging",null,"lib/logging");
        
        // -- URI with relative location --
        data.add("http://machine.com/my.conf|resources/my.conf","http://machine.com/my.conf","resources/my.conf");
        data.add("http://machine.com:8080/my.conf|resources/my.conf","http://machine.com:8080/my.conf","resources/my.conf");
        data.add("https://machine.com:8080/my.conf|resources/my.conf","https://machine.com:8080/my.conf","resources/my.conf");
        // Windows URI (drive mapped)
        data.add("file:///Z:/share/my.conf|resources/my.conf","file:///Z:/share/my.conf","resources/my.conf");
        // Windows URI (network share)
        data.add("file:////nas/share/my.conf|resources/my.conf","file:////nas/share/my.conf","resources/my.conf");
        
        // -- URI with absolute location --
        data.add("http://machine.com/db.dat|/var/run/db.dat","http://machine.com/db.dat","/var/run/db.dat");
        data.add("http://machine.com:8080/b/db.dat|/var/run/db.dat","http://machine.com:8080/b/db.dat","/var/run/db.dat");
        data.add("https://machine.com:8080/c/db.dat|/var/run/db.dat","https://machine.com:8080/c/db.dat","/var/run/db.dat");
        // Windows URI (drive mapped) to drive mapped output
        data.add("file:///Z:/share/my.conf|C:/db/db.dat","file:///Z:/share/my.conf","C:/db/db.dat");
        data.add("file:///Z:/share/my.conf|C:\\db\\db.dat","file:///Z:/share/my.conf","C:\\db\\db.dat");
        // Windows URI (drive mapped) to network share output
        data.add("file:///Z:/share/my.conf|\\\\nas\\apps\\db\\db.dat","file:///Z:/share/my.conf","\\\\nas\\apps\\db\\db.dat");
        // Windows URI (network share) to drive mapped output
        data.add("file:////nas/share/my.conf|C:/db/db.dat","file:////nas/share/my.conf","C:/db/db.dat");
        data.add("file:////nas/share/my.conf|C:\\db\\db.dat","file:////nas/share/my.conf","C:\\db\\db.dat");
        // Windows URI (network share) to network share output
        data.add("file:////nas/share/my.conf|\\\\nas\\apps\\db\\db.dat","file:////nas/share/my.conf","\\\\nas\\apps\\db\\db.dat");
        return data;
    }

    @Parameter(value = 0)
    public String rawFileRef;
    @Parameter(value = 1)
    public String expectedUri;
    @Parameter(value = 2)
    public String expectedLocation;

    @Test
    public void testFileArg()
    {
        FileArg arg = new FileArg(null,rawFileRef);
        if (expectedUri == null)
        {
            assertThat("URI",arg.uri,nullValue());
        }
        else
        {
            assertThat("URI",arg.uri,is(expectedUri));
        }
        assertThat("Location",arg.location,is(expectedLocation));
    }

}
