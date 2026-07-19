import groovy.io.FileType
import org.freeplane.features.mode.Controller
import org.freeplane.features.mode.ModeController
import static javax.swing.JOptionPane.showMessageDialog

import groovy.transform.SourceURI
import java.net.URI

@SourceURI
URI scriptUri

scriptsDir = new File(scriptUri).parentFile

// Lista para armazenar os nomes dos scripts executados
def executedScripts = []

// Check if the directory exists and is indeed a directory
if (!scriptsDir.exists() || !scriptsDir.isDirectory()) {
        println "Directory not found: ${scriptsDir.absolutePath}"
} else {
        // Iterate over all files in the folder (you can adjust to search recursively if needed)
        scriptsDir.eachFile(FileType.FILES) { File file ->
                // Optionally: consider only files with the .groovy extension
                if (file.name.toLowerCase().endsWith('.groovy')) {
                        // Read the first line of the file to check for the init annotation
                        file.withReader('UTF-8') { reader ->
                                def firstLine = reader.readLine()
                                if (firstLine?.trim() == '//init') {
                                        println "Executing init script: ${file.name}"
                                        evaluate(file)
                                        executedScripts.add(file.name)
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
