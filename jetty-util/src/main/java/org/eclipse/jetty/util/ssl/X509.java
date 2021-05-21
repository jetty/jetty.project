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

package org.eclipse.jetty.util.ssl;

import java.net.InetAddress;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class X509
{
    private static final Logger LOG = Log.getLogger(X509.class);
    /*
     * @see {@link X509Certificate#getKeyUsage()}
     */
    private static final int KEY_USAGE__KEY_CERT_SIGN = 5;
    /*
     * @see {@link X509Certificate#getSubjectAlternativeNames()}
     */
    private static final int SUBJECT_ALTERNATIVE_NAMES__DNS_NAME = 2;
    private static final int SUBJECT_ALTERNATIVE_NAMES__IP_ADDRESS = 7;

    public static boolean isCertSign(X509Certificate x509)
    {
        boolean[] keyUsage = x509.getKeyUsage();
        if ((keyUsage == null) || (keyUsage.length <= KEY_USAGE__KEY_CERT_SIGN))
        {
            return false;
        }
        return keyUsage[KEY_USAGE__KEY_CERT_SIGN];
    }

    private final X509Certificate _x509;
    private final String _alias;
    private final Set<String> _hosts = new LinkedHashSet<>();
    private final Set<String> _wilds = new LinkedHashSet<>();
    private final Set<InetAddress> _addresses = new LinkedHashSet<>();

    public X509(String alias, X509Certificate x509)
    {
        _alias = alias;
        _x509 = x509;

        try
        {
            // Look for alternative name extensions
            Collection<List<?>> altNames = x509.getSubjectAlternativeNames();
            if (altNames != null)
            {
                for (List<?> list : altNames)
                {
                    int nameType = ((Number)list.get(0)).intValue();
                    switch (nameType)
                    {
                        case SUBJECT_ALTERNATIVE_NAMES__DNS_NAME:
                        {
                            String name = list.get(1).toString();
                            if (LOG.isDebugEnabled())
                                LOG.debug("Certificate alias={} SAN dns={} in {}", alias, name, this);
                            addName(name);
                            break;
                        }
                        case SUBJECT_ALTERNATIVE_NAMES__IP_ADDRESS:
                        {
                            String address = list.get(1).toString();
                            if (LOG.isDebugEnabled())
                                LOG.debug("Certificate alias={} SAN ip={} in {}", alias, address, this);
                            addAddress(address);
                            break;
                        }
                        default:
                            break;
                    }
                }
            }

            // If no names found, look up the CN from the subject
            LdapName name = new LdapName(x509.getSubjectX500Principal().getName(X500Principal.RFC2253));
            for (Rdn rdn : name.getRdns())
            {
                if (rdn.getType().equalsIgnoreCase("CN"))
                {
                    String cn = rdn.getValue().toString();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Certificate CN alias={} CN={} in {}", alias, cn, this);
                    addName(cn);
                }
            }
        }
        catch (Exception x)
        {
            throw new IllegalArgumentException(x);
        }
    }

    protected void addName(String cn)
    {
        if (cn != null)
        {
            cn = StringUtil.asciiToLowerCase(cn);
            if (cn.startsWith("*."))
                _wilds.add(cn.substring(2));
            else
                _hosts.add(cn);
        }
    }

    private void addAddress(String host)
    {
        // Class InetAddress handles IPV6 brackets and IPv6 short forms, so that [::1]
        // would match 0:0:0:0:0:0:0:1 as well as 0000:0000:0000:0000:0000:0000:0000:0001.
        InetAddress address = toInetAddress(host);
        if (address != null)
            _addresses.add(address);
    }

    private InetAddress toInetAddress(String address)
    {
        try
        {
            return InetAddress.getByName(address);
        }
        catch (Throwable x)
        {
            LOG.ignore(x);
            return null;
        }
    }

    public String getAlias()
    {
        return _alias;
    }

    public X509Certificate getCertificate()
    {
        return _x509;
    }

    public Set<String> getHosts()
    {
        return Collections.unmodifiableSet(_hosts);
    }

    public Set<String> getWilds()
    {
        return Collections.unmodifiableSet(_wilds);
    }

    public boolean matches(String host)
    {
        host = StringUtil.asciiToLowerCase(host);
        if (_hosts.contains(host) || _wilds.contains(host))
            return true;

        int dot = host.indexOf('.');
        if (dot >= 0)
        {
            String domain = host.substring(dot + 1);
            if (_wilds.contains(domain))
                return true;
        }

        // Check if the host looks like an IP address to avoid
        // DNS lookup for host names that did not match.
        if (seemsIPAddress(host))
        {
            InetAddress address = toInetAddress(host);
            if (address != null)
                return _addresses.contains(address);
        }

        return false;
    }

    private static boolean seemsIPAddress(String host)
    {
        // IPv4 is just numbers and dots.
        String ipv4RegExp = "[0-9\\.]+";
        // IPv6 is hex and colons and possibly brackets.
        String ipv6RegExp = "[0-9a-fA-F:\\[\\]]+";
        return host.matches(ipv4RegExp) ||
            (host.matches(ipv6RegExp) && containsAtLeastTwoColons(host));
    }

    private static boolean containsAtLeastTwoColons(String host)
    {
        int index = host.indexOf(':');
        if (index >= 0)
            index = host.indexOf(':', index + 1);
        return index > 0;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(%s,h=%s,a=%s,w=%s)",
            getClass().getSimpleName(),
            hashCode(),
            _alias,
            _hosts,
            _addresses,
            _wilds);
    }
}
