package de.klackwerk.pneumatik.api.v1

import de.klackwerk.pneumatik.api.ApiMappers
import de.klackwerk.pneumatik.api.BaseApiController
import de.klackwerk.pneumatik.security.User
import de.klackwerk.pneumatik.security.UserEditPasswordCommand
import de.klackwerk.pneumatik.security.UserManagementCreateUserCommand
import de.klackwerk.pneumatik.security.UserManagementEditUserCommand
import de.klackwerk.pneumatik.security.UserManagementService
import de.klackwerk.pneumatik.security.UserService
import grails.plugin.springsecurity.annotation.Secured

@Secured('ROLE_ADMIN')
class UsersController extends BaseApiController {

    static namespace = 'v1'

    UserService userService
    UserManagementService userManagementService

    /** GET /api/v1/users/me */
    def me() {
        User user = userService.currentUser
        if (!user) {
            respondError(401, 'unauthenticated', 'Not authenticated')
            return
        }
        respondData(ApiMappers.userToMap(user))
    }

    /** PUT /api/v1/users/me/password */
    def changePassword(UserEditPasswordCommand cmd) {
        if (cmd.hasErrors()) {
            respondValidationErrors(cmd)
            return
        }
        User user = userService.editUserPassword(cmd)
        if (user.hasErrors()) {
            respondValidationErrors(user)
            return
        }
        respondData(ApiMappers.userToMap(user))
    }

    /** GET /api/v1/users */
    def index() {
        Map p = pagination(50, 200)
        List<User> users = User.list(sort: 'id', max: p.max as int, offset: p.offset as int)
        respondData(users.collect { ApiMappers.userToMap(it) }, paginationMeta(p, User.count()))
    }

    def show(String id) {
        User user = User.get(id)
        if (!user) {
            respondNotFound('User')
            return
        }
        respondData(ApiMappers.userToMap(user))
    }

    def save(UserManagementCreateUserCommand cmd) {
        if (cmd.hasErrors()) {
            respondValidationErrors(cmd)
            return
        }
        User user = userManagementService.createUser(cmd)
        if (user.hasErrors()) {
            respondValidationErrors(user)
            return
        }
        respondCreated(ApiMappers.userToMap(user), locationOf("/api/v1/users/${user.id}"))
    }

    def update(String id, UserManagementEditUserCommand cmd) {
        User existing = User.get(id)
        if (!existing) {
            respondNotFound('User')
            return
        }
        // validation ran at binding time without the id; re-run with it so
        // uniqueness checks can exclude the record being edited
        cmd.id = id
        cmd.clearErrors()
        if (!cmd.validate()) {
            respondValidationErrors(cmd)
            return
        }
        User user = userManagementService.editUser(cmd)
        if (user.hasErrors()) {
            respondValidationErrors(user)
            return
        }
        respondData(ApiMappers.userToMap(user))
    }
}
