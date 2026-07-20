// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/issues/2384#issuecomment-2809204920

import org.freeplane.features.mode.Controller;

mapView = Controller.getCurrentController().getMapViewManager().mapView

if(node == c.viewRoot) {
    mapView.selectRight(false)
    firstRightChildren = node.children?.find{!it.topOrLeft}
    if (firstRightChildren == null) return
    c.select(firstRightChildren)
}

else if(node.topOrLeft) {
    mapView.selectRight(false)
}

else if (node.children.size() > 0) {
    mapView.selectRight(false)
    firstVisibleChild = node.children.find{it.visible}
    c.select(firstVisibleChild)
}
else menuUtils.executeMenuItems([ 'FreeScrollAction.LEFT' ])
