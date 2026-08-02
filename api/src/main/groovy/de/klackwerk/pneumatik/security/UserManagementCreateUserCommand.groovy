package de.klackwerk.pneumatik.security

import grails.validation.Validateable

class UserManagementCreateUserCommand implements Validateable {

    String username
    String password
    String email

    /** role authority strings, e.g. ["ROLE_ADMIN"] */
    List<String> roles

    static constraints = {
        username nullable: false, blank: false, validator: { String val ->
            if (User.findByUsername(val)) return ['unique']
        }
        email nullable: false, blank: false, validator: { String val ->
            if (User.findByEmail(val)) return ['unique']
        }
        password nullable: false, blank: false, minSize: 12
        roles nullable: true, validator: { List<String> val ->
            if (val && val.any { Role.findByAuthority(it) == null }) return ['unknownRole']
        }
    }
}
