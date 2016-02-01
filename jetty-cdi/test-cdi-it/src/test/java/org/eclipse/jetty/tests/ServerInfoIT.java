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

package org.eclipse.jetty.tests;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.net.URI;

import org.eclipse.jetty.toolchain.test.SimpleRequest;
import org.junit.Test;

public class ServerInfoIT
{
    @Test
    public void testGET() throws Exception {
        URI serverURI = new URI("http://localhost:58080/cdi-webapp/");
        SimpleRequest req = new SimpleRequest(serverURI);
        
        // Typical response:
        // context = ServletContext@o.e.j.w.WebAppContext@37cb63fd{/cdi-webapp,
        // file:///tmp/jetty-0.0.0.0-58080-cdi-webapp.war-_cdi-webapp-any-417759194514596377.dir/webapp/,AVAILABLE}
        // {/cdi-webapp.war}\ncontext.contextPath = /cdi-webapp\ncontext.effective-version = 3.1\n
        assertThat(req.getString("serverinfo"),
                allOf(
                containsString("context = ServletContext@"),
                containsString("context.contextPath = /cdi-webapp"),
                containsString("context.effective-version = 3.1")
                ));
    }
}
