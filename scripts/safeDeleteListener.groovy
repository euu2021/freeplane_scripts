// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT

//init

import org.freeplane.features.map.*
import org.freeplane.features.mode.Controller
import org.freeplane.features.mode.ModeController
import org.freeplane.plugin.script.proxy.ProxyUtils
import org.freeplane.plugin.script.proxy.ProxyFactory
import org.freeplane.plugin.script.proxy.ScriptUtils

import static javax.swing.JOptionPane.showMessageDialog

ModeController modeController = Controller.getCurrentModeController();
MapController mapController = modeController.getMapController();

mapController.addUIMapChangeListener(new IMapChangeListener() {
    @Override
    default void mapChanged(MapChangeEvent event) {}

    @Override
    default void onNodeDeleted(NodeDeletionEvent nodeDeletionEvent) {}

    @Override
    default void onNodeInserted(NodeModel parent, NodeModel child, int newIndex) {}

    @Override
    default void onNodeMoved(NodeMoveEvent nodeMoveEvent) {}

    @Override
    default void onPreNodeMoved(NodeMoveEvent nodeMoveEvent) {}

    @Override
    default void onPreNodeDelete(NodeDeletionEvent nodeDeletionEvent) {
        NodeModel deletedNode = nodeDeletionEvent.node
        allDeletedNodes = ProxyUtils.findImpl(null, deletedNode, false)
        if (allDeletedNodes.size() > 20) { //Put here the minimum number of nodes to show the warning.
            showMessageDialog(Controller.currentController.mapViewManager.mapView.parent.parent, "${allDeletedNodes.size()} nodes will be deleted")
        }

        Collection<String> protectedTags = ["repetitivo", "protected", "niver"] //Put here the tags that you want to protect. If you put just the subcategory (eg, "bbb", when the complete tag is "aaa::bbb"), it will be protected.
        allDeletedNodes.each { outerIt ->
            proxyVersion = ProxyFactory.createNode(outerIt, ScriptUtils.getCurrentContext())

            if (proxyVersion.tags.containsAnyCategory(protectedTags)) {
                showMessageDialog(Controller.currentController.mapViewManager.mapView.parent.parent, "Node with protected tag will be deleted. Node: ${proxyVersion.text}")
            }
        }
    }
})
