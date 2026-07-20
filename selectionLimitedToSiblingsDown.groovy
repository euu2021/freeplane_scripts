// SPDX-License-Identifier: CC0-1.0
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2169

import org.freeplane.features.mode.Controller

if(node.root) return

nodePosition = node.parent.getChildPosition(node)
nextSibling = node.parent.children[nodePosition + 1]


if (nextSibling == null || node == c.viewRoot) {
    menuUtils.executeMenuItems([ 'FreeScrollAction.UP' ])
}

else {
    Controller.currentController.mapViewManager.mapView.getMapSelection().selectContinuous(nextSibling.delegate)
}
