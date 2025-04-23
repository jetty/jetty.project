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

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;

/**
 * <p>Groups abstractions related to response content decoding.</p>
 *
 * @see Factory
 * @see Factories
 * @see HttpClient#getContentDecoderFactories()
 */
public interface ContentDecoder
{
    /**
     * <p>A factory for {@link Content.Source} that decode response content.</p>
     * <p>A {@code Factory} has an {@link #getEncoding() encoding} and a
     * {@link #getWeight() weight} that are used in the {@code Accept-Encoding}
     * request header and in the {@code Content-Encoding} response headers.</p>
     * <p>{@code Factory} instances are configured in {@link HttpClient} via
     * {@link HttpClient#getContentDecoderFactories()}.</p>
     */
    abstract class Factory extends ContainerLifeCycle
    {
        private final String encoding;
        private final float weight;

        protected Factory(String encoding)
        {
            this(encoding, -1F);
        }

        protected Factory(String encoding, float weight)
        {
            this.encoding = Objects.requireNonNull(encoding);
            if (weight != -1F && !(weight >= 0F && weight <= 1F))
                throw new IllegalArgumentException("Invalid weight: " + weight);
            this.weight = weight;
        }

        /**
         * @return the type of the decoders created by this factory
         */
        public String getEncoding()
        {
            return encoding;
        }

        /**
         * @return the weight (between 0 and 1, at most 3 decimal digits) to use for the {@code Accept-Encoding} request header
         */
        public float getWeight()
        {
            return weight;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof Factory that))
                return false;
            return encoding.equals(that.encoding);
        }

        @Override
        public int hashCode()
        {
            return encoding.hashCode();
        }

        /**
         * <p>Creates a {@link Content.Source} that decodes the
         * chunks of the given {@link Content.Source} parameter.</p>
         *
         * @param contentSource the encoded {@link Content.Source}
         * @return the decoded {@link Content.Source}
         */
        public abstract Content.Source newDecoderContentSource(Content.Source contentSource);
    }

    class Factories extends ContainerLifeCycle implements Iterable<ContentDecoder.Factory>, Dumpable
    {
        private final Map<String, Factory> factories = new LinkedHashMap<>();
        private HttpField acceptEncodingField;

        public HttpField getAcceptEncodingField()
        {
            return acceptEncodingField;
        }

        @Override
        public Iterator<Factory> iterator()
        {
            return factories.values().iterator();
        }

        public boolean isEmpty()
        {
            return factories.isEmpty();
        }

        public void clear()
        {
            factories.clear();
            acceptEncodingField = null;
        }

        public Factory put(Factory factory)
        {
            Factory result = factories.put(factory.getEncoding(), factory);
            updateBean(result, factory, true);

            StringBuilder header = new StringBuilder();
            factories.forEach((encoding, value) ->
            {
                if (!header.isEmpty())
                    header.append(", ");
                header.append(encoding);
                float weight = value.getWeight();
                if (weight != -1F)
                    header.append(";q=").append(new DecimalFormat("#.###").format(weight));
            });
            acceptEncodingField = new HttpField(HttpHeader.ACCEPT_ENCODING, header.toString());

            return result;
        }

        @Override
        public String dump()
        {
            return Dumpable.dump(this);
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable.dumpObjects(out, indent, this, factories);
        }
    }
}
