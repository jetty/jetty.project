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

package org.eclipse.jetty.server;

import java.util.Locale;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

// Isolated because the default Locale is changed.
@Isolated
public class SecureRequestCustomizerTest
{
    private Server server;

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "ar-EG-u-nu-arab"})
    public void testStrictTransportSecurityDoesNotDependOnDefaultLocale(String languageTag) throws Exception
    {
        SecureRequestCustomizer customizer = new SecureRequestCustomizer();
        Locale defaultLocale = Locale.getDefault();
        try
        {
            // Make sure the max-age does not get formatted with the non-ascii
            // Arabic-Egyptian figures as max-age=٣١٥٣٦٠٠٠, which would
            // then be sent on the wire as max-age=???????? by the ISO-8859-1 encoding.
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            customizer.setStsMaxAge(31536000);
            customizer.setStsIncludeSubDomains(true);
        }
        finally
        {
            Locale.setDefault(defaultLocale);
        }

        server = new Server();
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.addCustomizer(customizer);
        LocalConnector connector = new LocalConnector(server, new HttpConnectionFactory(httpConfiguration));
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        server.start();

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(HttpHeader.STRICT_TRANSPORT_SECURITY), is("max-age=31536000; includeSubDomains"));
    }
}
