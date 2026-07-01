package org.eclipse.jetty.security;

public interface ServletAuthenticator
{
    String MUST_VALIDATE_KEY = ServletAuthenticator.class.getName() + ".MUST_VALIDATE_KEY";
    String CONSTRAINT_KEY = ServletAuthenticator.class.getName() + ".CONSTRAINT_KEY";
}
