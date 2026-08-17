package de.klackwerk.pneumatik.security

import grails.validation.Validateable
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

class UserEditPasswordCommand implements Validateable {

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder()
    def springSecurityService

    String passwordOld
    String passwordNew
    String passwordNewConfirm

    static constraints = {
        passwordOld nullable: false, blank: false, validator: { val, obj ->
            User user = obj.springSecurityService.getCurrentUser() as User
            if (user?.password && !obj.passwordEncoder.matches(val, user?.password)) return ['wrong']
        }
        passwordNew nullable: false, blank: false, minSize: 12
        passwordNewConfirm nullable: false, blank: false, validator: { val, obj ->
            if (val && val != obj.passwordNew) return ['notMatch']
        }
    }
}
