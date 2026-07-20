// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2268

// @ExecutionModes({ON_SINGLE_NODE})

c.getSortedSelection(true).each{
    parentNode = it.parent
    originalNodeIndex = parentNode.getChildPosition(it)

    parentNode.appendBranch(it)
    newNode = parentNode.children[-1]
    newNode.moveTo(parentNode, originalNodeIndex + 1)
}
