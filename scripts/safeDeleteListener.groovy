// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Version: 1.1

/***
 * Warns before a deletion that is probably a mistake: a dialog appears when more than 20
 * nodes would go at once, and another one for every node about to be deleted that carries
 * a protected tag. Both the threshold and the list of protected tags are set in the code
 * below.
 *
 * It only does something if it is running, so it has to be started automatically: register
 * it in whatever runs your scripts at startup.
 *
 * CHANGELOG
 * ---------
 *   1.1 (2026-07-26)
 *       The "//init" marker line was removed. Which scripts start automatically is now
 *       declared by the tool that starts them, not by a marker inside the file. If you
 *       were relying on initScriptsTool finding that marker, add this script to your
 *       autostart list instead.
 *   1.0 (2026-07-26)
 *       First versioned release. Earlier history is in the repository log.
 */

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
