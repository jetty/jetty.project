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

package org.eclipse.jetty.util.ssl;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.security.cert.X509Certificate;

import org.junit.Test;

public class X509Test
{
    @Test
    public void testIsCertSign_Normal()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                boolean[] keyUsage = new boolean[8];
                keyUsage[5] = true;
                return keyUsage;
            }
        };
        
        assertThat("Normal X509", X509.isCertSign(bogusX509), is(true));
    }
    
    @Test
    public void testIsCertSign_Normal_NoSupported()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                boolean[] keyUsage = new boolean[8];
                keyUsage[5] = false;
                return keyUsage;
            }
        };
        
        assertThat("Normal X509", X509.isCertSign(bogusX509), is(false));
    }
    
    @Test
    public void testIsCertSign_NonStandard_Short()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                boolean[] keyUsage = new boolean[6]; // at threshold
                keyUsage[5] = true;
                return keyUsage;
            }
        };
        
        assertThat("NonStandard X509", X509.isCertSign(bogusX509), is(true));
    }
    
    @Test
    public void testIsCertSign_NonStandard_Shorter()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                boolean[] keyUsage = new boolean[5]; // just below threshold
                return keyUsage;
            }
        };
        
        assertThat("NonStandard X509", X509.isCertSign(bogusX509), is(false));
    }
    
    @Test
    public void testIsCertSign_Normal_Null()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                return null;
            }
        };
        
        assertThat("Normal X509", X509.isCertSign(bogusX509), is(false));
    }
    
    @Test
    public void testIsCertSign_Normal_Empty()
    {
        X509Certificate bogusX509 = new X509CertificateAdapter()
        {
            @Override
            public boolean[] getKeyUsage()
            {
                return new boolean[0];
            }
        };
        
        assertThat("Normal X509", X509.isCertSign(bogusX509), is(false));
    }
}
