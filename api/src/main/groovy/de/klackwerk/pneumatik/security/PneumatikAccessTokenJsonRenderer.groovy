package de.klackwerk.pneumatik.security

import grails.plugin.springsecurity.rest.token.AccessToken
import grails.plugin.springsecurity.rest.token.rendering.AccessTokenJsonRenderer
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Renders the login response.
 *
 * Replaces the plugin's default renderer for one reason: its `refresh_token`
 * is a JWT built from the access-token claims with the expiration removed
 * (AbstractJwtTokenGenerator#generateRefreshToken), so it validates as a
 * never-expiring access token. Handing that to a browser would make the
 * one-hour access-token lifetime meaningless. Pneumatik issues its own
 * opaque, hashed, rotating token instead — see RefreshTokenService.
 */
@Slf4j
@CompileStatic
class PneumatikAccessTokenJsonRenderer implements AccessTokenJsonRenderer {

    RefreshTokenService refreshTokenService

    @Override
    String generateJson(AccessToken accessToken) {
        UserDetails principal = accessToken.principal as UserDetails

        Map result = [
                username    : principal.username,
                roles       : accessToken.authorities.collect { GrantedAuthority role -> role.authority },
                token_type  : 'Bearer',
                access_token: accessToken.accessToken,
        ] as Map

        if (accessToken.expiration) {
            result.expires_in = accessToken.expiration
        }

        String refreshToken = refreshTokenService.issueForUsername(principal.username)
        if (refreshToken) {
            result.refresh_token = refreshToken
        } else {
            log.warn "Could not issue a refresh token for '${principal.username}' — no matching user record"
        }

        return JsonOutput.toJson(result)
    }
}
