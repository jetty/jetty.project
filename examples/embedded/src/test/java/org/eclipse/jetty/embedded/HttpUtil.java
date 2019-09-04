//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.embedded;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jetty.util.IO;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class HttpUtil
{
    public static void assertGzippedResponse(HttpURLConnection http)
    {
        String value = http.getHeaderField("Content-Encoding");
        assertThat("Content-Encoding", value, containsString("gzip"));
    }

    public static String getGzippedResponseBody(HttpURLConnection http) throws IOException
    {
        try (InputStream in = http.getInputStream();
             GZIPInputStream gzipInputStream = new GZIPInputStream(in))
        {
            return IO.toString(gzipInputStream, UTF_8);
        }
    }

    public static String getResponseBody(HttpURLConnection http) throws IOException
    {
        try (InputStream in = http.getInputStream())
        {
            return IO.toString(in, UTF_8);
        }
    }

    public static void dumpResponseHeaders(HttpURLConnection http)
    {
        int i = 0;
        while (true)
        {
            String field = http.getHeaderField(i);
            if (field == null)
                return;
            String key = http.getHeaderFieldKey(i);
            if (key != null)
            {
                System.out.printf("%s: ", key);
            }
            System.out.println(field);
            i++;
        }
    }

    /**
     * Disable the Hostname and Certificate verification in {@link HttpsURLConnection}
     */
    public static void disableSecureConnectionVerification() throws NoSuchAlgorithmException, KeyManagementException
    {
        TrustManager[] trustAllCerts = new TrustManager[]{new TrustAllCerts()};
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new AllHostnamesValid());
    }

    public static class TrustAllCerts implements X509TrustManager
    {
        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException
        {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException
        {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return null;
        }
    }

    public static class AllHostnamesValid implements HostnameVerifier
    {

        @Override
        public boolean verify(String s, SSLSession sslSession)
        {
            return true;
        }
    }
}
