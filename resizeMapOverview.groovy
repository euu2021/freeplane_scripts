// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/issues/1771#issuecomment-2042789498

import javax.swing.JScrollPane
import java.awt.Rectangle;

import org.freeplane.features.mode.Controller
import org.freeplane.view.swing.map.MapView

MapView mapView = Controller.currentController.mapViewManager.mapView

JScrollPane findScrollPaneParent(java.awt.Component component) {
    while (component != null && !(component instanceof JScrollPane)) {
        component = component.getParent()
    }
    return component as JScrollPane
}

JScrollPane mapViewScrollPane = findScrollPaneParent(mapView)

width = 200
height = 200

Rectangle newBounds = new Rectangle(00, 00, width, height)

mapViewScrollPane.parent.setMapOverviewBounds(newBounds)
