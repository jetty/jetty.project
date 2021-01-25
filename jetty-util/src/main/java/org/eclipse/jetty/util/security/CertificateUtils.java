//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.security;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CRL;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Objects;

import org.eclipse.jetty.util.resource.Resource;

public class CertificateUtils
{

    public static KeyStore getKeyStore(Resource store, String storeType, String storeProvider, String storePassword) throws Exception
    {
        KeyStore keystore = null;

        if (store != null)
        {
            Objects.requireNonNull(storeType, "storeType cannot be null");
            if (storeProvider != null)
            {
                keystore = KeyStore.getInstance(storeType, storeProvider);
            }
            else
            {
                keystore = KeyStore.getInstance(storeType);
            }

            if (!store.exists())
                throw new IllegalStateException(store.getName() + " is not a valid keystore");

            try (InputStream inStream = store.getInputStream())
            {
                keystore.load(inStream, storePassword == null ? null : storePassword.toCharArray());
            }
        }

        return keystore;
    }

    public static Collection<? extends CRL> loadCRL(String crlPath) throws Exception
    {
        Collection<? extends CRL> crlList = null;

        if (crlPath != null)
        {
            InputStream in = null;
            try
            {
                in = Resource.newResource(crlPath).getInputStream();
                crlList = CertificateFactory.getInstance("X.509").generateCRLs(in);
            }
            finally
            {
                if (in != null)
                {
                    in.close();
                }
            }
        }

        return crlList;
    }
}
