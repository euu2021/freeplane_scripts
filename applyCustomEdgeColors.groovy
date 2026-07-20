// SPDX-License-Identifier: CC0-1.0
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2794

// @ExecutionModes({ON_SINGLE_NODE})
/**
 * Apply Custom Edge Colors to Selected Nodes and Their Descendants
 * 
 * Applies the user-defined edge colors (from "Edit edge colors" menu)
 * to the currently selected nodes and all their descendants (entire branch).
 * Colors cycle through if there are more selected nodes than available colors.
 * 
 * Usage: Select nodes, then run this script.
 */

import java.awt.Color
import org.freeplane.features.edge.EdgeController
import org.freeplane.features.edge.mindmapmode.MEdgeController

def mapModel = node.mindMap.delegate
def controller = EdgeController.controller as MEdgeController

if (!controller.areEdgeColorsAvailable(mapModel)) {
    ui.informationMessage("No edge colors defined.\n\nDefine them via: Format > Edit edge colors...")
    return
}

def selectedNodes = c.selecteds

// Helper to compare colors
def sameColor = { c1, c2 -> 
    c1.red == c2.red && c1.green == c2.green && c1.blue == c2.blue 
}

// Collect edge colors
def edgeColors = []
def firstColor = controller.getEdgeColor(mapModel, 1)
edgeColors << firstColor

def colorIndex = 2
while (colorIndex <= 100) {
    Color color = controller.getEdgeColor(mapModel, colorIndex)
    if (color == null || sameColor(color, firstColor)) {
        break
    }
    edgeColors << color
    colorIndex++
}

// Apply colors to selected nodes and all their descendants
def colorCount = edgeColors.size()
def totalNodes = 0

selectedNodes.eachWithIndex { selectedNode, i ->
    def colorToApply = edgeColors[i % colorCount]
    
    // Apply to the selected node and all its descendants
    selectedNode.findAll().each { n ->
        n.style.edge.color = colorToApply
        totalNodes++
    }
}

c.statusInfo = "Applied ${colorCount} color(s) to ${totalNodes} node(s) in ${selectedNodes.size()} branch(es)"
