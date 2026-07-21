// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2245

import java.awt.Color
import org.freeplane.features.mode.Controller
import org.freeplane.features.styles.MapStyleModel
import org.freeplane.features.map.NodeModel
import org.freeplane.features.nodestyle.NodeStyleModel
import org.freeplane.features.cloud.CloudModel
import org.freeplane.core.undo.IActor


map = Controller.currentController.mapViewManager.mapView.getMap()

final IActor actor = new IActor() {
    @Override
    public void act() {
        apply()
    }

    @Override
    public String getDescription() {
        return "invert colors";
    }

    @Override
    public void undo() {
        apply()
        mindMap = node.getMindMap()
        mindMap.setBackgroundColor(invertColor(mindMap.getBackgroundColor() ?: Color.white))
    }
};
Controller.getCurrentModeController().execute(actor, map);



////////////////Methods/////////////

def invertColor(Color color) {
    return new Color(255 - color.getRed(), 255 - color.getGreen(),
            255 - color.getBlue())
}

def addAllNodes(NodeModel pNode, List allNodes) {
    allNodes.add(pNode)
    pNode.getChildren().each {addAllNodes(it, allNodes)}
}

def getAllChildren(NodeModel pNode) {
    allNodes = []
    addAllNodes(pNode, allNodes)
    return allNodes
}


def apply() {

// invert map background color
    mindMap = node.getMindMap()
    mindMap.setBackgroundColor(invertColor(mindMap.getBackgroundColor() ?: Color.white))

    mapController = Controller.getCurrentController()
    mindMap = mapController.getMap()
    mapRoot = mindMap.getRootNode()
    styleModel = MapStyleModel.getExtension(mindMap)
    styleMap = styleModel.getStyleMap()
    styleRoot = styleMap.getRootNode()

// get map nodes with style nodes
    combinedNodes = getAllChildren(styleRoot)
    combinedNodes.addAll(getAllChildren(mapRoot))

// set default text color to black if null
    defaultStyle = combinedNodes.find { it.text == 'Default' }
    if (!NodeStyleModel.getColor(defaultStyle)) {
        NodeStyleModel.setColor(defaultStyle, Color.black)
    }

// invert all colors
    for (styledNode in combinedNodes) {
        textColor = NodeStyleModel.getColor(styledNode)
        if (textColor != null){
            NodeStyleModel.setColor(styledNode, invertColor(textColor))
        }
        backgroundColor = NodeStyleModel.getBackgroundColor(styledNode)
        if (backgroundColor != null){
            NodeStyleModel.setBackgroundColor(styledNode, invertColor(backgroundColor))
        }
        cloud = CloudModel.getModel(styledNode)
        cloudColor = cloud.getColor()
        if (cloudColor != null){
            cloud.setColor(invertColor(cloudColor))
        }
    }

    def mainView = Controller.getCurrentController().getMapViewManager().getMapViewComponent()
    def oldZoom = mainView.getZoom()
    mainView.setZoom(10)
    mainView.setZoom(oldZoom)

}
