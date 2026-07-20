// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2169

if(node.root) return

nodePosition = node.parent.getChildPosition(node)
previousSibling = node.parent.children[nodePosition - 1]

if (nodePosition == 0 ||  node == c.viewRoot) {
    menuUtils.executeMenuItems([ 'FreeScrollAction.DOWN' ])
}

else {
    c.select(previousSibling)
}
