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

package org.eclipse.jetty.ee9.security.jaspi.provider;

import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.auth.message.module.ServerAuthModule;
import org.eclipse.jetty.ee9.security.jaspi.JaspiMessageInfo;

/**
 * <p>A {@link ServerAuthContext} that delegates to a single {@link ServerAuthModule}, initialized with
 * the request policy of this context.</p>
 * <p>The Servlet Container Profile requires a non-null request policy whose {@code isMandatory} agrees
 * with the mandatory message key, and at least one target policy asking for the sender to be
 * authenticated. A module of its own is initialized with the fixed policy of this context; one shared
 * with the context of the other mandatory value is initialized with a policy that reports the message.</p>
 */
class SimpleServerAuthContext implements ServerAuthContext
{
    private static final MessagePolicy MANDATORY = authenticateSender(true);
    private static final MessagePolicy OPTIONAL = authenticateSender(false);

    private final ServerAuthModule serverAuthModule;
    private final MessageRequestPolicy messageRequestPolicy;

    /**
     * @param mandatory whether authentication is mandatory for every message of this context.
     */
    @SuppressWarnings("rawtypes")
    public SimpleServerAuthContext(CallbackHandler callbackHandler, ServerAuthModule serverAuthModule, Map properties, boolean mandatory) throws AuthException
    {
        this.serverAuthModule = serverAuthModule;
        this.messageRequestPolicy = null;
        serverAuthModule.initialize(mandatory ? MANDATORY : OPTIONAL, null, callbackHandler, properties);
    }

    /**
     * A context for a module shared with the context of the other mandatory value, which cannot hold
     * a policy for each and cannot be initialized again once a message is being processed with it.
     */
    @SuppressWarnings("rawtypes")
    public SimpleServerAuthContext(CallbackHandler callbackHandler, ServerAuthModule serverAuthModule, Map properties) throws AuthException
    {
        this.serverAuthModule = serverAuthModule;
        this.messageRequestPolicy = new MessageRequestPolicy();
        serverAuthModule.initialize(messageRequestPolicy, null, callbackHandler, properties);
    }

    private static MessagePolicy authenticateSender(boolean mandatory)
    {
        MessagePolicy.TargetPolicy targetPolicy =
            new MessagePolicy.TargetPolicy(null, () -> MessagePolicy.ProtectionPolicy.AUTHENTICATE_SENDER);
        return new MessagePolicy(new MessagePolicy.TargetPolicy[]{targetPolicy}, mandatory);
    }

    /**
     * Make the message being processed the one a shared module's request policy reports, for as long
     * as that module is in the call.
     *
     * @param messageInfo the message being processed.
     * @return the message the calling thread was processing before, to give to {@link #exit(Boolean)}.
     */
    private Boolean enter(MessageInfo messageInfo)
    {
        return messageRequestPolicy == null ? null : messageRequestPolicy.enter(messageInfo);
    }

    /**
     * @param outer the message the calling thread was processing before, which it carries on with;
     * a module that re-enters this context makes the call it is in such a message.
     */
    private void exit(Boolean outer)
    {
        if (messageRequestPolicy != null)
            messageRequestPolicy.exit(outer);
    }

    @Override
    public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
    {
        Boolean outer = enter(messageInfo);
        try
        {
            return serverAuthModule.validateRequest(messageInfo, clientSubject, serviceSubject);
        }
        finally
        {
            exit(outer);
        }
    }

    @Override
    public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
    {
        Boolean outer = enter(messageInfo);
        try
        {
            return serverAuthModule.secureResponse(messageInfo, serviceSubject);
        }
        finally
        {
            exit(outer);
        }
    }

    @Override
    public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException
    {
        Boolean outer = enter(messageInfo);
        try
        {
            serverAuthModule.cleanSubject(messageInfo, subject);
        }
        finally
        {
            exit(outer);
        }
    }

    /**
     * <p>The request policy of a {@link ServerAuthModule} shared by the mandatory and the optional
     * context of a configuration, which cannot hold a policy for each.</p>
     * <p>{@link #isMandatory()} is that of the message the calling thread is processing, so it only
     * answers while the module is in a {@link ServerAuthContext} call on that thread: it is false
     * during {@code initialize} and on any thread the module starts of its own. A module that needs
     * the value elsewhere reads {@link JaspiMessageInfo#MANDATORY_KEY} from the message.</p>
     */
    private static class MessageRequestPolicy extends MessagePolicy
    {
        private final ThreadLocal<Boolean> mandatory = new ThreadLocal<>();

        private MessageRequestPolicy()
        {
            super(OPTIONAL.getTargetPolicies(), false);
        }

        @Override
        public boolean isMandatory()
        {
            return Boolean.TRUE.equals(mandatory.get());
        }

        private Boolean enter(MessageInfo messageInfo)
        {
            Boolean outer = mandatory.get();
            mandatory.set(SimpleAuthConfig.isMandatory(messageInfo));
            return outer;
        }

        private void exit(Boolean outer)
        {
            if (outer == null)
                mandatory.remove();
            else
                mandatory.set(outer);
        }
    }
}
