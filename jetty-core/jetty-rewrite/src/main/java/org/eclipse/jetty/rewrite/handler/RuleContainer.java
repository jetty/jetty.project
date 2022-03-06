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
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base container to group rules. Can be extended so that the contained rules
 * will only be applied under certain conditions
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
        return _rules;
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
     * Add a Rule
     *
     * @param rule The rule to add to the end of the rules array
     */
    public void addRule(Rule rule)
    {
        _rules.add(rule);
    }

    /**
     * @return the originalPathAttribte. If non null, this string will be used
     * as the attribute name to store the original request path.
     */
    public String getOriginalPathAttribute()
    {
        return _originalPathAttribute;
    }

    /**
     * @param originalPathAttribute If non null, this string will be used
     * as the attribute name to store the original request path.
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
     * @return a {@code Request} and {@code Processor}, possibly wrapped by rules to implement the rule's logic
     */
    @Override
    public Request.WrapperProcessor matchAndApply(Request.WrapperProcessor input) throws IOException
    {
        if (_originalPathAttribute != null)
        {
            HttpURI httpURI = input.getHttpURI();
            input.setAttribute(_originalPathAttribute, httpURI.getPath());
            if (_originalQueryStringAttribute != null)
                input.setAttribute(_originalQueryStringAttribute, httpURI.getQuery());
        }

        boolean match = false;
        for (Rule rule : _rules)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("applying {}", rule);
            Request.WrapperProcessor output = rule.matchAndApply(input);
            if (output == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("did not match {}", rule);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("matched {}", rule);

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

        // TODO: check the logic below
/*
            String applied = rule.matchAndApply(input);
            if (applied != null)
            {
                if (_rewriteRequestURI)
                {
                    String encoded = URIUtil.encodePath(applied);
                    if (rule instanceof Rule.ApplyURI)
                        ((Rule.ApplyURI)rule).applyURI(baseRequest, baseRequest.getRequestURI(), encoded);
                    else
                    {
                        HttpURI baseUri = baseRequest.getHttpURI();
                        baseRequest.setHttpURI(HttpURI.build(baseUri, encoded)
                            .param(baseUri.getParam())
                            .query(baseUri.getQuery()));
                    }
                }

                if (_rewritePathInfo)
                    baseRequest.setContext(baseRequest.getContext(), applied);

                target = applied;

                if (rule.isHandling())
                {
                    LOG.debug("handling {}", rule);
                    baseRequest.setHandled(true);
                }

                if (rule.isCompleting())
                {
                    LOG.debug("terminating {}", rule);
                    break;
                }
            }
        }

        return target;
 */
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, _rules);
    }
}
