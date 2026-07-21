// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2522

import org.freeplane.api.NodeChangeListener
import org.freeplane.api.NodeChanged
import groovy.lang.GroovyShell
import org.freeplane.features.icon.IconController
import org.freeplane.features.icon.mindmapmode.MIconController
import org.freeplane.features.mode.Controller
import org.freeplane.core.ui.components.UITools
import org.freeplane.plugin.script.proxy.ScriptUtils

// Check if we have any existing proxy listeners for NodeChangeListener
def existingTagListener = mindMap.listeners.find { listener ->
    // Check if it's a proxy class (jdk.proxy) and implements NodeChangeListener
    def className = listener.getClass().getName()
    return className.startsWith("jdk.proxy") && 
           listener instanceof NodeChangeListener &&
           !listener.getClass().getName().contains('IdentifiableNodeChangeListener')
}

// Toggle behavior based on whether we found our listener
if (existingTagListener) {
    // Remove the existing listener
    mindMap.removeListener(existingTagListener)
    UITools.informationMessage("Tag Evaluator deactivated!")
} else {
    // Get the icon controller to access tags
    MIconController iconController = (MIconController) Controller.currentModeController.getExtension(IconController.class)

    // Create a listener for tag changes
    NodeChangeListener myNodeChangeListener = { NodeChanged event ->
        if (event.changedElement == NodeChanged.ChangedElement.TAGS) {
            def currentNode = event.node
            try {
                def nodeTags = iconController.getTags(currentNode.delegate)
                if (!nodeTags) return

                // Prepare the script evaluation environment
                def shell = new GroovyShell()
                def header = '''
                import org.freeplane.plugin.script.proxy.ScriptUtils
                final c = ScriptUtils.c()
                final node = ScriptUtils.node()
                '''

                def tagsWithFormula = nodeTags.findAll { it.content.startsWith("=") }
                if (tagsWithFormula.isEmpty()) return
                
                // Process each tag that starts with "="
                tagsWithFormula.each { tag ->
                    try {
                        def formulaToEvaluate = tag.content.substring(1)
                        def binding = shell.context
                        binding.setVariable("node", currentNode)
                        def newTagText = shell.evaluate(header + "\n" + formulaToEvaluate)

                        // Remove the old tag and add the new one
                        currentNode.tags.remove(tag.content)
                        currentNode.tags.add(newTagText.toString())
                    } catch (Exception e) {
                        UITools.errorMessage("Error evaluating tag on node '${currentNode.text}': ${e.message}")
                    }
                }
            } catch (Exception e) {
                UITools.errorMessage("Error processing node '${currentNode.text}': ${e.message}")
            }
        }
    } as NodeChangeListener

    // Register the listener for future changes
    mindMap.addListener(myNodeChangeListener)
    UITools.informationMessage("Tag Evaluator activated! Tags starting with '=' will be evaluated as Groovy expressions.")
}
