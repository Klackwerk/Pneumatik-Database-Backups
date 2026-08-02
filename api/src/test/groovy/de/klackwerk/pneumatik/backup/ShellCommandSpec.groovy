package de.klackwerk.pneumatik.backup

import spock.lang.Specification

class ShellCommandSpec extends Specification {

    void 'quoting wraps a value so the shell expands nothing inside it'() {
        expect:
        ShellCommand.quote(value) == quoted

        where:
        value                   | quoted
        'shop'                  | "'shop'"
        ''                      | "''"
        null                    | "''"
        'shop; rm -rf /'        | "'shop; rm -rf /'"
        'shop$(id)'             | "'shop\$(id)'"
        'shop`id`'              | "'shop`id`'"
        'a"b'                   | "'a\"b'"
        'back\\slash'           | "'back\\slash'"
    }

    void 'a literal single quote is closed, escaped and reopened'() {
        expect: "O'Brien becomes 'O'\\''Brien'"
        ShellCommand.quote("O'Brien") == "'O'\\''Brien'"
    }

    void 'joining quotes every element separately'() {
        expect:
        ShellCommand.join(['mysqldump', '-u', 'ro ot', 'sh;op']) == "'mysqldump' '-u' 'ro ot' 'sh;op'"
    }

    void 'environment prefixes quote the value but not the name'() {
        expect:
        ShellCommand.environmentPrefix([PGSSLMODE: 'require', OTHER: 'a b']) ==
                "PGSSLMODE='require' OTHER='a b'"
    }

    void 'an empty environment produces no prefix'() {
        expect:
        ShellCommand.environmentPrefix([:]) == ''
    }
}
