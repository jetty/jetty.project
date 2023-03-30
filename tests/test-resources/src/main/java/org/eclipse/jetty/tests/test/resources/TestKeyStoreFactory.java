//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.test.resources;

import java.io.InputStream;
import java.security.KeyStore;

public class TestKeyStoreFactory
{

    public static final String KEY_STORE_PASSWORD = "storepwd";

    public static KeyStore getServerKeyStore()
    {
        return getKeyStore("org.eclipse.jetty.tests.test.resources/certs/server/server.p12", KEY_STORE_PASSWORD);
    }

    public static KeyStore getClientKeyStore()
    {
        return getKeyStore("org.eclipse.jetty.tests.test.resources/certs/client/client.p12", KEY_STORE_PASSWORD);
    }

    public static KeyStore getProxyKeyStore()
    {
        return getKeyStore("org.eclipse.jetty.tests.test.resources/certs/proxy/proxy.p12", KEY_STORE_PASSWORD);
    }

    public static KeyStore getTrustStore()
    {
        return getKeyStore("org.eclipse.jetty.tests.test.resources/certs/trustStore.jks",
                KEY_STORE_PASSWORD);
    }

    private static KeyStore getKeyStore(String resourcePath, String password)
    {
        try (InputStream inputStream = ClassLoader.getSystemResourceAsStream(resourcePath))
        {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(inputStream, password.toCharArray());
            return keyStore;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }
}
