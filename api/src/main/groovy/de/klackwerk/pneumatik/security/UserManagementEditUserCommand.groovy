package de.klackwerk.pneumatik.security

import grails.validation.Validateable

class UserManagementEditUserCommand implements Validateable {

    /** id of the user being edited; set by the controller from the URL */
    String id

    String username
    String password
    String email
    Boolean enabled

    /** role authority strings, e.g. ["ROLE_ADMIN"] */
    List<String> authorities

    static constraints = {
        id nullable: true
        username nullable: false, blank: false, validator: { String val, UserManagementEditUserCommand obj ->
            if (User.findByUsernameAndIdNotEqual(val, obj.id)) return ['unique']
        }
        email nullable: false, blank: false, validator: { String val, UserManagementEditUserCommand obj ->
            if (User.findByEmailAndIdNotEqual(val, obj.id)) return ['uniqueEmail']
        }
        password nullable: true, blank: false, minSize: 12
        enabled nullable: true
        authorities nullable: true, validator: { List<String> val ->
            if (val && val.any { Role.findByAuthority(it) == null }) return ['unknownRole']
        }
    }
}
