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

import java.util.List;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * <p>{@code RewriteHandler} rewrites incoming requests through a set of {@link Rule}s.</p>
 * <p>{@code RewriteHandler} can rewrite the request URI, but also HTTP cookies and HTTP headers.
 * When a rule matches, {@code RewriteHandler} can perform actions such as redirect to a
 * different URI or directly produce a response with a configurable HTTP status code.</p>
 * <p>Rules can be grouped into a {@link RuleContainer}, which is itself a {@code Rule}.
 * If the container rule matches (for example, virtual host name when using
 * {@link VirtualHostRuleContainer}), then the contained rules will be applied.</p>
 * <p>Rules are applied in the same sequence they are added to the container.
 * If a rule matches, it applies some logic (typically wrapping the request),
 * and the next rule is invoked (with the wrapped request), until a
 * {@link Rule#isTerminating() terminating rule} is found, or all the rules have
 * been processed.</p>
 * <p>Request URI matching is performed either via Servlet pattern matching (using
 * {@link PatternRule} subclasses), via regular expression matching (using
 * {@link RegexRule} subclasses), or by a custom implementation of {@code Rule}.</p>
 */
public class RewriteHandler extends Handler.Wrapper
{
    private final RuleContainer _rules;

    public RewriteHandler()
    {
        this(new RuleContainer());
    }

    public RewriteHandler(RuleContainer rules)
    {
        _rules = rules;
        addBean(_rules);
    }

    /**
     * @return the {@link RuleContainer} used by this handler
     */
    public RuleContainer getRuleContainer()
    {
        return _rules;
    }

    /**
     * @return the list of {@link Rule}s.
     */
    public List<Rule> getRules()
    {
        return _rules.getRules();
    }

    /**
     * <p>Sets the list of rules, discarding the previous ones.</p>
     *
     * @param rules the list of {@link Rule}s to set
     */
    public void setRules(List<Rule> rules)
    {
        _rules.setRules(rules);
    }

    /**
     * <p>Adds a {@link Rule} to the existing ones.</p>
     *
     * @param rule the rule to add to the rules list
     */
    public void addRule(Rule rule)
    {
        _rules.addRule(rule);
    }

    /**
     * @see RuleContainer#getOriginalPathAttribute()
     */
    public String getOriginalPathAttribute()
    {
        return _rules.getOriginalPathAttribute();
    }

    /**
     * @see RuleContainer#setOriginalPathAttribute(String)
     */
    public void setOriginalPathAttribute(String originalPathAttribute)
    {
        _rules.setOriginalPathAttribute(originalPathAttribute);
    }

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        if (!isStarted())
            return false;

        Rule.Processor input = new Rule.Processor(request);
        Rule.Processor output = _rules.matchAndApply(input);

        // No rule matched, call super with the original request.
        if (output == null)
            return super.process(request, response, callback);

        // At least one rule matched, call super with the result of the rule applications.
        output.setProcessor(getHandler());
        return output.process(output, response, callback);
    }
}
