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

package org.eclipse.jetty.client;

import java.util.Locale;

import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

// Isolated because the default Locale is changed.
@Isolated
public class ContentDecoderFactoriesTest
{
    @ParameterizedTest
    @ValueSource(strings = {"en-US", "ar-EG-u-nu-arab"})
    public void testAcceptEncodingDoesNotDependOnDefaultLocale(String languageTag)
    {
        // The Arabic-Egyptian locale with Arabic digits does not format numbers using the ascii
        // characters 0-9 nor '.' as decimal separator, if it was used the weight would be
        // formatted as "q=٠٫٥" for instance.
        ContentDecoder.Factories factories = new ContentDecoder.Factories();
        Locale defaultLocale = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            factories.put(new TestFactory("foo", 0.5F));
            factories.put(new TestFactory("bar", 0.125F));
        }
        finally
        {
            Locale.setDefault(defaultLocale);
        }
        assertThat(factories.getAcceptEncodingField().getValue(), is("foo;q=0.5, bar;q=0.125"));
    }

    private static class TestFactory extends ContentDecoder.Factory
    {
        private TestFactory(String encoding, float weight)
        {
            super(encoding, weight);
        }

        @Override
        public Content.Source newDecoderContentSource(Content.Source contentSource)
        {
            return contentSource;
        }
    }
}
