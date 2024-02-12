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

package org.eclipse.jetty.tests.multipart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MultiPartFormArgumentsProvider implements ArgumentsProvider
{
    private static URL getMultipartResource(String resourceName)
    {
        URL url = Thread.currentThread().getContextClassLoader().getResource(resourceName);
        assertNotNull(url, "Unable to find resource: " + resourceName);
        return url;
    }

    private Arguments asArgs(String formPrefix, Charset charset) throws IOException
    {
        URL urlRaw = getMultipartResource("multipart/" + formPrefix + ".raw");
        URL urlExpectations = getMultipartResource("multipart/" + formPrefix + ".expected.txt");

        try (InputStream input = urlExpectations.openStream();
             InputStreamReader inputStreamReader = new InputStreamReader(input, UTF_8);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader))
        {
            MultiPartRequest multiPartRequest = new MultiPartRequest(urlRaw);
            MultiPartExpectations expectations = MultiPartExpectations.parse(bufferedReader, multiPartRequest);
            return Arguments.of(multiPartRequest, charset, expectations);
        }
    }

    public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) throws Exception
    {
        List<Arguments> args = new ArrayList<>();

        // == Arbitrary / Non-Standard Examples ==
        args.add(asArgs("multipart-uppercase", null));
        args.add(asArgs("multipart-base64", null));  // base64 transfer encoding deprecated
        args.add(asArgs("multipart-base64-long", null)); // base64 transfer encoding deprecated

        // == Capture of raw request body contents from Apache HttpClient 4.5.5 ==

        args.add(asArgs("browser-capture-company-urlencoded-apache-httpcomp", null));
        args.add(asArgs("browser-capture-complex-apache-httpcomp", null));
        args.add(asArgs("browser-capture-duplicate-names-apache-httpcomp", null));
        args.add(asArgs("browser-capture-encoding-mess-apache-httpcomp", null));
        args.add(asArgs("browser-capture-nested-apache-httpcomp", null));
        args.add(asArgs("browser-capture-nested-binary-apache-httpcomp", null));
        args.add(asArgs("browser-capture-number-only2-apache-httpcomp", null));
        args.add(asArgs("browser-capture-number-only-apache-httpcomp", null));
        args.add(asArgs("browser-capture-sjis-apache-httpcomp", null));
        args.add(asArgs("browser-capture-strange-quoting-apache-httpcomp", null));
        args.add(asArgs("browser-capture-text-files-apache-httpcomp", null));
        args.add(asArgs("browser-capture-unicode-names-apache-httpcomp", null));
        args.add(asArgs("browser-capture-zalgo-text-plain-apache-httpcomp", null));

        // == Capture of raw request body contents from Eclipse Jetty Http Client 9.4.9 ==

        args.add(asArgs("browser-capture-complex-jetty-client", null));
        args.add(asArgs("browser-capture-duplicate-names-jetty-client", null));
        args.add(asArgs("browser-capture-encoding-mess-jetty-client", null));
        args.add(asArgs("browser-capture-nested-jetty-client", null));
        args.add(asArgs("browser-capture-number-only-jetty-client", null));
        args.add(asArgs("browser-capture-sjis-jetty-client", null));
        args.add(asArgs("browser-capture-text-files-jetty-client", null));
        args.add(asArgs("browser-capture-unicode-names-jetty-client", null));
        args.add(asArgs("browser-capture-whitespace-only-jetty-client", null));

        // == Capture of raw request body contents from various browsers ==

        // simple form - 2 fields
        args.add(asArgs("browser-capture-form1-android-chrome", null));
        args.add(asArgs("browser-capture-form1-android-firefox", null));
        args.add(asArgs("browser-capture-form1-chrome", null));
        args.add(asArgs("browser-capture-form1-edge", null));
        args.add(asArgs("browser-capture-form1-firefox", null));
        args.add(asArgs("browser-capture-form1-ios-safari", null));
        args.add(asArgs("browser-capture-form1-msie", null));
        args.add(asArgs("browser-capture-form1-osx-safari", null));

        // form submitted as shift-jis (with HTML5 specific hidden _charset_ field)
        args.add(asArgs("browser-capture-sjis-charset-form-android-chrome", null)); // contains html encoded character
        args.add(asArgs("browser-capture-sjis-charset-form-android-firefox", null)); // contains html encoded character
        args.add(asArgs("browser-capture-sjis-charset-form-chrome", null)); // contains html encoded character
        args.add(asArgs("browser-capture-sjis-charset-form-edge", null));
        args.add(asArgs("browser-capture-sjis-charset-form-firefox", null)); // contains html encoded character
        args.add(asArgs("browser-capture-sjis-charset-form-ios-safari", null)); // contains html encoded character
        args.add(asArgs("browser-capture-sjis-charset-form-msie", null));
        args.add(asArgs("browser-capture-sjis-charset-form-safari", null)); // contains html encoded character

        // form submitted with simple file upload
        args.add(asArgs("browser-capture-form-fileupload-android-chrome", null));
        args.add(asArgs("browser-capture-form-fileupload-android-firefox", null));
        args.add(asArgs("browser-capture-form-fileupload-chrome", null));
        args.add(asArgs("browser-capture-form-fileupload-edge", null));
        args.add(asArgs("browser-capture-form-fileupload-firefox", null));
        args.add(asArgs("browser-capture-form-fileupload-ios-safari", null));
        args.add(asArgs("browser-capture-form-fileupload-msie", null));
        args.add(asArgs("browser-capture-form-fileupload-safari", null));

        // form submitted with 2 files (1 binary, 1 text) and 2 text fields
        args.add(asArgs("browser-capture-form-fileupload-alt-chrome", null));
        args.add(asArgs("browser-capture-form-fileupload-alt-edge", null));
        args.add(asArgs("browser-capture-form-fileupload-alt-firefox", null));
        args.add(asArgs("browser-capture-form-fileupload-alt-msie", null));
        args.add(asArgs("browser-capture-form-fileupload-alt-safari", null));

        // form parts submitted as UTF-8
        args.add(asArgs("browser-capture-sjis-form-edge", UTF_8));
        args.add(asArgs("browser-capture-sjis-form-msie", UTF_8));
        args.add(asArgs("browser-capture-sjis-jetty-client", UTF_8));
        
        // form parts submitted at Shift_JIS (also contains html encoded character entities)
        // forms that were submitted without {@code _charset_} named part (as specified by the HTML5 spec).
        // This is a flaky and buggy part of the HTML spec in various browsers.
        // This technique used to be common, but is being replaced by using
        // the {@code _charset_} named part instead.

        Charset shiftJis = Charset.forName("Shift_JIS");
        args.add(asArgs("browser-capture-sjis-form-android-chrome", shiftJis));
        args.add(asArgs("browser-capture-sjis-form-android-firefox", shiftJis));
        args.add(asArgs("browser-capture-sjis-form-chrome", shiftJis));
        args.add(asArgs("browser-capture-sjis-form-firefox", shiftJis));
        args.add(asArgs("browser-capture-sjis-form-ios-safari", shiftJis));
        args.add(asArgs("browser-capture-sjis-form-safari", shiftJis));

        return args.stream();
    }
}
