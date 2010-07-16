// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.annotations;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Test;

/**
 * TestServletAnnotations
 *
 *
 */
public class TestServletAnnotations
{
   
    @Test
    public void testDeclareRoles ()
    throws Exception
    { 
        WebAppContext wac = new WebAppContext();
        wac.setAttribute(MetaData.METADATA, new MetaData(wac));
        ConstraintSecurityHandler sh = new ConstraintSecurityHandler();
        wac.setSecurityHandler(sh);
        sh.setRoles(new HashSet<String>(Arrays.asList(new String[]{"humpty", "dumpty"})));
        DeclareRolesAnnotationHandler handler = new DeclareRolesAnnotationHandler(wac);
        handler.doHandle(ServletC.class);
        assertTrue(sh.getRoles().contains("alice"));
        assertTrue(sh.getRoles().contains("humpty"));
        assertTrue(sh.getRoles().contains("dumpty"));
    }
}
