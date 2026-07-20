// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT
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
