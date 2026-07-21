// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2353

import org.freeplane.core.ui.components.UITools
import org.freeplane.features.cloud.CloudModel
import org.freeplane.features.cloud.CloudShape
import org.freeplane.features.mode.Controller
import org.freeplane.view.swing.map.NodeView
import org.freeplane.view.swing.map.cloud.CloudView
import org.freeplane.view.swing.map.cloud.CloudViewFactory

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

Controller.currentController.MapViewManager.mapView.addMouseListener(new MouseAdapter() {
    @Override
    void mouseClicked(MouseEvent e) {
        Point localPoint = e.getPoint()

        mapView = Controller.currentController.MapViewManager.mapView

        node.mindMap.root.findAll().each {
            if(mapView.getNodeView(it.delegate) == null) return

            node1 = it.delegate

            CloudModel cloudModel
            if(CloudModel.getModel(node1) == null) {
                cloudModel = new CloudModel()
                cloudModel.setShape(CloudShape.RECT)
            }
            else cloudModel = CloudModel.getModel(node1)

            NodeView nv = mapView.getNodeView(node1)

            def point = mapView.getNodeContentLocation(nv)

            CloudView cloud = new CloudViewFactory().createCloudView(cloudModel, nv)
            cloudPolygon = cloud.getCoordinates()
            Rectangle cloudBounds = cloudPolygon.getBounds()

            nvInnerBounds = nv.getInnerBounds()

            final Point contentXY = new Point(0, 0);
            UITools.convertPointToAncestor(nv, contentXY, mapView);
            contentXY;

            Rectangle cloudBoundsReal = new Rectangle((int) point.x, (int) point.y, (int) cloudBounds.width, (int) cloudBounds.height)

            boolean visible2 = cloudBoundsReal.contains(localPoint)

            if(visible2) {
                c.select(it)
            }
        }
    }
})
