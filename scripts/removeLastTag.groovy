// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/issues/2811#issuecomment-3765160102

// Script to remove the last tag from selected nodes
// @ExecutionModes({ON_SINGLE_NODE})

def selectedNodes = c.selecteds

int removedCount = 0
selectedNodes.each { n ->
    def tags = n.tags.getTags()
    if (tags && !tags.isEmpty()) {
        n.tags.remove(tags.size() - 1)
        removedCount++
    }
}

c.statusInfo = "Last tag removed from ${removedCount} node(s)."
