// SPDX-License-Identifier: CC0-1.0
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2485

//script 2

import groovy.transform.SourceURI
import java.net.URI

script1FileName = "recentRootsNavigator_script1.groovy"

@SourceURI
URI scriptUri

scriptDir = new File(scriptUri).parentFile

File script1 = new File(scriptDir, script1FileName)

c.script(script1).withAllPermissions().executeOn(node)
