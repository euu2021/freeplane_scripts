// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2411

import org.freeplane.features.mode.Controller
import org.freeplane.features.map.INodeSelectionListener
import org.freeplane.features.map.NodeModel
import org.freeplane.plugin.script.proxy.ProxyFactory
import org.freeplane.plugin.script.proxy.ScriptUtils
import org.freeplane.view.swing.map.NodeView

import javax.swing.JViewport
import java.awt.Rectangle

INodeSelectionListener mySelectionListener = new INodeSelectionListener() {
    @Override
    public void onDeselect(NodeModel node) {
        proxyVersion = ProxyFactory.createNode(node, ScriptUtils.getCurrentContext())
        if (proxyVersion == c.viewRoot) return
        if (proxyVersion.icons.contains("button_ok")) proxyVersion.icons.remove("button_ok")
    }

    @Override
    public void onSelect(NodeModel node) {
        proxyVersion = ProxyFactory.createNode(node, ScriptUtils.getCurrentContext())
        if (proxyVersion == c.viewRoot) return
        siblings = proxyVersion.parent.children - proxyVersion
        hasUnfoldedSibling = siblings.any{ it.folded == false && it.children.size() > 0 }
        hasSiblingNotVisibleInViewport = siblings.any{ !isNodeVisibleInViewport(it.delegate) }
        if (hasUnfoldedSibling && hasSiblingNotVisibleInViewport) {
            if (!proxyVersion.icons.contains("button_ok")) proxyVersion.icons.add("button_ok")
        }
    }

}

Controller.currentController.modeController.mapController.addNodeSelectionListener(mySelectionListener)

def boolean isNodeVisibleInViewport(NodeModel nodeNotProxy) {

    def mapView = Controller.currentController.MapViewManager.mapView
    def viewport = mapView.getParent()
    if (!(viewport instanceof JViewport)) {
        return false
    }

    NodeView nodeView2 = mapView.getNodeView(nodeNotProxy)
    if(nodeView2 == null) {
        return false
    }

    def pointOnMap = mapView.getNodeContentLocation(nodeView2)

    if (pointOnMap == null) return false

    nodeView2.getContentPane().height

    Rectangle r = new Rectangle()
    r.x = pointOnMap.x
    r.y = pointOnMap.y
    r.width = nodeView2.getContentPane().width
    r.height = nodeView2.getContentPane().height

    def viewRect = viewport.getViewRect()

    visible = viewRect.intersects(r)

    return visible
}
