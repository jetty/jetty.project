package org.eclipse.jetty.security.authentication;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.UserIdentity;

/**
 * <p>A service to query for user roles.</p>
 */
public interface AuthorizationService
{
    /**
     * @param request the current HTTP request
     * @param name the user name
     * @param credentials the user credentials
     * @return a {@link UserIdentity} to query for roles of the given user
     */
    UserIdentity getUserIdentity(HttpServletRequest request, String name, Object credentials);

    /**
     * <p>Wraps a {@link LoginService} as an AuthorizationService</p>
     *
     * @param loginService the {@link LoginService} to wrap
     * @return an AuthorizationService that delegates the query for roles to the given {@link LoginService}
     */
    public static AuthorizationService from(LoginService loginService)
    {
        return (request, name, credentials) -> loginService.login(name, credentials, request);
    }
}
