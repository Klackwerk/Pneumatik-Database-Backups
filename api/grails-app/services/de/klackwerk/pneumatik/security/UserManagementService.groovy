package de.klackwerk.pneumatik.security

import grails.gorm.transactions.Transactional

@Transactional
class UserManagementService {

    User editUser(UserManagementEditUserCommand cmd) {
        User user = User.get(cmd.id)
        user.username = cmd.username
        user.email = cmd.email
        if (cmd.password) user.password = cmd.password
        user.enabled = cmd.enabled ?: false

        List<Role> targetRoles = (cmd.authorities ?: []).collect { Role.findByAuthority(it) }.findAll()
        List<Long> targetRoleIds = targetRoles*.id

        List<Long> rolesToRemoveIds = user.authorities.findAll { Role role ->
            !targetRoleIds.contains(role.id)
        }*.id
        List<Long> rolesToAddIds = targetRoles.findAll { Role role ->
            !user.authorities*.id.contains(role.id)
        }*.id

        rolesToRemoveIds.each {
            UserRole.remove(user, Role.get(it))
        }
        rolesToAddIds.each {
            UserRole.create(user, Role.get(it))
        }

        return user
    }

    User createUser(UserManagementCreateUserCommand cmd) {
        User user = new User()
        user.username = cmd.username
        user.password = cmd.password
        user.email = cmd.email
        user.save()

        cmd.roles?.each { String authority ->
            Role role = Role.findByAuthority(authority)
            if (role) {
                UserRole.create(user, role)
            }
        }

        user.save()
        return user
    }
}
