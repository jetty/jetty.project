//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.naming.InvalidNameException;
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
    private static final int KEY_USAGE__KEY_CERT_SIGN=5;

    /*
     *
     * @see {@link X509Certificate#getSubjectAlternativeNames()}
     */
    private static final int SUBJECT_ALTERNATIVE_NAMES__DNS_NAME=2;

    public static boolean isCertSign(X509Certificate x509)
    {
        boolean[] key_usage=x509.getKeyUsage();
        return key_usage!=null && key_usage[KEY_USAGE__KEY_CERT_SIGN];
    }

    private final X509Certificate _x509;
    private final String _alias;
    private final List<String> _hosts=new ArrayList<>();
    private final List<String> _wilds=new ArrayList<>();

    public X509(String alias,X509Certificate x509) throws CertificateParsingException, InvalidNameException
    {
        _alias=alias;
        _x509 = x509;

        // Look for alternative name extensions
        boolean named=false;
        Collection<List<?>> altNames = x509.getSubjectAlternativeNames();
        if (altNames!=null)
        {
            for (List<?> list : altNames)
            {
                if (((Number)list.get(0)).intValue() == SUBJECT_ALTERNATIVE_NAMES__DNS_NAME)
                {
                    String cn = list.get(1).toString();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Certificate SAN alias={} CN={} in {}",alias,cn,this);
                    if (cn!=null)
                    {
                        named=true;
                        addName(cn);
                    }
                }
            }
        }

        // If no names found, look up the CN from the subject
        if (!named)
        {
            LdapName name=new LdapName(x509.getSubjectX500Principal().getName(X500Principal.RFC2253));
            for (Rdn rdn : name.getRdns())
            {
                if (rdn.getType().equalsIgnoreCase("CN"))
                {
                    String cn = rdn.getValue().toString();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Certificate CN alias={} CN={} in {}",alias,cn,this);
                    if (cn!=null && cn.contains(".") && !cn.contains(" "))
                        addName(cn);
                }
            }
        }
    }

    protected void addName(String cn)
    {
        cn=StringUtil.asciiToLowerCase(cn);
        if (cn.startsWith("*."))
            _wilds.add(cn.substring(2));
        else
            _hosts.add(cn);
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
        return new HashSet<>(_hosts);
    }

    public Set<String> getWilds()
    {
        return new HashSet<>(_wilds);
    }

    public boolean matches(String host)
    {
        host=StringUtil.asciiToLowerCase(host);
        if (_hosts.contains(host) || _wilds.contains(host))
            return true;

        int dot = host.indexOf('.');
        if (dot>=0)
        {
            String domain=host.substring(dot+1);
            if (_wilds.contains(domain))
                return true;
        }
        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(%s,h=%s,w=%s)",
                getClass().getSimpleName(),
                hashCode(),
                _alias,
                _hosts,
                _wilds);
    }
}
