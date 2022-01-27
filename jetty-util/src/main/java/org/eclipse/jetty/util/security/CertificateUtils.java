//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
