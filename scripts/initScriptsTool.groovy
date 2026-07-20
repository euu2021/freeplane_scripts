// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/issues/2264

import groovy.io.FileType
import org.freeplane.features.mode.Controller
import org.freeplane.core.resources.ResourceController
import static javax.swing.JOptionPane.showMessageDialog

// Runs every init script found in the registered script directories.
// An init script is a `.groovy` with a line that is exactly `//init` among its
// first lines (so a license/header block above the marker is fine).

def raw = ResourceController.resourceController.getProperty('script_directories') ?: ''
def dirs = raw.split(/;+/).collect { it?.trim() }.findAll { it }.collect { new File(it) }

def isInit = { File file ->
    file.withReader('UTF-8') { reader ->
        for (int i = 0; i < 15; i++) {
            def line = reader.readLine()
            if (line == null) break
            if (line.trim() == '//init') return true
        }
        return false
    }
}

def executed = []
dirs.each { dir ->
    if (dir.isDirectory()) {
        dir.eachFile(FileType.FILES) { File file ->
            if (file.name.toLowerCase().endsWith('.groovy') && isInit(file)) {
                def label = "${dir.name}/${file.name}"
                println "Executing init script: ${label}"
                evaluate(file)
                executed << label
            }
        }
    }
}

def message = executed ? ("Executed init scripts: " + executed.join(", ")) : "No init scripts were executed."
showMessageDialog(Controller.currentController.mapViewManager.mapView.parent.parent, message)
