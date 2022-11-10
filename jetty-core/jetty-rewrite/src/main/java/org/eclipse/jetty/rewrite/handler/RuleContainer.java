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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A container that groups {@link Rule}s and is itself a {@code Rule}.</p>
 * <p>The contained rules will be applied only if the container rule matches.</p>
 */
public class RuleContainer extends Rule implements Iterable<Rule>, Dumpable
{
    public static final String ORIGINAL_QUERYSTRING_ATTRIBUTE_SUFFIX = ".QUERYSTRING";
    private static final Logger LOG = LoggerFactory.getLogger(RuleContainer.class);

    private final List<Rule> _rules = new ArrayList<>();

    private String _originalPathAttribute;
    private String _originalQueryStringAttribute;

    /**
     * @return the list of {@code Rule}s
     */
    public List<Rule> getRules()
    {
        return List.copyOf(_rules);
    }

    /**
     * @param rules the list of {@link Rule}.
     */
    public void setRules(List<Rule> rules)
    {
        _rules.clear();
        _rules.addAll(rules);
    }

    @Override
    public Iterator<Rule> iterator()
    {
        return _rules.iterator();
    }

    /**
     * <p>Adds a {@link Rule} to the existing ones.</p>
     *
     * @param rule the rule to add to the rules list
     */
    public void addRule(Rule rule)
    {
        _rules.add(rule);
    }

    /**
     * @return the request attribute name used to store the request original path
     * @see #setOriginalPathAttribute(String)
     */
    public String getOriginalPathAttribute()
    {
        return _originalPathAttribute;
    }

    /**
     * <p>Sets a request attribute name that will be used to store the request original path.</p>
     * <p>A request attribute name that stores the request original query is derived from this
     * attribute name by adding {@link #ORIGINAL_QUERYSTRING_ATTRIBUTE_SUFFIX}, as in:</p>
     * <pre>
     * String originalQueryAttribute = ruleContainer.getOriginalPathAttribute() + ORIGINAL_QUERYSTRING_ATTRIBUTE_SUFFIX;
     * </pre>
     *
     * @param originalPathAttribute the request attribute name used to store the request original path
     */
    public void setOriginalPathAttribute(String originalPathAttribute)
    {
        _originalPathAttribute = originalPathAttribute;
        _originalQueryStringAttribute = originalPathAttribute + ORIGINAL_QUERYSTRING_ATTRIBUTE_SUFFIX;
    }

    /**
     * <p>Processes the rules.</p>
     *
     * @param input the input {@code Request} and {@code Processor}
     * @return a {@code Request} and {@code Processor}, possibly wrapped by rules to implement the rule's logic,
     * or {@code null} if no rule matched
     */
    @Override
    public RequestProcessor matchAndApply(RequestProcessor input) throws IOException
    {
        String originalPathAttribute = getOriginalPathAttribute();
        if (originalPathAttribute != null)
        {
            HttpURI httpURI = input.getHttpURI();
            input.setAttribute(originalPathAttribute, httpURI.getPath());
            String originalQueryStringAttribute = _originalQueryStringAttribute;
            if (originalQueryStringAttribute != null)
                input.setAttribute(originalQueryStringAttribute, httpURI.getQuery());
        }

        boolean match = false;
        for (Rule rule : _rules)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("applying {}", rule);
            RequestProcessor output = rule.matchAndApply(input);
            if (output == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("no match {}", rule);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("match {}", rule);

                match = true;

                // Chain the rules.
                input = output;

                if (rule.isTerminating())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("terminating {}", rule);
                    break;
                }
            }
        }

        return match ? input : null;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, _rules);
    }
}
