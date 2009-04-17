package org.eclipse.jetty.server;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;


/* ------------------------------------------------------------ */
/** The Authentication state of a request.
 * <p>
 * The Authentication state can be one of several sub-types that
 * reflects where the request is in the many different authentication
 * cycles. Authentication might not yet be checked or it might be checked
 * and failed, checked and deferred or succeeded. 
 * 
 */
public interface Authentication
{

    
    /* ------------------------------------------------------------ */
    /** A successful Authentication with User information.
     */
    public interface User extends Authentication
    {
        String getAuthMethod();
        UserIdentity getUserIdentity(); 
        boolean isUserInRole(UserIdentity.Scope scope,String role);
        void logout(); 
    }
    
    
    /* ------------------------------------------------------------ */
    /** A deferred authentication with methods to progress 
     * the authentication process.
     */
    public interface Deferred extends Authentication
    {
        /* ------------------------------------------------------------ */
        /** Authenticate if possible without sending a challenge.
         * This is used to check credentials that have been sent for 
         * non-manditory authentication.
         * @return The new Authentication state.
         */
        Authentication authenticate();

        /* ------------------------------------------------------------ */
        /** Authenticate and possibly send a challenge.
         * This is used to initiate authentication for previously 
         * non-manditory authentication.
         * @return The new Authentication state.
         */
        Authentication authenticate(ServletRequest request,ServletResponse response);
        
        
        /* ------------------------------------------------------------ */
        /** Login with the LOGIN authenticator
         * @param username
         * @param password
         * @return The new Authentication state
         */
        Authentication login(String username,String password);
    }

    
    /* ------------------------------------------------------------ */
    /** Authentication Response sent state.
     * Responses are sent by authenticators either to issue an
     * authentication challenge or on successful authentication in
     * order to redirect the user to the original URL.
     */
    public interface ResponseSent extends Authentication
    { 
    }
    
    /* ------------------------------------------------------------ */
    /** An Authentication Challenge has been sent.
     */
    public interface Challenge extends ResponseSent
    { 
    }

    /* ------------------------------------------------------------ */
    /** An Authentication Failure has been sent.
     */
    public interface Failure extends ResponseSent
    { 
    }

    /* ------------------------------------------------------------ */
    /** Unauthenticated state.
     * <p> 
     * This convenience instance is for non mandatory authentication where credentials
     * have been presented and checked, but failed authentication. 
     */
    public final static Authentication UNAUTHENTICATED = new Authentication(){public String toString(){return "UNAUTHENTICATED";}};

    /* ------------------------------------------------------------ */
    /** Authentication not checked
     * <p>
     * This convenience instance us for non mandatory authentication when no 
     * credentials are present to be checked.
     */
    public final static Authentication NOT_CHECKED = new Authentication(){public String toString(){return "NOT CHECKED";}};

    /* ------------------------------------------------------------ */
    /** Authentication challenge sent.
     * <p>
     * This convenience instance is for when an authentication challenge has been sent.
     */
    public final static Authentication CHALLENGE = new Authentication.Challenge(){public String toString(){return "CHALLENGE";}};

    /* ------------------------------------------------------------ */
    /** Authentication failure sent.
     * <p>
     * This convenience instance is for when an authentication failure has been sent.
     */
    public final static Authentication FAILURE = new Authentication.Failure(){public String toString(){return "FAILURE";}};
}
