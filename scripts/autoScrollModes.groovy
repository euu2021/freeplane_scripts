// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2307

import org.freeplane.core.resources.ResourceController
import org.freeplane.view.swing.ui.MouseEventActor
import org.freeplane.features.map.*
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import java.awt.Component
import org.freeplane.features.mode.Controller

INodeSelectionListener mySelectionListener = new INodeSelectionListener() {
    @Override
    public void onDeselect(NodeModel node) {

    }

    @Override
    public void onSelect(NodeModel node) {
        if(MouseEventActor.INSTANCE.isActive() && ResourceController.getResourceController().getBooleanProperty("autoscroll_disabled_for_mouse_interaction")) return
        Component mapView = Controller.getCurrentController().getMapViewManager().getMapViewComponent();
        if(SwingUtilities.getRootPane(mapView) == null) return
        menuUtils.executeMenuItems([ 'MoveSelectedNodeAction.TOP_RIGHT' ])
    }

}

Controller.currentController.modeController.mapController.addNodeSelectionListener(mySelectionListener)
