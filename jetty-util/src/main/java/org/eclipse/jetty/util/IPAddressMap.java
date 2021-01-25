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

package org.eclipse.jetty.util;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * Internet address map to object
 * <p>
 * Internet addresses may be specified as absolute address or as a combination of
 * four octet wildcard specifications (a.b.c.d) that are defined as follows.
 * </p>
 * <pre>
 *     nnn  - an absolute value (0-255)
 * mmm-nnn  - an inclusive range of absolute values,
 *            with following shorthand notations:
 *              nnn- =&gt; nnn-255
 *             -nnn  =&gt; 0-nnn
 *             -     =&gt; 0-255
 *          a,b,...  - a list of wildcard specifications
 * </pre>
 *
 * @param <TYPE> the Map Entry value type
 * @deprecated
 */
@SuppressWarnings("serial")
public class IPAddressMap<TYPE> extends HashMap<String, TYPE>
{
    private final HashMap<String, IPAddrPattern> _patterns = new HashMap<String, IPAddrPattern>();

    /**
     * Construct empty IPAddressMap.
     */
    public IPAddressMap()
    {
        super(11);
    }

    /**
     * Construct empty IPAddressMap.
     *
     * @param capacity initial capacity
     */
    public IPAddressMap(int capacity)
    {
        super(capacity);
    }

    /**
     * Insert a new internet address into map
     *
     * @see java.util.HashMap#put(java.lang.Object, java.lang.Object)
     */
    @Override
    public TYPE put(String addrSpec, TYPE object)
        throws IllegalArgumentException
    {
        if (addrSpec == null || addrSpec.trim().length() == 0)
            throw new IllegalArgumentException("Invalid IP address pattern: " + addrSpec);

        String spec = addrSpec.trim();
        if (_patterns.get(spec) == null)
            _patterns.put(spec, new IPAddrPattern(spec));

        return super.put(spec, object);
    }

    /**
     * Retrieve the object mapped to the specified internet address literal
     *
     * @see java.util.HashMap#get(java.lang.Object)
     */
    @Override
    public TYPE get(Object key)
    {
        return super.get(key);
    }

    /**
     * Retrieve the first object that is associated with the specified
     * internet address by taking into account the wildcard specifications.
     *
     * @param addr internet address
     * @return associated object
     */
    public TYPE match(String addr)
    {
        Map.Entry<String, TYPE> entry = getMatch(addr);
        return entry == null ? null : entry.getValue();
    }

    /**
     * Retrieve the first map entry that is associated with the specified
     * internet address by taking into account the wildcard specifications.
     *
     * @param addr internet address
     * @return map entry associated
     */
    public Map.Entry<String, TYPE> getMatch(String addr)
    {
        if (addr != null)
        {
            for (Map.Entry<String, TYPE> entry : super.entrySet())
            {
                if (_patterns.get(entry.getKey()).match(addr))
                {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Retrieve a lazy list of map entries associated with specified
     * internet address by taking into account the wildcard specifications.
     *
     * @param addr internet address
     * @return lazy list of map entries
     */
    public Object getLazyMatches(String addr)
    {
        if (addr == null)
            return LazyList.getList(super.entrySet());

        Object entries = null;
        for (Map.Entry<String, TYPE> entry : super.entrySet())
        {
            if (_patterns.get(entry.getKey()).match(addr))
            {
                entries = LazyList.add(entries, entry);
            }
        }
        return entries;
    }

    /**
     * IPAddrPattern
     *
     * Represents internet address wildcard.
     * Matches the wildcard to provided internet address.
     */
    private static class IPAddrPattern
    {
        private final OctetPattern[] _octets = new OctetPattern[4];

        /**
         * Create new IPAddrPattern
         *
         * @param value internet address wildcard specification
         * @throws IllegalArgumentException if wildcard specification is invalid
         */
        public IPAddrPattern(String value)
            throws IllegalArgumentException
        {
            if (value == null || value.trim().length() == 0)
                throw new IllegalArgumentException("Invalid IP address pattern: " + value);

            try
            {
                StringTokenizer parts = new StringTokenizer(value, ".");

                String part;
                for (int idx = 0; idx < 4; idx++)
                {
                    part = parts.hasMoreTokens() ? parts.nextToken().trim() : "0-255";

                    int len = part.length();
                    if (len == 0 && parts.hasMoreTokens())
                        throw new IllegalArgumentException("Invalid IP address pattern: " + value);

                    _octets[idx] = new OctetPattern(len == 0 ? "0-255" : part);
                }
            }
            catch (IllegalArgumentException ex)
            {
                throw new IllegalArgumentException("Invalid IP address pattern: " + value, ex);
            }
        }

        /**
         * Match the specified internet address against the wildcard
         *
         * @param value internet address
         * @return true if specified internet address matches wildcard specification
         * @throws IllegalArgumentException if specified internet address is invalid
         */
        public boolean match(String value)
            throws IllegalArgumentException
        {
            if (value == null || value.trim().length() == 0)
                throw new IllegalArgumentException("Invalid IP address: " + value);

            try
            {
                StringTokenizer parts = new StringTokenizer(value, ".");

                boolean result = true;
                for (int idx = 0; idx < 4; idx++)
                {
                    if (!parts.hasMoreTokens())
                        throw new IllegalArgumentException("Invalid IP address: " + value);

                    if (!(result &= _octets[idx].match(parts.nextToken())))
                        break;
                }
                return result;
            }
            catch (IllegalArgumentException ex)
            {
                throw new IllegalArgumentException("Invalid IP address: " + value, ex);
            }
        }
    }

    /**
     * OctetPattern
     *
     * Represents a single octet wildcard.
     * Matches the wildcard to the specified octet value.
     */
    private static class OctetPattern extends BitSet
    {
        private final BitSet _mask = new BitSet(256);

        /**
         * Create new OctetPattern
         *
         * @param octetSpec octet wildcard specification
         * @throws IllegalArgumentException if wildcard specification is invalid
         */
        public OctetPattern(String octetSpec)
            throws IllegalArgumentException
        {
            try
            {
                if (octetSpec != null)
                {
                    String spec = octetSpec.trim();
                    if (spec.length() == 0)
                    {
                        _mask.set(0, 255);
                    }
                    else
                    {
                        StringTokenizer parts = new StringTokenizer(spec, ",");
                        while (parts.hasMoreTokens())
                        {
                            String part = parts.nextToken().trim();
                            if (part.length() > 0)
                            {
                                if (part.indexOf('-') < 0)
                                {
                                    int value = Integer.parseInt(part);
                                    _mask.set(value);
                                }
                                else
                                {
                                    int low = 0;
                                    int high = 255;

                                    String[] bounds = part.split("-", -2);
                                    if (bounds.length != 2)
                                    {
                                        throw new IllegalArgumentException("Invalid octet spec: " + octetSpec);
                                    }

                                    if (bounds[0].length() > 0)
                                    {
                                        low = Integer.parseInt(bounds[0]);
                                    }
                                    if (bounds[1].length() > 0)
                                    {
                                        high = Integer.parseInt(bounds[1]);
                                    }

                                    if (low > high)
                                    {
                                        throw new IllegalArgumentException("Invalid octet spec: " + octetSpec);
                                    }

                                    _mask.set(low, high + 1);
                                }
                            }
                        }
                    }
                }
            }
            catch (NumberFormatException ex)
            {
                throw new IllegalArgumentException("Invalid octet spec: " + octetSpec, ex);
            }
        }

        /**
         * Match specified octet value against the wildcard
         *
         * @param value octet value
         * @return true if specified octet value matches the wildcard
         * @throws IllegalArgumentException if specified octet value is invalid
         */
        public boolean match(String value)
            throws IllegalArgumentException
        {
            if (value == null || value.trim().length() == 0)
                throw new IllegalArgumentException("Invalid octet: " + value);

            try
            {
                int number = Integer.parseInt(value);
                return match(number);
            }
            catch (NumberFormatException ex)
            {
                throw new IllegalArgumentException("Invalid octet: " + value);
            }
        }

        /**
         * Match specified octet value against the wildcard
         *
         * @param number octet value
         * @return true if specified octet value matches the wildcard
         * @throws IllegalArgumentException if specified octet value is invalid
         */
        public boolean match(int number)
            throws IllegalArgumentException
        {
            if (number < 0 || number > 255)
                throw new IllegalArgumentException("Invalid octet: " + number);

            return _mask.get(number);
        }
    }
}
