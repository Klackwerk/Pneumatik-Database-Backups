package de.klackwerk.pneumatik.security

import grails.gorm.transactions.Transactional

@Transactional
class UserService {

    def springSecurityService
    RefreshTokenService refreshTokenService

    User editUserPassword(UserEditPasswordCommand cmd) {
        User user = springSecurityService.getCurrentUser() as User
        user.password = cmd.passwordNew
        user.save()
        refreshTokenService.revokeAllForUser(user)
        return user
    }

    User getCurrentUser() {
        return springSecurityService.getCurrentUser() as User
    }

    Boolean userHasUserRoleOrAdminRole(User user) {
        return userHasRoles(user, ['ROLE_USER', 'ROLE_ADMIN'])
    }

    Boolean userHasRoles(User user, List<String> roleList) {
        def result = user?.getAuthorities()?.collect { it.authority }?.intersect(roleList)
        if (result) {
            return true
        }
        return false
    }

    Boolean userHasRole(User user, String role) {
        return userHasRoles(user, [role])
    }

    Boolean currentUserHasUserRoleOrAdminRole() {
        return userHasUserRoleOrAdminRole(getCurrentUser())
    }

    Boolean userIsAllowedToEdit(candidate, User user) {
        Boolean candidateWasCreatedByUser = user == candidate.createdBy
        Boolean adminRights = userHasUserRoleOrAdminRole(user)
        if (candidateWasCreatedByUser || adminRights || !candidate.createdBy) return true
        return false
    }

    Boolean userIsAllowedToEdit(candidate) {
        return userIsAllowedToEdit(candidate, getCurrentUser())
    }

    Boolean userIsAllowedToRelease(User user) {
        if (userHasUserRoleOrAdminRole(user)) return true
        return false
    }

    Boolean userIsAllowedToRelease() {
        userIsAllowedToRelease(getCurrentUser())
    }

    List<User> getAllUsersWithRole(Role role) {
        return User.list().findAll { it.authorities?.contains(role) }
    }
}
