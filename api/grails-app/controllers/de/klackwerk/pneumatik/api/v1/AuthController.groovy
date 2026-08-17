package de.klackwerk.pneumatik.api.v1

import de.klackwerk.pneumatik.api.BaseApiController
import de.klackwerk.pneumatik.security.RefreshTokenService
import de.klackwerk.pneumatik.security.User
import grails.plugin.springsecurity.annotation.Secured
import grails.plugin.springsecurity.rest.token.AccessToken
import grails.plugin.springsecurity.rest.token.generation.TokenGenerator
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService

/**
 * Session lifecycle beyond login (which spring-security-rest handles at
 * /api/v1/auth/login).
 *
 */
@Secured('permitAll')
class AuthController extends BaseApiController {

    static namespace = 'v1'

    RefreshTokenService refreshTokenService
    TokenGenerator tokenGenerator
    UserDetailsService userDetailsService

    /**
     * POST /api/v1/auth/refresh — exchange a refresh token for a new access
     * token. The refresh token is rotated, so the response carries a new one
     * and the presented token stops working.
     */
    def refresh() {
        String presented = presentedToken()
        if (!presented) {
            respondError(400, 'missing_refresh_token', 'A refresh_token is required')
            return
        }

        Map rotated = refreshTokenService.rotate(presented)
        if (!rotated) {
            respondError(401, 'invalid_refresh_token', 'The refresh token is unknown, expired or already used')
            return
        }

        User user = rotated.user as User
        UserDetails principal
        try {
            principal = userDetailsService.loadUserByUsername(user.username)
        } catch (Exception ignored) {
            respondError(401, 'invalid_refresh_token', 'The account no longer exists')
            return
        }

        // an account disabled or locked since the token was issued must not
        // be able to renew its way back in
        if (!principal.enabled || !principal.accountNonLocked || !principal.accountNonExpired) {
            refreshTokenService.revokeAllForUser(user)
            respondError(401, 'account_unavailable', 'This account can no longer sign in')
            return
        }

        AccessToken accessToken = tokenGenerator.generateAccessToken(principal)
        Map result = [
                username    : principal.username,
                roles       : principal.authorities.collect { GrantedAuthority role -> role.authority },
                token_type  : 'Bearer',
                access_token: accessToken.accessToken,
                refresh_token: rotated.token,
        ]
        if (accessToken.expiration) {
            result.expires_in = accessToken.expiration
        }
        renderJson(result)
    }

    /**
     * POST /api/v1/auth/logout — revoke a refresh token. Access tokens are
     * stateless and simply expire; this stops the session being renewed.
     */
    def logout() {
        refreshTokenService.revoke(presentedToken())
        // always 204: whether the token existed is not the caller's business
        respondNoContent()
    }

    private String presentedToken() {
        return (request.JSON instanceof Map ? (request.JSON as Map).refresh_token : null) as String
    }
}
