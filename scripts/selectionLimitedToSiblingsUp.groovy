// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2169

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
