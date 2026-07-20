// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2633

// Simple Tag Creator Script - Creates a new tag and adds to selected node

import java.awt.Color
import org.freeplane.features.icon.IconController
import org.freeplane.features.icon.Tag
import org.freeplane.features.icon.mindmapmode.MIconController
import org.freeplane.features.mode.Controller

// PREDEFINED VALUES - Change these as needed
TAG_NAME = "MyNewTag"
TAG_COLOR = Color.ORANGE

// Main execution - create the tag and add to selected node
createNewTagSimple()

def createNewTagSimple() {
    try {
        // Check if a node is selected
        def selectedNode = c.selected
        if (!selectedNode) {
            c.statusInfo = "Please select a node first"
            println "ERROR: No node selected. Please select a node first."
            return
        }
        
        // Get the icon controller
        def iconController = (MIconController) Controller.currentModeController.getExtension(IconController.class)
        
        // Create new tag with the predefined color
        def newTag = new Tag(TAG_NAME, TAG_COLOR)
        
        // Add the tag to the selected node (this creates the tag in the system)
        iconController.addTags(selectedNode.delegate, [newTag])
        
        // Success message
        c.statusInfo = "Created and added tag '${TAG_NAME}' to selected node"
        println "SUCCESS: Created tag '${TAG_NAME}' with color ${TAG_COLOR} and added to node: ${selectedNode.text}"
        
    } catch (Exception e) {
        c.statusInfo = "ERROR: ${e.message}"
        println "ERROR creating tag: ${e.message}"
        e.printStackTrace()
    }
}
