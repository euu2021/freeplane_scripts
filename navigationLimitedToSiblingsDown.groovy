if(node.root) return

nodePosition = node.parent.getChildPosition(node)
nextSibling = node.parent.children[nodePosition + 1]


if (nextSibling == null || node == c.viewRoot) {
    menuUtils.executeMenuItems([ 'FreeScrollAction.UP' ])
}

else {
    c.select(nextSibling)
}