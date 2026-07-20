// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/issues/2761#issuecomment-3591534515

// @ExecutionModes({ON_SINGLE_NODE})
// Script: Open Linked Map and Close Current
// Description: Opens the linked map from the selected node and closes the current map.
// Useful for navigating between maps with backlinks without accumulating open maps.

import java.net.URLDecoder

def currentMap = node.map

// Get link from selected node
def linkUri = node.link.uri
if (linkUri == null) {
    ui.informationMessage("No link found in the selected node.")
    return
}

def linkText = linkUri.toString()

// Check if it's a link to a mind map file
if (!linkText.contains(".mm")) {
    ui.informationMessage("The link does not appear to point to a mind map file.")
    return
}

// Extract node ID if present (e.g., #ID_123456)
def targetNodeId = null
if (linkText.contains('#')) {
    targetNodeId = linkText.replaceFirst('^.*#', '')  // Get everything after #
}

// Remove node ID if present (e.g., #ID_123456)
def mapPath = linkText.replaceFirst('#.*$', '')

// Remove file:// prefixes if present
mapPath = mapPath
        .replace('freeplane://', '')
        .replace('file:///', '')
        .replace('file:/', '')

// Decode URL encoding (%20 -> space, etc.)
mapPath = URLDecoder.decode(mapPath, 'UTF-8')

// Resolve the file path
def linkedFile = new File(mapPath)

// Handle relative paths
if (!linkedFile.isAbsolute()) {
    def currentMapFile = node.map.file
    if (currentMapFile != null) {
        linkedFile = new File(currentMapFile.parentFile, mapPath)
    } else {
        ui.informationMessage("Cannot resolve relative path: current map is not saved.")
        return
    }
}

// Get canonical path to normalize the path
linkedFile = linkedFile.getCanonicalFile()

if (!linkedFile.exists()) {
    ui.informationMessage("The linked file does not exist:\n${linkedFile.absolutePath}")
    return
}

// Open the linked map
c.mapLoader(linkedFile.absolutePath).withView().load()

// Store current map reference before opening new one
def mapToClose = currentMap

// Close the original map (force close without saving prompt if unchanged, allow interaction if changed)
mapToClose.close(true, true)

// Switch back to the linked map
def loadedMap = c.mapLoader(linkedFile.absolutePath).withView().load()

// Navigate to specific node if ID was provided
if (targetNodeId != null) {
    def targetNode = loadedMap.node(targetNodeId)
    if (targetNode != null) {
        c.select(targetNode)
        c.centerOnNode(targetNode)
    }
}
