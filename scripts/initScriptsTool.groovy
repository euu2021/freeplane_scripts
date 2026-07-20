// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/issues/2264

import groovy.io.FileType
import org.freeplane.features.mode.Controller
import org.freeplane.features.mode.ModeController
import static javax.swing.JOptionPane.showMessageDialog

import groovy.transform.SourceURI
import java.net.URI

@SourceURI
URI scriptUri

// This tool lives one level under the scripts root
// (e.g. ...\FP\Scripts\compartilhados\initScriptsTool.groovy), so its parent's
// parent is the root that holds all the script subfolders.
scriptsRoot = new File(scriptUri).parentFile.parentFile

// Names of the init scripts that were executed
def executedScripts = []

if (!scriptsRoot?.exists() || !scriptsRoot.isDirectory()) {
    println "Scripts root not found: ${scriptsRoot?.absolutePath}"
} else {
    // Iterate over every subfolder of the scripts root...
    scriptsRoot.eachDir { File dir ->
        // ...and, within each, run every .groovy whose first line is exactly '//init'
        dir.eachFile(FileType.FILES) { File file ->
            if (file.name.toLowerCase().endsWith('.groovy')) {
                def firstLine = file.withReader('UTF-8') { it.readLine() }
                if (firstLine?.trim() == '//init') {
                    def label = "${file.parentFile.name}/${file.name}"
                    println "Executing init script: ${label}"
                    evaluate(file)
                    executedScripts.add(label)
                }
            }
        }
    }
}

// Build the message to be displayed
def message
if (executedScripts.size() > 0) {
    message = "Executed init scripts: " + executedScripts.join(", ")
} else {
    message = "No init scripts were executed."
}

// Show a dialog with the executed scripts
showMessageDialog(
    Controller.currentController.mapViewManager.mapView.parent.parent,
    message
)
