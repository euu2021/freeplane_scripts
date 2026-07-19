import org.freeplane.features.mode.Controller

if(node.root) return

nodePosition = node.parent.getChildPosition(node)
previousSibling = node.parent.children[nodePosition - 1]


if (nodePosition == 0  || node == c.viewRoot) {
    menuUtils.executeMenuItems([ 'FreeScrollAction.DOWN' ])
}

else {
    Controller.currentController.mapViewManager.mapView.getMapSelection().selectContinuous(previousSibling.delegate)
}