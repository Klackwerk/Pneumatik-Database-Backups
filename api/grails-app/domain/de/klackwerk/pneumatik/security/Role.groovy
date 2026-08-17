package de.klackwerk.pneumatik.security

import grails.compiler.GrailsCompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

@GrailsCompileStatic
@EqualsAndHashCode(includes = 'authority')
@ToString(includes = 'authority', includeNames = true, includePackage = false)
class Role implements Serializable {

    private static final long serialVersionUID = 1

    String id
    String authority

    static constraints = {
        authority nullable: false, blank: false, unique: true
    }

    static mapping = {
        table 'role'
        id generator: 'uuid2'
        cache true
    }

    static String getRoleIdFromString(String roleString) {
        return getRoleFromString(roleString)?.id
    }

    static Role getRoleFromString(String roleString) {
        return findByAuthority(roleString)
    }
}
